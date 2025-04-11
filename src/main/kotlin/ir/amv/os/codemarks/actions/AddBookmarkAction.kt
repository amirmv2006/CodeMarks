package ir.amv.os.codemarks.actions

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import ir.amv.os.codemarks.services.CodeMarkService
import com.intellij.openapi.components.service

class AddBookmarkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        addBookmark(project, editor)
    }

    private fun addBookmark(project: Project, editor: Editor) {
        WriteCommandAction.runWriteCommandAction(project) {
            val line = editor.caretModel.logicalPosition.line
            val bookmarkManager = BookmarkManager.getInstance(project)
            bookmarkManager.addTextBookmark(editor.virtualFile, line, "CodeMarks: CodeMark")
            
            // Trigger a rescan to ensure bookmark is properly synced
            project.service<CodeMarkService>().scanAndSync()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
} 