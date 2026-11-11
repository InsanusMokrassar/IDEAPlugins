package dev.inmo.idea.navigation.flow.diagram.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
class NavigationAnalyzerTest {
    private lateinit var disposable: Disposable
    private lateinit var psi: KtPsiFactory

    @Before
    fun createParser() {
        disposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "navigation-analyzer-fixtures")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        psi = KtPsiFactory(environment.project, false)
    }

    @After
    fun disposeParser() = Disposer.dispose(disposable)

    private fun source(path: String, content: String): KotlinSource = KotlinSource(path, psi.createFile(path.substringAfterLast('/'), content.trimIndent()))

    private fun model(): KotlinSource = source("src/commonMain/kotlin/Models.kt", """
        package sample
        interface Base
        class HomeConfig : Base
        class DetailConfig : Base
        class OtherConfig : Base
    """)

    private fun view(): KotlinSource = source("src/jsMain/kotlin/DetailView.kt", """
        package sample
        import dev.inmo.navigation.core.NavigationChain
        import dev.inmo.navigation.mvvm.compose.ComposeView
        class DetailView(chain: NavigationChain<Base>, config: DetailConfig) : ComposeView<DetailConfig, Base, VM>(config, chain)
    """)

    private fun analyze(vararg input: KotlinSource): NavigationGraph = NavigationAnalyzer().analyze(input.toList())

    @Test
    fun `typed factories link config to precise view constructor and class identifiers`() {
        val factory = source("src/jsMain/kotlin/Factory.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNodeFactory
            val factory = NavigationNodeFactory.Typed<DetailConfig, Base> { chain, config ->
                DetailView(chain, config)
            }
        """)
        val detailView = view()
        val graph = analyze(model(), detailView, factory)
        val binding = graph.views.single { it.configName == "sample.DetailConfig" }
        assertEquals("sample.DetailView", binding.viewName)
        assertEquals(factory.file.text.indexOf("DetailView("), binding.registration!!.offset)
        assertEquals(4, binding.registration!!.line)
        assertEquals(5, binding.registration!!.column)
        assertEquals(detailView.file.text.indexOf("DetailView("), binding.declaration.offset)
        val config = graph.configurations.single { it.name == "sample.DetailConfig" }
        assertEquals(model().file.text.indexOf("DetailConfig"), config.declaration.offset)
    }

    @Test
    fun `interactor node argument gives source and operation callee gives exact location`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            fun edit(node: NavigationNode<HomeConfig, Base>) {
                node.chain.push(DetailConfig())
                node.chain.replace(config = OtherConfig(), node = node)
                node.chain.pop()
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(listOf("push", "replace", "pop"), graph.transitions.map { it.operation })
        assertEquals(setOf("sample.HomeConfig"), graph.transitions.map { it.sourceConfig }.toSet())
        assertEquals(listOf("sample.DetailConfig", "sample.OtherConfig", null), graph.transitions.map { it.targetConfig })
        assertEquals(TransitionKind.BACK, graph.transitions.last().kind)
        assertEquals(input.file.text.indexOf("push("), graph.transitions.first().location.offset)
        assertEquals(4, graph.transitions.first().location.line)
        assertEquals(16, graph.transitions.first().location.column)
    }

    @Test
    fun `imports aliases typealiases and generic view inheritance retain qualified identities`() {
        val input = source("Aliases.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode as Node
            import dev.inmo.navigation.core.NavigationChain as Chain
            import dev.inmo.navigation.mvvm.compose.ComposeView as View
            import dev.inmo.navigation.core.NavigationNodeFactory.Typed as TypedFactory
            typealias ScreenNode<C> = Node<C, Base>
            typealias Details = DetailConfig
            open class GenericView<C : Base>(c: C, chain: Chain<Base>) : View<C, Base, VM>(c, chain)
            class AliasView(c: Details, chain: Chain<Base>) : GenericView<Details>(c, chain)
            val factory = TypedFactory<Details, Base> { chain, config -> AliasView(config, chain) }
            fun go(node: ScreenNode<HomeConfig>) { node.chain.push(Details()) }
        """)
        val graph = analyze(model(), input)
        val transition = graph.transitions.single()
        assertEquals("sample.HomeConfig", transition.sourceConfig)
        assertEquals("sample.DetailConfig", transition.targetConfig)
        assertEquals("sample.AliasView", graph.views.single().viewName)
        assertNotNull(graph.views.single().registration)
    }

    @Test
    fun `untyped conditional factory retains actual view allocation`() {
        val input = source("Factory.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNodeFactory
            val factory = NavigationNodeFactory<Base> { chain, config ->
                when (config) {
                    is DetailConfig -> DetailView(chain, config)
                    else -> null
                }
            }
        """)
        val binding = analyze(model(), view(), input).views.single()
        assertEquals(input.file.text.indexOf("DetailView("), binding.registration!!.offset)
    }

    @Test
    fun `same view names in platform source sets keep separate declarations and factories`() {
        fun platform(platform: String) = source("feature/src/$platform/kotlin/Screen.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationChain
            import dev.inmo.navigation.core.NavigationNodeFactory
            import dev.inmo.navigation.mvvm.compose.ComposeView
            class DetailView(chain: NavigationChain<Base>, config: DetailConfig) : ComposeView<DetailConfig, Base, VM>(config, chain)
            val factory = NavigationNodeFactory.Typed<DetailConfig, Base> { chain, config -> DetailView(chain, config) }
        """)
        val graph = analyze(model(), platform("jsMain"), platform("jvmMain"))
        assertEquals(2, graph.views.size)
        graph.views.forEach { assertEquals(it.declaration.path, it.registration!!.path) }
        assertEquals(2, graph.views.map { it.declaration.path }.toSet().size)
    }

    @Test
    fun `forwarding wrappers propagate concrete arguments through several functions`() {
        val wrappers = source("Wrappers.kt", """
            package utility
            import dev.inmo.navigation.core.NavigationChain
            fun <T> NavigationChain<T>.pushOrBackUntil(config: T) { push(config) }
            fun <T> NavigationChain<T>.resetToSingleNode(config: T) {
                pushOrBackUntil(config)
                replace(stackFlow.value.first(), config)
            }
        """)
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import utility.resetToSingleNode
            fun go(node: NavigationNode<HomeConfig, Base>) {
                node.chain.resetToSingleNode(DetailConfig())
            }
        """)
        val graph = analyze(model(), view(), wrappers, input)
        assertEquals(1, graph.transitions.size)
        assertEquals("resetToSingleNode", graph.transitions.single().operation)
        assertEquals("sample.HomeConfig", graph.transitions.single().sourceConfig)
        assertEquals("sample.DetailConfig", graph.transitions.single().targetConfig)
        assertEquals("Client.kt", graph.transitions.single().location.path)
    }

    @Test
    fun `anonymous interactor local wrapper is resolved from lexical scope`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            val interactor = object {
                val mainChain: NavigationChain<Base> = obtainChain()
                fun navigateSection(target: Base) { mainChain.push(target) }
                fun select(node: NavigationNode<HomeConfig, Base>) { navigateSection(DetailConfig()) }
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(1, graph.transitions.size)
        assertEquals("navigateSection", graph.transitions.single().operation)
        assertEquals("sample.HomeConfig", graph.transitions.single().sourceConfig)
        assertEquals("sample.DetailConfig", graph.transitions.single().targetConfig)
    }

    @Test
    fun `sealed config objects allocate parent view through replacement wrapper`() {
        val input = source("Password.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            import dev.inmo.navigation.core.NavigationNodeFactory
            import dev.inmo.navigation.mvvm.compose.ComposeView
            sealed interface PasswordConfig : Base {
                class Pending : PasswordConfig
                data object Completed : PasswordConfig
            }
            class PasswordView(c: PasswordConfig, chain: NavigationChain<Base>) : ComposeView<PasswordConfig, Base, VM>(c, chain)
            val factory = NavigationNodeFactory.Typed<PasswordConfig, Base> { chain, config -> PasswordView(config, chain) }
            fun transition(node: NavigationNode<PasswordConfig, Base>, target: Base) { node.chain.replace(node, target) }
            fun complete(node: NavigationNode<PasswordConfig, Base>) { transition(node, PasswordConfig.Completed) }
        """)
        val graph = analyze(model(), input)
        assertEquals("sample.PasswordConfig.Completed", graph.transitions.single().targetConfig)
        assertEquals("sample.PasswordConfig", graph.transitions.single().sourceConfig)
        val childBinding = graph.views.single { it.configName == "sample.PasswordConfig.Completed" }
        assertEquals("sample.PasswordView", childBinding.viewName)
        assertEquals(1, graph.allocationsFor(childBinding.configName).size)
    }

    @Test
    fun `local val and config copy retain destination type`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            fun go(node: NavigationNode<HomeConfig, Base>, typed: DetailConfig) {
                val destination = OtherConfig()
                node.chain.push(destination)
                node.chain.push(typed.copy())
                node.chain.push(node.config.copy())
            }
        """)
        val graph = analyze(model(), view(), input)
        assertTrue(graph.transitions.any { it.targetConfig == "sample.OtherConfig" })
        assertTrue(graph.transitions.any { it.targetConfig == "sample.DetailConfig" })
        assertTrue(graph.transitions.any { it.targetConfig == "sample.HomeConfig" })
    }

    @Test
    fun `containment handles both subchain overloads and imported Compose alias`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChainId
            import dev.inmo.navigation.compose.InjectNavigationNode as Inject
            fun children(node: NavigationNode<HomeConfig, Base>, id: NavigationChainId) {
                node.createSubChain(DetailConfig(), id)
                node.createSubChain(id, OtherConfig())
                Inject(DetailConfig())
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(3, graph.transitions.size)
        assertTrue(graph.transitions.all { it.kind == TransitionKind.CONTAINMENT })
        assertEquals(listOf("sample.DetailConfig", "sample.OtherConfig", "sample.DetailConfig"), graph.transitions.map { it.targetConfig })
    }

    @Test
    fun `subtree mapper branches produce destinations without attributing null to a screen`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.extensions.*
            fun go(node: NavigationNode<HomeConfig, Base>) {
                node.chain.pushInSubTree { candidate -> if (condition) DetailConfig() else OtherConfig() }
                node.replaceNodesInSubTree { candidate -> if (condition) DetailConfig() else null }
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(3, graph.transitions.size)
        assertEquals(setOf("sample.DetailConfig", "sample.OtherConfig"), graph.transitions.map { it.targetConfig }.toSet())
    }

    @Test
    fun `instance qualified imported subtree alias resolves its configuration argument`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChainId
            import dev.inmo.navigation.core.extensions.pushInSubTree as pushBelow
            fun go(node: NavigationNode<HomeConfig, Base>, id: NavigationChainId) {
                node.chain.pushBelow(id, DetailConfig())
            }
        """)
        val transition = analyze(model(), view(), input).transitions.single()
        assertEquals("pushBelow", transition.operation)
        assertEquals("sample.DetailConfig", transition.targetConfig)
    }

    @Test
    fun `early return chain alias preserves wrapper destinations and nonnull receiver`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            val interactor = object {
                fun mainChain(): NavigationChain<Base>? = obtainChain()
                fun navigateSection(target: Base) {
                    val mainChain = mainChain() ?: return
                    mainChain.push(target)
                }
                fun select(node: NavigationNode<HomeConfig, Base>) {
                    navigateSection(DetailConfig())
                    mainChain()!!.push(OtherConfig())
                }
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(listOf("sample.DetailConfig", "sample.OtherConfig"), graph.transitions.map { it.targetConfig })
        assertEquals(setOf("sample.HomeConfig"), graph.transitions.map { it.sourceConfig }.toSet())
    }

    @Test
    fun `Koin delegated navigation chain has the supplied generic type`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            import org.koin.core.component.inject
            class Owner {
                val chain by inject<NavigationChain<Base>>()
                fun go(node: NavigationNode<HomeConfig, Base>) { chain.push(DetailConfig()) }
            }
        """)
        val transition = analyze(model(), view(), input).transitions.single()
        assertEquals("sample.HomeConfig", transition.sourceConfig)
        assertEquals("sample.DetailConfig", transition.targetConfig)
    }

    @Test
    fun `implicit chain scope and node constructor property retain source`() {
        val input = source("Vm.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            class VM(private val node: NavigationNode<HomeConfig, Base>) {
                fun go() { with(node.chain) { push(DetailConfig()) } }
            }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals("sample.DetailConfig", graph.transitions.single().targetConfig)
        assertEquals("sample.HomeConfig", graph.transitions.single().sourceConfig)
    }

    @Test
    fun `unrelated push replace comments strings and config construction create no transitions`() {
        val input = source("Unrelated.kt", """
            package unrelated
            class Config
            class NavigationChain { fun push(c: Config) {} }
            fun sample(chain: NavigationChain) {
                chain.push(Config())
                val message = "node.chain.push(Config())"
                // node.chain.replace(node, Config())
                val config = Config()
                "abc".replace("a", "b")
            }
        """)
        val graph = analyze(model(), view(), input)
        assertTrue(graph.transitions.isEmpty())
        assertFalse(graph.configurations.any { it.name == "unrelated.Config" })
    }

    @Test
    fun `same simple configuration names never bind across packages`() {
        val left = source("Left.kt", """
            package left
            class Config
        """)
        val right = source("Right.kt", """
            package right
            class Config
        """)
        val input = source("Client.kt", """
            package screen
            import left.Config as LeftConfig
            import right.Config as RightConfig
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            import dev.inmo.navigation.mvvm.compose.ComposeView
            class Screen(c: LeftConfig, chain: NavigationChain<Any>) : ComposeView<LeftConfig, Any, VM>(c, chain)
            fun go(node: NavigationNode<LeftConfig, Any>) { node.chain.push(RightConfig()) }
        """)
        val graph = analyze(left, right, input)
        assertEquals("right.Config", graph.transitions.single().targetConfig)
        assertEquals("left.Config", graph.views.single().configName)
        assertFalse(graph.views.any { it.configName == "right.Config" })
    }

    @Test
    fun `unresolved provider produces unknown target and diagnostic`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            interface Provider { fun config(): Base }
            fun go(node: NavigationNode<HomeConfig, Base>, provider: Provider) { node.chain.push(provider.config()) }
        """)
        val graph = analyze(model(), view(), input)
        assertEquals(1, graph.transitions.size)
        assertNull(graph.transitions.single().targetConfig)
        assertTrue(graph.diagnostics.any { it.message.contains("destination") })
    }

    @Test
    fun `ambiguous wildcard imports do not invent a destination`() {
        val left = source("Left.kt", "package left\nclass Config")
        val right = source("Right.kt", "package right\nclass Config")
        val input = source("Client.kt", """
            package sample
            import left.*
            import right.*
            import dev.inmo.navigation.core.NavigationNode
            fun go(node: NavigationNode<HomeConfig, Base>) { node.chain.push(Config()) }
        """)
        val graph = analyze(model(), left, right, input)
        assertNull(graph.transitions.single().targetConfig)
        assertTrue(graph.diagnostics.any { it.message.contains("destination") })
    }

    @Test
    fun `ambiguous wrapper overload is reported without selecting a candidate`() {
        val input = source("Client.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            fun NavigationChain<Base>.navigate(config: DetailConfig) { push(config) }
            fun NavigationChain<Base>.navigate(config: OtherConfig) { push(config) }
            fun go(node: NavigationNode<HomeConfig, Base>, config: Base) { node.chain.navigate(config) }
        """)
        val graph = analyze(model(), view(), input)
        assertFalse(graph.transitions.any { it.operation == "navigate" })
        assertTrue(graph.diagnostics.any { it.message.contains("Ambiguous wrapper overload") })
    }

    @Test
    fun `named arguments do not shift later positional wrapper arguments`() {
        val input = source("MixedArguments.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            fun NavigationChain<Base>.navigate(first: Base, second: Base = DetailConfig()) { push(second) }
            fun go(node: NavigationNode<HomeConfig, Base>) {
                node.chain.navigate(first = DetailConfig(), OtherConfig())
                node.chain.navigate(OtherConfig())
            }
        """)
        val graph = analyze(model(), input)
        assertEquals(listOf("sample.OtherConfig", "sample.DetailConfig"), graph.transitions.map { it.targetConfig })
    }

    @Test
    fun `matching factory function and callable variable are not constructors`() {
        val input = source("Factories.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            fun DetailConfig(flag: Boolean): OtherConfig = OtherConfig()
            fun go(node: NavigationNode<HomeConfig, Base>) {
                node.chain.push(DetailConfig(true))
                node.chain.push(sample.DetailConfig(true))
                node.chain.push(DetailConfig())
                val DetailConfig = { OtherConfig() }
                node.chain.push(DetailConfig())
                node.chain.push(sample.DetailConfig())
            }
        """)
        val graph = analyze(model(), input)
        assertEquals(listOf("sample.OtherConfig", "sample.OtherConfig", "sample.DetailConfig", null, "sample.DetailConfig"),
            graph.transitions.map { it.targetConfig })
        assertTrue(graph.diagnostics.any { it.message.contains("destination") })
    }

    @Test
    fun `constructor and function with equally fitting arguments stay ambiguous`() {
        val input = source("AmbiguousFactory.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            class Destination(value: Int) : Base
            fun Destination(value: String): OtherConfig = OtherConfig()
            fun go(node: NavigationNode<HomeConfig, Base>) { node.chain.push(Destination(1)) }
        """)
        val graph = analyze(model(), input)
        assertNull(graph.transitions.single().targetConfig)
        assertTrue(graph.diagnostics.any { it.message.contains("destination") })
    }

    @Test
    fun `implicit navigation properties match explicit receivers and respect local values`() {
        val input = source("ImplicitProperties.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            import dev.inmo.navigation.core.NavigationChain
            class UnrelatedChain { fun push(config: Base) {} }
            fun NavigationNode<HomeConfig, Base>.go() {
                chain.push(DetailConfig())
                this.chain.push(DetailConfig())
                chain.push(config)
                this.chain.push(this.config)
            }
            fun NavigationNode<HomeConfig, Base>.shadow() {
                val chain = UnrelatedChain()
                val config = OtherConfig()
                chain.push(DetailConfig())
                this.chain.push(config)
            }
            fun NavigationChain<Base>.fromParent() {
                parentNode?.chain?.push(DetailConfig())
                this.parentNode?.chain?.push(DetailConfig())
            }
        """)
        val graph = analyze(model(), input)
        assertEquals(listOf("sample.DetailConfig", "sample.DetailConfig", "sample.HomeConfig", "sample.HomeConfig",
            "sample.OtherConfig", "sample.DetailConfig", "sample.DetailConfig"), graph.transitions.map { it.targetConfig })
        assertTrue(graph.transitions.take(5).all { it.sourceConfig == "sample.HomeConfig" })
        assertTrue(graph.transitions.takeLast(2).all { it.sourceConfig == null })
    }

    private fun diamond(depth: Int): KotlinSource = source("Hierarchy.kt", buildString {
        appendLine("package sample")
        appendLine("interface A0")
        appendLine("interface B0")
        for (level in 1..depth) {
            appendLine("interface A$level : A${level - 1}, B${level - 1}")
            appendLine("interface B$level : A${level - 1}, B${level - 1}")
        }
    })

    @Test
    fun `supertype traversal visits shared diamond states once and caches completed queries`() {
        var checks = 0
        val symbols = KotlinSymbols(listOf(diamond(20))) { ++checks }
        checks = 0
        assertNull(symbols.asSupertype(KotlinType("sample.A20"), NAV_NODE))
        assertTrue("Every traversal must check cancellation", checks > 0)
        assertTrue("Shared ancestors must not be expanded exponentially: $checks checks", checks < 400)
        checks = 0
        repeat(20) { assertNull(symbols.asSupertype(KotlinType("sample.A20"), NAV_NODE)) }
        assertTrue("Cached queries must remain cancellable without walking the hierarchy again: $checks checks", checks in 20..80)
    }

    @Test
    fun `supertype traversal can be cancelled without caching partial results`() {
        var remaining: Int? = null
        val cancellation = IllegalStateException("cancelled during hierarchy traversal")
        val root = source("Root.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            interface Root : A20, NavigationNode<HomeConfig, Base>
        """)
        val symbols = KotlinSymbols(listOf(model(), diamond(20), root)) {
            remaining?.let { remaining = it - 1; if (it == 0) throw cancellation }
        }
        remaining = 5
        try {
            symbols.asSupertype(KotlinType("sample.Root"), NAV_NODE)
            fail("Expected cancellation during traversal")
        } catch (actual: IllegalStateException) {
            assertSame(cancellation, actual)
        }
        remaining = null
        assertEquals(KotlinType(NAV_NODE, listOf(KotlinType("sample.HomeConfig"), KotlinType("sample.Base"))),
            symbols.asSupertype(KotlinType("sample.Root"), NAV_NODE))
    }

    @Test
    fun `supertype traversal preserves generic ambiguity and terminates on changing cycles`() {
        val input = source("GenericHierarchy.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            interface Parent<C> : NavigationNode<C, Base>
            interface Left : Parent<HomeConfig>
            interface Right : Parent<DetailConfig>
            interface Ambiguous : Left, Right
            interface NestedAmbiguous : Ambiguous, Left
            interface Same : Left, Parent<HomeConfig>
            class Box<T>
            interface CycleA<T> : CycleB<Box<T>>
            interface CycleB<T> : CycleA<Box<T>>
        """)
        val symbols = KotlinSymbols(listOf(model(), input)) {}
        assertNull(symbols.asSupertype(KotlinType("sample.Ambiguous"), NAV_NODE))
        assertNull(symbols.asSupertype(KotlinType("sample.NestedAmbiguous"), NAV_NODE))
        assertEquals(KotlinType(NAV_NODE, listOf(KotlinType("sample.HomeConfig"), KotlinType("sample.Base"))),
            symbols.asSupertype(KotlinType("sample.Same"), NAV_NODE))
        assertNull(symbols.asSupertype(KotlinType("sample.CycleA", listOf(KotlinType("sample.HomeConfig"))), NAV_NODE))
        assertEquals(KotlinType(NAV_NODE, listOf(KotlinType("sample.DetailConfig"), KotlinType("sample.Base"))),
            symbols.asSupertype(KotlinType("sample.Parent", listOf(KotlinType("sample.DetailConfig"))), NAV_NODE))
        assertEquals(KotlinType(NAV_NODE, listOf(KotlinType("sample.HomeConfig"), KotlinType("sample.Base"))),
            symbols.asSupertype(KotlinType("sample.Parent", listOf(KotlinType("sample.HomeConfig"))), NAV_NODE))
    }

    @Test
    fun `cancellation is propagated and analyzer keeps no state between invocations`() {
        val analyzer = NavigationAnalyzer()
        var checks = 0
        val cancellation = IllegalStateException("cancelled")
        try {
            analyzer.analyze(listOf(model(), view())) { if (++checks == 10) throw cancellation }
            fail("Expected cancellation")
        } catch (actual: IllegalStateException) {
            assertSame(cancellation, actual)
        }
        assertEquals(1, analyzer.analyze(listOf(model(), view())).views.size)
        assertTrue(analyzer.analyze(emptyList()).views.isEmpty())
    }
}
