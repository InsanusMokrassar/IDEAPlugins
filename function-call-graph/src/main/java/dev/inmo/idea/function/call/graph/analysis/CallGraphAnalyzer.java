package dev.inmo.idea.function.call.graph.analysis;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtCallElement;
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFunction;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.uast.*;
import org.jetbrains.uast.visitor.AbstractUastVisitor;

import java.util.*;

/** Run in a cancellable smart read action. Calls are statically resolved by the IDE. */
public final class CallGraphAnalyzer {
    public static final int MAX_NODES = 120;
    public static final int MAX_SITES = 1500;

    private final Project project;
    private final Map<String, CallGraph.Node> nodes = new LinkedHashMap<>();
    private final Map<String, Target> targets = new HashMap<>();
    private final Map<Link, LinkedHashMap<String, CallGraph.Site>> edges = new LinkedHashMap<>();
    private final Set<String> unresolved = new HashSet<>();
    private final int maxNodes;
    private final int maxSites;
    private int siteCount;
    private boolean truncated;

    public CallGraphAnalyzer(Project project) { this(project, MAX_NODES, MAX_SITES); }

    CallGraphAnalyzer(Project project, int maxNodes, int maxSites) {
        this.project = project;
        if (maxNodes < 1 || maxSites < 1) throw new IllegalArgumentException("Graph limits must be positive");
        this.maxNodes = maxNodes;
        this.maxSites = maxSites;
    }

    /** Prefer the referenced function under the caret, then its enclosing declaration. */
    public static @Nullable PsiElement findFunction(PsiFile file, int offset) {
        if (file.getTextLength() == 0) return null;
        int position = Math.min(Math.max(0, offset), file.getTextLength() - 1);
        PsiReference reference = file.findReferenceAt(position);
        if (reference != null) {
            PsiElement resolved = reference.resolve();
            if (resolved != null && callable(resolved) != null) return physical(resolved);
        }
        for (PsiElement element = file.findElementAt(position); element != null; element = element.getParent()) {
            Target target = callable(element);
            if (target != null) return target.source;
        }
        return null;
    }

    public CallGraph analyze(PsiElement source, int depth) {
        if (depth < 1 || depth > 5) throw new IllegalArgumentException("Depth must be between 1 and 5");
        Target root = analyzable(source);
        if (root == null) throw new IllegalArgumentException("Place the caret on a Java or Kotlin function.");
        String rootId = addNode(root, 0);
        expand(rootId, depth, true);
        expand(rootId, depth, false);
        List<CallGraph.Edge> result = edges.entrySet().stream()
                .map(e -> new CallGraph.Edge(e.getKey().caller, e.getKey().callee, List.copyOf(e.getValue().values())))
                .toList();
        return new CallGraph(rootId, List.copyOf(nodes.values()), result, truncated, unresolved.size());
    }

    private void expand(String rootId, int depth, boolean incoming) {
        Set<String> visited = new HashSet<>();
        Deque<Step> queue = new ArrayDeque<>();
        queue.add(new Step(rootId, 0));
        while (!queue.isEmpty()) {
            ProgressManager.checkCanceled();
            Step step = queue.removeFirst();
            if (step.distance >= depth || !visited.add(step.id)) continue;
            Target target = targets.get(step.id);
            // Library declarations are useful endpoints, but their implementations are not traversed.
            if (target == null || (step.distance > 0 && !inProject(target.source))) continue;
            int layer = (incoming ? -1 : 1) * (step.distance + 1);
            Set<String> adjacent = incoming ? incoming(target, step.id, layer) : outgoing(target, step.id, layer);
            for (String id : adjacent) queue.addLast(new Step(id, step.distance + 1));
            if (truncated && siteCount >= maxSites) break;
        }
    }

