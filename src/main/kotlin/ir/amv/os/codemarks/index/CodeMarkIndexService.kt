package ir.amv.os.codemarks.index

import com.intellij.ide.bookmark.BookmarkType
import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.messages.Topic
import com.intellij.openapi.application.ModalityState
import com.intellij.psi.search.GlobalSearchScope
import ir.amv.os.codemarks.services.CodeMarkService
import ir.amv.os.codemarks.services.CodeMarkSettings
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * Implementation of CodeMarkService that uses FileBasedIndex to scan project files.
 * This service maintains backward compatibility with the existing CodeMarkService interface.
 */
@Service(Service.Level.PROJECT)
class CodeMarkIndexService(private val project: Project) : CodeMarkService, Disposable {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkIndexService::class.java)
        private const val CODEMARKS_GROUP_NAME = "CodeMarks"
        private val BOOKMARK_PATTERN = Pattern.compile("CodeMarks(?:\\[(\\w+)\\])?:\\s*(.*)", Pattern.CASE_INSENSITIVE)
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val bookmarksManager = BookmarksManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val settings = CodeMarkSettings.getInstance(project)

    override fun getSettings(): CodeMarkSettings = settings

    init {
        LOG.info("Initializing CodeMarkIndexService for project: ${project.name}")
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
        return CodeMarksIndex.shouldIndexFile(file, project)
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
        LOG.info("Getting or creating group: $groupName")

        if (bookmarksManager == null) {
            LOG.error("BookmarksManager is null")
            return null
        }

        val groups = bookmarksManager.groups
        LOG.info("Found ${groups.size} groups")
        groups.forEach { LOG.info("Group: ${it.name}") }

        val existingGroup = groups.find { it.name == groupName }
        if (existingGroup != null) {
            LOG.info("Found existing group: $groupName")
            return existingGroup
        }

        LOG.info("Creating new group: $groupName")
        val newGroup = bookmarksManager.addGroup(groupName, false)
        if (newGroup == null) {
            LOG.error("Failed to create group: $groupName")
        } else {
            LOG.info("Created new group: $groupName")
        }
        return newGroup
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
                val bookmarksToAdd = mutableListOf<CodeMarkInfo>()
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

                // Scan for new bookmarks using the index
                scanFilesUsingIndex(bookmarksToAdd)

                // Apply changes in UI thread
                fun applyChanges() {
                    WriteCommandAction.runWriteCommandAction(project) {
                        // Remove invalid bookmarks
                        bookmarksToRemove.forEach { bookmark ->
                            bookmarksManager?.remove(bookmark)
                        }

                        // Add new bookmarks
                        LOG.info("Adding ${bookmarksToAdd.size} bookmarks")
                        bookmarksToAdd.forEach { info ->
                            LOG.info("Adding bookmark: ${info.filePath}:${info.lineNumber} - ${info.description}")

                            // Use the file directly from the scanFileUsingIndex method
                            val fileUrl = info.filePath
                            val filePathFromUrl = fileUrl.replace("file://", "").replace("temp://", "")

                            // Try to find the file by URL first
                            var fileToUse = VirtualFileManager.getInstance().findFileByUrl(fileUrl)

                            // If that fails, try to find it by path
                            if (fileToUse == null) {
                                fileToUse = VirtualFileManager.getInstance().findFileByUrl("file://$filePathFromUrl")
                            }

                            if (fileToUse != null) {
                                LOG.info("File found: ${fileToUse.path}")
                                val group = getOrCreateCodeMarksGroup(info.suffix)
                                if (group != null) {
                                    LOG.info("Group found/created: ${group.name}")
                                    val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                                    bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                                    bookmarkState.attributes.putAll(mapOf(
                                        "file" to fileToUse.path,
                                        "url" to fileToUse.url,
                                        "line" to info.lineNumber.toString()
                                    ))
                                    LOG.info("Creating bookmark with attributes: ${bookmarkState.attributes}")
                                    val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                                    if (bookmark != null) {
                                        LOG.info("Bookmark created, adding to group")
                                        group.add(bookmark, BookmarkType.DEFAULT, info.description)
                                        LOG.info("Bookmark added to group")
                                    } else {
                                        LOG.error("Failed to create bookmark")
                                    }
                                } else {
                                    LOG.error("Failed to get or create group for suffix: ${info.suffix}")
                                }
                            } else {
                                LOG.error("File not found for URL: ${fileUrl} or path: ${filePathFromUrl}")
                            }
                        }

                        // Verify bookmarks were added
                        val bookmarks = bookmarksManager?.bookmarks ?: emptyList()
                        LOG.info("After adding, bookmarks count: ${bookmarks.size}")
                        bookmarks.forEach { bookmark ->
                            LOG.info("Bookmark: ${bookmark.attributes["file"]}:${bookmark.attributes["line"]}")
                        }
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
                val bookmarksToAdd = mutableListOf<CodeMarkInfo>()
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

                // Scan file for new bookmarks using the index
                scanFileUsingIndex(file, bookmarksToAdd)

                // Apply changes in UI thread
                fun applyChanges() {
                    WriteCommandAction.runWriteCommandAction(project) {
                        // Remove invalid bookmarks
                        bookmarksToRemove.forEach { bookmark ->
                            bookmarksManager?.remove(bookmark)
                        }

                        // Add new bookmarks
                        LOG.info("Adding ${bookmarksToAdd.size} bookmarks for file ${file.path}")
                        bookmarksToAdd.forEach { info ->
                            LOG.info("Adding bookmark: ${info.filePath}:${info.lineNumber} - ${info.description}")

                            // Use the file directly from the scanFileUsingIndex method
                            val fileUrl = info.filePath
                            val filePathFromUrl = fileUrl.replace("file://", "").replace("temp://", "")

                            // Try to find the file by URL first
                            var fileToUse = VirtualFileManager.getInstance().findFileByUrl(fileUrl)

                            // If that fails, try to find it by path
                            if (fileToUse == null) {
                                fileToUse = VirtualFileManager.getInstance().findFileByUrl("file://$filePathFromUrl")
                            }

                            // If that fails too, try to use the original file if we're in a single file scan
                            if (fileToUse == null && file.path == filePathFromUrl) {
                                fileToUse = file
                            }

                            if (fileToUse != null) {
                                LOG.info("File found: ${fileToUse.path}")
                                val group = getOrCreateCodeMarksGroup(info.suffix)
                                if (group != null) {
                                    LOG.info("Group found/created: ${group.name}")
                                    val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                                    bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                                    bookmarkState.attributes.putAll(mapOf(
                                        "file" to fileToUse.path,
                                        "url" to fileToUse.url,
                                        "line" to info.lineNumber.toString()
                                    ))
                                    LOG.info("Creating bookmark with attributes: ${bookmarkState.attributes}")
                                    val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                                    if (bookmark != null) {
                                        LOG.info("Bookmark created, adding to group")
                                        group.add(bookmark, BookmarkType.DEFAULT, info.description)
                                        LOG.info("Bookmark added to group")
                                    } else {
                                        LOG.error("Failed to create bookmark")
                                    }
                                } else {
                                    LOG.error("Failed to get or create group for suffix: ${info.suffix}")
                                }
                            } else {
                                LOG.error("File not found for URL: ${fileUrl} or path: ${filePathFromUrl}")
                            }
                        }

                        // Verify bookmarks were added
                        val bookmarks = bookmarksManager?.bookmarks ?: emptyList()
                        LOG.info("After adding, bookmarks count: ${bookmarks.size}")
                        bookmarks.forEach { bookmark ->
                            LOG.info("Bookmark: ${bookmark.attributes["file"]}:${bookmark.attributes["line"]}")
                        }
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

    private fun scanFilesUsingIndex(bookmarksToAdd: MutableList<CodeMarkInfo>) {
        LOG.info("Starting scanFilesUsingIndex")
        try {
            ReadAction.run<RuntimeException> {
                val index = FileBasedIndex.getInstance()
                val allKeys = index.getAllKeys(CodeMarksIndex.NAME, project)
                LOG.info("Found ${allKeys.size} keys in the index")

                for (key in allKeys) {
                    val values = index.getValues(CodeMarksIndex.NAME, key, GlobalSearchScope.projectScope(project))
                    LOG.info("Found ${values.size} values for key $key")
                    for (codeMarks in values) {
                        LOG.info("Adding ${codeMarks.size} CodeMarks from key $key")
                        bookmarksToAdd.addAll(codeMarks)
                    }
                }
            }

            // If no bookmarks were found using the index, fall back to the old directory traversal method
            if (bookmarksToAdd.isEmpty()) {
                LOG.warn("No bookmarks found using the index, falling back to directory traversal")
                scanDirectoryForBookmarks(bookmarksToAdd)
            }
        } catch (e: Exception) {
            LOG.error("Error in scanFilesUsingIndex", e)
            // Fall back to the old directory traversal method
            scanDirectoryForBookmarks(bookmarksToAdd)
        }
        LOG.info("Completed scanFilesUsingIndex, found ${bookmarksToAdd.size} bookmarks")
    }

    private fun scanDirectoryForBookmarks(bookmarksToAdd: MutableList<CodeMarkInfo>) {
        LOG.info("Starting scanDirectoryForBookmarks")
        try {
            val contentRoots = ReadAction.compute<Array<VirtualFile>, RuntimeException> {
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
            }

            if (contentRoots == null) {
                LOG.warn("No content roots found")
                return
            }

            LOG.info("Found ${contentRoots.size} content roots")
            for (dir in contentRoots) {
                scanDirectoryRecursively(dir, bookmarksToAdd)
            }
        } catch (e: Exception) {
            LOG.error("Error in scanDirectoryForBookmarks", e)
        }
        LOG.info("Completed scanDirectoryForBookmarks, found ${bookmarksToAdd.size} bookmarks")
    }

    private fun scanDirectoryRecursively(dir: VirtualFile, bookmarksToAdd: MutableList<CodeMarkInfo>) {
        try {
            val children = ReadAction.compute<Array<VirtualFile>, RuntimeException> {
                dir.children
            } ?: return

            for (file in children) {
                when {
                    file.isDirectory -> scanDirectoryRecursively(file, bookmarksToAdd)
                    shouldScanFile(file) -> scanFileForBookmarks(file, bookmarksToAdd)
                }
            }
        } catch (e: Exception) {
            LOG.error("Error in scanDirectoryRecursively for ${dir.path}", e)
        }
    }

    private fun scanFileForBookmarks(file: VirtualFile, bookmarksToAdd: MutableList<CodeMarkInfo>) {
        try {
            LOG.info("Scanning file for bookmarks: ${file.path} with URL: ${file.url}, protocol: ${file.url.substringBefore(":")}")
            val document = ReadAction.compute<Document?, RuntimeException> {
                fileDocumentManager.getDocument(file)
            } ?: return

            val text = document.text
            val matcher = BOOKMARK_PATTERN.matcher(text)

            while (matcher.find()) {
                val startOffset = matcher.start()
                val lineNumber = document.getLineNumber(startOffset)
                val suffix = matcher.group(1)
                val description = matcher.group(2).trim()

                LOG.info("Found CodeMark in ${file.path} at line $lineNumber: $description")
                // Store the URL instead of the path to ensure we can find the file later
                // Make sure the URL uses the file:// protocol
                val fileUrl = if (file.url.startsWith("temp://")) {
                    "file://" + file.path
                } else {
                    file.url
                }
                LOG.info("Using URL: $fileUrl for file: ${file.path}")
                bookmarksToAdd.add(CodeMarkInfo(fileUrl, lineNumber, description, suffix))
            }
        } catch (e: Exception) {
            LOG.error("Error in scanFileForBookmarks for ${file.path}", e)
        }
    }

    private fun scanFileUsingIndex(file: VirtualFile, bookmarksToAdd: MutableList<CodeMarkInfo>) {
        LOG.info("Starting scanFileUsingIndex for ${file.path}")
        try {
            ReadAction.run<RuntimeException> {
                val index = FileBasedIndex.getInstance()
                val values = index.getValues(CodeMarksIndex.NAME, file.path, GlobalSearchScope.projectScope(project))
                LOG.info("Found ${values.size} values for file ${file.path}")

                for (codeMarks in values) {
                    LOG.info("Adding ${codeMarks.size} CodeMarks from file ${file.path}")
                    bookmarksToAdd.addAll(codeMarks)
                }
            }

            // If no bookmarks were found using the index, fall back to scanning the file directly
            if (bookmarksToAdd.isEmpty()) {
                LOG.warn("No bookmarks found using the index for ${file.path}, falling back to direct file scan")
                scanFileForBookmarks(file, bookmarksToAdd)
            }
        } catch (e: Exception) {
            LOG.error("Error in scanFileUsingIndex for ${file.path}", e)
            // Fall back to scanning the file directly
            scanFileForBookmarks(file, bookmarksToAdd)
        }
        LOG.info("Completed scanFileUsingIndex for ${file.path}, found ${bookmarksToAdd.size} bookmarks")
    }

    private fun isValidBookmark(filePath: String?, lineNumber: Int?, description: String?): Boolean {
        LOG.info("Checking if bookmark is valid: filePath=$filePath, lineNumber=$lineNumber, description=$description")
        if (filePath == null || lineNumber == null || description == null) {
            LOG.warn("Invalid bookmark: null parameters")
            return false
        }

        val file = VirtualFileManager.getInstance().findFileByUrl("file://$filePath")
        if (file == null) {
            LOG.warn("Invalid bookmark: file not found for path $filePath")
            return false
        }

        if (!shouldScanFile(file)) {
            LOG.warn("Invalid bookmark: file ${file.path} should not be scanned")
            return false
        }

        // Check if application is available before using ReadAction
        val application = ApplicationManager.getApplication()
        if (application == null) {
            LOG.warn("Application is not available, skipping document retrieval for ${file.path}")
            return false
        }

        val document = ReadAction.compute<Document?, RuntimeException> {
            fileDocumentManager.getDocument(file)
        }
        if (document == null) {
            LOG.warn("Invalid bookmark: document not found for file ${file.path}")
            return false
        }

        if (lineNumber >= document.lineCount) {
            LOG.warn("Invalid bookmark: line number $lineNumber is out of range for file ${file.path} with ${document.lineCount} lines")
            return false
        }

        // First try to use the index to check if this bookmark is still valid
        try {
            val isValidFromIndex = ReadAction.compute<Boolean, RuntimeException> {
                val index = FileBasedIndex.getInstance()
                val values = index.getValues(CodeMarksIndex.NAME, file.path, GlobalSearchScope.projectScope(project))
                LOG.info("Found ${values.size} values for file ${file.path} in index")

                for (codeMarks in values) {
                    for (codeMark in codeMarks) {
                        if (codeMark.lineNumber == lineNumber && codeMark.description == description) {
                            LOG.info("Bookmark found in index: ${file.path}:$lineNumber - $description")
                            return@compute true
                        }
                    }
                }

                false
            }

            if (isValidFromIndex) {
                return true
            }
        } catch (e: Exception) {
            LOG.error("Error checking bookmark validity using index for ${file.path}", e)
        }

        // If the index check failed or didn't find the bookmark, fall back to checking the file directly
        LOG.info("Bookmark not found in index, checking file directly: ${file.path}:$lineNumber - $description")
        return ReadAction.compute<Boolean, RuntimeException> {
            try {
                // Get the line text
                val startOffset = document.getLineStartOffset(lineNumber)
                val endOffset = document.getLineEndOffset(lineNumber)
                val line = document.getText(TextRange(startOffset, endOffset))
                LOG.info("Line text: $line")

                // Use the regex pattern to check if the line contains a CodeMark comment
                val matcher = BOOKMARK_PATTERN.matcher(line)
                if (!matcher.find()) {
                    LOG.warn("Invalid bookmark: no CodeMark pattern found in line")
                    return@compute false
                }

                val foundDescription = matcher.group(2).trim()
                val isValid = foundDescription == description
                LOG.info("Bookmark validity check result: $isValid (found description: $foundDescription)")
                isValid
            } catch (e: Exception) {
                LOG.error("Error checking bookmark validity directly for ${file.path}", e)
                false
            }
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        alarm.dispose()
    }

    override fun organizeGroups() {
        LOG.info("organizeGroups called in CodeMarkIndexService")
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
                        // For empty groups, we can't remove the group itself,
                        // but we can ensure it stays empty
                        LOG.info("Found empty group: ${group.name}")
                    } else {
                        // Sort bookmarks within the group by description
                        val sortedBookmarks = bookmarks.sortedBy { bookmark -> 
                            group.getDescription(bookmark) ?: "" 
                        }

                        // Reorder bookmarks in the group
                        if (sortedBookmarks != bookmarks) {
                            // Remove all bookmarks
                            bookmarks.forEach { bookmark ->
                                group.remove(bookmark)
                            }

                            // Add them back in sorted order
                            sortedBookmarks.forEach { bookmark ->
                                val description = group.getDescription(bookmark) ?: ""
                                group.add(bookmark, BookmarkType.DEFAULT, description)
                            }
                        }
                    }
                }

                // For now, we can't directly sort groups or remove empty ones
                // This would require more complex manipulation of the BookmarksManager API
                // The current implementation sorts bookmarks within groups by description
                // and logs empty groups for debugging
            }
        }
    }
}
