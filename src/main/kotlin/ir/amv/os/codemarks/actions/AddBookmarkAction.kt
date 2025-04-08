package ir.amv.os.codemarks.actions

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class AddBookmarkAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file: VirtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val document = editor.document
        val caretLine = editor.caretModel.logicalPosition.line
        val lineText = document.text.split("\n")[caretLine].trim()
        
        val bookmarkManager = BookmarkManager.getInstance(project)
        bookmarkManager.addTextBookmark(file, caretLine, lineText)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = project != null && editor != null
    }
} 