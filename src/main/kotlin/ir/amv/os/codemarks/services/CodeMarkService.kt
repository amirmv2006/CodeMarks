package ir.amv.os.codemarks.services

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.BookmarkType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import java.util.regex.Pattern
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ModalityState
import com.intellij.util.messages.Topic
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.find.FindManager
import com.intellij.find.FindModel
import com.intellij.find.FindResult
import com.intellij.openapi.util.TextRange
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.roots.ContentIterator

interface CodeMarkService {
    fun scanAndSync()
    fun scanAndSync(file: VirtualFile)
    fun getSettings(): CodeMarkSettings
    fun organizeGroups()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeMarkService = project.service<CodeMarkService>()
    }
}

@Service(Service.Level.PROJECT)
class CodeMarkServiceImpl(private val project: Project) : CodeMarkService, Disposable {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkServiceImpl::class.java)
        private val BOOKMARK_PATTERN = Pattern.compile("CodeMarks(?:\\[(\\w+)\\])?:\\s*(.*)", Pattern.CASE_INSENSITIVE)
        private const val BOOKMARK_PATTERN_STRING = "CodeMarks(?:\\[(\\w+)\\])?:\\s*(.*)"
        private const val CODEMARKS_GROUP_NAME = "CodeMarks"

        private fun matchesGlob(fileName: String, glob: String): Boolean {
            if (glob == "*") return true
            val fs = java.nio.file.FileSystems.getDefault()
            val matcher = fs.getPathMatcher("glob:$glob")
            return matcher.matches(fs.getPath(fileName))
        }
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val bookmarksManager = BookmarksManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val settings = CodeMarkSettings.getInstance(project)

    override fun getSettings(): CodeMarkSettings = settings

    init {
        LOG.info("Initializing CodeMarkService for project: ${project.name}")
        setupListeners()
    }

    private fun setupListeners() {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val modifiedFiles = events.filter { it.file != null && shouldScanFile(it.file!!) }
                if (modifiedFiles.isNotEmpty()) {
                    if (modifiedFiles.size == 1) {
                        scheduleSingleFileSync(modifiedFiles[0].file!!)
                    } else {
                        scheduleSync()
                    }
                }
            }
        })

        val connection = project.messageBus.connect()
        connection.subscribe(Topic.create("DocumentListener", DocumentListener::class.java, Topic.BroadcastDirection.NONE), object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = fileDocumentManager.getFile(event.document)
                if (file != null && shouldScanFile(file)) {
                    scheduleSingleFileSync(file)
                }
            }
        })

        connection.subscribe(Topic.create("FileDocumentManagerListener", FileDocumentManagerListener::class.java, Topic.BroadcastDirection.NONE), object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = fileDocumentManager.getFile(document)
                if (file != null && shouldScanFile(file)) {
                    LOG.warn("beforeDocumentSaving: ${file.path}")
                    scheduleSingleFileSync(file)
                }
            }

            override fun beforeAllDocumentsSaving() {
                LOG.warn("beforeAllDocumentsSaving")
                scheduleSync()
            }
        })
    }

    private fun shouldScanFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false

        // Check if file belongs to the project
        val fileIndex = ProjectFileIndex.getInstance(project)
        val isInContent = com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
            fileIndex.isInContent(file)
        }
        if (!isInContent) {
            return false
        }

        val fileName = file.name
        return settings.fileTypePatterns.any { pattern ->
            try {
                matchesGlob(fileName, pattern)
            } catch (e: Exception) {
                LOG.error("Invalid pattern: $pattern", e)
                false
            }
        }
    }

    private fun scheduleSync() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            ApplicationManager.getApplication().invokeLater({
                doScanAndSync()
            }, ModalityState.any())
        }, 100)
    }

    private fun scheduleSingleFileSync(file: VirtualFile) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            ApplicationManager.getApplication().invokeLater({
                doScanAndSync(file)
            }, ModalityState.any())
        }, 100)
    }

    override fun scanAndSync() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({
                doScanAndSync()
            }, ModalityState.any())
            return
        }
        doScanAndSync()
    }

    override fun scanAndSync(file: VirtualFile) {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({
                doScanAndSync(file)
            }, ModalityState.any())
            return
        }
        doScanAndSync(file)
    }

    private fun getOrCreateCodeMarksGroup(suffix: String? = null): com.intellij.ide.bookmark.BookmarkGroup? {
        val groupName = if (suffix != null) "$CODEMARKS_GROUP_NAME $suffix" else CODEMARKS_GROUP_NAME
        val existingGroup = bookmarksManager?.groups?.find { it.name == groupName }
        if (existingGroup != null) return existingGroup

        return bookmarksManager?.addGroup(groupName, false)
    }

    private fun doScanAndSync() {
        if (!project.isInitialized || project.isDisposed) {
            LOG.warn("Project not initialized or disposed")
            return
        }

        // Check if application is available
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping CodeMarks scan")
            return
        }

        // Check if we're in unit test mode
        val isTestMode = application.isUnitTestMode

        // Define a function to handle scanning and applying changes
        fun performScanAndApplyChanges() {
            try {
                val contentRoots = ReadAction.compute<Array<VirtualFile>, RuntimeException> {
                    ProjectRootManager.getInstance(project).contentRoots
                }

                if (contentRoots == null) {
                    LOG.warn("No content roots found")
                    return
                }

                val bookmarksToAdd = mutableListOf<BookmarkData>()
                val bookmarksToRemove = mutableListOf<com.intellij.ide.bookmark.Bookmark>()

                // First validate existing bookmarks
                ReadAction.run<RuntimeException> {
                    bookmarksManager?.let { manager ->
                        manager.groups.forEach { group ->
                            if (group.name.startsWith(CODEMARKS_GROUP_NAME)) {
                                group.getBookmarks().forEach { bookmark ->
                                    val lineNumber = bookmark.attributes["line"]?.toIntOrNull()
                                    val description = group.getDescription(bookmark)
                                    val filePath = if (bookmark is com.intellij.ide.bookmark.providers.LineBookmarkImpl) {
                                        bookmark.file?.path
                                    } else {
                                        bookmark.attributes["file"]
                                    }

                                    if (filePath == null || !isValidBookmark(filePath, lineNumber, description)) {
                                        bookmarksToRemove.add(bookmark)
                                    }
                                }
                            }
                        }
                    }
                }

                // Scan for new bookmarks using FileIndex
                scanFilesUsingFileIndex(bookmarksToAdd)

                // Apply changes in UI thread
                fun applyChanges() {
                    WriteCommandAction.runWriteCommandAction(project) {
                        // Remove invalid bookmarks
                        bookmarksToRemove.forEach { bookmark ->
                            bookmarksManager?.remove(bookmark)
                        }

                        // Add new bookmarks
                        bookmarksToAdd.forEach { (file, suffix, description, line) ->
                            val group = getOrCreateCodeMarksGroup(suffix)
                            val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                            bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                            bookmarkState.attributes.putAll(mapOf(
                                "file" to file.path,
                                "url" to file.url,
                                "line" to line.toString()
                            ))
                            val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                            if (bookmark != null) {
                                group?.add(bookmark, BookmarkType.DEFAULT, description)
                            }
                        }

                        // Organize codemark groups (sort by name, remove empty groups, sort codemarks by description)
                        organizeCodeMarkGroups()
                    }
                }

                if (isTestMode) {
                    // In test mode, always run synchronously
                    if (ApplicationManager.getApplication().isDispatchThread) {
                        // If we're already on the dispatch thread, run directly
                        applyChanges()
                    } else {
                        // Otherwise, wait for it to complete on the dispatch thread
                        val latch = java.util.concurrent.CountDownLatch(1)
                        ApplicationManager.getApplication().invokeLater({
                            try {
                                // Check if project is still valid before applying changes
                                if (!project.isDisposed) {
                                    applyChanges()
                                } else {
                                    LOG.warn("Project is disposed, skipping applying CodeMarks changes in test mode")
                                }
                            } finally {
                                latch.countDown()
                            }
                        }, ModalityState.any())
                        // Wait for the UI thread to complete the action
                        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } else {
                    // In normal mode, just schedule on UI thread
                    ApplicationManager.getApplication().invokeLater({
                        // Check if project is still valid before applying changes
                        if (!project.isDisposed) {
                            applyChanges()
                        } else {
                            LOG.warn("Project is disposed, skipping applying CodeMarks changes")
                        }
                    }, ModalityState.any())
                }
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) {
                    throw e
                }
                LOG.error("Error in scan and sync", e)
            }
        }

        if (isTestMode) {
            // In test mode, run synchronously
            if (ApplicationManager.getApplication().isDispatchThread) {
                performScanAndApplyChanges()
            } else {
                ApplicationManager.getApplication().invokeLater({
                    // Check if project is still valid before performing scan
                    if (!project.isDisposed) {
                        performScanAndApplyChanges()
                    } else {
                        LOG.warn("Project is disposed, skipping CodeMarks scan in test mode")
                    }
                }, ModalityState.any())
            }
        } else {
            // In normal mode, run in background
            ApplicationManager.getApplication().executeOnPooledThread {
                performScanAndApplyChanges()
            }
        }
    }

    private fun doScanAndSync(file: VirtualFile) {
        if (!project.isInitialized || project.isDisposed) {
            LOG.warn("Project not initialized or disposed")
            return
        }

        // Check if we're in unit test mode
        val isTestMode = ApplicationManager.getApplication().isUnitTestMode

        // Define a function to handle scanning and applying changes
        fun performScanAndApplyChanges() {
            try {
                val bookmarksToAdd = mutableListOf<BookmarkData>()
                val bookmarksToRemove = mutableListOf<com.intellij.ide.bookmark.Bookmark>()

                // First validate existing bookmarks for this file
                ReadAction.run<RuntimeException> {
                    bookmarksManager?.let { manager ->
                        manager.groups.forEach { group ->
                            if (group.name.startsWith(CODEMARKS_GROUP_NAME)) {
                                group.getBookmarks().forEach { bookmark ->
                                    val lineNumber = bookmark.attributes["line"]?.toIntOrNull()
                                    val description = group.getDescription(bookmark)
                                    val filePath = if (bookmark is com.intellij.ide.bookmark.providers.LineBookmarkImpl) {
                                        bookmark.file?.path
                                    } else {
                                        bookmark.attributes["file"]
                                    }

                                    if (filePath == file.path && !isValidBookmark(filePath, lineNumber, description)) {
                                        bookmarksToRemove.add(bookmark)
                                    }
                                }
                            }
                        }
                    }
                }

                // Scan file for new bookmarks
                scanFileForBookmarks(file, bookmarksToAdd)

                // Apply changes in UI thread
                fun applyChanges() {
                    WriteCommandAction.runWriteCommandAction(project) {
                        // Remove invalid bookmarks
                        bookmarksToRemove.forEach { bookmark ->
                            bookmarksManager?.remove(bookmark)
                        }

                        // Add new bookmarks
                        bookmarksToAdd.forEach { (file, suffix, description, line) ->
                            val group = getOrCreateCodeMarksGroup(suffix)
                            val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                            bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                            bookmarkState.attributes.putAll(mapOf(
                                "file" to file.path,
                                "url" to file.url,
                                "line" to line.toString()
                            ))
                            val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                            if (bookmark != null) {
                                group?.add(bookmark, BookmarkType.DEFAULT, description)
                            }
                        }

                        // Organize codemark groups (sort by name, remove empty groups, sort codemarks by description)
                        organizeCodeMarkGroups()
                    }
                }

                if (isTestMode) {
                    // In test mode, always run synchronously
                    if (ApplicationManager.getApplication().isDispatchThread) {
                        // If we're already on the dispatch thread, run directly
                        applyChanges()
                    } else {
                        // Otherwise, wait for it to complete on the dispatch thread
                        val latch = java.util.concurrent.CountDownLatch(1)
                        ApplicationManager.getApplication().invokeLater({
                            try {
                                // Check if project is still valid before applying changes
                                if (!project.isDisposed) {
                                    applyChanges()
                                } else {
                                    LOG.warn("Project is disposed, skipping applying CodeMarks changes for file ${file.path} in test mode")
                                }
                            } finally {
                                latch.countDown()
                            }
                        }, ModalityState.any())
                        // Wait for the UI thread to complete the action
                        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } else {
                    // In normal mode, just schedule on UI thread
                    ApplicationManager.getApplication().invokeLater({
                        // Check if project is still valid before applying changes
                        if (!project.isDisposed) {
                            applyChanges()
                        } else {
                            LOG.warn("Project is disposed, skipping applying CodeMarks changes for file ${file.path}")
                        }
                    }, ModalityState.any())
                }
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException) {
                    throw e
                }
                LOG.error("Error in scan and sync for file ${file.path}", e)
            }
        }

        if (isTestMode) {
            // In test mode, run synchronously
            if (ApplicationManager.getApplication().isDispatchThread) {
                performScanAndApplyChanges()
            } else {
                ApplicationManager.getApplication().invokeLater({
                    // Check if project is still valid before performing scan
                    if (!project.isDisposed) {
                        performScanAndApplyChanges()
                    } else {
                        LOG.warn("Project is disposed, skipping CodeMarks scan for file ${file.path} in test mode")
                    }
                }, ModalityState.any())
            }
} else {
            // In normal mode, run in background
            ApplicationManager.getApplication().executeOnPooledThread {
                performScanAndApplyChanges()
            }
        }
    }

    private data class BookmarkData(
val file: VirtualFile,
        val suffix: String?,
        val description: String,
        val line: Int
    )

    private fun scanFilesUsingFileIndex(bookmarksToAdd: MutableList<BookmarkData>) {
        // Check if application is available before using ReadAction
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping file index scan")
            return
        }

        val fileIndex = ProjectFileIndex.getInstance(project)

        ReadAction.run<RuntimeException> {
            // Create a content iterator that processes each file
            val iterator = ContentIterator { file ->
                if (shouldScanFile(file)) {
                    scanFileForBookmarks(file, bookmarksToAdd)
                }
                true // Continue iteration
            }

            // Iterate through all content files in the project
            fileIndex.iterateContent(iterator)
        }
    }

    // Keep this method for backward compatibility or specific file scanning
    private fun scanDirectoryForBookmarks(dir: VirtualFile, bookmarksToAdd: MutableList<BookmarkData>) {
        // Check if application is available before using ReadAction
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping directory scan for ${dir.path}")
            return
        }

        val children = ReadAction.compute<Array<VirtualFile>, RuntimeException> {
            dir.children
        } ?: return

        children.forEach { file ->
            when {
                file.isDirectory -> scanDirectoryForBookmarks(file, bookmarksToAdd)
                shouldScanFile(file) -> scanFileForBookmarks(file, bookmarksToAdd)
            }
        }
    }

    private fun scanFileForBookmarks(file: VirtualFile, bookmarksToAdd: MutableList<BookmarkData>) {
        val lastModified = file.timeStamp
        val lastScanned = settings.lastScanState[file.path]

        // Skip if file hasn't changed since last scan
        if (lastScanned != null && lastScanned == lastModified) {
            return
        }

        // Check if application is available before using ReadAction
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping document retrieval for ${file.path}")
            return
        }

        val document = ReadAction.compute<Document?, RuntimeException> {
            fileDocumentManager.getDocument(file)
        } ?: return

        // Use IntelliJ's FindManager for efficient searching
        ReadAction.run<RuntimeException> {
            val findManager = FindManager.getInstance(project)
            val findModel = FindModel()
            findModel.isRegularExpressions = true
            findModel.stringToFind = BOOKMARK_PATTERN_STRING
            findModel.isCaseSensitive = false
            findModel.isWholeWordsOnly = false

            var offset = 0
            val text = document.text

            while (offset < text.length) {
                val findResult = findManager.findString(text, offset, findModel)
                if (!findResult.isStringFound) break

                // Get the line number for this result
                val startOffset = findResult.startOffset
                val lineNumber = document.getLineNumber(startOffset)

                // Get the full line text
                val lineStartOffset = document.getLineStartOffset(lineNumber)
                val lineEndOffset = document.getLineEndOffset(lineNumber)
                val lineText = document.getText(TextRange(lineStartOffset, lineEndOffset))

                // Use the regex pattern to extract groups
                val matcher = BOOKMARK_PATTERN.matcher(lineText)
                if (matcher.find()) {
                    val suffix = matcher.group(1)
                    val description = matcher.group(2).trim()
                    LOG.info("Found bookmark at ${file.path}:${lineNumber + 1} with description: $description in group: $suffix")
                    bookmarksToAdd.add(BookmarkData(file, suffix, description, lineNumber))
                }

                // Move to the next occurrence
                offset = findResult.endOffset
            }
        }

        // Update last scan time
        settings.lastScanState[file.path] = lastModified
    }

    private fun isValidBookmark(filePath: String?, lineNumber: Int?, description: String?): Boolean {
        if (filePath == null || lineNumber == null || description == null) return false

        val file = VirtualFileManager.getInstance().findFileByUrl("file://$filePath") ?: return false
        if (!shouldScanFile(file)) return false

        // Check if application is available before using ReadAction
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping document retrieval for ${file.path}")
            return false
        }

        val document = ReadAction.compute<Document?, RuntimeException> {
            fileDocumentManager.getDocument(file)
        } ?: return false

        if (lineNumber >= document.lineCount) return false

        return ReadAction.compute<Boolean, RuntimeException> {
            // Get the line text
            val startOffset = document.getLineStartOffset(lineNumber)
            val endOffset = document.getLineEndOffset(lineNumber)
            val line = document.getText(TextRange(startOffset, endOffset))

            // Use FindManager to check if the pattern exists in this line
            val findManager = FindManager.getInstance(project)
            val findModel = FindModel()
            findModel.isRegularExpressions = true
            findModel.stringToFind = BOOKMARK_PATTERN_STRING
            findModel.isCaseSensitive = false
            findModel.isWholeWordsOnly = false

            val findResult = findManager.findString(line, 0, findModel)
            if (!findResult.isStringFound) return@compute false

            // Use the regex pattern to extract the description
            val matcher = BOOKMARK_PATTERN.matcher(line)
            if (!matcher.find()) return@compute false

            val foundDescription = matcher.group(2).trim()
            foundDescription == description
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        Disposer.dispose(alarm)
    }

    override fun organizeGroups() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({
                organizeCodeMarkGroups()
            }, ModalityState.any())
            return
        }
        organizeCodeMarkGroups()
    }

    /**
     * Organizes codemark groups:
     * 1. Sorts groups by name
     * 2. Removes empty groups
     * 3. Sorts codemarks within each group by description
     */
    private fun organizeCodeMarkGroups() {
        bookmarksManager?.let { manager ->
            WriteCommandAction.runWriteCommandAction(project) {
                // Get all codemark groups
                val codemarkGroups = manager.groups
                    .filter { it.name.startsWith(CODEMARKS_GROUP_NAME) }
                    .toList()

                // Process each group
                codemarkGroups.forEach { group ->
                    val bookmarks = group.getBookmarks().toList()

                    if (bookmarks.isEmpty()) {
                        // Remove empty groups
                        LOG.info("Removing empty group: ${group.name}")
                        manager.groups.remove(group)
                    } else {
                        // Store descriptions before sorting and removing
                        val bookmarkDescriptions = bookmarks.associateWith { bookmark ->
                            group.getDescription(bookmark) ?: ""
                        }

                        // Sort bookmarks within the group by description (case-insensitive)
                        val sortedBookmarks = bookmarks.sortedBy { bookmark -> 
                            (bookmarkDescriptions[bookmark] ?: "").lowercase()
                        }

                        // Reorder bookmarks in the group
                        if (sortedBookmarks != bookmarks) {
                            // Remove all bookmarks
                            bookmarks.forEach { bookmark ->
                                group.remove(bookmark)
                            }

                            // Add them back in sorted order with new bookmark instances
                            sortedBookmarks.forEach { bookmark ->
                                val description = bookmarkDescriptions[bookmark] ?: ""
                                // Create a new bookmark with the same attributes
                                val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                                bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                                bookmarkState.attributes.putAll(bookmark.attributes)
                                val newBookmark = bookmarksManager.createBookmark(bookmarkState)
                                if (newBookmark != null) {
                                    group.add(newBookmark, BookmarkType.DEFAULT, description)
                                }
                            }
                        }
                    }
                }

                // The implementation sorts bookmarks within groups by description
                // and removes empty groups
            }
        }
    }
}
