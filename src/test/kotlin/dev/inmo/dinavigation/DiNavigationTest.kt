@file:OptIn(org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt::class)

package dev.inmo.dinavigation

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

/** Real K2 resolution against Koin 4.2.2 and MicroUtils 0.30.1 binaries, not API-shaped stubs. */
class DiNavigationTest : LightJavaCodeInsightFixtureTestCase() {
    override fun setUp() {
        super.setUp()
        val jars = System.getProperty("di.fixture.libraries").split(File.pathSeparator)
        ModuleRootModificationUtil.addModuleLibrary(module, "Koin and MicroUtils fixtures",
            jars.map { VfsUtil.getUrlForLibraryRoot(File(it)) }, emptyList())
    }

    fun testInferredTypesAndBidirectionalLeafMarkers() {
        val file = add("Sample.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.core.Koin
            class Service
            class Consumer(val service: Service)
            val bindings = module {
                single { Service() }
                factory { Consumer(get()) }
            }
            fun use(koin: Koin): Service = koin.getOrNull<Service>()!!
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(sites.toString(), 4, sites.size)
            val definition = sites.single { it.call.kind == DiKind.DEFINITION && it.call.types == setOf("sample.Service") }
            val targets = service.targets(definition)
            assertEquals(2, targets.size)
            assertTrue(targets.all { it.call.kind == DiKind.LOOKUP })
            targets.forEach { assertEquals(definition.anchor, service.targets(it).single().anchor) }
            val provider = DiLineMarkerProvider()
            val markers = mutableListOf<LineMarkerInfo<*>>()
            val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
            provider.collectSlowLineMarkers(leaves, markers)
            assertEquals(4, markers.size)
            assertTrue(markers.all { it.element?.firstChild == null })
            assertEquals(2, markers.map { it.icon }.distinct().size)
        }
    }

    fun testOrdinaryCollectionAndParametersCallsAreNotDI() {
        val file = add("Unrelated.kt", """
            package sample
            import org.koin.core.parameter.ParametersHolder
            fun unrelated(p: ParametersHolder) {
                listOf(1).single()
                listOf(1).getOrNull(0)
                mapOf("a" to 1).get("a")
                p.get<String>()
            }
            class Fake { fun <T> get(): T = error("no"); fun single() = 1 }
            fun fake(f: Fake) { f.get<String>(); f.single() }
        """)
        smart { assertEmpty(service.sites(file)) }
    }

    fun testQualifiersRandomDefinitionsAndGetAllDistinct() {
        val file = add("Qualified.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.core.Koin
            import org.koin.core.qualifier.named
            import dev.inmo.micro_utils.koin.singleWithRandomQualifier
            import dev.inmo.micro_utils.koin.getAllDistinct
            class Service
            val key = named("blue")
            val bindings = module {
                single { Service() }
                single(key) { Service() }
                single(named("red")) { Service() }
                singleWithRandomQualifier { Service() }
            }
            fun use(koin: Koin) {
                koin.get<Service>()
                koin.getOrNull<Service>(named("blue"))
                koin.getAllDistinct<Service>()
            }
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(7, sites.size)
            val plain = sites.single { it.call.kind == DiKind.LOOKUP && it.call.qualifier == DiQualifier.None }
            assertEquals(1, service.targets(plain).size)
            val blue = sites.single { it.call.kind == DiKind.LOOKUP && it.call.qualifier == DiQualifier.Named("blue") }
            assertEquals(DiQualifier.Named("blue"), service.targets(blue).single().call.qualifier)
            val all = sites.single { it.call.qualifier == DiQualifier.Any }
            assertEquals(4, service.targets(all).size)
            val random = sites.single { it.call.qualifier is DiQualifier.Random }
            assertEquals(all.anchor, service.targets(random).single().anchor)
        }
    }

