package ir.amv.os.codemarks.services

import com.intellij.ide.bookmark.BookmarksManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeMarkServiceTest : BasePlatformTestCase() {

    private lateinit var service: CodeMarkService
    private val bookmarksManager: BookmarksManager
        get() = BookmarksManager.getInstance(project)!!

    override fun setUp() {
        super.setUp()
        service = CodeMarkService.getInstance(project)
        bookmarksManager.bookmarks.forEach { bookmarksManager.remove(it) }
    }

    override fun tearDown() {
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                bookmarksManager.bookmarks.forEach { bookmarksManager.remove(it) }
            }
        }
        super.tearDown()
    }

    @Test
    fun testJavaFileBookmarkCreation() {
        myFixture.configureByText("Test.java", """
            public class Test {
                // Some code
                // Some more code
                // CodeMarks: Test bookmark
                // Final line
            }
        """.trimIndent())

        WriteCommandAction.runWriteCommandAction(project) {
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
        }

        ApplicationManager.getApplication().invokeAndWait({
            service.scanAndSync()
        }, ModalityState.defaultModalityState())

        val bookmarks = bookmarksManager.bookmarks
        assertEquals(1, bookmarks.size, "Expected exactly one bookmark")
        val bookmark = bookmarks.first()
        val attributes = bookmark.attributes
        println("[DEBUG_LOG] Line attribute value: '${attributes["line"]}'")
        val description = bookmarksManager.groups.find { group -> 
            group.getBookmarks().contains(bookmark)
        }?.getDescription(bookmark)
        println("[DEBUG_LOG] Description value: '$description'")
        assertTrue("Line attribute should be '3' but was '${attributes["line"]}'", attributes["line"] == "3")
        assertTrue("Description should be 'Test bookmark' but was '$description'", 
                  description == "Test bookmark")
    }
} 
