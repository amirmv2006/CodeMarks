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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import java.util.concurrent.ConcurrentHashMap

interface CodeMarkService {
    fun scanAndSync()
    fun getSettings(): CodeMarkSettings

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
        private const val CODEMARKS_GROUP_NAME = "CodeMarks"
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
                    scheduleSync()
                }
            }
        })

        val connection = project.messageBus.connect()
        connection.subscribe(Topic.create("DocumentListener", DocumentListener::class.java, Topic.BroadcastDirection.NONE), object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = fileDocumentManager.getFile(event.document)
                if (file != null && shouldScanFile(file)) {
                    scheduleSync()
                }
            }
        })

        connection.subscribe(Topic.create("FileDocumentManagerListener", FileDocumentManagerListener::class.java, Topic.BroadcastDirection.NONE), object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = fileDocumentManager.getFile(document)
                if (file != null && shouldScanFile(file)) {
                    LOG.warn("beforeDocumentSaving: ${file.path}")
                    scheduleSync()
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
        val fileName = file.name
        return settings.fileTypePatterns.any { pattern ->
            when (pattern) {
                "*" -> true
                else -> fileName.matches(pattern.toRegex())
            }
        }
    }

    private fun scheduleSync() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            ApplicationManager.getApplication().invokeAndWait({
                doScanAndSync()
            }, ModalityState.defaultModalityState())
        }, 100)
    }

    override fun scanAndSync() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({
                doScanAndSync()
            }, ModalityState.defaultModalityState())
            return
        }
        doScanAndSync()
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

        WriteCommandAction.runWriteCommandAction(project) {
            try {
                // Clear existing bookmarks first
                bookmarksManager?.let { manager ->
                    manager.bookmarks.forEach { bookmark ->
                        val text = bookmark.toString()
                        if (text.startsWith("CodeMarks:")) {
                            manager.remove(bookmark)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error clearing bookmarks", e)
            }
        }

        // Scan all content roots
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            if (!root.isValid) continue
            scanDirectory(root)
        }
    }

    private fun scanDirectory(dir: VirtualFile) {
        val children = ReadAction.compute<Array<VirtualFile>, RuntimeException> {
            dir.children
        } ?: return

        children.forEach { file ->
            when {
                file.isDirectory -> scanDirectory(file)
                shouldScanFile(file) -> scanFile(file)
            }
        }
    }

    private fun scanFile(file: VirtualFile) {
        val lastModified = file.timeStamp
        val lastScanned = settings.lastScanState[file.path]
        
        // Skip if file hasn't changed since last scan
        if (lastScanned != null && lastScanned == lastModified) {
            return
        }

        val document = ReadAction.compute<Document?, RuntimeException> {
            fileDocumentManager.getDocument(file)
        } ?: return

        val text = ReadAction.compute<String, RuntimeException> {
            document.text
        }

        text.lines().forEachIndexed { index, line ->
            val matcher = BOOKMARK_PATTERN.matcher(line)
            if (matcher.find()) {
                val suffix = matcher.group(1)
                val description = matcher.group(2).trim()
                LOG.info("Found bookmark at ${file.path}:${index + 1} with description: $description in group: $suffix")
                try {
                    // Get our group and check if a bookmark already exists at this line
                    val group = getOrCreateCodeMarksGroup(suffix)
                    val existingBookmark = group?.getBookmarks()?.find { bookmark ->
                        val attributes = bookmark.attributes
                        attributes["url"] == file.url && 
                        attributes["line"] == index.toString()
                    }

                    // First, check if this bookmark exists in any other group
                    val bookmarkInOtherGroup = if (existingBookmark == null) {
                        bookmarksManager?.bookmarks?.find { bookmark ->
                            val attributes = bookmark.attributes
                            attributes["url"] == file.url && 
                            attributes["line"] == index.toString()
                        }
                    } else null

                    // If found in another group, remove it
                    if (bookmarkInOtherGroup != null) {
                        bookmarksManager?.remove(bookmarkInOtherGroup)
                    }

                    // Now handle the description comparison if bookmark exists in our group
                    if (existingBookmark != null) {
                        val existingDescription = group?.getDescription(existingBookmark)
                        if (existingDescription == description) {
                            // Bookmark exists with the same description, skip
                            return@forEachIndexed
                        } else {
                            // Bookmark exists but with different description, remove it
                            group?.remove(existingBookmark)
                        }
                    }

                    // Create new bookmark
                    val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                    bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                    bookmarkState.attributes.putAll(mapOf(
                        "file" to file.path,
                        "url" to file.url,
                        "line" to index.toString(),
                        "description" to description
                    ))
                    val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                    if (bookmark != null) {
                        group?.add(bookmark, BookmarkType.DEFAULT, description)
                        
                        // Clean up empty CodeMarks groups
                        bookmarksManager?.groups?.forEach { g ->
                            if (g.name.startsWith(CODEMARKS_GROUP_NAME) && g.getBookmarks().isEmpty()) {
                                g.remove()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to add bookmark at ${file.path}:${index + 1}", e)
                }
            }
        }

        // Update last scan time
        settings.lastScanState[file.path] = lastModified
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        Disposer.dispose(alarm)
    }
} 