package dev.inmo.idea.navigation.flow.diagram.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphRendererTest {
    private val declaration = SourceLocation("src/Дом.kt", 4, 2, 3)
    private val call = SourceLocation("src/App.kt", 30, 5, 7)
    private val registration = SourceLocation("src/Factory.kt", 18, 4, 1)

    @Test
    fun `all formats remain identical when analyzer collection order changes`() {
        val graph = NavigationGraph(
            configurations = listOf(Configuration("B", call), Configuration("A", declaration)),
            views = listOf(ViewBinding("ZView", "A", call), ViewBinding("AView", "A", declaration, registration)),
            transitions = listOf(
                Transition("B", "A", "replace", TransitionKind.NAVIGATION, call),
                Transition("A", "B", "push", TransitionKind.NAVIGATION, declaration),
            ),
            diagnostics = listOf(Diagnostic(call, "later"), Diagnostic(null, "general"), Diagnostic(call, "earlier")),
        )
        val reversed = graph.copy(
            configurations = graph.configurations.reversed(),
            views = graph.views.reversed(),
            transitions = graph.transitions.reversed(),
            diagnostics = graph.diagnostics.reversed(),
        )
        assertEquals(GraphRenderer.mermaid(graph), GraphRenderer.mermaid(reversed))
        assertEquals(GraphRenderer.dot(graph), GraphRenderer.dot(reversed))
        assertEquals(GraphRenderer.json(graph), GraphRenderer.json(reversed))
        assertTrue(GraphRenderer.mermaid(graph).contains("n0[\"A<br/>AView<br/>ZView\"]"))
    }

    @Test
    fun `containment is dashed and navigation is solid with call locations`() {
        val graph = NavigationGraph(transitions = listOf(
            Transition("A", "B", "push", TransitionKind.NAVIGATION, call),
            Transition("A", "B", "createSubChain", TransitionKind.CONTAINMENT, declaration),
        ))
        val mermaid = GraphRenderer.mermaid(graph)
        assertTrue(mermaid.contains("n0 -->|\"push @ src/App.kt:5:7 (offset 30)\"| n1"))
        assertTrue(mermaid.contains("n0 -.->|\"createSubChain @ src/Дом.kt:2:3 (offset 4)\"| n1"))
        val dot = GraphRenderer.dot(graph)
        assertTrue(dot.contains("n0 -> n1 [style=solid, label=\"push @ src/App.kt:5:7 (offset 30)\"]"))
        assertTrue(dot.contains("n0 -> n1 [style=dashed"))
    }

    @Test
    fun `unknown and runtime back destinations never become diagram edges`() {
        val graph = NavigationGraph(transitions = listOf(
            Transition("A", null, "pop", TransitionKind.BACK, call),
            Transition(null, "B", "push", TransitionKind.NAVIGATION, declaration),
            Transition("A", null, "push", TransitionKind.NAVIGATION, call),
        ))
        val mermaid = GraphRenderer.mermaid(graph)
        assertFalse(mermaid.contains(" -->|"))
        assertTrue(mermaid.contains("BACK: A -> runtime back destination via pop"))
        assertTrue(mermaid.contains("unresolved source -> B"))
        assertTrue(mermaid.contains("A -> unresolved destination"))
        assertFalse(GraphRenderer.dot(graph).contains(" -> n"))
        assertTrue(GraphRenderer.json(graph).contains("\"targetConfig\":null,\"operation\":\"pop\",\"kind\":\"BACK\""))
    }

    @Test
    fun `mermaid quotes and syntax characters cannot inject diagram syntax`() {
        val name = "Дом\"<node>&#|\\`%\nnext"
        val graph = NavigationGraph(
            configurations = listOf(Configuration(name, declaration)),
            diagnostics = listOf(Diagnostic(null, "first\n    injected --> node\r%%{init: value}%%")),
        )
        val mermaid = GraphRenderer.mermaid(graph)
        assertTrue(mermaid.contains("Дом#34;#60;node#62;#38;#35;#124;#92;#96;#37;<br/>next"))
        assertFalse(mermaid.contains("\n    injected"))
        assertFalse(mermaid.contains("%%{init:"))
        assertTrue(mermaid.contains("first\\u000a"))
    }

    @Test
    fun `dot quotes backslashes and line breaks are escaped`() {
        val graph = NavigationGraph(configurations = listOf(Configuration("Дом\"\\N\nnext", declaration)))
        val dot = GraphRenderer.dot(graph)
        assertTrue(dot.contains("label=\"Дом\\\"\\\\N\\nnext\""))
        assertFalse(dot.contains("\nnext"))
    }

    @Test
    fun `json preserves every source location and escapes all control characters`() {
        val controls = (0..31).map(Int::toChar).joinToString("")
        val graph = NavigationGraph(
            configurations = listOf(Configuration("Дом\"\\", declaration)),
            views = listOf(ViewBinding("View", "Дом\"\\", call, registration)),
            transitions = listOf(Transition("Дом\"\\", null, "pop", TransitionKind.BACK, call)),
            diagnostics = listOf(Diagnostic(declaration, controls + "\u2028\u2029"), Diagnostic(null, "global")),
        )
        val json = GraphRenderer.json(graph)
        assertTrue(json.contains("\"name\":\"Дом\\\"\\\\\""))
        assertTrue(json.contains("\"declaration\":{\"path\":\"src/Дом.kt\",\"offset\":4,\"line\":2,\"column\":3}"))
        assertTrue(json.contains("\"registration\":{\"path\":\"src/Factory.kt\",\"offset\":18,\"line\":4,\"column\":1}"))
        assertTrue(json.contains("\"location\":{\"path\":\"src/App.kt\",\"offset\":30,\"line\":5,\"column\":7}"))
        assertTrue(json.contains("\"location\":null,\"message\":\"global\""))
        assertTrue(json.contains("\\u0000"))
        assertTrue(json.contains("\\b\\t\\n"))
        assertTrue(json.contains("\\u2028\\u2029"))
        assertFalse(json.dropLast(1).any { it < ' ' })
    }

    @Test
    fun `empty graph produces valid format skeletons`() {
        val graph = NavigationGraph()
        assertTrue(GraphRenderer.mermaid(graph).startsWith("flowchart LR\n"))
        assertTrue(GraphRenderer.dot(graph).endsWith("}\n"))
        assertEquals("{\"configurations\":[],\"views\":[],\"transitions\":[],\"diagnostics\":[]}\n", GraphRenderer.json(graph))
    }
}