    fun testInfixBindsAndMicroUtilsInferredWithBinds() {
        val file = add("Bindings.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.dsl.binds
            import org.koin.core.Koin
            import dev.inmo.micro_utils.koin.singleWithBinds
            interface First
            interface Second
            class Impl : First, Second
            val bindings = module {
                single { Impl() } binds arrayOf(First::class, Second::class)
                singleWithBinds { Impl() }
            }
            fun use(koin: Koin) { koin.get<First>(); koin.get<Second>() }
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(4, sites.size)
            sites.filter { it.call.kind == DiKind.DEFINITION }.forEach {
                assertTrue(it.call.types.containsAll(setOf("sample.Impl", "sample.First", "sample.Second")))
                assertEquals(2, service.targets(it).size)
            }
        }
    }

    fun testForwardingFunctionAndPropertyWrappersAcrossFiles() {
        add("Types.kt", "package sample\nclass Service")
        add("Wrappers.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.module.Module
            import org.koin.core.scope.Scope
            import org.koin.core.definition.Definition
            import org.koin.core.definition.KoinDefinition
            import org.koin.core.qualifier.named
            val meQualifier = named("me")
            fun Module.singleMe(definition: Definition<Service>): KoinDefinition<Service> = single(meQualifier, definition = definition)
            val Koin.me: Service get() = get(qualifier = meQualifier)
            val Scope.me: Service get() = get(qualifier = meQualifier)
        """)
        val definitions = add("Definitions.kt", """
            package sample
            import org.koin.dsl.module
            val bindings = module { singleMe { Service() } }
        """)
        val uses = add("Uses.kt", """
            package other
            import sample.me as currentMe
            import org.koin.core.Koin
            fun use(koin: Koin) = koin.currentMe
        """)
        smart {
            val definition = service.sites(definitions).single()
            assertEquals(DiQualifier.Named("me"), definition.call.qualifier)
            val use = service.sites(uses).single()
            assertEquals(DiQualifier.Named("me"), use.call.qualifier)
            assertTrue(service.targets(definition).any { it.anchor == use.anchor })
            assertTrue(service.targets(use).any { it.anchor == definition.anchor })
        }
    }

    fun testGenericErasureAndActualCollectionOrLazyBeans() {
        val file = add("Generic.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.core.Koin
            import dev.inmo.micro_utils.koin.getAny
            val bindings = module {
                single<List<String>> { listOf("x") }
                single<Lazy<String>> { lazy { "x" } }
            }
            fun use(koin: Koin) {
                koin.get<List<Int>>()
                koin.get<Lazy<String>>()
                koin.getAny<List<String>>()
            }
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(5, sites.size)
            assertEquals(2, sites.count { it.call.types == setOf("kotlin.Lazy") })
            val listDefinition = sites.single { it.call.kind == DiKind.DEFINITION && it.call.types == setOf("kotlin.collections.List") }
            assertEquals(2, service.targets(listDefinition).size)
        }
    }

    fun testFluentBindingsAndConstructorOptions() {
        val file = add("Fluent.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.dsl.bind
            import org.koin.dsl.binds
            import org.koin.core.Koin
            import org.koin.core.qualifier.named
            import org.koin.core.module.dsl.singleOf
            import org.koin.core.module.dsl.bind
            import org.koin.core.module.dsl.named
            import org.koin.core.module.dsl.withOptions
            interface Api
            interface Other
            class Impl : Api, Other
            val bindings = module {
                single { Impl() }.bind<Api>()
                factory { Impl() }.binds(arrayOf(Other::class))
                singleOf(::Impl) { bind<Api>(); named("constructor") }
                single { Impl() } withOptions { bind<Other>(); named("options") }
            }
            fun use(koin: Koin) {
                koin.get<Api>()
                koin.get<Other>()
                koin.get<Api>(named("constructor"))
                koin.get<Other>(named("options"))
            }
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(sites.toString(), 8, sites.size)
            sites.filter { it.call.kind == DiKind.LOOKUP }.forEach {
                assertEquals(it.presentation(), 1, service.targets(it).size)
            }
        }
    }

    fun testEditsInvalidateCachedTargetsAndStaleMarkerOrigin() {
        val file = add("Changing.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.core.Koin
            import org.koin.core.qualifier.named
            class Service
            val bindings = module { single(named("blue")) { Service() } }
            fun use(koin: Koin) = koin.get<Service>(named("blue"))
        """)
        val origin = smart {
            service.sites(file).single { it.call.kind == DiKind.DEFINITION }.also {
                assertEquals(1, service.targets(it).size)
            }
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            val position = document.text.indexOf("blue")
            document.replaceString(position, position + 4, "red")
        }
        smart {
            assertEmpty(service.targets(origin))
            assertEquals(DiQualifier.Named("red"), service.sites(file).single { it.call.kind == DiKind.DEFINITION }.call.qualifier)
        }
    }

