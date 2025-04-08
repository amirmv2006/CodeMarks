package ir.amv.os.codemarks.services

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
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

interface CodeMarkService {
    fun scanAndSync()

    companion object {
        fun getInstance(project: Project): CodeMarkService = project.service<CodeMarkService>()
    }
}

@Service(Service.Level.PROJECT)
class CodeMarkServiceImpl(private val project: Project) : CodeMarkService, Disposable {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkServiceImpl::class.java)
        private val SUPPORTED_FILE_EXTENSIONS = setOf("java", "kt", "scala", "groovy", "xml", "gradle")
        private val BOOKMARK_PATTERN = Pattern.compile("//\\s*@bookmark\\s*(.*)")
    }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()

    init {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                scheduleSync()
            }
        })

        fileDocumentManager.addFileDocumentListener(object : com.intellij.openapi.fileEditor.FileDocumentManagerListener {
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
            ApplicationManager.getApplication().invokeLater({
                WriteCommandAction.runWriteCommandAction(project) {
                    scanAndSync()
                }
            }, ModalityState.defaultModalityState())
        }, 100)
    }

    private fun isSourceFile(file: VirtualFile): Boolean {
        return file.extension in SUPPORTED_FILE_EXTENSIONS
    }

    override fun scanAndSync() {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().invokeLater({ 
                WriteCommandAction.runWriteCommandAction(project) {
                    scanAndSync()
                }
            }, ModalityState.defaultModalityState())
            return
        }

        LOG.info("Scanning for bookmarks")
        val existingBookmarks = bookmarkManager.validBookmarks
        val existingBookmarkFiles = existingBookmarks.map { it.file }.toSet()

        // Remove all existing bookmarks
        existingBookmarks.forEach { bookmarkManager.removeBookmark(it) }

        // Scan project files for bookmarks
        scanDirectory(project.baseDir)
    }

    private fun scanDirectory(dir: VirtualFile) {
        dir.children?.forEach { file ->
            when {
                file.isDirectory -> scanDirectory(file)
                isSourceFile(file) -> scanFile(file)
            }
        }
    }

    private fun scanFile(file: VirtualFile) {
        val document = fileDocumentManager.getDocument(file) ?: return
        val text = document.text

        text.lines().forEachIndexed { index, line ->
            val matcher = BOOKMARK_PATTERN.matcher(line)
            if (matcher.find()) {
                val description = matcher.group(1).trim()
                LOG.info("Found bookmark at ${file.path}:${index + 1} with description: $description")
                bookmarkManager.addFileBookmark(file, index, description)
            }
        }
    }

    override fun dispose() {
        // No cleanup needed
    }
} 