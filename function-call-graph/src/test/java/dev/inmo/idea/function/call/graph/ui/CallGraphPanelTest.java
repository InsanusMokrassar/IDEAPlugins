package dev.inmo.idea.function.call.graph.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.ui.components.JBList;
import dev.inmo.idea.function.call.graph.analysis.CallGraph;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CallGraphPanelTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected boolean runInDispatchThread() { return false; }

    public void testDoubleClickNavigatesFromAnUnlockedUiCallback() throws Exception {
        Target target = target();
        CountDownLatch resolved = new CountDownLatch(1);
        CallGraphPanel panel = panel(checkedPointer(target.pointer(), resolved));
        try {
            clickWithoutReadLock(panel);
            finishNavigation();
            assertEquals(0, resolved.getCount());
            EdtTestUtil.runInEdtAndWait(() -> {
                var manager = FileEditorManager.getInstance(getProject());
                assertTrue(manager.isFileOpen(target.file()));
                assertNotNull(manager.getSelectedTextEditor());
                assertEquals(target.offset(), manager.getSelectedTextEditor().getCaretModel().getOffset());
            });
        } finally { EdtTestUtil.runInEdtAndWait(() -> Disposer.dispose(panel)); }
    }

    public void testDeletedTargetDoesNotNavigate() throws Exception {
        Target target = target();
        CountDownLatch resolved = new CountDownLatch(1);
        CallGraphPanel panel = panel(checkedPointer(target.pointer(), resolved));
        try {
            EdtTestUtil.runInEdtAndWait(() -> WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                PsiElement method = target.pointer().getElement();
                assertNotNull(method);
                method.delete();
            }));
            clickWithoutReadLock(panel);
            finishNavigation();
            assertEquals(0, resolved.getCount());
            EdtTestUtil.runInEdtAndWait(() -> {
                assertFalse(FileEditorManager.getInstance(getProject()).isFileOpen(target.file()));
                assertTrue(labels(panel).stream().anyMatch(label -> label.getText().startsWith("This source location is no longer available.")));
            });
        } finally { EdtTestUtil.runInEdtAndWait(() -> Disposer.dispose(panel)); }
    }

    public void testDisposalCancelsNavigationBeforeUiDelivery() throws Exception {
        Target target = target();
        CountDownLatch resolved = new CountDownLatch(1);
        CallGraphPanel panel = panel(checkedPointer(target.pointer(), resolved));
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
                doubleClick(panel);
                assertTrue("The background read must start", resolved.await(10, TimeUnit.SECONDS));
                Disposer.dispose(panel);
            }, false);
            finishNavigation();
            clickWithoutReadLock(panel);
            finishNavigation();
            EdtTestUtil.runInEdtAndWait(() ->
                    assertFalse(FileEditorManager.getInstance(getProject()).isFileOpen(target.file())));
        } finally {
            if (!Disposer.isDisposed(panel)) EdtTestUtil.runInEdtAndWait(() -> Disposer.dispose(panel));
        }
    }

    public void testSourceLabelsAndCallSitesDoNotCreateHtmlViews() throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            String source = "<html><b>literal source</b>";
            CallGraphPanel panel = new CallGraphPanel(getProject());
            try {
                var node = new CallGraph.Node("root", source, "Source.kt:1", 0, null);
                var site = new CallGraph.Site("Source.kt", 1, source, CallGraph.Kind.CALL, null);
                CallGraph graph = new CallGraph("root", List.of(node),
                        List.of(new CallGraph.Edge("root", "root", List.of(site))), false, 0);
                var field = CallGraphPanel.class.getDeclaredField("graph");
                field.setAccessible(true);
                field.set(panel, graph);
                GraphCanvas canvas = component(panel, GraphCanvas.class);
                canvas.setGraph(graph);
                canvas.getActionMap().get("next-node").actionPerformed(new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "test"));
                JLabel title = labels(panel).stream().filter(label -> label.getText().startsWith(source)).findFirst().orElseThrow();
                assertNull(title.getClientProperty(BasicHTML.propertyKey));
                JBList<?> list = component(panel, JBList.class);
                Object row = list.getModel().getElementAt(0);
                @SuppressWarnings("unchecked")
                ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) list.getCellRenderer();
                JLabel rendered = (JLabel) renderer.getListCellRendererComponent(new JList<>(), row, 0, false, false);
                assertEquals(row.toString(), rendered.getText());
                assertTrue(rendered.getText().startsWith(source));
                assertNull(rendered.getClientProperty(BasicHTML.propertyKey));
            } finally { Disposer.dispose(panel); }
        }, false);
    }

    private Target target() throws Exception {
        return EdtTestUtil.runInEdtAndGet(() -> {
            PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("Target.java", "class Target { void selected() {} }");
            PsiElement method = file.getClasses()[0].getMethods()[0];
            return new Target(SmartPointerManager.createPointer(method), file.getVirtualFile(), method.getTextOffset());
        });
    }

    private CallGraphPanel panel(SmartPsiElementPointer<PsiElement> pointer) throws Exception {
        return EdtTestUtil.runInEdtAndGet(() -> {
            CallGraphPanel panel = new CallGraphPanel(getProject());
            component(panel, GraphCanvas.class).setGraph(new CallGraph("root", List.of(
                    new CallGraph.Node("root", "selected()", "Target.java:1", 0, pointer)
            ), List.of(), false, 0));
            return panel;
        });
    }

    @SuppressWarnings("unchecked")
    private SmartPsiElementPointer<PsiElement> checkedPointer(SmartPsiElementPointer<PsiElement> pointer, CountDownLatch resolved) {
        return (SmartPsiElementPointer<PsiElement>) Proxy.newProxyInstance(
                SmartPsiElementPointer.class.getClassLoader(), new Class<?>[]{SmartPsiElementPointer.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getElement")) {
                        assertTrue("PSI must be read under a lock", ApplicationManager.getApplication().isReadAccessAllowed());
                        assertFalse("Pointer restoration must run in the background", ApplicationManager.getApplication().isDispatchThread());
                        resolved.countDown();
                    }
                    try { return method.invoke(pointer, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private void clickWithoutReadLock(CallGraphPanel panel) throws Exception {
        EdtTestUtil.runInEdtAndWait(() -> {
            assertFalse(ApplicationManager.getApplication().isReadAccessAllowed());
            doubleClick(panel);
        }, false);
    }

    private static void doubleClick(CallGraphPanel panel) {
        GraphCanvas canvas = component(panel, GraphCanvas.class);
        canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, 0, 0, 100, 100, 2, false, MouseEvent.BUTTON1));
    }

    private static void finishNavigation() throws Exception {
        EdtTestUtil.runInEdtAndWait(NonBlockingReadActionImpl::waitForAsyncTaskCompletion);
    }

    private static <T extends Component> T component(Container parent, Class<T> type) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container container) {
                T found = component(container, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<JLabel> labels(Container parent) {
        java.util.ArrayList<JLabel> result = new java.util.ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label) result.add(label);
            if (child instanceof Container container) result.addAll(labels(container));
        }
        return result;
    }

    private record Target(SmartPsiElementPointer<PsiElement> pointer, VirtualFile file, int offset) { }
}
