package dev.inmo.dinavigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Exercises the production coroutine worker; waiting never holds the EDT or a read action. */
class DiAsyncIndexTest : LightJavaCodeInsightFixtureTestCase() {
    override fun runInDispatchThread() = false

    override fun setUp() {
        super.setUp()
        val jars = System.getProperty("di.fixture.libraries").split(File.pathSeparator)
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            ModuleRootModificationUtil.addModuleLibrary(module, "Koin and MicroUtils async fixtures",
                jars.map { VfsUtil.getUrlForLibraryRoot(File(it)) }, emptyList())
        }
    }

    fun testProductionWorkerRefreshesAndDeliversNavigationOnEdt() {
        val application = ApplicationManager.getApplication()
        assertFalse(application.isDispatchThread)
        assertFalse(application.isReadAccessAllowed)
        val usages = EdtTestUtil.runInEdtAndGet<KtFile, RuntimeException> {
            myFixture.addFileToProject("Definitions.kt", """
                package asyncsample
                import org.koin.core.module.Module
                import org.koin.core.qualifier.named
                class AsyncService
                fun Module.install() {
                    single(named("blue")) { AsyncService() }
                    single(named("red")) { AsyncService() }
                }
            """.trimIndent())
            (myFixture.addFileToProject("Usages.kt", """
                package asyncsample
                import org.koin.core.Koin
                import org.koin.core.qualifier.named
                fun use(koin: Koin): AsyncService = koin.get<AsyncService>(named("blue"))
            """.trimIndent()) as KtFile).also {
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val service = DiNavigationService.getInstance(project)
        val worker = EdtTestUtil.runInEdtAndGet<kotlinx.coroutines.Job, RuntimeException> {
            service.requestRefresh()
            service.startWorkerForTests()
        }
        try {
            awaitReady(service)
            val origin = ReadAction.compute<DiSite, RuntimeException> {
                service.sites(usages).single { it.call.kind == DiKind.LOOKUP }
            }
            val first = request(service, origin)
            assertEquals(DiQualifier.Named("blue"), first.single().call.qualifier)
            assertEquals(DiKind.DEFINITION, first.single().call.kind)
            assertEquals(setOf("asyncsample.AsyncService"), first.single().call.types)
            val firstOffset = first.single().offset
            val analyzedBeforeEdit = service.analyzedFileCount

            EdtTestUtil.runInEdtAndWait<RuntimeException> {
                val manager = PsiDocumentManager.getInstance(project)
                val document = manager.getDocument(usages)!!
                WriteCommandAction.runWriteCommandAction(project) {
                    val offset = document.text.indexOf("blue")
                    assertTrue(offset >= 0)
                    document.replaceString(offset, offset + "blue".length, "red")
                }
                manager.commitAllDocuments()
                assertFalse("The PSI change must invalidate the published index", service.isReady)
            }
            awaitReady(service)
            assertTrue("The production worker must refresh the edited file", service.analyzedFileCount > analyzedBeforeEdit)
            // Reuse the original marker, as a click can arrive while its file is being refreshed.
            val second = request(service, origin)
            assertEquals(DiQualifier.Named("red"), second.single().call.qualifier)
            assertEquals(DiKind.DEFINITION, second.single().call.kind)
            assertTrue("Navigation must use the refreshed definition", firstOffset != second.single().offset)
        } finally {
            runBlocking { withTimeout(10_000) { worker.cancelAndJoin() } }
        }
    }

    private fun awaitReady(service: DiNavigationService) {
        assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
        runBlocking {
            withTimeout(60_000) {
                while (!service.isReady) delay(10)
            }
        }
    }

    private fun request(service: DiNavigationService, origin: DiSite): List<DiSite> {
        val result = CompletableFuture<Pair<List<DiSite>, List<String>>>()
        EdtTestUtil.runInEdtAndWait<RuntimeException> {
            service.requestTargets(origin, onResult = { targets ->
                try {
                    assertTrue("Navigation callbacks must run on the EDT", ApplicationManager.getApplication().isDispatchThread)
                    result.complete(targets to targets.map { it.presentation() })
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }, onError = { result.completeExceptionally(AssertionError(it)) })
        }
        val (targets, labels) = result.get(60, TimeUnit.SECONDS)
        assertEquals(1, targets.size)
        assertTrue(labels.single(), labels.single().contains("Definitions.kt"))
        assertTrue(labels.single(), labels.single().contains("AsyncService"))
        // The callback dispatcher may grant model access; the renderer must also work without it.
        EdtTestUtil.runInEdtAndWait<RuntimeException>({
            assertTrue(ApplicationManager.getApplication().isDispatchThread)
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            assertEquals(labels, targets.map { it.presentation() })
        }, false)
        return targets
    }
}