    private Set<String> outgoing(Target target, String callerId, int layer) {
        Set<String> adjacent = new LinkedHashSet<>();
        AbstractUastVisitor visitor = new AbstractUastVisitor() {
            @Override public boolean visitElement(UElement node) {
                ProgressManager.checkCanceled();
                return truncated && siteCount >= maxSites;
            }
            @Override public boolean visitMethod(UMethod node) { return true; }
            @Override public boolean visitClass(UClass node) { return true; }
            @Override public boolean visitLambdaExpression(ULambdaExpression node) { return true; }

            @Override public boolean visitCallExpression(UCallExpression call) {
                if (truncated && siteCount >= maxSites) return true;
                ProgressManager.checkCanceled();
                if (call.getKind() == UastCallKind.METHOD_CALL || call.getKind() == UastCallKind.CONSTRUCTOR_CALL) {
                    record(resolvedTarget(call.resolve(), call.getSourcePsi()), call.getSourcePsi(), CallGraph.Kind.CALL);
                }
                return false;
            }

            @Override public boolean visitCallableReferenceExpression(UCallableReferenceExpression reference) {
                if (truncated && siteCount >= maxSites) return true;
                ProgressManager.checkCanceled();
                PsiElement resolved = resolvedTarget(reference.resolve(), reference.getSourcePsi());
                record(resolved, reference.getSourcePsi(), CallGraph.Kind.REFERENCE);
                return false;
            }

            private void record(PsiElement resolved, PsiElement site, CallGraph.Kind kind) {
                if (site == null) return;
                if (resolved == null) { unresolved.add(identity(site)); return; }
                Target callee = callable(resolved);
                if (callee == null) return;
                String calleeId = connect(callerId, callee, site, kind, layer, false);
                if (calleeId != null) adjacent.add(calleeId);
            }
        };
        for (UElement region : target.regions) {
            ProgressManager.checkCanceled();
            if (truncated && siteCount >= maxSites) break;
            region.accept(visitor);
        }
        return adjacent;
    }

    private Set<String> incoming(Target target, String calleeId, int layer) {
        Set<String> adjacent = new LinkedHashSet<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Set<String> seenReferences = new HashSet<>();
        Processor<PsiReference> processor = reference -> {
            ProgressManager.checkCanceled();
            PsiElement element = reference.getElement();
            String key = identity(element) + ":" + reference.getRangeInElement();
            if (!seenReferences.add(key)) return true;
            Usage usage = usage(reference, target);
            if (usage == null) return true;
            Target caller = enclosingContext(usage.expression, element);
            String callerId = connect(calleeId, caller, usage.source, usage.kind, layer, true);
            if (callerId != null) adjacent.add(callerId);
            return !truncated || siteCount < maxSites;
        };
        if (target.method != null) MethodReferencesSearch.search(target.method, scope, true).forEach(processor);
        if (!(target.source instanceof PsiMethod) && (!truncated || siteCount < maxSites)) {
            ReferencesSearch.search(target.source, scope).forEach(processor);
        }
        return adjacent;
    }

    private @Nullable Usage usage(PsiReference reference, Target target) {
        UElement expression = UastContextKt.toUElement(reference.getElement());
        for (UElement node = expression; node != null; node = node.getUastParent()) {
            PsiElement resolved;
            CallGraph.Kind kind;
            if (node instanceof UCallableReferenceExpression ref) {
                resolved = resolvedTarget(ref.resolve(), ref.getSourcePsi());
                kind = CallGraph.Kind.REFERENCE;
            } else if (node instanceof UCallExpression call) {
                resolved = resolvedTarget(call.resolve(), call.getSourcePsi());
                kind = CallGraph.Kind.CALL;
            } else if (node instanceof UDeclaration || node instanceof ULambdaExpression) {
                return null;
            } else continue;
            // Stop at the first callable expression: never mistake its containing call for this reference.
            if (resolved == null || !equivalent(resolved, target.source)) return null;
            PsiElement source = node.getSourcePsi();
            return source == null ? null : new Usage(node, source, kind);
        }
        return null;
    }

    private Target enclosingContext(UElement expression, PsiElement fallback) {
        for (UElement node = expression.getUastParent(); node != null; node = node.getUastParent()) {
            if (node instanceof UMethod method) return fromMethod(method.getJavaPsi(), method);
            if (node instanceof ULambdaExpression lambda && lambda.getSourcePsi() != null) {
                PsiElement source = lambda.getSourcePsi();
                String name = source instanceof PsiNamedElement named ? named.getName() : null;
                return context(source, lambda.getBody(), source instanceof KtNamedFunction function
                        ? kotlinLabel(function) : name == null ? "λ lambda" : name + "()");
            }
            if (node instanceof UField field && field.getSourcePsi() != null) {
                return context(field.getSourcePsi(), field.getUastInitializer(), field.getName() + " initializer");
            }
            if (node instanceof UClassInitializer initializer && initializer.getSourcePsi() != null) {
                return context(initializer.getSourcePsi(), initializer.getUastBody(), "initializer");
            }
        }
        // A source location remains navigable even when its language has no callable enclosing context.
        return context(fallback, null, "call site");
    }

    private @Nullable String addNode(Target target, int layer) {
        String id = identity(target.source);
        if (nodes.containsKey(id)) return id;
        if (nodes.size() >= maxNodes) { truncated = true; return null; }
        nodes.put(id, new CallGraph.Node(id, target.label, location(target.source), layer, pointer(target.source)));
        targets.put(id, target);
        return id;
    }

