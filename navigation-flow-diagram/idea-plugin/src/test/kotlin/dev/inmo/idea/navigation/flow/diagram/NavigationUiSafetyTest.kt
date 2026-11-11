package dev.inmo.idea.navigation.flow.diagram

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.inmo.idea.navigation.flow.diagram.analysis.SourceLocation
import java.awt.Component
import java.awt.Container
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicHTML

class NavigationUiSafetyTest : LightJavaCodeInsightFixtureTestCase() {
    override fun runInDispatchThread() = false

    fun testSourceNamesStayLiteralInMenusTablesAndTooltips() = onEdt {
        val text = "<html><b>Source screen</b>"
        val target = NavigationTarget(text, SourceLocation("source.kt", 0, 1, 1))
        val menu = NavigationTextRenderer<NavigationTarget> { it.label }
        val menuLabel = menu.getListCellRendererComponent(JList(), target, 0, false, false) as JLabel
        assertEquals(text, menuLabel.text)
        assertPlain(menuLabel)

        val panel = NavigationPanel(project)
        Disposer.register(testRootDisposable, panel)
        @Suppress("UNCHECKED_CAST")
        val selector = components(panel).filterIsInstance<JComboBox<*>>().single() as JComboBox<String>
        val selection = selector.renderer.getListCellRendererComponent(JList(), text, 0, false, false) as JLabel
        assertEquals(text, selection.text)
        assertPlain(selection)

        val table = components(panel).filterIsInstance<JTable>().single()
        val cell = table.getDefaultRenderer(Any::class.java)
            .getTableCellRendererComponent(table, text, false, false, 0, 0) as JLabel
        assertEquals(text, cell.text)
        assertPlain(cell)

        val tooltip = NavigationDiagramPanel { }.createToolTip().apply { tipText = text }
        assertEquals(text, tooltip.tipText)
        assertPlain(tooltip)
    }

    fun testSlowExportDoesNotBlockEdtAndWritesCapturedContents() {
        val destination = Files.createTempFile("navigation-export-", ".mmd")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        try {
            onEdt {
                queueDiagramExport(project, destination, "flowchart LR\n", onError = { finished.completeExceptionally(it) }) { path, text ->
                    try {
                        assertFalse("File I/O must run outside the EDT", SwingUtilities.isEventDispatchThread())
                        started.countDown()
                        assertTrue("Writer must be released", release.await(10, TimeUnit.SECONDS))
                        Files.writeString(path, text)
                        finished.complete(Unit)
                    } catch (failure: Throwable) {
                        finished.completeExceptionally(failure)
                        throw failure
                    }
                }
            }
            assertTrue("Background write must start", started.await(10, TimeUnit.SECONDS))
            assertEquals("UI remains responsive", onEdt { "UI remains responsive" })
            release.countDown()
            finished.get(10, TimeUnit.SECONDS)
            assertEquals("flowchart LR\n", Files.readString(destination))
        } finally {
            release.countDown()
            Files.deleteIfExists(destination)
        }
    }

    fun testExportIoErrorIsReportedOnEdt() {
        val reported = CompletableFuture<Pair<Boolean, String?>>()
        onEdt {
            queueDiagramExport(project, java.nio.file.Path.of("unused.mmd"), "graph", onError = {
                reported.complete(SwingUtilities.isEventDispatchThread() to it.message)
            }, write = { _, _ -> throw IOException("Expected write failure") })
        }
        assertEquals(true to "Expected write failure", reported.get(10, TimeUnit.SECONDS))
    }

    private fun assertPlain(component: JComponent) {
        component.preferredSize
        assertNull("Source text must not create Swing's HTML view", component.getClientProperty(BasicHTML.propertyKey))
    }

    private fun components(component: Component): Sequence<Component> = sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(components(it)) }
    }

    private fun <T> onEdt(action: () -> T): T {
        val result = CompletableFuture<T>()
        SwingUtilities.invokeLater {
            try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        return result.get(15, TimeUnit.SECONDS)
    }
}
