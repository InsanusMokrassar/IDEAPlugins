package dev.inmo.idea.function.call.graph.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import dev.inmo.idea.function.call.graph.analysis.CallGraph;
import dev.inmo.idea.function.call.graph.analysis.CallGraphAnalyzer;
import org.jetbrains.concurrency.CancellablePromise;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class CallGraphPanel extends JPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(CallGraphPanel.class);
    private final Project project;
    private final GraphCanvas canvas;
    private final JBLabel status = new JBLabel("Place the caret on a function, then choose Show Function Call Graph.");
    private final JBLabel sitesTitle = new JBLabel("Call sites — select a node; double-click a row to open its location");
    private final DefaultListModel<SiteRow> sites = new DefaultListModel<>();
    private final JBList<SiteRow> siteList = new JBList<>(sites);
    private final JSpinner depth = new JSpinner(new SpinnerNumberModel(1, 1, 5, 1));
    private final JButton refresh = new JButton("Refresh");
    private final JButton recenter = new JButton("Focus selected");
    private final JButton open = new JButton("Open declaration");
    private final JButton cancel = new JButton("Cancel");
    private CallGraph graph;
    private CallGraph.Node selected;
    private SmartPsiElementPointer<PsiElement> root;
    private CancellablePromise<?> pending;
    private long generation;
    private volatile boolean disposed;

    public CallGraphPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        canvas = new GraphCanvas(this::select, node -> navigate(node.source()));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.add(new JBLabel("Depth:"));
        toolbar.add(depth);
        toolbar.add(refresh);
        toolbar.add(recenter);
        toolbar.add(open);
        JButton zoomOut = new JButton("−");
        JButton zoomIn = new JButton("+");
        JButton resetZoom = new JButton("100%");
        zoomOut.setToolTipText("Zoom out");
        zoomIn.setToolTipText("Zoom in");
        toolbar.add(zoomOut);
        toolbar.add(zoomIn);
        toolbar.add(resetZoom);
        toolbar.add(cancel);
        JPanel north = new JPanel(new BorderLayout());
        north.add(toolbar, BorderLayout.NORTH);
        status.setBorder(JBUI.Borders.empty(2, 8, 6, 8));
        north.add(status, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        JPanel locations = new JPanel(new BorderLayout());
        sitesTitle.putClientProperty("html.disable", Boolean.TRUE);
        sitesTitle.setBorder(JBUI.Borders.empty(4, 8));
        locations.add(sitesTitle, BorderLayout.NORTH);
        locations.add(new JBScrollPane(siteList), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JBScrollPane(canvas), locations);
        split.setResizeWeight(0.75);
        split.setDividerLocation(300);
        add(split, BorderLayout.CENTER);

        DefaultListCellRenderer siteRenderer = new DefaultListCellRenderer();
        siteRenderer.putClientProperty("html.disable", Boolean.TRUE);
        siteList.setCellRenderer(siteRenderer);
        siteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        siteList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) openSite();
            }
        });
        siteList.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-site");
        siteList.getActionMap().put("open-site", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { openSite(); }
        });
        refresh.addActionListener(event -> reload());
        depth.addChangeListener(event -> reload());
        recenter.addActionListener(event -> {
            if (selected != null) { root = selected.source(); reload(); }
        });
        open.addActionListener(event -> { if (selected != null) navigate(selected.source()); });
        cancel.addActionListener(event -> {
            cancelPending();
            status.setText("Analysis cancelled. The previous diagram, if any, is still displayed.");
        });
        zoomIn.addActionListener(event -> canvas.zoom(1.2));
        zoomOut.addActionListener(event -> canvas.zoom(1 / 1.2));
        resetZoom.addActionListener(event -> canvas.resetZoom());
        updateButtons(false);
    }

    public void showFunction(SmartPsiElementPointer<PsiFile> file, int offset) {
        compute(() -> {
            PsiFile current = file.getElement();
            return current == null ? null : CallGraphAnalyzer.findFunction(current, offset);
        });
    }

    private void reload() {
        if (root != null) {
            var pointer = root;
            compute(pointer::getElement);
        }
    }

    private void compute(Supplier<PsiElement> target) {
        cancelPending();
        long request = generation;
        int levels = (Integer) depth.getValue();
        status.setText("Calculating callers and outgoing calls…");
        updateButtons(true);
        pending = ReadAction.nonBlocking(() -> {
            PsiElement source = target.get();
            if (source == null || !source.isValid()) {
                throw new IllegalArgumentException("No function found. Place the caret on a Java/Kotlin function and run the action again.");
            }
            return new CallGraphAnalyzer(project).analyze(source, levels);
        }).inSmartMode(project).withDocumentsCommitted(project).expireWith(this)
                .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                    if (disposed || generation != request) return;
                    pending = null;
                    graph = result;
                    root = result.nodes().stream().filter(n -> n.id().equals(result.rootId())).findFirst().orElseThrow().source();
                    canvas.setGraph(result);
                    select(result.nodes().stream().filter(n -> n.id().equals(result.rootId())).findFirst().orElseThrow());
                    int count = result.edges().stream().mapToInt(e -> e.sites().size()).sum();
                    status.setText(result.nodes().size() + " nodes · " + count + " call/reference sites · arrows: caller → callee"
                            + (result.unresolvedCalls() > 0 ? " · unresolved sites: " + result.unresolvedCalls() : "")
                            + (result.truncated() ? " · LIMIT REACHED — reduce depth or focus a node" : ""));
                    updateButtons(false);
                }).submit(AppExecutorUtil.getAppExecutorService());
        pending.onError(error -> {
            if (error instanceof ProcessCanceledException || error instanceof CancellationException) return;
            if (!(error instanceof IllegalArgumentException)) LOG.warn("Call graph analysis failed", error);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || project.isDisposed() || generation != request) return;
                pending = null;
                status.setText(error instanceof IllegalArgumentException ? error.getMessage() : "Analysis failed. See the IDE log for details.");
                updateButtons(false);
            }, ModalityState.any());
        });
    }

    private void select(CallGraph.Node node) {
        selected = node;
        sites.clear();
        if (graph == null) return;
        Map<String, String> labels = graph.nodes().stream().collect(Collectors.toMap(CallGraph.Node::id, CallGraph.Node::label));
        for (CallGraph.Edge edge : graph.edges()) {
            if (!edge.callerId().equals(node.id()) && !edge.calleeId().equals(node.id())) continue;
            String direction = labels.get(edge.callerId()) + " → " + labels.get(edge.calleeId());
            for (CallGraph.Site site : edge.sites()) sites.addElement(new SiteRow(direction, site));
        }
        sitesTitle.setText(node.label() + " — " + sites.size() + " sites (double-click or Enter to open)");
        updateButtons(pending != null);
    }

    private void openSite() {
        SiteRow row = siteList.getSelectedValue();
        if (row != null) navigate(row.site.source());
    }

    private void navigate(SmartPsiElementPointer<PsiElement> pointer) {
        if (disposed || project.isDisposed()) return;
        ReadAction.nonBlocking(() -> {
            PsiElement source = pointer.getElement();
            PsiFile file = source == null || !source.isValid() ? null : source.getContainingFile();
            var virtualFile = file == null ? null : file.getVirtualFile();
            return virtualFile == null || !virtualFile.isValid() ? null
                    : new OpenFileDescriptor(project, virtualFile, source.getTextOffset());
        }).withDocumentsCommitted(project).expireWith(this)
                .expireWhen(() -> disposed || project.isDisposed()).coalesceBy(this, "navigation")
                .finishOnUiThread(ModalityState.defaultModalityState(), descriptor -> {
                    if (disposed || project.isDisposed()) return;
                    if (descriptor == null || !descriptor.getFile().isValid()) {
                        status.setText("This source location is no longer available. Refresh the diagram.");
                    } else descriptor.navigate(true);
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void updateButtons(boolean busy) {
        depth.setEnabled(!busy);
        refresh.setEnabled(root != null && !busy);
        recenter.setEnabled(selected != null && !busy);
        open.setEnabled(selected != null);
        cancel.setEnabled(busy);
    }

    private void cancelPending() {
        generation++;
        if (pending != null) { pending.cancel(); pending = null; }
        updateButtons(false);
    }

    @Override public void dispose() { disposed = true; cancelPending(); }

    private record SiteRow(String direction, CallGraph.Site site) {
        @Override public String toString() { return direction + "  |  " + site; }
    }
}