    private @Nullable String connect(String currentId, Target neighbor, PsiElement site, CallGraph.Kind kind,
                                     int layer, boolean incoming) {
        String neighborId = identity(neighbor.source);
        String caller = incoming ? neighborId : currentId;
        String callee = incoming ? currentId : neighborId;
        var existing = edges.get(new Link(caller, callee));
        if (existing != null && existing.containsKey(identity(site) + ":" + kind)) return neighborId;
        // Check the site budget before inserting its endpoint, avoiding disconnected leftover nodes.
        if (siteCount >= maxSites) { truncated = true; return null; }
        if (addNode(neighbor, layer) == null) return null;
        return addSite(caller, callee, site, kind) ? neighborId : null;
    }

    private boolean addSite(String caller, String callee, PsiElement source, CallGraph.Kind kind) {
        Link link = new Link(caller, callee);
        LinkedHashMap<String, CallGraph.Site> sites = edges.get(link);
        String id = identity(source) + ":" + kind;
        if (sites != null && sites.containsKey(id)) return true;
        if (siteCount >= maxSites) { truncated = true; return false; }
        if (sites == null) {
            sites = new LinkedHashMap<>();
            edges.put(link, sites);
        }
        String snippet = source.getText().replaceAll("\\s+", " ").strip();
        if (snippet.length() > 140) snippet = snippet.substring(0, 137) + "…";
        sites.put(id, new CallGraph.Site(fileName(source), line(source), snippet, kind, pointer(source)));
        siteCount++;
        return true;
    }

    private static @Nullable Target callable(PsiElement element) {
        if (element instanceof PsiMethod method) {
            PsiElement source = physical(method);
            if (source.getTextRange() == null || source.getContainingFile() == null) return null;
            UMethod uMethod = UastContextKt.toUElement(source, UMethod.class);
            if (uMethod == null) {
                if (method.isConstructor()) {
                    Target constructor = classConstructor(source);
                    if (constructor != null) return constructor;
                }
                ULambdaExpression local = UastContextKt.toUElement(source, ULambdaExpression.class);
                if (local != null) return new Target(source, method, executionRegions(source, local.getBody()), source instanceof KtNamedFunction function
                        ? kotlinLabel(function) : method.getName() + "()");
            }
            return fromMethod(method, uMethod);
        }
        // Converting arbitrary expressions to UMethod can return a containing synthetic declaration.
        if (!(element instanceof PsiNamedElement)) return null;
        UMethod method = UastContextKt.toUElement(element, UMethod.class);
        if (method != null && method.getSourcePsi() == element) return fromMethod(method.getJavaPsi(), method);
        ULambdaExpression lambda = UastContextKt.toUElement(element, ULambdaExpression.class);
        if (lambda != null && lambda.getSourcePsi() == element) {
            return context(element, lambda.getBody(), element instanceof KtNamedFunction function
                    ? kotlinLabel(function) : ((PsiNamedElement) element).getName() + "()");
        }
        return classConstructor(element);
    }

    private static @Nullable Target classConstructor(PsiElement source) {
        if (!(source instanceof PsiClass || source instanceof KtClassOrObject)) return null;
        UClass declaration = UastContextKt.toUElement(source, UClass.class);
        if (declaration != null) {
            for (UMethod method : declaration.getMethods()) {
                // An implicit constructor navigates to its class; explicit overloads have their own source.
                if (method.isConstructor() && method.getSourcePsi() == source) {
                    return fromMethod(method.getJavaPsi(), method);
                }
            }
        }
        // Java's implicit constructor has no UMethod. Its class still supplies a searchable source target.
        if (source instanceof PsiClass declarationPsi && !declarationPsi.isInterface()
                && !declarationPsi.isEnum() && !declarationPsi.isRecord() && declarationPsi.getConstructors().length == 0) {
            return context(source, null, declarationPsi.getName() + "." + declarationPsi.getName() + "()");
        }
        return null;
    }

    private static @Nullable Target analyzable(PsiElement source) {
        if (source instanceof PsiField || source instanceof KtProperty) {
            UField field = UastContextKt.toUElement(source, UField.class);
            if (field != null) return context(source, field.getUastInitializer(), field.getName() + " initializer");
        }
        Target function = callable(source);
        if (function != null) return function;
        ULambdaExpression lambda = UastContextKt.toUElement(source, ULambdaExpression.class);
        if (lambda != null) return context(source, lambda.getBody(), "λ lambda");
        UField field = UastContextKt.toUElement(source, UField.class);
        if (field != null) return context(source, field.getUastInitializer(), field.getName() + " initializer");
        UClassInitializer initializer = UastContextKt.toUElement(source, UClassInitializer.class);
        if (initializer != null) return context(source, initializer.getUastBody(), "initializer");
        return null;
    }

