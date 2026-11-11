package dev.inmo.idea.function.call.graph.ui;

import dev.inmo.idea.function.call.graph.analysis.CallGraph;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.swing.plaf.basic.BasicHTML;

import static org.junit.Assert.*;

/** These UI checks run headlessly without an IDE application or PSI test fixtures. */
public class GraphCanvasTest {
    @Test public void keyboardCanSelectAndOpenEveryNode() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> selected = new ArrayList<>();
            List<String> opened = new ArrayList<>();
            GraphCanvas canvas = new GraphCanvas(node -> selected.add(node.id()), node -> opened.add(node.id()));
            canvas.setGraph(example());
            for (int i = 0; i < 3; i++) {
                press(canvas, KeyEvent.VK_RIGHT);
                press(canvas, KeyEvent.VK_ENTER);
            }
            assertEquals(new HashSet<>(List.of("caller", "root", "leaf")), new HashSet<>(selected));
            assertEquals(selected, opened);
            press(canvas, KeyEvent.VK_LEFT);
            assertEquals("caller", selected.getLast());
        });
    }

    @Test public void zoomKeepsScrollableBoundsPositiveAndCanReset() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GraphCanvas canvas = new GraphCanvas(node -> { }, node -> { });
            canvas.setGraph(example());
            Dimension original = canvas.getPreferredSize();
            canvas.zoom(2);
            assertEquals(original.width * 2, canvas.getPreferredSize().width);
            assertEquals(original.height * 2, canvas.getPreferredSize().height);
            for (int i = 0; i < 50; i++) canvas.zoom(0.5);
            assertTrue(canvas.getPreferredSize().width > 0);
            assertTrue(canvas.getPreferredSize().height > 0);
            canvas.resetZoom();
            assertEquals(original, canvas.getPreferredSize());
        });
    }

    @Test public void rendersRecursionBackEdgesAndReferences() throws Exception {
        BufferedImage[] image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            GraphCanvas canvas = new GraphCanvas(node -> { }, node -> { });
            canvas.setGraph(example());
            canvas.setSize(canvas.getPreferredSize());
            image[0] = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image[0].createGraphics();
            try { canvas.paint(graphics); } finally { graphics.dispose(); }
            HashSet<Integer> colors = new HashSet<>();
            for (int x = 0; x < image[0].getWidth(); x += 3) {
                for (int y = 0; y < image[0].getHeight(); y += 3) colors.add(image[0].getRGB(x, y));
            }
            assertTrue("The diagram must paint nodes, edges and text", colors.size() > 10);
        });
        Path output = Path.of("build/reports/diagram-preview.png");
        Files.createDirectories(output.getParent());
        ImageIO.write(image[0], "png", output.toFile());
    }

    @Test public void sourceLabelsRemainLiteralInTooltips() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String label = "<html><b>literal source</b>";
            GraphCanvas canvas = new GraphCanvas(node -> { }, node -> { });
            canvas.setGraph(new CallGraph("root", List.of(
                    new CallGraph.Node("root", label, "Source.kt:1", 0, null)
            ), List.of(), false, 0));
            var event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0, 100, 100, 0, false);
            JToolTip tooltip = canvas.createToolTip();
            tooltip.setTipText(canvas.getToolTipText(event));
            assertEquals(label + " — Source.kt:1", tooltip.getTipText());
            assertNull("Source text must not create an HTML view", tooltip.getClientProperty(BasicHTML.propertyKey));
        });
    }

    private static void press(GraphCanvas canvas, int key) {
        Object action = canvas.getInputMap().get(KeyStroke.getKeyStroke(key, 0));
        assertNotNull("Key must have a registered action", action);
        canvas.getActionMap().get(action).actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "test"));
    }

    private static CallGraph example() {
        var call = new CallGraph.Site("Demo.kt", 3, "process(1)", CallGraph.Kind.CALL, null);
        var reference = new CallGraph.Site("Demo.kt", 7, "::process", CallGraph.Kind.REFERENCE, null);
        return new CallGraph("root", List.of(
                new CallGraph.Node("caller", "start()", "Demo.kt:6", -1, null),
                new CallGraph.Node("root", "process(Int)", "Demo.kt:3", 0, null),
                new CallGraph.Node("leaf", "leaf(Int)", "Demo.kt:1", 1, null)
        ), List.of(
                new CallGraph.Edge("caller", "root", List.of(call, reference)),
                new CallGraph.Edge("root", "root", List.of(call)),
                new CallGraph.Edge("root", "leaf", List.of(call, call)),
                new CallGraph.Edge("leaf", "root", List.of(reference))
        ), false, 0);
    }
}