    fun testDefinitionHandleRetainsRandomQualifierIdentity() {
        val file = add("Handles.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.module.Module
            import dev.inmo.micro_utils.koin.get
            import dev.inmo.micro_utils.koin.singleWithRandomQualifier
            class Service
            val owner = Module()
            val first = owner.singleWithRandomQualifier { Service() }
            val second = owner.singleWithRandomQualifier { Service() }
            fun use(koin: Koin) {
                koin.get(first)
                koin.get(second)
                koin.get<Service>()
            }
        """)
        smart {
            val sites = service.sites(file)
            assertEquals(5, sites.size)
            val definitions = sites.filter { it.call.kind == DiKind.DEFINITION }
            assertEquals(2, definitions.map { it.call.qualifier }.distinct().size)
            definitions.forEach { definition ->
                val use = service.targets(definition).single()
                assertEquals(definition.anchor, service.targets(use).single().anchor)
            }
            val plain = sites.single { it.call.kind == DiKind.LOOKUP && it.call.qualifier == DiQualifier.None }
            assertEmpty(service.targets(plain))
        }
    }

    fun testSourceWrapperForwardsRenamedQualifierAndDefault() {
        val file = add("Forwarding.kt", """
            package sample
            import org.koin.dsl.module
            import org.koin.core.Koin
            import org.koin.core.qualifier.Qualifier
            import org.koin.core.qualifier.named
            class Service
            fun Koin.obtain(key: Qualifier? = named("default")): Service = get(qualifier = key)
            val bindings = module {
                single(named("default")) { Service() }
                single(named("custom")) { Service() }
            }
            fun use(koin: Koin) { koin.obtain(); koin.obtain(named("custom")) }
        """)
        smart {
            val calls = service.sites(file).filter { it.pointer.element?.text?.startsWith("obtain(") == true }
            assertEquals(2, calls.size)
            assertEquals(setOf(DiQualifier.Named("default"), DiQualifier.Named("custom")), calls.map { it.call.qualifier }.toSet())
            calls.forEach { assertEquals(it.call.qualifier, service.targets(it).single().call.qualifier) }
        }
    }

    fun testDefinitionWrapperRetainsConstructorOptionsAndBindings() {
        val file = add("WrappedOptions.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.module.Module
            import org.koin.core.definition.KoinDefinition
            import org.koin.core.module.dsl.singleOf
            import org.koin.core.module.dsl.bind
            import org.koin.core.module.dsl.named
            import org.koin.core.qualifier.named
            interface Api
            class Service : Api
            fun Module.register(key: String = "wrapped"): KoinDefinition<Service> =
                singleOf(::Service) { bind<Api>(); named(key) }
            fun Module.forward(): KoinDefinition<Service> = register()
            fun Module.install() { forward() }
            fun use(koin: Koin) { koin.get<Api>(named("wrapped")); koin.get<Service>() }
        """)
        smart {
            val sites = service.sites(file)
            val wrapper = sites.single { it.pointer.element?.text == "forward()" }
            assertEquals(setOf("sample.Service", "sample.Api"), wrapper.call.types)
            assertEquals(DiQualifier.Named("wrapped"), wrapper.call.qualifier)
            val qualified = sites.single { it.call.kind == DiKind.LOOKUP && it.call.qualifier == DiQualifier.Named("wrapped") }
            assertTrue(service.targets(qualified).any { it.anchor == wrapper.anchor })
            val plain = sites.single { it.call.kind == DiKind.LOOKUP && it.call.qualifier == DiQualifier.None }
            assertEmpty(service.targets(plain))
        }
    }

