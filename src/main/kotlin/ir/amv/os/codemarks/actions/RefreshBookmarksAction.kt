package ir.amv.os.codemarks.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import ir.amv.os.codemarks.services.CodeMarkService

class RefreshBookmarksAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = CodeMarkService.getInstance(project)
        
        // Clear last scan state to force full rescan
        service.getSettings().lastScanState.clear()
        
        // Trigger rescan
        service.scanAndSync()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
} 