package ir.amv.os.codemarks.components

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import ir.amv.os.codemarks.services.CodeMarkService
import ir.amv.os.codemarks.services.CodeMarkServiceImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CodeMarkStartupActivity : ProjectActivity, DumbAware {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        LOG.info("CodeMarkStartupActivity executing for project: ${project.name}")
        val codeMarkService = CodeMarkService.getInstance(project)
        
        withContext(Dispatchers.Default) {
            // Initial scan
            LOG.info("Starting initial CodeMarks scan")
            ApplicationManager.getApplication().invokeAndWait({
                WriteCommandAction.runWriteCommandAction(project) {
                    codeMarkService.scanAndSync()
                }
            }, ModalityState.defaultModalityState())
            LOG.info("Initial CodeMarks scan completed")

            // Listen for file changes
            LOG.info("Setting up file change listener")
            project.messageBus.connect(project).subscribe(
                VirtualFileManager.VFS_CHANGES,
                object : BulkFileListener {
                    override fun after(events: List<VFileEvent>) {
                        for (event in events) {
                            val file = event.file
                            if (file != null) {
                                LOG.info("File change detected for ${file.path}, rescanning CodeMarks")
                                ApplicationManager.getApplication().invokeLater({
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        codeMarkService.scanAndSync(file)
                                    }
                                }, ModalityState.defaultModalityState())
                            }
                        }
                    }
                }
            )
            LOG.info("CodeMarkStartupActivity setup completed")
        }
    }
} 