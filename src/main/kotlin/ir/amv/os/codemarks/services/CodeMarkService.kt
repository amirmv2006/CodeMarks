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

interface CodeMarkService {
    fun scanAndSync()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CodeMarkService = project.service<CodeMarkService>()
    }
}

@Service(Service.Level.PROJECT)
class CodeMarkServiceImpl(private val project: Project) : CodeMarkService, Disposable {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkServiceImpl::class.java)
        private val SUPPORTED_FILE_EXTENSIONS = setOf("java", "kt", "scala", "groovy", "xml", "gradle")
        private val BOOKMARK_PATTERN = Pattern.compile("//\\s*CodeMarks:\\s*(.*)", Pattern.CASE_INSENSITIVE)
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val bookmarksManager = BookmarksManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()

    init {
        LOG.info("Initializing CodeMarkService for project: ${project.name}")
        setupListeners()
    }

    private fun setupListeners() {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                scheduleSync()
            }
        })

        val connection = project.messageBus.connect()
        connection.subscribe(Topic.create("com.intellij.openapi.editor.event.DocumentListener", DocumentListener::class.java), object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                scheduleSync()
            }
        })

        connection.subscribe(Topic.create("com.intellij.openapi.fileEditor.FileDocumentManagerListener", FileDocumentManagerListener::class.java), object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                LOG.warn("beforeDocumentSaving: ${fileDocumentManager.getFile(document)?.path}")
                scheduleSync()
            }

            override fun beforeAllDocumentsSaving() {
                LOG.warn("beforeAllDocumentsSaving")
                scheduleSync()
            }
        })
    }

    private fun scheduleSync() {
        alarm.cancelAllRequests()
        alarm.addRequest({
            ApplicationManager.getApplication().invokeAndWait({
                doScanAndSync()
            }, ModalityState.defaultModalityState())
        }, 100)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        return file.extension in SUPPORTED_FILE_EXTENSIONS
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

        val sourceRoots = ProjectRootManager.getInstance(project).contentSourceRoots
        for (root in sourceRoots) {
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
                isSourceFile(file) -> scanFile(file)
            }
        }
    }

    private fun scanFile(file: VirtualFile) {
        val document = ReadAction.compute<Document?, RuntimeException> {
            fileDocumentManager.getDocument(file)
        } ?: return

        val text = ReadAction.compute<String, RuntimeException> {
            document.text
        }

        text.lines().forEachIndexed { index, line ->
            val matcher = BOOKMARK_PATTERN.matcher(line)
            if (matcher.find()) {
                val description = matcher.group(1).trim()
                LOG.info("Found bookmark at ${file.path}:${index + 1} with description: $description")
                try {
                    val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
                    bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
                    bookmarkState.description = "CodeMarks: $description"
                    bookmarkState.attributes.putAll(mapOf(
                        "file" to file.path,
                        "url" to file.url,
                        "line" to (index + 1).toString()
                    ))
                    val bookmark = bookmarksManager?.createBookmark(bookmarkState)
                    if (bookmark != null) {
                        bookmarksManager?.add(bookmark, BookmarkType.DEFAULT)
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to add bookmark at ${file.path}:${index + 1}", e)
                }
            }
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        Disposer.dispose(alarm)
    }
} 