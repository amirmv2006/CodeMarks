package ir.amv.os.codemarks.actions

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ir.amv.os.codemarks.services.CodeMarkService
import com.intellij.openapi.components.service

class AddBookmarkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        addBookmark(project, editor, file)
    }

    private fun addBookmark(project: Project, editor: Editor, file: VirtualFile) {
        WriteCommandAction.runWriteCommandAction(project) {
            val bookmarkManager = BookmarkManager.getInstance(project)
            val line = editor.caretModel.logicalPosition.line
            val description = "CodeMark"
            bookmarkManager.addEditorBookmark(editor, line)?.let {
                bookmarkManager.setDescription(it, description)
            }
            
            // Trigger a rescan to ensure bookmark is properly synced
            project.service<CodeMarkService>().scanAndSync()
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
} 