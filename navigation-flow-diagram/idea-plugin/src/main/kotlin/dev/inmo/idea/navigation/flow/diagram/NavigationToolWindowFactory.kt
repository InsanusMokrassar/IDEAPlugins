package dev.inmo.idea.navigation.flow.diagram

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import dev.inmo.idea.navigation.flow.diagram.analysis.GraphRenderer
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationGraph
import dev.inmo.idea.navigation.flow.diagram.analysis.Transition
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class NavigationToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = NavigationPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

internal class NavigationPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = NavigationService.getInstance(project)
    private var graph = NavigationGraph()
    private var displayedRevision = -1L
    private val status = JLabel("Analyzing navigation…")
    private val focus = JComboBox<String>().apply { renderer = NavigationTextRenderer { it } }
    private var updatingFocus = false
    private val diagram = NavigationDiagramPanel { name ->
        if (canUseGraph()) {
            graph.views.firstOrNull { it.configName == name }?.let {
                service.open(NavigationTarget(it.viewName, it.declaration), displayedRevision)
            } ?: graph.configurations.firstOrNull { it.name == name }?.let {
                service.open(NavigationTarget(it.name, it.declaration), displayedRevision)
            }
        }
    }
    private val rows = TransitionTable()
    private val table = JBTable(rows).apply {
        setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
    }
    private val mermaid = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    }
    private val diagnostics = JTextArea().apply { isEditable = false }
    private val copy = JButton("Copy Mermaid")
    private val export = JButton("Export .mmd")

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEADING))
        toolbar.add(JButton("Refresh").apply { addActionListener { service.requestRefresh(); updateState() } })
        toolbar.add(copy.apply { addActionListener {
            if (canUseGraph()) CopyPasteManager.getInstance().setContents(StringSelection(mermaid.text))
        } })
        toolbar.add(export.apply { addActionListener { exportDiagram() } })
        toolbar.add(JLabel("Screen:"))
        toolbar.add(focus.apply {
            addItem(ALL_SCREENS)
            addActionListener { if (!updatingFocus) showGraph() }
        })
        add(JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(status, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        add(JTabbedPane().apply {
            addTab("Diagram", JBScrollPane(diagram))
            addTab("Transitions", JBScrollPane(table))
            addTab("Mermaid", JBScrollPane(mermaid))
            addTab("Unresolved", JBScrollPane(diagnostics))
        }, BorderLayout.CENTER)
        table.autoCreateRowSorter = true
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2) openSelectedTransition() }
        })
        table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ENTER"), "openTransition")
        table.actionMap.put("openTransition", object : AbstractAction() {
            override fun actionPerformed(event: ActionEvent?) = openSelectedTransition()
        })
        project.messageBus.connect(this).subscribe(NavigationListener.TOPIC, NavigationListener { updateState() })
        updateState()
    }

    private fun updateState() {
        val current = service.snapshot
        copy.isEnabled = current != null
        export.isEnabled = current != null
        focus.isEnabled = current != null
        table.isEnabled = current != null
        if (current == null) {
            status.text = service.error ?: "Analyzing navigation…"
            return
        }
        graph = current.graph
        displayedRevision = current.revision
        val selected = focus.selectedItem
        updatingFocus = true
        try {
            focus.removeAllItems()
            focus.addItem(ALL_SCREENS)
            (graph.configurations.map { it.name } + graph.views.map { it.configName } +
                graph.transitions.flatMap { listOfNotNull(it.sourceConfig, it.targetConfig) })
                .distinct().sorted().forEach(focus::addItem)
            if ((0 until focus.itemCount).any { focus.getItemAt(it) == selected }) focus.selectedItem = selected
        } finally { updatingFocus = false }
        status.text = "${graph.views.size} View bindings · ${graph.transitions.size} operations · ${graph.diagnostics.size} unresolved. Double-click a node or transition to open source."
        diagnostics.text = graph.diagnostics.joinToString("\n") {
            val source = it.location?.let { location -> "${relative(location.path)}:${location.line}: " }.orEmpty()
            source + it.message
        }.ifEmpty { "No unresolved calls reported. This is a static source graph; runtime conditions and DI selection are not evaluated." }
        diagnostics.caretPosition = 0
        showGraph()
    }

    private fun showGraph() {
        val selected = (focus.selectedItem as? String)?.takeUnless { it == ALL_SCREENS }
        val visible = if (selected == null) graph else {
            val transitions = graph.transitions.filter { it.sourceConfig == selected || it.targetConfig == selected }
            val names = transitions.flatMap { listOfNotNull(it.sourceConfig, it.targetConfig) }.toSet() + selected
            graph.copy(configurations = graph.configurations.filter { it.name in names },
                views = graph.views.filter { it.configName in names }, transitions = transitions)
        }
        diagram.setGraph(visible)
        rows.transitions = visible.transitions
        mermaid.text = GraphRenderer.mermaid(visible)
        mermaid.caretPosition = 0
    }

    private fun openSelectedTransition() {
        if (!canUseGraph() || table.selectedRow < 0) return
        val transition = rows.transitions[table.convertRowIndexToModel(table.selectedRow)]
        service.open(NavigationTarget(transition.operation, transition.location), displayedRevision)
    }

    private fun exportDiagram() {
        if (!canUseGraph()) return
        val contents = mermaid.text
        val destination = FileChooserFactory.getInstance().createSaveFileDialog(
            FileSaverDescriptor("Export Navigation Diagram", "Save the visible graph in Mermaid format", "mmd"), project,
        ).save("navigation.mmd") ?: return
        queueDiagramExport(project, destination.file.toPath(), contents, onError = { failure ->
            Messages.showErrorDialog(project, failure.message ?: "Cannot save diagram", "Navigation Export")
        })
    }

    private fun relative(path: String) = project.basePath?.let { path.removePrefix("$it/") } ?: path
    private fun canUseGraph() = service.snapshot?.revision == displayedRevision

    private inner class TransitionTable : AbstractTableModel() {
        var transitions = emptyList<Transition>()
            set(value) { field = value; fireTableDataChanged() }
        private val columns = arrayOf("From config", "To config", "Operation", "Kind", "Location")
        override fun getRowCount() = transitions.size
        override fun getColumnCount() = columns.size
        override fun getColumnName(column: Int) = columns[column]
        override fun getValueAt(row: Int, column: Int): Any = transitions[row].let { call ->
            when (column) {
                0 -> call.sourceConfig ?: "entry / unknown"
                1 -> call.targetConfig ?: "runtime / unknown"
                2 -> call.operation
                3 -> call.kind.name.lowercase()
                else -> "${relative(call.location.path)}:${call.location.line}"
            }
        }
    }

    override fun dispose() { }

    companion object { private const val ALL_SCREENS = "All screens" }
}

/** Keep filesystem latency outside the EDT; Task dispatches error callbacks back to the UI. */
internal fun queueDiagramExport(
    project: Project,
    destination: Path,
    contents: String,
    onError: (IOException) -> Unit,
    write: (Path, String) -> Unit = { path, text -> Files.writeString(path, text); Unit },
) {
    val task = object : Task.Backgroundable(project, "Exporting navigation diagram", false) {
        override fun run(indicator: ProgressIndicator) { write(destination, contents) }

        override fun onThrowable(error: Throwable) {
            if (error is IOException) {
                if (!project.isDisposed) onError(error)
            } else super.onThrowable(error)
        }
    }
    ProgressManager.getInstance().runProcessWithProgressAsynchronously(task, EmptyProgressIndicator())
}
