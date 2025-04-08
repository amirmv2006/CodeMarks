package ir.amv.os.codemarks.services

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.ide.bookmarks.Bookmark

@Service(Service.Level.PROJECT)
class CodeMarkService(private val project: Project) {
    companion object {
        fun getInstance(project: Project): CodeMarkService = project.service()
    }

    private val bookmarkManager = BookmarkManager.getInstance(project)
    private val psiManager = PsiManager.getInstance(project)

    fun scanAndSyncCodeMarks() {
        val existingBookmarks = bookmarkManager.allBookmarks.toList()
        val codeMarkComments = mutableListOf<Pair<VirtualFile, Int>>()
        
        // Scan all files for CodeMark comments
        project.baseDir?.children?.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, codeMarkComments)
            } else {
                scanFile(file, codeMarkComments)
            }
        }

        // Remove bookmarks that don't have corresponding comments
        existingBookmarks.forEach { bookmark: Bookmark ->
            val hasMatchingComment = codeMarkComments.any { 
                it.first == bookmark.file && it.second == bookmark.line 
            }
            if (!hasMatchingComment) {
                bookmarkManager.removeBookmark(bookmark)
            }
        }

        // Add bookmarks for new comments
        codeMarkComments.forEach { (file, line) ->
            val hasExistingBookmark = existingBookmarks.any { bookmark: Bookmark -> 
                bookmark.file == file && bookmark.line == line 
            }
            if (!hasExistingBookmark) {
                val psiFile = psiManager.findFile(file) ?: return@forEach
                val document = psiFile.viewProvider.document ?: return@forEach
                val lineText = document.text.split("\n")[line].trim()
                val description = lineText.substringAfter("CodeMarks: ").trim()
                bookmarkManager.addTextBookmark(file, line, description)
            }
        }
    }

    private fun scanDirectory(dir: VirtualFile, codeMarkComments: MutableList<Pair<VirtualFile, Int>>) {
        dir.children.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, codeMarkComments)
            } else {
                scanFile(file, codeMarkComments)
            }
        }
    }

    private fun scanFile(file: VirtualFile, codeMarkComments: MutableList<Pair<VirtualFile, Int>>) {
        val psiFile = psiManager.findFile(file) ?: return
        val document = psiFile.viewProvider.document ?: return

        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiComment) {
                    val text = element.text
                    if (text.contains("CodeMarks: ")) {
                        val lineNumber = document.getLineNumber(element.textOffset)
                        codeMarkComments.add(Pair(file, lineNumber))
                    }
                }
                super.visitElement(element)
            }
        })
    }
} 