    fun testWrapperDoesNotInventBindingsFromFilteredDelegate() {
        val file = add("FilteredWrapper.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.module.Module
            import org.koin.core.definition.Definition
            import dev.inmo.micro_utils.koin.singleWithBinds
            interface Api
            class Service : Api
            inline fun <reified T : Any> Module.filtered(noinline definition: Definition<T>) =
                singleWithBinds(bindFilter = { false }, definition = definition)
            fun Module.install() { filtered { Service() } }
            fun use(koin: Koin) { koin.get<Api>(); koin.get<Service>() }
        """)
        smart {
            val sites = service.sites(file)
            val wrapper = sites.single { it.pointer.element?.text == "filtered { Service() }" }
            assertEquals(setOf("sample.Service"), wrapper.call.types)
            val api = sites.single { it.call.kind == DiKind.LOOKUP && it.call.types == setOf("sample.Api") }
            assertEmpty(service.targets(api))
            val primary = sites.single { it.call.kind == DiKind.LOOKUP && it.call.types == setOf("sample.Service") }
            assertEquals(wrapper.anchor, service.targets(primary).single().anchor)
        }
    }

    fun testWarmNavigationDoesNoSemanticWorkAcrossLargeProjectOrUnrelatedEdit() {
        val definitions = (0 until 200).map { number ->
            add("many/Bindings$number.kt", """
                package sample
                import org.koin.core.Koin
                import org.koin.core.module.Module
                class Service$number
                fun Module.install$number() { single { Service$number() } }
                fun use$number(koin: Koin) {
                    ${List(8) { "koin.get<Service$number>()" }.joinToString("\n")}
                }
            """)
        }
        val unrelated = add("UnrelatedBody.kt", """
            package sample
            fun compute(value: Int) = value
            fun unrelated(): Int = compute(123)
        """)
        var origin: DiSite? = null
        val warmup = measureNanoTime {
            smart { origin = service.sites(definitions.first()).single { it.call.kind == DiKind.DEFINITION } }
        }
        val before = service.analyzedFileCount
        val queries = measureNanoTime {
            smart { repeat(100) { assertEquals(8, service.targets(origin!!).size) } }
        }
        assertEquals("Warm clicks must not resolve other files", before, service.analyzedFileCount)
        replace(unrelated, "123", "456")
        smart { assertEquals(8, service.targets(origin!!).size) }
        assertEquals("An unrelated function-body edit must retain the semantic cache", before, service.analyzedFileCount)
        println("DI BENCHMARK: 200 files, 1800 DI sites; prepare=${warmup / 1_000_000}ms; 100 cached queries=${queries / 1_000_000}ms; reanalyzed after unrelated edit=0")
    }

    fun testLocalLookupEditRefreshesOnlyChangedFile() {
        val definition = add("Definitions.kt", """
            package sample
            import org.koin.core.module.Module
            import org.koin.core.qualifier.named
            class Service
            fun Module.install() { single(named("blue")) { Service() } }
        """)
        val use = add("Use.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.qualifier.named
            fun consume(koin: Koin) { val service = koin.get<Service>(named("blue")); println(service) }
        """)
        val origin = smart { service.sites(definition).single().also { assertEquals(1, service.targets(it).size) } }
        val before = service.analyzedFileCount
        replace(use, "blue", "red")
        smart { assertEmpty(service.targets(origin)) }
        assertEquals("Only the edited source should be re-resolved", before + 1, service.analyzedFileCount)
    }

