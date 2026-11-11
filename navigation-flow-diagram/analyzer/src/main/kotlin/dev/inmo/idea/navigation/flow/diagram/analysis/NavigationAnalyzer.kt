package dev.inmo.idea.navigation.flow.diagram.analysis

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

/**
 * Builds a conservative source graph without loading or compiling the analyzed application.
 * Names, declared types, inheritance and local parameter forwarding are resolved from supplied PSI.
 * Dynamic dispatch and expressions without a provable configuration produce diagnostics.
 * No PSI or mutable analysis state is retained after [analyze] returns.
 */
class NavigationAnalyzer {
    fun analyze(sources: List<KotlinSource>, checkCanceled: () -> Unit = {}): NavigationGraph =
        AnalysisRun(KotlinSymbols(sources, checkCanceled)).analyze()
}

private sealed interface Destination {
    data class Named(val name: String) : Destination
    data class Parameter(val parameter: KtParameter) : Destination
    data object Unknown : Destination
}

private data class Effect(val kind: TransitionKind, val destinations: Set<Destination>)

private class AnalysisRun(private val symbols: KotlinSymbols) {
    private val bindings = linkedSetOf<ViewBinding>()
    private val registeredConfigs = linkedSetOf<String>()
    private val diagnostics = linkedSetOf<Diagnostic>()
    private val summaries = mutableMapOf<KtNamedFunction, Set<Effect>>()
    private val callsByFunction = symbols.calls.groupBy { it.ancestors().filterIsInstance<KtNamedFunction>().firstOrNull() }

    fun analyze(): NavigationGraph {
        symbols.errors.forEach { diagnostics += Diagnostic(symbols.location(it), "Kotlin syntax error: ${it.errorDescription}") }
        discoverViews()
        discoverFactories()
        summarizeWrappers()
        val transitions = linkedSetOf<Transition>()
        symbols.calls.forEach { call ->
            symbols.checkCanceled()
            val effects = effects(call)
            effects.forEach { effect ->
                val owner = call.ancestors().filterIsInstance<KtNamedFunction>().firstOrNull()
                val forwarding = effect.destinations.filterIsInstance<Destination.Parameter>().any {
                    it.parameter in owner?.valueParameters.orEmpty() && symbols.concreteType(symbols.type(it.parameter.typeReference), registeredConfigs) == null
                }
                if (forwarding && summaries.containsKey(owner)) {
                    diagnostics += Diagnostic(symbols.callLocation(call), "Configuration is forwarded through a wrapper parameter; concrete destinations are reported at its call sites.")
                    return@forEach
                }
                val source = sourceConfig(call)
                val targets = effect.destinations.flatMap { destination ->
                    when (destination) {
                        is Destination.Named -> listOf(destination.name)
                        is Destination.Parameter -> listOfNotNull(symbols.concreteType(symbols.type(destination.parameter.typeReference), registeredConfigs))
                        Destination.Unknown -> emptyList()
                    }
                }.distinct()
                val unknownTarget = effect.destinations.any { destination ->
                    destination == Destination.Unknown || destination is Destination.Parameter && symbols.concreteType(symbols.type(destination.parameter.typeReference), registeredConfigs) == null
                }
                if (effect.kind != TransitionKind.BACK && (unknownTarget || targets.isEmpty())) {
                    diagnostics += Diagnostic(symbols.callLocation(call), "Cannot determine every destination configuration for ${call.simpleName()}; dynamic or unsupported expressions remain unresolved.")
                }
                if (source == null && effect.kind != TransitionKind.CONTAINMENT) {
                    diagnostics += Diagnostic(symbols.callLocation(call), "Cannot determine a unique source configuration for ${call.simpleName()}.")
                }
                val allTargets: List<String?> = if (effect.kind == TransitionKind.BACK) listOf(null) else targets + if (unknownTarget || targets.isEmpty()) listOf(null) else emptyList()
                allTargets.forEach { target ->
                    if (target != null) registeredConfigs += target
                    if (source != null) registeredConfigs += source
                    transitions += Transition(source, target, call.simpleName(), effect.kind, symbols.callLocation(call))
                }
            }
        }
        // A typed factory uses KClass.isInstance; sealed children inherit their parent's view binding.
        val baseBindings = bindings.toList()
        symbols.classes.keys.forEach { config ->
            symbols.checkCanceled()
            baseBindings.filter { it.configName != config && symbols.isSubtype(config, it.configName) }.forEach { binding ->
                registeredConfigs += config
                bindings += binding.copy(configName = config)
            }
        }
        val configurations = registeredConfigs.flatMap { name ->
            symbols.classes[name].orEmpty().map { Configuration(name, symbols.declarationLocation(it)) }
        }.distinct().sortedWith(compareBy({ it.name }, { it.declaration.path }, { it.declaration.offset }))
        return NavigationGraph(
            configurations,
            bindings.sortedWith(compareBy({ it.configName }, { it.viewName }, { it.declaration.path }, { it.registration?.offset ?: -1 })),
            transitions.sortedWith(compareBy({ it.location.path }, { it.location.offset }, { it.targetConfig.orEmpty() }, { it.kind.name })),
            diagnostics.sortedWith(compareBy({ it.location?.path.orEmpty() }, { it.location?.offset ?: -1 }, { it.message })),
        )
    }

