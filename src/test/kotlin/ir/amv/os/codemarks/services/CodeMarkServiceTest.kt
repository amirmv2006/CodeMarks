package ir.amv.os.codemarks.services

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import kotlin.test.assertEquals

class CodeMarkServiceTest : BasePlatformTestCase() {

    private lateinit var service: CodeMarkService
    private lateinit var bookmarkManager: BookmarkManager

    override fun setUp() {
        super.setUp()
        service = CodeMarkService.getInstance(project)
        bookmarkManager = BookmarkManager.getInstance(project)
    }

    @Test
    fun testJavaFileBookmarkCreation() {
        val file = myFixture.configureByText("Test.java", """
            public class Test {
                // Some code
                // Some more code
                // @bookmark Test bookmark
                // Final line
            }
        """.trimIndent())

        ApplicationManager.getApplication().invokeAndWait({
            WriteCommandAction.runWriteCommandAction(project) {
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            }
            service.scanAndSync()
            // Wait for alarm to process and bookmark to be created
            Thread.sleep(500)
        }, ModalityState.defaultModalityState())

        val bookmarks = bookmarkManager.validBookmarks
        assertEquals(1, bookmarks.size, "Expected exactly one bookmark")
        assertEquals(4, bookmarks[0].line, "Bookmark should be on line 4")
        assertEquals("Test bookmark", bookmarks[0].description, "Bookmark description should match")
    }
} 