package dev.inmo.idea.function.call.graph.analysis;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;

import java.util.List;

/** An immutable snapshot: UI code never retains raw PSI or UAST objects. */
public record CallGraph(String rootId, List<Node> nodes, List<Edge> edges,
                        boolean truncated, int unresolvedCalls) {
    public CallGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Node(String id, String label, String detail, int layer,
                       SmartPsiElementPointer<PsiElement> source) { }

    public record Edge(String callerId, String calleeId, List<Site> sites) {
        public Edge { sites = List.copyOf(sites); }
    }

    public enum Kind { CALL, REFERENCE }

    public record Site(String file, int line, String snippet, Kind kind,
                       SmartPsiElementPointer<PsiElement> source) {
        @Override public String toString() {
            return file + ":" + line + "  [" + (kind == Kind.CALL ? "call" : "reference") + "]  " + snippet;
        }
    }
}