    private fun discoverViews() {
        symbols.classes.forEach { (name, declarations) ->
            declarations.forEach { declaration ->
                symbols.checkCanceled()
                val node = symbols.asSupertype(symbols.classType(declaration), NAV_NODE) ?: return@forEach
                val config = node.arguments.firstOrNull()?.name?.takeIf { it in symbols.classes } ?: return@forEach
                registeredConfigs += config
                bindings += ViewBinding(name, config, symbols.declarationLocation(declaration))
            }
        }
    }

    private fun discoverFactories() {
        symbols.calls.forEach { factory ->
            symbols.checkCanceled()
            val factoryType = symbols.constructionType(factory)
            if (factoryType?.name !in setOf(NAV_FACTORY, "$NAV_FACTORY.Typed")) return@forEach
            val config = if (factoryType?.name == "$NAV_FACTORY.Typed") factoryType.arguments.firstOrNull()?.name else null
            val lambda = factory.lambdaArguments.lastOrNull()?.getLambdaExpression()
                ?: factory.valueArguments.mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }.lastOrNull()
            if (lambda == null) {
                diagnostics += Diagnostic(symbols.callLocation(factory), "Factory uses a callback whose view allocation cannot be inspected locally.")
                return@forEach
            }
            var matched = false
            symbols.calls.asSequence().filter { it !== factory && it.ancestors().any { parent -> parent === lambda } }.forEach { allocation ->
                val allocatedType = symbols.constructionType(allocation) ?: return@forEach
                val views = symbols.classes[allocatedType.name].orEmpty()
                views.forEach { view ->
                    val nodeType = symbols.asSupertype(allocatedType, NAV_NODE) ?: return@forEach
                    val viewConfig = nodeType.arguments.firstOrNull()?.name?.takeIf { it in symbols.classes } ?: return@forEach
                    val bindingConfig = config?.takeIf { it in symbols.classes } ?: viewConfig
                    if (bindingConfig != viewConfig && !symbols.isSubtype(bindingConfig, viewConfig)) {
                        diagnostics += Diagnostic(symbols.callLocation(allocation), "Factory configuration $bindingConfig does not match the declared view configuration $viewConfig.")
                        return@forEach
                    }
                    // Allocation belongs to the platform source set containing this factory when possible.
                    val allocationPlatform = sourceSet(symbols.callLocation(allocation).path)
                    val viewPlatform = sourceSet(symbols.declarationLocation(view).path)
                    if (allocationPlatform != null && viewPlatform != null && viewPlatform != "commonMain" && allocationPlatform != viewPlatform) return@forEach
                    addRegistration(view, bindingConfig, allocation)
                    matched = true
                }
            }
            if (!matched) diagnostics += Diagnostic(symbols.callLocation(factory), "Cannot resolve a view allocation in this navigation factory.")
        }
        // Explicit createNode implementations use the same return contracts as SAM factory lambdas.
        symbols.functions.filter { it.name == "createNode" }.forEach { function ->
            val owner = function.ancestors().filterIsInstance<KtClassOrObject>().firstOrNull() ?: return@forEach
            val isFactory = owner.superTypeListEntries.any { symbols.type(it.typeReference)?.name == NAV_FACTORY }
            if (!isFactory) return@forEach
            callsByFunction[function].orEmpty().forEach { allocation ->
                val allocated = symbols.constructionType(allocation) ?: return@forEach
                val config = symbols.asSupertype(allocated, NAV_NODE)?.arguments?.firstOrNull()?.name ?: return@forEach
                if (config !in symbols.classes) return@forEach
                symbols.classes[allocated.name].orEmpty().forEach { view -> addRegistration(view, config, allocation) }
            }
        }
    }

    private fun sourceSet(path: String): String? = Regex("(?:^|/)src/([^/]+)/").find(path)?.groupValues?.get(1)

    private fun addRegistration(view: KtClassOrObject, config: String, allocation: KtCallExpression) {
        val name = view.fqName?.asString() ?: return
        val location = symbols.declarationLocation(view)
        bindings.removeAll { it.viewName == name && it.configName == config && it.declaration == location && it.registration == null }
        bindings += ViewBinding(name, config, location, symbols.callLocation(allocation))
        registeredConfigs += config
    }

    private fun summarizeWrappers() {
        // Only forwarding functions become wrappers. Ordinary handlers with fixed destinations do not
        // turn every ViewModel/UI invocation into a second, speculative navigation operation.
        var changed = true
        var iteration = 0
        while (changed && iteration++ <= symbols.functions.size) {
            symbols.checkCanceled()
            changed = false
            symbols.functions.forEach { function ->
                val forwarded = callsByFunction[function].orEmpty().flatMap { effects(it, reportAmbiguity = false) }
                    .filter { effect -> effect.kind != TransitionKind.BACK && effect.destinations.any { it is Destination.Parameter && it.parameter in function.valueParameters } }
                    .toSet()
                if (forwarded.isNotEmpty() && summaries[function] != forwarded) {
                    summaries[function] = forwarded
                    changed = true
                }
            }
        }
    }

    private fun effects(call: KtCallExpression, reportAmbiguity: Boolean = true): Set<Effect> {
        directEffect(call)?.let { return setOf(it) }
        val candidates = symbols.resolveFunctions(call)
        val wrappers = candidates.filter { it in summaries }
        if (wrappers.isEmpty()) return emptySet()
        if (candidates.size != 1) {
            if (reportAmbiguity) diagnostics += Diagnostic(symbols.callLocation(call), "Ambiguous wrapper overload for ${call.simpleName()}; no destination is inferred.")
            return emptySet()
        }
        val function = wrappers.single()
        return summaries.getValue(function).map { effect ->
            effect.copy(destinations = effect.destinations.flatMap { destination ->
                if (destination is Destination.Parameter && destination.parameter in function.valueParameters) {
                    destinations(symbols.argument(call, destination.parameter, function.valueParameters.indexOf(destination.parameter)))
                } else setOf(destination)
            }.toSet())
        }.toSet()
    }

    private fun directEffect(call: KtCallExpression): Effect? {
        val name = call.simpleName()
        val fqn = symbols.callFqn(call)
        if (fqn in setOf(NAV_COMPOSE + "InjectNavigationNode", NAV_COMPOSE + "NavigationSubNode")) {
            return Effect(TransitionKind.CONTAINMENT, destinations(argument(call, "config", 0)))
        }
        if (fqn == NAV_COMPOSE + "initNavigation") {
            val rootConfig = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "rootNodeConfig" }?.getArgumentExpression()
            // ConfigHolder overloads contain complete hierarchies and are intentionally not guessed.
            val candidate = rootConfig ?: call.valueArguments.firstOrNull()?.getArgumentExpression()
            val destination = destinations(candidate)
            if (destination.any { it is Destination.Named && it.name in registeredConfigs }) return Effect(TransitionKind.CONTAINMENT, destination)
            return null
        }
        val receiver = if (call.receiver() == null) symbols.implicitReceiver(call) else symbols.typeOf(call.receiver())
        val chain = symbols.asSupertype(receiver, NAV_CHAIN) != null
        val node = symbols.asSupertype(receiver, NAV_NODE) != null
        val either = receiver?.name == NAV_CORE + "ChainOrNodeEither"
        if (!chain && !node && !either) return null
        if (chain) {
            when (name) {
                "push" -> return Effect(TransitionKind.NAVIGATION, destinations(argument(call, "config", 0)))
                "replace", "reversedReplace" -> return Effect(TransitionKind.NAVIGATION, destinations(argument(call, "config", 1)))
                "pop", "drop", "clear", "dropItself" -> return Effect(TransitionKind.BACK, emptySet())
            }
        }
        if (node) {
            if (name == "removeSubChain") return Effect(TransitionKind.BACK, emptySet())
            if (name == "createSubChain") {
                val named = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "config" }?.getArgumentExpression()
                val first = call.valueArguments.firstOrNull()?.getArgumentExpression()
                val index = if (symbols.typeOf(first)?.name == NAV_CORE + "NavigationChainId") 1 else 0
                return Effect(TransitionKind.CONTAINMENT, destinations(named ?: argument(call, "config", index)))
            }
        }
        val extension = fqn?.takeIf { it.startsWith(NAV_CORE + "extensions.") }?.substringAfterLast('.') ?: return null
        if (extension in setOf("dropInSubTree", "dropNodesInSubTree", "dropChainsInSubTree", "dropNodeInSubTree", "dropChainInSubTree")) {
            return Effect(TransitionKind.BACK, emptySet())
        }
        if (extension in setOf("replaceInSubTree", "replaceNodesInSubTree", "replaceChainsInSubTree", "pushInSubTree", "pushInNodesInSubTree", "pushInChainsInSubTree")) {
            val config = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == "config" }?.getArgumentExpression()
                ?: call.lambdaArguments.lastOrNull()?.getLambdaExpression()
                ?: if (call.valueArguments.size == 1) call.valueArguments.single().getArgumentExpression() else call.valueArguments.getOrNull(1)?.getArgumentExpression()
            return Effect(TransitionKind.NAVIGATION, destinations(config))
        }
        return null
    }

    private fun argument(call: KtCallExpression, name: String, position: Int): KtExpression? =
        call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }?.getArgumentExpression()
            ?: call.valueArguments.getOrNull(position)?.takeIf { !it.isNamed() }?.getArgumentExpression()

    private fun destinations(expression: KtExpression?, seen: Set<PsiElement> = emptySet()): Set<Destination> {
        if (expression == null || expression in seen || seen.size > 32) return setOf(Destination.Unknown)
        symbols.checkCanceled()
        val expr = expression.unwrapped()
        val next = seen + expr
        fun fromType() = symbols.concreteType(symbols.typeOf(expr), registeredConfigs)?.let { setOf(Destination.Named(it)) } ?: setOf(Destination.Unknown)
        return when (expr) {
            is KtNameReferenceExpression -> when (val declaration = symbols.declaration(expr.getReferencedName(), expr)) {
                is KtParameter -> setOf(Destination.Parameter(declaration))
                is KtProperty -> {
                    if (!declaration.isVar && declaration.initializer != null) destinations(declaration.initializer, next)
                    else fromType()
                }
                else -> fromType()
            }
            is KtQualifiedExpression -> {
                val resolved = symbols.resolveName(expr.text, expr)
                if (resolved in symbols.classes) setOf(Destination.Named(resolved!!))
                else if (expr.selectorExpression is KtCallExpression) destinations(expr.selectorExpression, next)
                else fromType()
            }
            is KtCallExpression -> {
                val constructed = symbols.constructionType(expr)
                when {
                    constructed?.name in symbols.classes -> setOf(Destination.Named(constructed!!.name))
                    expr.simpleName() in setOf("copy", "takeIf", "takeUnless", "also", "apply") && expr.receiver() != null -> destinations(expr.receiver(), next)
                    expr.simpleName() in setOf("remember", "run", "let") && expr.lambdaArguments.isNotEmpty() -> destinations(expr.lambdaArguments.last().getLambdaExpression(), next)
                    else -> fromType()
                }
            }
            is KtLambdaExpression -> destinations(expr.bodyExpression, next)
            is KtBlockExpression -> destinations(expr.statements.lastOrNull(), next)
            is KtReturnExpression -> destinations(expr.returnedExpression, next)
            is KtIfExpression -> destinations(expr.then, next) + (expr.`else`?.let { destinations(it, next) } ?: emptySet())
            is KtWhenExpression -> expr.entries.flatMap { destinations(it.expression, next) }.toSet()
            is KtBinaryExpressionWithTypeRHS -> fromType()
            is KtBinaryExpression -> if (expr.operationToken == KtTokens.ELVIS) destinations(expr.left, next) + destinations(expr.right, next) else fromType()
            is KtConstantExpression -> if (expr.text == "null") emptySet() else setOf(Destination.Unknown)
            else -> fromType()
        }
    }

    private fun sourceConfig(call: KtCallExpression): String? {
        fun nodeConfig(type: KotlinType?): String? = symbols.asSupertype(type, NAV_NODE)?.arguments?.firstOrNull()?.name?.takeIf { it in symbols.classes || it in registeredConfigs }
        fun receiverConfig(expression: KtExpression?, seen: Set<PsiElement> = emptySet()): String? {
            if (expression == null || expression in seen || seen.size > 24) return null
            nodeConfig(symbols.typeOf(expression))?.let { return it }
            if (expression is KtQualifiedExpression) receiverConfig(expression.receiverExpression, seen + expression)?.let { return it }
            if (expression is KtNameReferenceExpression) {
                val property = symbols.declaration(expression.getReferencedName(), expression) as? KtProperty
                if (property != null && !property.isVar) return receiverConfig(property.initializer, seen + expression)
            }
            return null
        }
        receiverConfig(call.receiver())?.let { return it }
        if (call.simpleName() in setOf("replace", "reversedReplace")) {
            receiverConfig(argument(call, "node", 0))?.let { return it }
        }
        nodeConfig(symbols.implicitReceiver(call))?.let { return it }
        call.ancestors().forEach { ancestor ->
            val candidates = when (ancestor) {
                is KtNamedFunction -> ancestor.valueParameters.mapNotNull { nodeConfig(symbols.type(it.typeReference)) }
                is KtClassOrObject -> {
                    nodeConfig(symbols.classType(ancestor))?.let { return it }
                    (ancestor as? KtClass)?.primaryConstructorParameters.orEmpty().mapNotNull { nodeConfig(symbols.type(it.typeReference)) }
                }
                else -> emptyList()
            }.distinct()
            if (candidates.isNotEmpty()) return candidates.singleOrNull()
        }
        return null
    }
}