    private static Target fromMethod(PsiMethod method, @Nullable UMethod uMethod) {
        PsiElement source = uMethod != null && uMethod.getSourcePsi() != null ? uMethod.getSourcePsi() : physical(method);
        PsiClass owner = method.getContainingClass();
        String prefix = owner == null || owner.getName() == null ? "" : owner.getName() + ".";
        String parameters = String.join(", ", Arrays.stream(method.getParameterList().getParameters())
                .map(p -> p.getType().getPresentableText()).toList());
        String label = source instanceof KtNamedFunction function ? kotlinLabel(function)
                : prefix + method.getName() + "(" + parameters + ")";
        return new Target(source, method, executionRegions(source, uMethod == null ? null : uMethod.getUastBody()), label);
    }

    private static String kotlinLabel(KtNamedFunction function) {
        KtClassOrObject owner = PsiTreeUtil.getParentOfType(function, KtClassOrObject.class);
        String prefix = owner == null || owner.getName() == null ? "" : owner.getName() + ".";
        String receiver = function.getReceiverTypeReference() == null ? "" : function.getReceiverTypeReference().getText() + ".";
        String parameters = String.join(", ", function.getValueParameters().stream()
                .map(p -> p.getTypeReference() == null ? "?" : p.getTypeReference().getText()).toList());
        return prefix + receiver + function.getName() + "(" + parameters + ")";
    }

    private static Target context(PsiElement source, UElement body, String label) {
        return new Target(source, null, executionRegions(source, body), label);
    }

    private static List<UElement> executionRegions(PsiElement source, @Nullable UElement body) {
        List<UElement> regions = new ArrayList<>();
        if (source instanceof KtFunction function) {
            for (var parameter : function.getValueParameters()) {
                var value = parameter.getDefaultValue();
                UExpression expression = value == null ? null : UastContextKt.toUElement(value, UExpression.class);
                if (expression != null) regions.add(expression);
            }
        }
        if (body != null) regions.add(body);
        return List.copyOf(regions);
    }

    private boolean equivalent(PsiElement first, PsiElement second) {
        return PsiManager.getInstance(project).areElementsEquivalent(physical(first), physical(second));
    }

    private boolean inProject(PsiElement source) {
        PsiFile file = source.getContainingFile();
        return file != null && file.getVirtualFile() != null
                && ProjectFileIndex.getInstance(project).isInContent(file.getVirtualFile());
    }

    private SmartPsiElementPointer<PsiElement> pointer(PsiElement element) {
        return SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element);
    }

    private static PsiElement physical(PsiElement element) { return element.getNavigationElement(); }

    private static @Nullable PsiElement resolvedTarget(@Nullable PsiElement resolved, @Nullable PsiElement site) {
        if (resolved != null) {
            PsiElement source = physical(resolved);
            if (source.getTextRange() != null && source.getContainingFile() != null && callable(resolved) != null) return resolved;
        }
        // Kotlin UAST can resolve local functions to a synthetic method or a non-callable variable.
        // Resolve the physical callee reference instead of relying on UAST's internal fake-method classes.
        PsiElement callee = site instanceof KtCallElement call ? call.getCalleeExpression()
                : site instanceof KtCallableReferenceExpression reference ? reference.getCallableReference() : null;
        if (callee != null) {
            for (PsiReference reference : callee.getReferences()) {
                PsiElement candidate = reference.resolve();
                if (candidate != null && physical(candidate) instanceof KtNamedFunction function) return function;
            }
        }
        return null;
    }

    private static String identity(PsiElement element) {
        PsiFile file = element.getContainingFile();
        VirtualFile virtual = file == null ? null : file.getVirtualFile();
        String path = virtual == null ? fileName(element) : virtual.getUrl();
        return path + ":" + element.getTextRange().getStartOffset() + ":" + element.getTextRange().getEndOffset();
    }

    private static String fileName(PsiElement element) {
        PsiFile file = element.getContainingFile();
        return file == null ? "library" : file.getName();
    }

    private static int line(PsiElement element) {
        PsiFile file = element.getContainingFile();
        var document = file == null ? null : PsiDocumentManager.getInstance(element.getProject()).getDocument(file);
        return document == null ? 1 : document.getLineNumber(Math.min(element.getTextOffset(), document.getTextLength())) + 1;
    }

    private static String location(PsiElement element) { return fileName(element) + ":" + line(element); }

    private record Target(PsiElement source, PsiMethod method, List<UElement> regions, String label) { }
    private record Step(String id, int distance) { }
    private record Link(String caller, String callee) { }
    private record Usage(UElement expression, PsiElement source, CallGraph.Kind kind) { }
}