    fun testExplicitWrapperBodyChangeRefreshesCallersInOtherFiles() {
        val wrappers = add("Wrappers.kt", """
            package sample
            import org.koin.core.Koin
            import org.koin.core.qualifier.named
            class Service
            fun Koin.obtain(): Service = get(named("blue"))
        """)
        add("Definitions.kt", """
            package sample
            import org.koin.core.module.Module
            import org.koin.core.qualifier.named
            fun Module.install() { single(named("blue")) { Service() }; single(named("red")) { Service() } }
        """)
        val caller = add("Caller.kt", """
            package sample
            import org.koin.core.Koin
            fun use(koin: Koin) { val value = koin.obtain(); println(value) }
        """)
        val origin = smart { service.sites(caller).single().also {
            assertEquals(DiQualifier.Named("blue"), service.targets(it).single().call.qualifier)
        } }
        replace(wrappers, "blue", "red")
        smart { assertEquals(DiQualifier.Named("red"), service.targets(origin).single().call.qualifier) }
    }

    fun testPopupPresentationDoesNotReadPsi() {
        val file = add("Presentation.kt", """
            package sample
            import org.koin.core.Koin
            class Service
            fun use(koin: Koin) = koin.get<Service>()
        """)
        val site = smart { service.sites(file).single() }
        val label = ApplicationManager.getApplication().executeOnPooledThread<String> {
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
            site.presentation()
        }.get(5, TimeUnit.SECONDS)
        assertTrue(label, label.contains("Presentation.kt"))
        assertTrue(label, label.contains("Service"))
    }

    fun testOrdinaryFunctionBecomingWrapperRefreshesPreviouslyEmptyCallerCache() {
        add("Types.kt", "package sample\nclass Service")
        // Keep 'obtain' in the candidate-name set before either body is edited.
        add("KnownWrapper.kt", """
            package other
            import org.koin.core.Koin
            import sample.Service
            fun Koin.obtain(): Service = get()
        """)
        val ordinary = add("Ordinary.kt", """
            package sample
            import org.koin.core.Koin
            fun Koin.obtain(): Service { println("before"); return get() }
        """)
        val definitions = add("Definitions.kt", """
            package sample
            import org.koin.core.module.Module
            fun Module.install() { single { Service() } }
        """)
        val caller = add("Caller.kt", """
            package sample
            import org.koin.core.Koin
            fun use(koin: Koin) { println(koin.obtain()) }
        """)
        smart { assertEmpty(service.sites(caller)) }
        replace(ordinary, "println(\"before\"); ", "")
        smart {
            val origin = service.sites(caller).single()
            assertEquals(DiKind.LOOKUP, origin.call.kind)
            assertEquals(service.sites(definitions).single().anchor, service.targets(origin).single().anchor)
        }
    }

    fun testCancelledRefreshResumesCompletedFilesAndNeverPublishesPartialResults() {
        repeat(6) { number ->
            add("Cancelled$number.kt", """
                package sample
                import org.koin.core.Koin
                class Service$number
                fun use$number(koin: Koin) = koin.get<Service$number>()
            """)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = service.analyzedFileCount
        try {
            allowAnalysisOnEdt {
                // Inject the platform's cancellation at a fixed boundary. ProgressManager polling
                // depends on the runner/thread context and is not what this cache test verifies.
                ReadAction.run<RuntimeException> {
                    service.refreshForTests {
                        if (service.analyzedFileCount == before + 3) throw ProcessCanceledException()
                    }
                }
            }
            fail("Expected cancellation before the complete index was published")
        } catch (_: ProcessCanceledException) { /* Expected: partial file entries remain reusable. */ }
        assertFalse(service.isReady)
        val completed = service.analyzedFileCount - before
        assertEquals("Completed files must survive cancellation", 3L, completed)
        smart { assertTrue(service.isReady) }
        assertEquals("Resuming must reuse files completed before cancellation", before + 6, service.analyzedFileCount)
    }

    private fun replace(file: KtFile, old: String, new: String) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            val position = document.text.indexOf(old)
            check(position >= 0)
            document.replaceString(position, position + old.length, new)
        }
    }

    private val service get() = DiNavigationService.getInstance(project)
    private fun add(name: String, text: String) = myFixture.addFileToProject(name, text.trimIndent()) as KtFile
    private fun <T> smart(block: () -> T): T {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return allowAnalysisOnEdt { ReadAction.compute<T, RuntimeException> {
            service.refreshForTests()
            block()
        } }
    }
}
