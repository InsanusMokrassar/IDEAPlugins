package dev.inmo.idea.function.call.graph;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import dev.inmo.idea.function.call.graph.ui.CallGraphPanel;
import org.jetbrains.annotations.NotNull;

public final class ShowCallGraphAction extends AnAction {
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override public void update(@NotNull AnActionEvent event) {
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        Project project = event.getProject();
        boolean supported = file != null && (file.getLanguage().getID().equals("JAVA") || file.getLanguage().getID().equals("kotlin"));
        event.getPresentation().setEnabled(project != null && supported
                && event.getData(CommonDataKeys.EDITOR) != null && !DumbService.isDumb(project));
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        var editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || file == null || editor == null) return;
        int offset = editor.getCaretModel().getOffset();
        var pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file);
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Function Call Graph");
        if (window == null) return;
        window.activate(() -> {
            for (var content : window.getContentManager().getContents()) {
                if (content.getComponent() instanceof CallGraphPanel panel) {
                    panel.showFunction(pointer, offset);
                    break;
                }
            }
        });
    }
}
