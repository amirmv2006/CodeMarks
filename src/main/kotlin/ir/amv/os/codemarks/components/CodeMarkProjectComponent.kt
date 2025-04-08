package ir.amv.os.codemarks.components

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import ir.amv.os.codemarks.services.CodeMarkService

class CodeMarkStartupActivity : StartupActivity {
    override fun runActivity(project: Project) {
        val codeMarkService = CodeMarkService.getInstance(project)
        val connection = project.messageBus.connect()

        // Initial scan
        codeMarkService.scanAndSyncCodeMarks()

        // Listen for file changes
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                codeMarkService.scanAndSyncCodeMarks()
            }
        })

        // Disconnect on project close
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun before(events: List<VFileEvent>) {
                if (project.isDisposed) {
                    connection.disconnect()
                }
            }
        })
    }
} 