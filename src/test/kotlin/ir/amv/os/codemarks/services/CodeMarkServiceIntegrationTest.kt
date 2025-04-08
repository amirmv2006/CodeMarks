package ir.amv.os.codemarks.services

import com.intellij.ide.bookmarks.BookmarkManager
import com.intellij.ide.bookmarks.BookmarksListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import ir.amv.os.codemarks.components.CodeMarkStartupActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.intellij.openapi.components.service

class CodeMarkServiceIntegrationTest : BasePlatformTestCase() {
    private lateinit var codeMarkService: CodeMarkService
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var sourceFile: VirtualFile
    private lateinit var startupActivity: CodeMarkStartupActivity

    override fun setUp() {
        super.setUp()
        codeMarkService = project.service<CodeMarkService>()
        bookmarkManager = BookmarkManager.getInstance(project)
        startupActivity = CodeMarkStartupActivity()

        // Create a source root and add a Java file
        WriteCommandAction.runWriteCommandAction(project) {
            val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src")
            
            // Set up source roots
            val model = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry = model.addContentEntry(sourceRoot)
            contentEntry.addSourceFolder(sourceRoot, false)
            model.commit()

            // Create a Java file with a bookmark comment
            sourceFile = sourceRoot.createChildData(this, "Test.java")
            val document = FileDocumentManager.getInstance().getDocument(sourceFile)!!
            document.setText("""
                package test;
                
                public class Test {
                    // @bookmark Amir
                    public void test() {
                    }
                }
            """.trimIndent())
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }

    override fun tearDown() {
        runBlocking {
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    bookmarkManager.validBookmarks.forEach { bookmarkManager.removeBookmark(it) }
                }
            }
        }
        super.tearDown()
    }

    fun testProjectStartupScenario() = runBlocking {
        // Wait for indexing to finish and run in smart mode
        DumbService.getInstance(project).runReadActionInSmartMode {
            // Simulate project startup
            runBlocking { startupActivity.execute(project) }

            // Give time for startup activity to complete
            Thread.sleep(500)

            // Verify bookmark was created by startup activity
            ApplicationManager.getApplication().invokeAndWait({
                val bookmarks = bookmarkManager.validBookmarks
                assertTrue("No bookmarks found after startup", bookmarks.isNotEmpty())
                val bookmark = bookmarks.find { it.description.contains("Amir") }
                assertNotNull("Bookmark with description containing 'Amir' not found after startup", bookmark)
                assertEquals("Bookmark should be on line 4", 4, bookmark!!.line)
                assertEquals("Bookmark should be in Test.java", sourceFile, bookmark.file)
            }, ModalityState.defaultModalityState())
        }
    }

    fun testFileChangeScenario() = runBlocking {
        // First clear any existing bookmarks
        ApplicationManager.getApplication().invokeAndWait({
            WriteCommandAction.runWriteCommandAction(project) {
                bookmarkManager.validBookmarks.forEach { bookmarkManager.removeBookmark(it) }
            }
        }, ModalityState.defaultModalityState())

        // Wait for indexing to finish and run in smart mode
        DumbService.getInstance(project).runReadActionInSmartMode {
            // First run startup activity to set up listeners
            runBlocking { startupActivity.execute(project) }

            // Give time for startup activity to complete
            Thread.sleep(500)

            // Modify the file
            ApplicationManager.getApplication().invokeAndWait({
                WriteCommandAction.runWriteCommandAction(project) {
                    val document = FileDocumentManager.getInstance().getDocument(sourceFile)!!
                    document.setText("""
                        package test;
                        
                        public class Test {
                            // @bookmark Updated
                            public void test() {
                            }
                        }
                    """.trimIndent())
                    FileDocumentManager.getInstance().saveDocument(document)
                }
            }, ModalityState.defaultModalityState())

            // Give time for the file change listener to process
            Thread.sleep(500)

            // Verify bookmark was updated by file listener
            ApplicationManager.getApplication().invokeAndWait({
                val bookmarks = bookmarkManager.validBookmarks
                assertTrue("No bookmarks found after file change", bookmarks.isNotEmpty())
                val bookmark = bookmarks.find { it.description.contains("Updated") }
                assertNotNull("Updated bookmark not found after file change", bookmark)
                assertEquals("Bookmark should be on line 4", 4, bookmark!!.line)
                assertEquals("Bookmark should be in Test.java", sourceFile, bookmark.file)
            }, ModalityState.defaultModalityState())
        }
    }

    fun testLoadingExistingProject() = runBlocking {
        // First clear any existing bookmarks
        ApplicationManager.getApplication().invokeAndWait({
            WriteCommandAction.runWriteCommandAction(project) {
                bookmarkManager.validBookmarks.forEach { bookmarkManager.removeBookmark(it) }
            }
        }, ModalityState.defaultModalityState())

        // Wait for indexing to finish and run in smart mode
        DumbService.getInstance(project).runReadActionInSmartMode {
            // Simulate IDE startup with existing project
            StartupManager.getInstance(project).runWhenProjectIsInitialized {
                runBlocking { startupActivity.execute(project) }
            }

            // Give time for startup activity to complete
            Thread.sleep(1000) // Increased sleep time for startup activity

            // Verify bookmark was created by startup activity
            ApplicationManager.getApplication().invokeAndWait({
                val bookmarks = bookmarkManager.validBookmarks
                assertTrue("No bookmarks found after project load", bookmarks.isNotEmpty())
                val bookmark = bookmarks.find { it.description.contains("Amir") }
                assertNotNull("Bookmark with description containing 'Amir' not found after project load", bookmark)
                assertEquals("Bookmark should be on line 4", 4, bookmark!!.line)
                assertEquals("Bookmark should be in Test.java", sourceFile, bookmark.file)
            }, ModalityState.defaultModalityState())
        }
    }
} 