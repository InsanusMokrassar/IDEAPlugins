package dev.inmo.idea.function.call.graph.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiNamedElement;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.PathUtil;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Integration tests against physical source files and the IDE's Java/Kotlin resolution. */
public final class CallGraphAnalyzerTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        String localJdk = System.getProperty("callgraph.test.jdk");
        return localJdk == null || localJdk.isBlank() ? JAVA_17
                : new DefaultLightProjectDescriptor(() -> JavaSdk.getInstance()
                        .createJdk("callgraph-test-jdk", localJdk, false));
    }

    public void testJavaIncomingAndOutgoingCallsHaveDirectedEdgesAndSourceLocations() {
        myFixture.configureByText("Flow.java", """
                class Flow {
                    void caller() { target(); }
                    void target<caret>() { leaf(); }
                    void leaf() { }
                }
                """);

        CallGraph graph = analyzeCaret(1);

        assertLabels(graph, "Flow.caller()", "Flow.target()", "Flow.leaf()");
        assertEquals(node(graph, "Flow.target()").id(), graph.rootId());
        assertEquals(-1, node(graph, "Flow.caller()").layer());
        assertEquals(0, root(graph).layer());
        assertEquals(1, node(graph, "Flow.leaf()").layer());
        CallGraph.Site incoming = assertEdge(graph, "Flow.caller()", "Flow.target()", CallGraph.Kind.CALL).sites().getFirst();
        CallGraph.Site outgoing = assertEdge(graph, "Flow.target()", "Flow.leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals("Flow.java", incoming.file());
        assertEquals(2, incoming.line());
        assertEquals(3, outgoing.line());
        assertEquals("target()", incoming.source().getElement().getText());
        assertEquals("leaf()", outgoing.source().getElement().getText());
        assertEquals(0, graph.unresolvedCalls());
        assertFalse(graph.truncated());
    }

    public void testCaretOnCallSelectsTargetNotCaller() {
        myFixture.configureByText("Selection.java", """
                class Selection {
                    void caller() { tar<caret>get(); }
                    void target() { }
                }
                """);

        CallGraph graph = analyzeCaret(1);

        assertEquals("Selection.target()", root(graph).label());
        assertEdge(graph, "Selection.caller()", "Selection.target()", CallGraph.Kind.CALL);
    }

    public void testOverloadedMethodsRemainDistinctInBothDirections() {
        myFixture.configureByText("Overloads.java", """
                class Overloads {
                    void dispatch<caret>() { accept(1); accept("text"); }
                    void onlyNumber() { accept(2); }
                    void onlyText() { accept("other"); }
                    void accept(int number) { }
                    void accept(String text) { }
                }
                """);

        CallGraph outgoing = analyzeCaret(1);
        assertLabels(outgoing, "Overloads.dispatch()", "Overloads.accept(int)", "Overloads.accept(String)");
        assertEdge(outgoing, "Overloads.dispatch()", "Overloads.accept(int)", CallGraph.Kind.CALL);
        assertEdge(outgoing, "Overloads.dispatch()", "Overloads.accept(String)", CallGraph.Kind.CALL);
        assertFalse(node(outgoing, "Overloads.accept(int)").id().equals(node(outgoing, "Overloads.accept(String)").id()));

        CallGraph incoming = analyze(source(node(outgoing, "Overloads.accept(int)")), 1);
        assertLabels(incoming, "Overloads.accept(int)", "Overloads.dispatch()", "Overloads.onlyNumber()");
        assertEdge(incoming, "Overloads.dispatch()", "Overloads.accept(int)", CallGraph.Kind.CALL);
        assertEdge(incoming, "Overloads.onlyNumber()", "Overloads.accept(int)", CallGraph.Kind.CALL);
    }

    public void testConstructorHasIncomingAndOutgoingCalls() {
        myFixture.configureByText("Widget.java", """
                class Widget {
                    Widget<caret>(int size) { initialize(); }
                    void initialize() { }
                    static Widget create() { return new Widget(1); }
                }
                """);

        CallGraph graph = analyzeCaret(1);

        assertEquals("Widget.Widget(int)", root(graph).label());
        CallGraph.Site construction = assertEdge(graph, "Widget.create()", "Widget.Widget(int)", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals("new Widget(1)", construction.snippet());
        assertEdge(graph, "Widget.Widget(int)", "Widget.initialize()", CallGraph.Kind.CALL);
    }

    public void testJavaDefaultConstructorCanBeFocusedWithoutLosingCallSites() {
        myFixture.configureByText("DefaultConstructor.java", """
                class DefaultConstructor {
                    void caller<caret>() { new Empty(); new Empty(); }
                    void other() { new Empty(); }
                }
                class Empty { }
                """);

        CallGraph outgoing = analyzeCaret(1);
        CallGraph.Node constructor = node(outgoing, "Empty.Empty()");
        CallGraph.Edge constructions = assertEdge(outgoing, root(outgoing), constructor,
                CallGraph.Kind.CALL, CallGraph.Kind.CALL);
        assertEquals(2L, constructions.sites().stream()
                .map(site -> site.source().getElement().getTextRange().getStartOffset()).distinct().count());

        CallGraph focused = analyze(source(constructor), 1);
        assertEquals(constructor.id(), focused.rootId());
        assertLabels(focused, "Empty.Empty()", "DefaultConstructor.caller()", "DefaultConstructor.other()");
        CallGraph.Edge callers = assertEdge(focused, "DefaultConstructor.caller()", "Empty.Empty()",
                CallGraph.Kind.CALL, CallGraph.Kind.CALL);
        assertEdge(focused, "DefaultConstructor.other()", "Empty.Empty()", CallGraph.Kind.CALL);
        assertEquals(constructions.sites().stream().map(CallGraph.Site::snippet).toList(),
                callers.sites().stream().map(CallGraph.Site::snippet).toList());
        assertEquals("DefaultConstructor.java", callers.sites().getFirst().file());
        assertEquals(2, callers.sites().getFirst().line());
        assertEquals(0, focused.unresolvedCalls());
    }

    public void testKotlinImplicitConstructorExpandsAndCanBeFocused() {
        addKotlinRuntime();
        configureKotlin("ImplicitConstructor.kt", """
                package sample
                fun root<caret>() { A() }
                class A { init { leaf() } }
                fun leaf() { }
                """);

        CallGraph shallow = analyzeCaret(1);
        assertLabels(shallow, "root()", "A.A()");
        assertEdge(shallow, "root()", "A.A()", CallGraph.Kind.CALL);

        CallGraph deeper = analyzeCaret(2);
        CallGraph.Node constructor = node(deeper, "A.A()");
        CallGraph.Edge initializer = assertEdge(deeper, constructor, function(deeper, "leaf()"), CallGraph.Kind.CALL);
        assertEdge(deeper, root(deeper), constructor, CallGraph.Kind.CALL);
        assertEquals(2, deeper.edges().size());
        assertEquals("leaf()", initializer.sites().getFirst().source().getElement().getText());

        CallGraph focused = analyze(source(constructor), 1);
        assertEquals(constructor.id(), focused.rootId());
        assertEdge(focused, "root()", "A.A()", CallGraph.Kind.CALL);
        CallGraph.Site focusedSite = assertEdge(focused, "A.A()", "leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals("ImplicitConstructor.kt", focusedSite.file());
        assertEquals(3, focusedSite.line());
        assertEquals(initializer.sites().getFirst().source().getElement(), focusedSite.source().getElement());
        assertEquals(0, focused.unresolvedCalls());

        CallGraph incoming = analyze(source(function(deeper, "leaf()")), 2);
        assertEquals(constructor.id(), node(incoming, "A.A()").id());
        assertEdge(incoming, "root()", "A.A()", CallGraph.Kind.CALL);
        assertEdge(incoming, "A.A()", "leaf()", CallGraph.Kind.CALL);
    }

    public void testKotlinObjectInitializerContextCanBeFocused() {
        addKotlinRuntime();
        configureKotlin("ObjectInitializer.kt", """
                package sample
                object Registry { init { leaf() } }
                fun leaf<caret>() { }
                """);

        CallGraph incoming = analyzeCaret(1);
        CallGraph.Node initializer = node(incoming, "Registry.Registry()");
        CallGraph.Site site = assertEdge(incoming, initializer, root(incoming), CallGraph.Kind.CALL).sites().getFirst();

        CallGraph focused = analyze(source(initializer), 1);
        assertEquals(initializer.id(), focused.rootId());
        CallGraph.Site focusedSite = assertEdge(focused, root(focused), function(focused, "leaf()"),
                CallGraph.Kind.CALL).sites().getFirst();
        assertEquals("leaf()", focusedSite.snippet());
        assertEquals(site.source().getElement(), focusedSite.source().getElement());
        assertEquals(2, focusedSite.line());
        assertEquals(0, focused.unresolvedCalls());
    }

    public void testKotlinExplicitConstructorOverloadsKeepDistinctSourcesWhenFocused() {
        addKotlinRuntime();
        configureKotlin("ConstructorOverloads.kt", """
                package sample
                class Widget {
                    constructor(size: Int) { numberLeaf() }
                    constructor(text: String) { textLeaf() }
                }
                fun caller<caret>() { Widget(1); Widget("text") }
                fun numberCaller() { Widget(2) }
                fun textCaller() { Widget("other") }
                fun numberLeaf() { }
                fun textLeaf() { }
                """);

        CallGraph outgoing = analyzeCaret(1);
        CallGraph.Node number = node(outgoing, "Widget.Widget(int)");
        CallGraph.Node text = node(outgoing, "Widget.Widget(String)");
        assertFalse(number.id().equals(text.id()));
        assertFalse(source(number).equals(source(text)));
        assertEdge(outgoing, root(outgoing), number, CallGraph.Kind.CALL);
        assertEdge(outgoing, root(outgoing), text, CallGraph.Kind.CALL);

        CallGraph numberFocused = analyze(source(number), 1);
        assertEquals(number.id(), numberFocused.rootId());
        assertEdge(numberFocused, function(numberFocused, "caller()"), root(numberFocused), CallGraph.Kind.CALL);
        assertEdge(numberFocused, function(numberFocused, "numberCaller()"), root(numberFocused), CallGraph.Kind.CALL);
        assertEdge(numberFocused, root(numberFocused), function(numberFocused, "numberLeaf()"), CallGraph.Kind.CALL);
        assertFalse(labels(numberFocused).contains("textCaller()"));
        assertFalse(labels(numberFocused).contains("textLeaf()"));

        CallGraph textFocused = analyze(source(text), 1);
        assertEquals(text.id(), textFocused.rootId());
        assertEdge(textFocused, function(textFocused, "caller()"), root(textFocused), CallGraph.Kind.CALL);
        assertEdge(textFocused, function(textFocused, "textCaller()"), root(textFocused), CallGraph.Kind.CALL);
        assertEdge(textFocused, root(textFocused), function(textFocused, "textLeaf()"), CallGraph.Kind.CALL);
        assertFalse(labels(textFocused).contains("numberCaller()"));
        assertFalse(labels(textFocused).contains("numberLeaf()"));
    }

    public void testRecursionTerminatesAndDepthLimitsEachDirection() {
        myFixture.configureByText("Recursive.java", """
                class Recursive {
                    void upstream() { before(); }
                    void before() { root(); }
                    void root<caret>() { root(); next(); }
                    void next() { root(); end(); }
                    void end() { }
                }
                """);

        CallGraph shallow = analyzeCaret(1);
        assertLabels(shallow, "Recursive.root()", "Recursive.before()", "Recursive.next()");
        assertEdge(shallow, "Recursive.root()", "Recursive.root()", CallGraph.Kind.CALL);
        assertEdge(shallow, "Recursive.root()", "Recursive.next()", CallGraph.Kind.CALL);
        assertEdge(shallow, "Recursive.next()", "Recursive.root()", CallGraph.Kind.CALL);

        CallGraph deeper = analyzeCaret(2);
        assertLabels(deeper, "Recursive.root()", "Recursive.before()", "Recursive.next()", "Recursive.upstream()", "Recursive.end()");
        assertEdge(deeper, "Recursive.upstream()", "Recursive.before()", CallGraph.Kind.CALL);
        assertEdge(deeper, "Recursive.next()", "Recursive.end()", CallGraph.Kind.CALL);
        // Incoming and outgoing walks see this same recursive call; it must stay one site.
        assertEdge(deeper, "Recursive.root()", "Recursive.root()", CallGraph.Kind.CALL);
        assertFalse(deeper.truncated());

        CallGraph deepest = analyzeCaret(5);
        assertEquals(deeper.nodes().size(), deepest.nodes().size());
        assertEquals(deeper.edges().size(), deepest.edges().size());
        assertFalse(deepest.truncated());
    }

    public void testRepeatedCallsAggregateWithoutLosingSameLineSites() {
        myFixture.configureByText("Repeated.java", """
                class Repeated {
                    void target() { }
                    void caller<caret>() {
                        target(); target();
                        target();
                    }
                }
                """);

        CallGraph outgoing = analyzeCaret(1);
        CallGraph.Edge edge = assertEdge(outgoing, "Repeated.caller()", "Repeated.target()",
                CallGraph.Kind.CALL, CallGraph.Kind.CALL, CallGraph.Kind.CALL);

        assertEquals(List.of(4, 4, 5), edge.sites().stream().map(CallGraph.Site::line).sorted().toList());
        assertEquals(3L, edge.sites().stream()
                .map(site -> site.source().getElement().getTextRange().getStartOffset()).distinct().count());

        CallGraph incoming = analyze(source(node(outgoing, "Repeated.target()")), 1);
        assertEdge(incoming, "Repeated.caller()", "Repeated.target()",
                CallGraph.Kind.CALL, CallGraph.Kind.CALL, CallGraph.Kind.CALL);
        assertEquals(1, incoming.edges().size());
    }

    public void testCallableReferencesAreSeparateFromDirectCalls() {
        myFixture.configureByText("References.java", """
                interface Job { void run(); }
                class References {
                    void target() { }
                    void caller<caret>() { target(); install(this::target); }
                    void install(Job job) { }
                }
                """);

        CallGraph outgoing = analyzeCaret(1);
        CallGraph.Edge targetEdge = assertEdge(outgoing, "References.caller()", "References.target()",
                CallGraph.Kind.CALL, CallGraph.Kind.REFERENCE);
        assertEdge(outgoing, "References.caller()", "References.install(Job)", CallGraph.Kind.CALL);
        assertEquals("this::target", targetEdge.sites().stream()
                .filter(site -> site.kind() == CallGraph.Kind.REFERENCE).findFirst().orElseThrow().snippet());

        CallGraph incoming = analyze(source(node(outgoing, "References.target()")), 1);
        assertLabels(incoming, "References.target()", "References.caller()");
        assertEdge(incoming, "References.caller()", "References.target()",
                CallGraph.Kind.CALL, CallGraph.Kind.REFERENCE);
    }

    public void testInitializerCallersRemainSeparateContexts() {
        myFixture.configureByText("Initializers.java", """
                class Initializers {
                    static int target<caret>() { return 1; }
                    int field = target();
                    static int staticField = target();
                    { target(); }
                    static { target(); }
                }
                """);

        CallGraph graph = analyzeCaret(1);
        List<CallGraph.Node> callers = graph.nodes().stream().filter(node -> !node.id().equals(graph.rootId())).toList();

        assertEquals(4, callers.size());
        assertEquals(2L, callers.stream().filter(node -> source(node) instanceof PsiField).count());
        assertEquals(2L, callers.stream().filter(node -> source(node) instanceof PsiClassInitializer).count());
        assertEquals(4, graph.edges().size());
        for (CallGraph.Node caller : callers) {
            assertEdge(graph, caller, root(graph), CallGraph.Kind.CALL);
            assertEquals(-1, caller.layer());
        }
    }

    public void testLambdaBodyIsNotAttributedToEnclosingMethod() {
        myFixture.configureByText("Lambdas.java", """
                interface Task { void run(); }
                class Lambdas {
                    void owner<caret>() { Task callback = () -> target(); }
                    void target() { }
                }
                """);

        CallGraph owner = analyzeCaret(1);
        assertLabels(owner, "Lambdas.owner()");
        assertEmpty(owner.edges());

        CallGraph target = analyzeAtText("void target", "void ".length(), 1);
        assertEquals(2, target.nodes().size());
        CallGraph.Node lambda = target.nodes().stream().filter(node -> !node.id().equals(target.rootId())).findFirst().orElseThrow();
        assertTrue(source(lambda) instanceof PsiLambdaExpression);
        assertEdge(target, lambda, root(target), CallGraph.Kind.CALL);
    }

    public void testNestedClassBodyIsNotAttributedToEnclosingMethod() {
        myFixture.configureByText("Nested.java", """
                class Nested {
                    void owner<caret>() {
                        class Local { void run() { target(); } }
                    }
                    void target() { }
                }
                """);

        CallGraph owner = analyzeCaret(1);
        assertLabels(owner, "Nested.owner()");
        assertEmpty(owner.edges());

        CallGraph target = analyzeAtText("void target", "void ".length(), 1);
        assertLabels(target, "Nested.target()", "Local.run()");
        assertEdge(target, "Local.run()", "Nested.target()", CallGraph.Kind.CALL);
    }

    public void testKotlinTopLevelAndMemberCalls() {
        addKotlinRuntime();
        configureKotlin("Functions.kt", """
                package sample
                fun root<caret>() { leaf(); Worker().member() }
                fun caller() { root() }
                fun leaf() { }
                class Worker { fun member() { } }
                """);

        CallGraph graph = analyzeCaret(1);

        assertEquals("root", ((PsiNamedElement) source(root(graph))).getName());
        assertEdge(graph, function(graph, "caller()"), root(graph), CallGraph.Kind.CALL);
        assertEdge(graph, root(graph), function(graph, "leaf()"), CallGraph.Kind.CALL);
        assertEdge(graph, root(graph), node(graph, "Worker.member()"), CallGraph.Kind.CALL);
        assertFalse(graph.truncated());
        assertEquals(0, graph.unresolvedCalls());
    }

    public void testJavaAndKotlinCallsResolveAcrossLanguages() {
        addKotlinRuntime();
        myFixture.addFileToProject("sample/JavaApi.java", """
                package sample;
                public class JavaApi {
                    public static void fromJava() { KotlinApiKt.entry(); }
                    public static void leaf() { }
                }
                """);
        configureKotlin("KotlinApi.kt", """
                package sample
                fun entry<caret>() { JavaApi.leaf() }
                """);

        CallGraph graph = analyzeCaret(1);

        assertEquals(3, graph.nodes().size());
        assertEdge(graph, node(graph, "JavaApi.fromJava()"), root(graph), CallGraph.Kind.CALL);
        assertEdge(graph, root(graph), node(graph, "JavaApi.leaf()"), CallGraph.Kind.CALL);
        assertEquals("KotlinApi.kt", source(root(graph)).getContainingFile().getName());
        assertEquals(0, graph.unresolvedCalls());
    }

    public void testKotlinCallableReferencesAreSeparateFromDirectCalls() {
        addKotlinRuntime();
        configureKotlin("KotlinReferences.kt", """
                package sample
                fun target() { }
                fun install(callback: () -> Unit) { }
                fun caller<caret>() {
                    target()
                    install(::target)
                }
                """);

        CallGraph outgoing = analyzeCaret(1);
        CallGraph.Node target = function(outgoing, "target()");
        CallGraph.Edge uses = assertEdge(outgoing, root(outgoing), target,
                CallGraph.Kind.CALL, CallGraph.Kind.REFERENCE);
        assertEdge(outgoing, root(outgoing), function(outgoing, "install(() -> Unit)"), CallGraph.Kind.CALL);
        assertEquals("::target", uses.sites().stream().filter(site -> site.kind() == CallGraph.Kind.REFERENCE)
                .findFirst().orElseThrow().snippet());

        CallGraph incoming = analyze(source(target), 1);
        assertEquals(2, incoming.nodes().size());
        assertEdge(incoming, function(incoming, "caller()"), root(incoming), CallGraph.Kind.CALL, CallGraph.Kind.REFERENCE);
    }

    public void testKotlinExtensionOverloadsRemainDistinct() {
        addKotlinRuntime();
        configureKotlin("Extensions.kt", """
                package sample
                fun String.transform(value: Int) { }
                fun String.transform(value: String) { }
                fun caller<caret>() { "value".transform(1); "value".transform("text") }
                fun numberCaller() { "value".transform(2) }
                fun textCaller() { "value".transform("other") }
                """);

        CallGraph outgoing = analyzeCaret(1);
        assertLabels(outgoing, "caller()", "String.transform(Int)", "String.transform(String)");
        assertEdge(outgoing, "caller()", "String.transform(Int)", CallGraph.Kind.CALL);
        assertEdge(outgoing, "caller()", "String.transform(String)", CallGraph.Kind.CALL);

        CallGraph incoming = analyze(source(node(outgoing, "String.transform(Int)")), 1);
        assertLabels(incoming, "String.transform(Int)", "caller()", "numberCaller()");
        assertEdge(incoming, "caller()", "String.transform(Int)", CallGraph.Kind.CALL);
        assertEdge(incoming, "numberCaller()", "String.transform(Int)", CallGraph.Kind.CALL);
    }

    public void testKotlinLocalFunctionHasItsOwnContext() {
        addKotlinRuntime();
        configureKotlin("LocalFunctions.kt", """
                package sample
                fun host<caret>() {
                    fun nested() { leaf() }
                }
                fun leaf() { }
                """);

        CallGraph host = analyzeCaret(1);
        assertEquals(1, host.nodes().size());
        assertEmpty(host.edges());

        CallGraph leaf = analyzeAtText("fun leaf", "fun ".length(), 1);
        assertEquals(2, leaf.nodes().size());
        CallGraph.Node nested = function(leaf, "nested()");
        assertEquals("nested", ((PsiNamedElement) source(nested)).getName());
        assertEdge(leaf, nested, root(leaf), CallGraph.Kind.CALL);

        CallGraph nestedRoot = analyze(source(nested), 1);
        assertEquals(nested.id(), nestedRoot.rootId());
        assertEdge(nestedRoot, root(nestedRoot), function(nestedRoot, "leaf()"), CallGraph.Kind.CALL);
    }

    public void testKotlinLocalFunctionIsExpandedOnlyThroughItsCall() {
        addKotlinRuntime();
        configureKotlin("CalledLocalFunction.kt", """
                package sample
                fun host<caret>() {
                    fun nested() { leaf() }
                    nested()
                }
                fun leaf() { }
                """);

        CallGraph shallow = analyzeCaret(1);
        assertEquals(2, shallow.nodes().size());
        assertEdge(shallow, root(shallow), function(shallow, "nested()"), CallGraph.Kind.CALL);

        CallGraph deeper = analyzeCaret(2);
        assertEquals(3, deeper.nodes().size());
        assertEdge(deeper, root(deeper), function(deeper, "nested()"), CallGraph.Kind.CALL);
        assertEdge(deeper, function(deeper, "nested()"), function(deeper, "leaf()"), CallGraph.Kind.CALL);
        assertEquals(0, deeper.unresolvedCalls());

        CallGraph nested = analyze(source(function(deeper, "nested()")), 1);
        assertEquals(3, nested.nodes().size());
        assertEdge(nested, function(nested, "host()"), root(nested), CallGraph.Kind.CALL);
        assertEdge(nested, root(nested), function(nested, "leaf()"), CallGraph.Kind.CALL);
    }

    public void testKotlinLocalCallableReferenceHasOneReferenceSiteInBothDirections() {
        addKotlinRuntime();
        configureKotlin("LocalReference.kt", """
                package sample
                fun host<caret>() {
                    fun nested() { leaf() }
                    consume(::nested)
                }
                fun consume(callback: () -> Unit) { }
                fun leaf() { }
                """);

        CallGraph shallow = analyzeCaret(1);
        assertLabels(shallow, "host()", "nested()", "consume(() -> Unit)");
        CallGraph.Node nested = function(shallow, "nested()");
        CallGraph.Site reference = assertEdge(shallow, root(shallow), nested, CallGraph.Kind.REFERENCE).sites().getFirst();
        assertEdge(shallow, root(shallow), function(shallow, "consume(() -> Unit)"), CallGraph.Kind.CALL);
        assertEquals("::nested", reference.snippet());
        assertEquals("::nested", reference.source().getElement().getText());
        assertEquals(4, reference.line());
        assertEquals("nested", ((PsiNamedElement) source(nested)).getName());

        CallGraph deeper = analyzeCaret(2);
        assertEquals(3, deeper.edges().size());
        assertEdge(deeper, root(deeper), function(deeper, "nested()"), CallGraph.Kind.REFERENCE);
        assertEdge(deeper, function(deeper, "nested()"), function(deeper, "leaf()"), CallGraph.Kind.CALL);

        CallGraph focused = analyze(source(nested), 1);
        assertEquals(nested.id(), focused.rootId());
        assertLabels(focused, "host()", "nested()", "leaf()");
        CallGraph.Site incoming = assertEdge(focused, function(focused, "host()"), root(focused),
                CallGraph.Kind.REFERENCE).sites().getFirst();
        assertEdge(focused, root(focused), function(focused, "leaf()"), CallGraph.Kind.CALL);
        assertEquals(reference.source().getElement(), incoming.source().getElement());
        assertEquals(0, focused.unresolvedCalls());
    }

    public void testKotlinDefaultArgumentCallAppearsInBothDirectionsAndAtDepthTwo() {
        addKotlinRuntime();
        configureKotlin("DefaultArgument.kt", """
                package sample
                fun target<caret>(value: Int = leaf()) { }
                fun leaf(): Int = 1
                fun caller() { target() }
                """);

        CallGraph target = analyzeCaret(1);
        assertLabels(target, "target(Int)", "leaf()", "caller()");
        CallGraph.Site defaultCall = assertEdge(target, "target(Int)", "leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEdge(target, "caller()", "target(Int)", CallGraph.Kind.CALL);
        assertEquals("leaf()", defaultCall.source().getElement().getText());
        assertEquals(2, defaultCall.line());

        CallGraph incoming = analyze(source(function(target, "leaf()")), 1);
        assertLabels(incoming, "leaf()", "target(Int)");
        CallGraph.Site incomingSite = assertEdge(incoming, "target(Int)", "leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals(defaultCall.source().getElement(), incomingSite.source().getElement());

        CallGraph caller = analyzeAtText("fun caller", "fun ".length(), 2);
        assertLabels(caller, "caller()", "target(Int)", "leaf()");
        assertEdge(caller, "caller()", "target(Int)", CallGraph.Kind.CALL);
        assertEdge(caller, "target(Int)", "leaf()", CallGraph.Kind.CALL);
        assertEquals(2, caller.edges().size());
        assertEquals(0, caller.unresolvedCalls());
    }

    public void testKotlinLocalDefaultArgumentBelongsToLocalFunction() {
        addKotlinRuntime();
        configureKotlin("LocalDefaultArgument.kt", """
                package sample
                fun host<caret>() {
                    fun nested(value: Int = leaf()) { }
                    nested()
                }
                fun leaf(): Int = 1
                """);

        CallGraph shallow = analyzeCaret(1);
        assertLabels(shallow, "host()", "nested(Int)");
        assertEdge(shallow, "host()", "nested(Int)", CallGraph.Kind.CALL);

        CallGraph deeper = analyzeCaret(2);
        assertLabels(deeper, "host()", "nested(Int)", "leaf()");
        assertEdge(deeper, "host()", "nested(Int)", CallGraph.Kind.CALL);
        CallGraph.Site defaultCall = assertEdge(deeper, "nested(Int)", "leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals(2, deeper.edges().size());
        assertEquals(3, defaultCall.line());

        CallGraph focused = analyze(source(function(deeper, "nested(Int)")), 1);
        assertEquals(function(deeper, "nested(Int)").id(), focused.rootId());
        assertEdge(focused, "host()", "nested(Int)", CallGraph.Kind.CALL);
        assertEdge(focused, "nested(Int)", "leaf()", CallGraph.Kind.CALL);

        CallGraph incoming = analyze(source(function(deeper, "leaf()")), 1);
        assertLabels(incoming, "leaf()", "nested(Int)");
        CallGraph.Site incomingSite = assertEdge(incoming, "nested(Int)", "leaf()", CallGraph.Kind.CALL).sites().getFirst();
        assertEquals(defaultCall.source().getElement(), incomingSite.source().getElement());
    }

    public void testKotlinDefaultLambdaBodyIsNotAttributedToFunction() {
        addKotlinRuntime();
        configureKotlin("DefaultLambda.kt", """
                package sample
                fun target<caret>(callback: () -> Unit = { leaf() }) { }
                fun leaf() { }
                fun caller() { target() }
                """);

        CallGraph target = analyzeCaret(1);
        assertLabels(target, "target(() -> Unit)", "caller()");
        assertEdge(target, "caller()", "target(() -> Unit)", CallGraph.Kind.CALL);
        assertEquals(1, target.edges().size());

        CallGraph caller = analyzeAtText("fun caller", "fun ".length(), 2);
        assertLabels(caller, "caller()", "target(() -> Unit)");
        assertEquals(1, caller.edges().size());

        CallGraph leaf = analyzeAtText("fun leaf", "fun ".length(), 1);
        assertEquals(2, leaf.nodes().size());
        CallGraph.Node lambda = leaf.nodes().stream().filter(node -> !node.id().equals(leaf.rootId())).findFirst().orElseThrow();
        assertEquals("{ leaf() }", source(lambda).getText());
        assertEdge(leaf, lambda, root(leaf), CallGraph.Kind.CALL);
    }

    public void testNodeLimitLeavesOnlyEdgesWithExistingEndpoints() {
        myFixture.configureByText("Limited.java", """
                class Limited {
                    void root<caret>() { first(); second(); }
                    void first() { }
                    void second() { }
                }
                """);

        CallGraph graph = analyzeCaret(1, 2, 100);

        assertTrue(graph.truncated());
        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        Set<String> ids = graph.nodes().stream().map(CallGraph.Node::id).collect(Collectors.toSet());
        for (CallGraph.Edge edge : graph.edges()) {
            assertTrue(ids.contains(edge.callerId()));
            assertTrue(ids.contains(edge.calleeId()));
            assertFalse(edge.sites().isEmpty());
        }
    }

    public void testSiteLimitReportsOnlyActualOmissions() {
        myFixture.configureByText("SiteLimit.java", """
                class SiteLimit {
                    void root<caret>() { leaf(); leaf(); leaf(); }
                    void leaf() { }
                }
                """);

        CallGraph truncated = analyzeCaret(1, 10, 2);
        assertTrue(truncated.truncated());
        assertEdge(truncated, "SiteLimit.root()", "SiteLimit.leaf()", CallGraph.Kind.CALL, CallGraph.Kind.CALL);

        CallGraph complete = analyzeCaret(1, 10, 3);
        assertFalse("Reaching the limit without omitting a site is a complete graph", complete.truncated());
        assertEdge(complete, "SiteLimit.root()", "SiteLimit.leaf()",
                CallGraph.Kind.CALL, CallGraph.Kind.CALL, CallGraph.Kind.CALL);
    }

    public void testUnresolvedCallsAreReportedWithoutFakeNodes() {
        myFixture.configureByText("Incomplete.java", """
                class Incomplete {
                    void root<caret>() { missing(); leaf(); }
                    void leaf() { }
                }
                """);

        CallGraph graph = analyzeCaret(1);

        assertLabels(graph, "Incomplete.root()", "Incomplete.leaf()");
        assertEdge(graph, "Incomplete.root()", "Incomplete.leaf()", CallGraph.Kind.CALL);
        assertEquals(1, graph.unresolvedCalls());
        assertFalse(graph.truncated());
    }

    private void addKotlinRuntime() {
        Path jar = Path.of(PathUtil.getJarPathForClass(kotlin.Unit.class));
        PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "kotlin-stdlib",
                jar.getParent().toString(), jar.getFileName().toString());
    }

    private void configureKotlin(String fileName, String text) {
        PsiFile file = myFixture.configureByText(fileName, text);
        assertTrue("The declared Kotlin plugin dependency must be loaded", "kotlin".equalsIgnoreCase(file.getLanguage().getID()));
    }

    private CallGraph analyzeCaret(int depth) {
        return analyzeCaret(depth, CallGraphAnalyzer.MAX_NODES, CallGraphAnalyzer.MAX_SITES);
    }

    private CallGraph analyzeCaret(int depth, int maxNodes, int maxSites) {
        PsiFile file = myFixture.getFile();
        int offset = myFixture.getCaretOffset();
        return analyzeInBackground(() -> {
            PsiElement selected = CallGraphAnalyzer.findFunction(file, offset);
            assertNotNull("The function at the caret must resolve", selected);
            return new CallGraphAnalyzer(getProject(), maxNodes, maxSites).analyze(selected, depth);
        });
    }

    private CallGraph analyzeAtText(String text, int offsetInText, int depth) {
        PsiFile file = myFixture.getFile();
        int offset = file.getText().indexOf(text);
        assertTrue("Missing fixture anchor: " + text, offset >= 0);
        return analyzeInBackground(() -> {
            PsiElement selected = CallGraphAnalyzer.findFunction(file, offset + offsetInText);
            assertNotNull("The anchored function must resolve", selected);
            return new CallGraphAnalyzer(getProject()).analyze(selected, depth);
        });
    }

    private CallGraph analyze(PsiElement source, int depth) {
        return analyzeInBackground(() -> new CallGraphAnalyzer(getProject()).analyze(source, depth));
    }

    private CallGraph analyzeInBackground(Supplier<CallGraph> analysis) {
        var future = ApplicationManager.getApplication().executeOnPooledThread(() -> ReadAction.compute(analysis::get));
        try {
            // Pump IDE events while waiting, and propagate assertion failures from the pooled read action.
            return PlatformTestUtil.waitForFuture(future, 30_000);
        } finally {
            future.cancel(true);
        }
    }

    private static PsiElement source(CallGraph.Node node) {
        PsiElement source = node.source().getElement();
        assertNotNull("Node must retain a valid source navigation pointer: " + node.label(), source);
        return source;
    }

    private static CallGraph.Node root(CallGraph graph) {
        return graph.nodes().stream().filter(node -> node.id().equals(graph.rootId())).findFirst().orElseThrow();
    }

    private static CallGraph.Node node(CallGraph graph, String label) {
        List<CallGraph.Node> matches = graph.nodes().stream().filter(node -> node.label().equals(label)).toList();
        assertEquals("Expected exactly one node named " + label + "; got " + labels(graph), 1, matches.size());
        return matches.getFirst();
    }

    private static CallGraph.Node function(CallGraph graph, String signature) {
        List<CallGraph.Node> matches = graph.nodes().stream()
                .filter(node -> node.label().equals(signature) || node.label().endsWith("." + signature)).toList();
        assertEquals("Expected exactly one function " + signature + "; got " + labels(graph), 1, matches.size());
        return matches.getFirst();
    }

    private static List<String> labels(CallGraph graph) {
        return graph.nodes().stream().map(CallGraph.Node::label).sorted().toList();
    }

    private static void assertLabels(CallGraph graph, String... expected) {
        assertEquals(Arrays.stream(expected).sorted().toList(), labels(graph));
    }

    private static CallGraph.Edge assertEdge(CallGraph graph, String caller, String callee, CallGraph.Kind... kinds) {
        return assertEdge(graph, node(graph, caller), node(graph, callee), kinds);
    }

    private static CallGraph.Edge assertEdge(CallGraph graph, CallGraph.Node caller, CallGraph.Node callee, CallGraph.Kind... kinds) {
        List<CallGraph.Edge> matches = graph.edges().stream()
                .filter(edge -> edge.callerId().equals(caller.id()) && edge.calleeId().equals(callee.id())).toList();
        assertEquals("Expected exactly one edge from " + caller.label() + " to " + callee.label(), 1, matches.size());
        CallGraph.Edge edge = matches.getFirst();
        assertEquals(Arrays.stream(kinds).sorted().toList(), edge.sites().stream().map(CallGraph.Site::kind).sorted().toList());
        return edge;
    }
}
