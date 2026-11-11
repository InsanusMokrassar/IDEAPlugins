package dev.inmo.idea.navigation.flow.diagram

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.kotlin.psi.KtFile
import java.awt.image.BufferedImage

/** Real IDE PSI/index/marker integration; fixture sources need no application dependencies to parse. */
class NavigationPluginTest : LightJavaCodeInsightFixtureTestCase() {
    private val service get() = NavigationService.getInstance(project)

    fun testViewAndConfigurationMarkersLeadToAllocationAndCallLeadsToView() {
        val file = addFixture()
        refreshed {
            val snapshot = service.snapshot!!
            val call = snapshot.graph.transitions.single { it.operation == "push" }
            assertEquals("sample.FirstConfig", call.sourceConfig)
            assertEquals("sample.SecondConfig", call.targetConfig)
            val markers = service.markers(file)
            val viewMarker = markers.single { file.findElementAt(it.location.offset)?.text == "SecondView" }
            assertEquals(call.location, viewMarker.targets.single().location)
            val callMarker = markers.single { file.findElementAt(it.location.offset)?.text == "push" }
            assertEquals("SecondView", file.findElementAt(callMarker.targets.single().location.offset)?.text)
            assertTrue(markers.any { file.findElementAt(it.location.offset)?.text == "SecondConfig" })

            val collected = mutableListOf<LineMarkerInfo<*>>()
            val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
            NavigationLineMarkerProvider().collectSlowLineMarkers(leaves, collected)
            assertEquals(3, collected.size)
            assertTrue(collected.all { it.element?.firstChild == null })
            repeat(50) { assertSame(snapshot, service.snapshot); assertEquals(markers, service.markers(file)) }
        }
    }

    fun testEditingTargetInvalidatesSnapshotAndMovesAllocationMarker() {
        val file = addFixture()
        refreshed { assertTrue(service.snapshot!!.graph.allocationsFor("sample.SecondConfig").isNotEmpty()) }
        val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf("push(SecondConfig)")
            assertTrue(start >= 0)
            document.replaceString(start, start + "push(SecondConfig)".length, "push(FirstConfig)")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNull(service.snapshot)
        ReadAction.run<RuntimeException> { assertEmpty(service.markers(file)) }
        refreshed {
            assertEmpty(service.snapshot!!.graph.allocationsFor("sample.SecondConfig"))
            assertEquals(1, service.snapshot!!.graph.allocationsFor("sample.FirstConfig").size)
            assertTrue(service.markers(file).any { file.findElementAt(it.location.offset)?.text == "FirstView" })
        }
    }

    fun testRemovingNavigationFileRemovesCachedAllocations() {
        val file = addFixture()
        refreshed { assertFalse(service.snapshot!!.graph.transitions.isEmpty()) }
        WriteCommandAction.runWriteCommandAction(project) { file.delete() }
        refreshed {
            assertEmpty(service.snapshot!!.graph.transitions)
            assertTrue(service.snapshot!!.markers.isEmpty())
        }
    }

    fun testUnrelatedPushAndTestSourceAreExcluded() {
        val main = addFixture()
        myFixture.addFileToProject("src/commonTest/kotlin/TestOnly.kt", """
            package sample
            import dev.inmo.navigation.core.NavigationNode
            fun onlyInTest(node: NavigationNode<SecondConfig, ViewConfig>) = node.chain.push(FirstConfig)
        """.trimIndent())
        val unrelated = myFixture.addFileToProject("Unrelated.kt", """
            package sample
            class Fake { fun push(config: ViewConfig) {} }
            fun ordinary(fake: Fake) { fake.push(SecondConfig); "a".replace("a", "b") }
        """.trimIndent()) as KtFile
        refreshed {
            assertEquals(1, service.snapshot!!.graph.transitions.count { it.targetConfig != null })
            assertEmpty(service.markers(unrelated))
            assertFalse(service.markers(main).isEmpty())
        }
    }

    fun testDiagramPaintsResolvedGraphWithoutBrowser() {
        addFixture()
        refreshed {
            val panel = NavigationDiagramPanel { }
            panel.setGraph(service.snapshot!!.graph)
            panel.size = panel.preferredSize
            assertTrue(panel.width > 250)
            val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { panel.paint(graphics) } finally { graphics.dispose() }
        }
    }

    fun testGutterTooltipDisplaysSourceMarkupLiterally() {
        val file = myFixture.addFileToProject("SourceNames.kt", """
            import dev.inmo.navigation.core.NavigationNode
            interface Base
            data object Home : Base
            data object Detail : Base
            class `<b>View</b>` : NavigationNode<Detail, Base>
            fun open(node: NavigationNode<Home, Base>) { node.chain.push(Detail) }
        """.trimIndent()) as KtFile
        refreshed {
            val markers = mutableListOf<LineMarkerInfo<*>>()
            val leaves = PsiTreeUtil.collectElements(file) { it.firstChild == null }.toMutableList()
            NavigationLineMarkerProvider().collectSlowLineMarkers(leaves, markers)
            val marker = markers.single { it.createGutterRenderer()!!.accessibleName.contains("<b>View</b>") }
            val pane = com.intellij.codeInsight.hint.LineTooltipRenderer.createEditorPane(
                marker.lineMarkerTooltip!!, com.intellij.ui.HintHint(), javax.swing.JLayeredPane(), false,
            )
            // IDEA inserts invisible soft breaks after path separators for wrapping.
            val rendered = pane.document.getText(0, pane.document.length).replace("\u200B", "")
            assertTrue(rendered, rendered.contains("<b>View</b>"))
        }
    }

    private fun addFixture() = myFixture.addFileToProject("Screens.kt", """
        package sample
        import dev.inmo.navigation.core.NavigationChain
        import dev.inmo.navigation.core.NavigationNode
        import dev.inmo.navigation.mvvm.compose.ComposeView
        interface ViewConfig
        data object FirstConfig : ViewConfig
        data object SecondConfig : ViewConfig
        class FirstView(chain: NavigationChain<ViewConfig>, config: FirstConfig) :
            ComposeView<FirstConfig, ViewConfig, Any>(config, chain)
        class SecondView(chain: NavigationChain<ViewConfig>, config: SecondConfig) :
            ComposeView<SecondConfig, ViewConfig, Any>(config, chain)
        fun open(node: NavigationNode<FirstConfig, ViewConfig>) {
            node.chain.push(SecondConfig)
        }
    """.trimIndent()) as KtFile

    private fun <T> refreshed(block: () -> T): T {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return ReadAction.compute<T, RuntimeException> {
            service.refreshForTests()
            block()
        }
    }
}
