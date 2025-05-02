package ir.amv.os.codemarks.services

import com.intellij.ide.bookmark.BookmarksManager
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
import org.junit.Test
import org.junit.jupiter.api.Timeout
import kotlinx.coroutines.Dispatchers
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CodeMarkServiceIntegrationTest : BasePlatformTestCase() {
    companion object {
        private val LOG = Logger.getInstance(CodeMarkServiceIntegrationTest::class.java)
    }

    private lateinit var codeMarkService: CodeMarkService
    private val bookmarksManager: BookmarksManager
        get() = BookmarksManager.getInstance(project)!!
    private lateinit var sourceFile: VirtualFile

    override fun setUp() {
        LOG.info("Starting setUp")
        super.setUp()
        LOG.info("BasePlatformTestCase.setUp completed")

        codeMarkService = project.service<CodeMarkService>()
        LOG.info("CodeMarkService initialized")

        LOG.info("BookmarksManager initialized")

        // Clean up any existing bookmarks
        LOG.info("Cleaning up existing bookmarks")
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                bookmarksManager.bookmarks.forEach { bookmarksManager.remove(it) }
            }
        }
        LOG.info("Bookmarks cleanup completed")

        // Create a source root and add a Java file
        LOG.info("Setting up source files")
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val sourceRoot = myFixture.tempDirFixture.findOrCreateDir("src")
                LOG.info("Source root created at: ${sourceRoot.path}")

                // Set up source roots
                val model = ModuleRootManager.getInstance(module).modifiableModel
                val contentEntry = model.addContentEntry(sourceRoot)
                contentEntry.addSourceFolder(sourceRoot, false)
                model.commit()
                LOG.info("Source roots configured")

                // Create a Java file with a bookmark comment
                sourceFile = sourceRoot.createChildData(this, "Test.java")
                val document = FileDocumentManager.getInstance().getDocument(sourceFile)!!
                document.setText("""
                    package test;

                    public class Test {
                        // CodeMarks: Amir
                        public void test() {
                        }
                    }
                """.trimIndent())
                FileDocumentManager.getInstance().saveDocument(document)
                LOG.info("Test.java created and saved")
            }
        }
        LOG.info("setUp completed")
    }

    override fun tearDown() {
        LOG.info("Starting tearDown")
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                bookmarksManager.bookmarks.forEach { bookmarksManager.remove(it) }
                if (sourceFile.isValid) {
                    sourceFile.delete(this)
                }
            }
        }
        LOG.info("Cleanup completed")
        super.tearDown()
        LOG.info("tearDown completed")
    }

    @Test
    fun testProjectStartupScenario() {
        LOG.info("Starting testProjectStartupScenario")

        // Explicitly scan for bookmarks
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                codeMarkService.scanAndSync()
            }
        }
        LOG.info("Explicitly scanned for bookmarks")

        // Verify bookmark details
        ApplicationManager.getApplication().invokeAndWait {
            LOG.info("Verifying bookmarks")
            val bookmarks = bookmarksManager.bookmarks
            assertTrue("No bookmarks found after startup", bookmarks.isNotEmpty())
            val bookmark = bookmarks.find { bookmark -> 
                bookmarksManager.groups.any { group ->
                    group.getDescription(bookmark)?.contains("Amir") == true
                }
            }
            assertNotNull("Bookmark with description containing 'Amir' not found after startup", bookmark)
            assertEquals("Bookmark should be on line 3", "3", bookmark!!.attributes["line"])
            assertEquals("Bookmark should be in Test.java", sourceFile.url, bookmark.attributes["url"])
        }

        LOG.info("Completed testProjectStartupScenario")
    }

    @Test
    fun testFileChangeScenario() {
        LOG.info("Starting testFileChangeScenario")

        // Modify the file
        ApplicationManager.getApplication().invokeAndWait {
            LOG.info("Modifying test file")
            WriteCommandAction.runWriteCommandAction(project) {
                val document = FileDocumentManager.getInstance().getDocument(sourceFile)!!
                document.setText("""
                    package test;

                    public class Test {
                        // CodeMarks: Updated
                        public void test() {
                        }
                    }
                """.trimIndent())
                FileDocumentManager.getInstance().saveDocument(document)
                LOG.info("Test file modified and saved")
            }
        }

        // Explicitly scan for bookmarks
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                codeMarkService.scanAndSync(sourceFile)
            }
        }
        LOG.info("Explicitly scanned for bookmarks")

        // Verify bookmark details
        ApplicationManager.getApplication().invokeAndWait {
            LOG.info("Verifying updated bookmarks")
            val bookmarks = bookmarksManager.bookmarks
            assertTrue("No bookmarks found after file change", bookmarks.isNotEmpty())
            val bookmark = bookmarks.find { bookmark -> 
                bookmarksManager.groups.any { group ->
                    group.getDescription(bookmark)?.contains("Updated") == true
                }
            }
            assertNotNull("Updated bookmark not found after file change", bookmark)
            assertEquals("Bookmark should be on line 3", "3", bookmark!!.attributes["line"])
            assertEquals("Bookmark should be in Test.java", sourceFile.url, bookmark.attributes["url"])
        }

        LOG.info("Completed testFileChangeScenario")
    }

    @Test
    fun testLoadingExistingProject() {
        LOG.info("Starting testLoadingExistingProject")

        // Explicitly scan for bookmarks
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                codeMarkService.scanAndSync()
            }
        }
        LOG.info("Explicitly scanned for bookmarks")

        // Verify bookmark details
        ApplicationManager.getApplication().invokeAndWait {
            LOG.info("Verifying bookmarks")
            val bookmarks = bookmarksManager.bookmarks
            assertTrue("No bookmarks found after project load", bookmarks.isNotEmpty())
            val bookmark = bookmarks.find { bookmark -> 
                bookmarksManager.groups.any { group ->
                    group.getDescription(bookmark)?.contains("Amir") == true
                }
            }
            assertNotNull("Bookmark with description containing 'Amir' not found after project load", bookmark)
            assertEquals("Bookmark should be on line 3", "3", bookmark!!.attributes["line"])
            assertEquals("Bookmark should be in Test.java", sourceFile.url, bookmark.attributes["url"])
        }

        LOG.info("Completed testLoadingExistingProject")
    }
} 
