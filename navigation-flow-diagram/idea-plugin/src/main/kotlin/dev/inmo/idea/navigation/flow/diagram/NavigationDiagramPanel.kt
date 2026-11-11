package dev.inmo.idea.navigation.flow.diagram

import com.intellij.ui.JBColor
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationGraph
import dev.inmo.idea.navigation.flow.diagram.analysis.Transition
import dev.inmo.idea.navigation.flow.diagram.analysis.TransitionKind
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import javax.swing.JPanel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A local Swing rendering: viewing diagrams needs no browser, server, or CDN. */
internal class NavigationDiagramPanel(private val open: (String) -> Unit) : JPanel() {
    private var graph = NavigationGraph()
    private var nodes = emptyMap<String, Rectangle>()

    init {
        toolTipText = ""
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) nodeAt(event.point)?.let(open)
            }
        })
    }

    fun setGraph(value: NavigationGraph) {
        graph = value
        val names = (value.views.map { it.configName } + value.configurations.map { it.name } +
            value.transitions.flatMap { listOfNotNull(it.sourceConfig, it.targetConfig) }).distinct().sorted()
        val edges = value.transitions.filter { it.sourceConfig != null && it.targetConfig != null }
        val incoming = edges.mapNotNull { it.targetConfig }.toSet()
        val children = edges.groupBy { it.sourceConfig }.mapValues { (_, calls) -> calls.mapNotNull { it.targetConfig }.distinct().sorted() }
        val layers = linkedMapOf<String, Int>()
        // Breadth-first layers terminate on cycles; disconnected screens remain visible.
        for (root in names.filter { it !in incoming } + names) {
            if (root in layers) continue
            val pending = ArrayDeque<Pair<String, Int>>()
            pending.add(root to 0)
            while (pending.isNotEmpty()) {
                val (name, layer) = pending.removeFirst()
                if (name in layers) continue
                layers[name] = layer
                children[name].orEmpty().forEach { if (it !in layers) pending.add(it to layer + 1) }
            }
        }
        val row = mutableMapOf<Int, Int>()
        nodes = layers.mapValues { (_, layer) ->
            val index = row.getOrDefault(layer, 0)
            row[layer] = index + 1
            Rectangle(32 + layer * 340, 48 + index * 118, 250, 64)
        }
        preferredSize = Dimension((nodes.values.maxOfOrNull { it.x + it.width } ?: 540) + 48,
            (nodes.values.maxOfOrNull { it.y + it.height } ?: 180) + 48)
        revalidate()
        repaint()
    }

    override fun getToolTipText(event: MouseEvent): String? = nodeAt(event.point)?.let { name ->
        val views = graph.views.filter { it.configName == name }.map { it.viewName }.distinct()
        if (views.isEmpty()) name else "$name → ${views.joinToString()}"
    }

    override fun createToolTip() = super.createToolTip().apply { putClientProperty("html.disable", true) }

    private fun nodeAt(point: Point): String? = nodes.entries.firstOrNull { it.value.contains(point) }?.key

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = JBColor.GRAY
            g.drawString("Solid: navigation   Dashed: embedded view   Back / unresolved operations: see Transitions", 20, 22)
            if (nodes.isEmpty()) {
                g.drawString("No navigation bindings found in project production sources.", 20, 68)
                return
            }
            val grouped = graph.transitions.filter { it.sourceConfig in nodes && it.targetConfig in nodes }
                .groupBy { Triple(it.sourceConfig, it.targetConfig, it.kind) }
            grouped.values.forEach { calls -> drawEdge(g, calls) }
            nodes.forEach { (name, box) ->
                g.color = JBColor.PanelBackground
                g.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
                g.color = JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
                g.stroke = BasicStroke(1.5f)
                g.drawRoundRect(box.x, box.y, box.width, box.height, 12, 12)
                val views = graph.views.filter { it.configName == name }.map { it.viewName.substringAfterLast('.') }.distinct()
                g.color = JBColor.foreground()
                g.drawString(shorten(g, views.joinToString().ifEmpty { name.substringAfterLast('.') }, box.width - 20), box.x + 10, box.y + 25)
                g.color = JBColor.GRAY
                g.drawString(shorten(g, name.substringAfterLast('.'), box.width - 20), box.x + 10, box.y + 47)
            }
        } finally { g.dispose() }
    }

    private fun drawEdge(g: Graphics2D, calls: List<Transition>) {
        val first = calls.first()
        val from = nodes[first.sourceConfig] ?: return
        val to = nodes[first.targetConfig] ?: return
        g.color = if (first.kind == TransitionKind.CONTAINMENT) JBColor.GRAY else JBColor.namedColor("Link.activeForeground", JBColor.BLUE)
        g.stroke = if (first.kind == TransitionKind.CONTAINMENT) BasicStroke(1.2f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f) else BasicStroke(1.2f)
        val startX: Double
        val startY: Double
        val endX: Double
        val endY: Double
        if (from == to) {
            startX = from.centerX
            startY = from.y.toDouble()
            endX = from.x + from.width.toDouble()
            endY = from.centerY
            val path = Path2D.Double().apply {
                moveTo(startX, startY)
                curveTo(startX, startY - 32, endX + 32, startY - 32, endX + 32, endY)
                lineTo(endX, endY)
            }
            g.draw(path)
            arrow(g, endX + 32, endY, endX, endY)
        } else {
            val rightward = to.centerX >= from.centerX
            val verticalOffset = if (first.kind == TransitionKind.CONTAINMENT) 14 else 0
            startX = if (rightward) from.maxX else from.minX
            startY = from.centerY + verticalOffset
            endX = if (rightward) to.minX else to.maxX
            endY = to.centerY + verticalOffset
            g.draw(Line2D.Double(startX, startY, endX, endY))
            arrow(g, startX, startY, endX, endY)
        }
        val label = calls.map { it.operation }.distinct().joinToString(" / ")
        val labelX = if (from == to) from.centerX.toInt() else ((startX + endX) / 2).toInt()
        val labelY = if (from == to) from.y - 8 else ((startY + endY) / 2 - 5).toInt()
        g.drawString(shorten(g, label, 160), labelX, labelY)
    }

    private fun arrow(g: Graphics2D, fromX: Double, fromY: Double, toX: Double, toY: Double) {
        val angle = atan2(toY - fromY, toX - fromX)
        val path = Path2D.Double().apply {
            moveTo(toX, toY)
            lineTo(toX - 9 * cos(angle - 0.45), toY - 9 * sin(angle - 0.45))
            lineTo(toX - 9 * cos(angle + 0.45), toY - 9 * sin(angle + 0.45))
            closePath()
        }
        g.fill(path)
    }

    private fun shorten(g: Graphics2D, text: String, width: Int): String {
        if (g.fontMetrics.stringWidth(text) <= width) return text
        var length = text.length
        while (length > 0 && g.fontMetrics.stringWidth(text.take(length) + "…") > width) length--
        return text.take(length) + "…"
    }
}
