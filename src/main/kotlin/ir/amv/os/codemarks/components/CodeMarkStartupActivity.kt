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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class CodeMarkStartupActivity : ProjectActivity, DumbAware {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkStartupActivity::class.java)
    }

    override suspend fun execute(project: Project) {
        LOG.info("CodeMarkStartupActivity executing for project: ${project.name}")
        val codeMarkService = CodeMarkService.getInstance(project)
        val isTestMode = ApplicationManager.getApplication().isUnitTestMode

        withContext(Dispatchers.Default) {
            // Initial scan
            LOG.info("Starting initial CodeMarks scan")
            if (isTestMode) {
                // In test mode, run synchronously on the main thread
                withContext(Dispatchers.Main) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        codeMarkService.scanAndSync()
                    }
                }
                // Make sure we wait for any background tasks to complete
                Thread.sleep(500)
            } else {
                // In normal mode, let the service handle the background processing
                // This will prevent IntelliJ from hanging during startup
                LOG.info("Running initial scan in background")
                // Check if project is disposed before running scan
                if (!project.isDisposed) {
                    codeMarkService.scanAndSync()
                } else {
                    LOG.warn("Project is disposed, skipping initial CodeMarks scan")
                }
            }
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
                                    // Check if project is disposed before running write action
                                    if (!project.isDisposed) {
                                        WriteCommandAction.runWriteCommandAction(project) {
                                            codeMarkService.scanAndSync(file)
                                        }
                                    } else {
                                        LOG.warn("Project is disposed, skipping CodeMarks scan for ${file.path}")
                                    }
                                }, ModalityState.any())
                            }
                        }
                    }
                }
            )
            LOG.info("CodeMarkStartupActivity setup completed")
        }
    }
} 
