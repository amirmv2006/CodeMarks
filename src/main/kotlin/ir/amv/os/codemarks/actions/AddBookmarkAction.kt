package ir.amv.os.codemarks.actions

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.ide.bookmark.BookmarkType
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
        addBookmark(editor, project)
    }

    private fun addBookmark(editor: Editor, project: Project) {
        WriteCommandAction.runWriteCommandAction(project) {
            val line = editor.caretModel.logicalPosition.line
            val bookmarksManager = BookmarksManager.getInstance(project)
            val bookmarkState = com.intellij.ide.bookmark.BookmarkState()
            bookmarkState.provider = "com.intellij.ide.bookmark.providers.LineBookmarkProvider"
            bookmarkState.description = "CodeMarks: Bookmark"
            bookmarkState.attributes.putAll(mapOf(
                "file" to editor.virtualFile.path,
                "url" to editor.virtualFile.url,
                "line" to (line + 1).toString(),
                "description" to "CodeMarks: Bookmark"
            ))
            val bookmark = bookmarksManager?.createBookmark(bookmarkState)
            if (bookmark != null) {
                bookmarksManager?.add(bookmark, BookmarkType.DEFAULT)
            }
            
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