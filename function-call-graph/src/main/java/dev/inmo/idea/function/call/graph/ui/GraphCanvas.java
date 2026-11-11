package dev.inmo.idea.function.call.graph.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import dev.inmo.idea.function.call.graph.analysis.CallGraph;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.List;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** A bounded, scrollable Swing diagram; no browser, server, or external renderer is needed. */
final class GraphCanvas extends JPanel {
    private static final int WIDTH = 270;
    private static final int HEIGHT = 64;
    private static final int COLUMN_GAP = 120;
    private static final int ROW_GAP = 42;
    private static final Color INCOMING = new JBColor(new Color(228, 240, 255), new Color(45, 62, 81));
    private static final Color OUTGOING = new JBColor(new Color(230, 247, 236), new Color(42, 68, 53));
    private static final Color ROOT = new JBColor(new Color(238, 230, 255), new Color(70, 53, 89));
    private final Consumer<CallGraph.Node> onSelect;
    private final Consumer<CallGraph.Node> onOpen;
    private final Map<String, Rectangle2D.Double> positions = new LinkedHashMap<>();
    private CallGraph graph;
    private String selected;
    private double scale = 1;
    private Dimension diagramSize = new Dimension(900, 300);

    GraphCanvas(Consumer<CallGraph.Node> onSelect, Consumer<CallGraph.Node> onOpen) {
        this.onSelect = onSelect;
        this.onOpen = onOpen;
        setOpaque(true);
        setBackground(JBColor.PanelBackground);
        setFocusable(true);
        getAccessibleContext().setAccessibleName("Function call diagram");
        setToolTipText("Click to list call sites; double-click to open a declaration. Ctrl+wheel zooms.");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                CallGraph.Node node = hit(event.getPoint());
                if (node == null) return;
                requestFocusInWindow();
                selected = node.id();
                onSelect.accept(node);
                repaint();
                if (event.getClickCount() == 2) onOpen.accept(node);
            }
        });
        addMouseWheelListener(event -> {
            if (event.isControlDown()) {
                zoom(event.getWheelRotation() < 0 ? 1.1 : 1 / 1.1);
                event.consume();
            } else if (getParent() != null) {
                getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, event, getParent()));
            }
        });
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-node");
        getActionMap().put("open-node", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (graph != null) graph.nodes().stream().filter(n -> n.id().equals(selected)).findFirst().ifPresent(onOpen);
            }
        });
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "next-node");
        getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "previous-node");
        for (int direction : new int[]{-1, 1}) {
            getActionMap().put(direction == 1 ? "next-node" : "previous-node", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { selectNext(direction); }
            });
        }
    }

    void setGraph(CallGraph graph) {
        this.graph = graph;
        selected = graph.rootId();
        positions.clear();
        Map<Integer, List<CallGraph.Node>> columns = graph.nodes().stream().collect(Collectors.groupingBy(CallGraph.Node::layer, TreeMap::new, Collectors.toList()));
        int rows = columns.values().stream().mapToInt(List::size).max().orElse(1);
        int height = Math.max(220, rows * (HEIGHT + ROW_GAP) + 100);
        int column = 0;
        for (List<CallGraph.Node> nodes : columns.values()) {
            nodes.sort(Comparator.comparing(CallGraph.Node::label).thenComparing(CallGraph.Node::detail));
            int y = 65 + (height - 100 - nodes.size() * (HEIGHT + ROW_GAP)) / 2;
            for (CallGraph.Node node : nodes) {
                positions.put(node.id(), new Rectangle2D.Double(50 + column * (WIDTH + COLUMN_GAP), y, WIDTH, HEIGHT));
                y += HEIGHT + ROW_GAP;
            }
            column++;
        }
        diagramSize = new Dimension(Math.max(500, column * (WIDTH + COLUMN_GAP) + 30), height + 40);
        revalidate();
        repaint();
        SwingUtilities.invokeLater(this::scrollToSelection);
    }

    void zoom(double factor) {
        scale = Math.max(0.35, Math.min(2.5, scale * factor));
        revalidate();
        repaint();
    }

    void resetZoom() { scale = 1; revalidate(); repaint(); }

    @Override public Dimension getPreferredSize() {
        return new Dimension((int) Math.ceil(diagramSize.width * scale), (int) Math.ceil(diagramSize.height * scale));
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.scale(scale, scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(JBUI.Fonts.label());
            if (graph == null) {
                g.setColor(JBColor.GRAY);
                g.drawString("Select a Java or Kotlin function in the editor to build a diagram.", 30, 50);
                return;
            }
            Set<Integer> headers = new HashSet<>();
            for (CallGraph.Node node : graph.nodes()) {
                if (headers.add(node.layer())) {
                    Rectangle2D box = positions.get(node.id());
                    g.setColor(JBColor.GRAY);
                    String title = node.layer() == 0 ? "Selected function" : node.layer() < 0 ? "Callers · " + -node.layer() : "Calls · " + node.layer();
                    g.drawString(title, (int) box.getX(), 30);
                }
            }
            for (CallGraph.Edge edge : graph.edges()) paintEdge(g, edge);
            for (CallGraph.Node node : graph.nodes()) paintNode(g, node);
        } finally { g.dispose(); }
    }

    private void paintEdge(Graphics2D g, CallGraph.Edge edge) {
        Rectangle2D from = positions.get(edge.callerId());
        Rectangle2D to = positions.get(edge.calleeId());
        boolean highlighted = edge.callerId().equals(selected) || edge.calleeId().equals(selected);
        g.setColor(highlighted ? JBColor.BLUE : JBColor.GRAY);
        boolean referenceOnly = edge.sites().stream().allMatch(site -> site.kind() == CallGraph.Kind.REFERENCE);
        g.setStroke(referenceOnly ? new BasicStroke(highlighted ? 1.8f : 1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{5, 4}, 0)
                : new BasicStroke(highlighted ? 1.8f : 1.1f));
        double x1, y1, x2, y2, controlX, controlY, labelX, labelY;
        CubicCurve2D curve;
        if (edge.callerId().equals(edge.calleeId())) {
            x1 = from.getCenterX() + 40; y1 = from.getY();
            x2 = from.getCenterX() - 40; y2 = from.getY();
            controlX = x2; controlY = y2 - 38;
            curve = new CubicCurve2D.Double(x1, y1, x1 + 30, y1 - 38, controlX - 30, controlY, x2, y2);
            labelX = from.getCenterX(); labelY = y1 - 26;
            controlX -= 30;
        } else if (from.getX() < to.getX()) {
            x1 = from.getMaxX(); y1 = from.getCenterY(); x2 = to.getX(); y2 = to.getCenterY();
            controlX = (x1 + x2) / 2; controlY = y2;
            curve = new CubicCurve2D.Double(x1, y1, controlX, y1, controlX, controlY, x2, y2);
            labelX = controlX; labelY = (y1 + y2) / 2 - 5;
        } else {
            x1 = from.getX(); y1 = from.getY() + 10; x2 = to.getMaxX(); y2 = to.getY() + 10;
            if (from.getX() == to.getX()) {
                x1 = from.getMaxX(); x2 = to.getMaxX();
                controlX = x1 + 52; controlY = y2;
                curve = new CubicCurve2D.Double(x1, y1, controlX, y1, controlX, controlY, x2, y2);
            } else {
                controlX = x2 + 50; controlY = y2 - 35;
                curve = new CubicCurve2D.Double(x1, y1, x1 - 50, y1 - 35, controlX, controlY, x2, y2);
            }
            labelX = (x1 + x2) / 2; labelY = (y1 + y2) / 2 - 20;
        }
        g.draw(curve);
        arrow(g, x2, y2, Math.atan2(y2 - controlY, x2 - controlX));
        if (edge.sites().size() > 1) g.drawString(Integer.toString(edge.sites().size()), (int) labelX, (int) labelY);
    }

    private void paintNode(Graphics2D g, CallGraph.Node node) {
        Rectangle2D box = positions.get(node.id());
        RoundRectangle2D shape = new RoundRectangle2D.Double(box.getX(), box.getY(), WIDTH, HEIGHT, 12, 12);
        g.setColor(node.id().equals(graph.rootId()) ? ROOT : node.layer() < 0 ? INCOMING : OUTGOING);
        g.fill(shape);
        g.setColor(node.id().equals(selected) ? JBColor.BLUE : JBColor.GRAY);
        g.setStroke(new BasicStroke(node.id().equals(selected) ? 2.2f : 1));
        g.draw(shape);
        g.setFont(JBUI.Fonts.label().deriveFont(Font.BOLD));
        g.setColor(JBColor.foreground());
        g.drawString(ellipsize(node.label(), g.getFontMetrics(), WIDTH - 24), (int) box.getX() + 12, (int) box.getY() + 25);
        g.setFont(JBUI.Fonts.smallFont());
        g.setColor(JBColor.GRAY);
        g.drawString(ellipsize(node.detail(), g.getFontMetrics(), WIDTH - 24), (int) box.getX() + 12, (int) box.getY() + 46);
    }

    private static void arrow(Graphics2D g, double x, double y, double angle) {
        Path2D head = new Path2D.Double();
        head.moveTo(x, y);
        head.lineTo(x - 10 * Math.cos(angle - 0.4), y - 10 * Math.sin(angle - 0.4));
        head.lineTo(x - 10 * Math.cos(angle + 0.4), y - 10 * Math.sin(angle + 0.4));
        head.closePath();
        g.fill(head);
    }

    private static String ellipsize(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) return text;
        int length = text.length();
        while (length > 0 && metrics.stringWidth(text.substring(0, length) + "…") > width) length--;
        return text.substring(0, length) + "…";
    }

    private CallGraph.Node hit(Point point) {
        if (graph == null) return null;
        return graph.nodes().stream().filter(node -> positions.get(node.id()).contains(point.x / scale, point.y / scale)).findFirst().orElse(null);
    }

    @Override public String getToolTipText(MouseEvent event) {
        CallGraph.Node node = hit(event.getPoint());
        return node == null ? "Arrows point from caller to callee. Dashed arrows are callable references." : node.label() + " — " + node.detail();
    }

    @Override public JToolTip createToolTip() {
        JToolTip tooltip = super.createToolTip();
        tooltip.putClientProperty("html.disable", Boolean.TRUE);
        return tooltip;
    }

    private void selectNext(int direction) {
        if (graph == null) return;
        List<String> ids = new ArrayList<>(positions.keySet());
        selected = ids.get(Math.floorMod(ids.indexOf(selected) + direction, ids.size()));
        graph.nodes().stream().filter(node -> node.id().equals(selected)).findFirst().ifPresent(onSelect);
        scrollToSelection();
        repaint();
    }

    private void scrollToSelection() {
        Rectangle2D box = positions.get(selected);
        if (box != null) scrollRectToVisible(new Rectangle((int) (box.getX() * scale) - 20, (int) (box.getY() * scale) - 20,
                (int) (WIDTH * scale) + 40, (int) (HEIGHT * scale) + 40));
    }
}
