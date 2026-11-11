package dev.inmo.idea.navigation.flow.diagram.analysis

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

internal const val NAV_CORE = "dev.inmo.navigation.core."
internal const val NAV_COMPOSE = "dev.inmo.navigation.compose."
internal const val NAV_NODE = NAV_CORE + "NavigationNode"
internal const val NAV_CHAIN = NAV_CORE + "NavigationChain"
internal const val NAV_FACTORY = NAV_CORE + "NavigationNodeFactory"

internal data class KotlinType(val name: String, val arguments: List<KotlinType> = emptyList())

internal fun PsiElement.ancestors(): Sequence<PsiElement> = generateSequence(parent) { it.parent }
internal fun KtCallExpression.receiver(): KtExpression? =
    (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === this }?.receiverExpression
internal fun KtCallExpression.simpleName(): String = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    ?: calleeExpression?.text.orEmpty()
internal fun KtExpression.unwrapped(): KtExpression =
    if (this is KtParenthesizedExpression) expression?.unwrapped() ?: this else this

/** Only names supported by this analyzer are resolved from library star imports. */
internal val navigationNames: Set<String> = buildSet {
    addAll(listOf(NAV_NODE, NAV_CHAIN, NAV_FACTORY, "$NAV_FACTORY.Typed", "$NAV_FACTORY.DefaultAggregator"))
    add(NAV_CORE + "NavigationChainId")
    add(NAV_CORE + "NavigationNodeId")
    add(NAV_CORE + "ChainOrNodeEither")
    add(NAV_CORE + "configs.NavigationNodeDefaultConfig")
    add(NAV_COMPOSE + "ComposeNode")
    add("dev.inmo.navigation.mvvm.compose.ComposeView")
    add("dev.inmo.navigation.mvvm.View")
    add("dev.inmo.navigation.mvvm.JvmComposeView")
    add("dev.inmo.navigation.mvvm.ViewFragment")
    add(NAV_CORE + "fragments.NodeFragment")
    listOf("InjectNavigationNode", "NavigationSubNode", "InjectNavigationChain", "SubChain", "initNavigation").forEach {
        add(NAV_COMPOSE + it)
    }
    listOf("replaceInSubTree", "replaceNodesInSubTree", "replaceChainsInSubTree", "pushInSubTree",
        "pushInNodesInSubTree", "pushInChainsInSubTree", "dropInSubTree", "dropNodesInSubTree",
        "dropChainsInSubTree", "dropNodeInSubTree", "dropChainInSubTree", "rootChain", "findChain",
        "findChainInSubTree", "findNode", "findNodeInSubTree", "findInSubTree").forEach {
        add(NAV_CORE + "extensions." + it)
    }
}

internal class KotlinSymbols(
    val sources: List<KotlinSource>,
    val checkCanceled: () -> Unit,
) {
    val classes = linkedMapOf<String, MutableList<KtClassOrObject>>()
    val aliases = linkedMapOf<String, MutableList<KtTypeAlias>>()
    val functions = mutableListOf<KtNamedFunction>()
    val calls = mutableListOf<KtCallExpression>()
    val errors = mutableListOf<com.intellij.psi.PsiErrorElement>()
    private val sourceByFile = sources.associateBy { it.file }
    private val lines = sources.associate { source ->
        source.file to buildList {
            add(0)
            source.file.text.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }
    }
    private val types = mutableMapOf<KtExpression, KotlinType?>()
    private val inProgress = mutableSetOf<KtExpression>()
    private val supertypes = mutableMapOf<Pair<KotlinType, String>, KotlinType?>()
    private val functionsByName by lazy { functions.groupBy { it.fqName?.asString() } }

    init {
        sources.forEach { source ->
            checkCanceled()
            source.file.accept(object : KtTreeVisitorVoid() {
                override fun visitElement(element: PsiElement) {
                    checkCanceled()
                    if (element is com.intellij.psi.PsiErrorElement) errors += element
                    super.visitElement(element)
                }

                override fun visitClassOrObject(classOrObject: KtClassOrObject) {
                    classOrObject.fqName?.asString()?.let { classes.getOrPut(it) { mutableListOf() } += classOrObject }
                    super.visitClassOrObject(classOrObject)
                }

                override fun visitTypeAlias(typeAlias: KtTypeAlias) {
                    typeAlias.fqName?.asString()?.let { aliases.getOrPut(it) { mutableListOf() } += typeAlias }
                    super.visitTypeAlias(typeAlias)
                }

                override fun visitNamedFunction(function: KtNamedFunction) {
                    functions += function
                    super.visitNamedFunction(function)
                }

                override fun visitCallExpression(expression: KtCallExpression) {
                    calls += expression
                    super.visitCallExpression(expression)
                }
            })
        }
    }

    fun location(element: PsiElement): SourceLocation {
        val file = element.containingFile as KtFile
        val offset = element.textOffset
        val starts = lines.getValue(file)
        val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        return SourceLocation(sourceByFile.getValue(file).path, offset, line + 1, offset - starts[line] + 1)
    }

    fun declarationLocation(declaration: KtNamedDeclaration): SourceLocation =
        location(declaration.nameIdentifier ?: declaration)

    fun callLocation(call: KtCallExpression): SourceLocation = location(call.calleeExpression ?: call)

    fun resolveName(raw: String, context: PsiElement): String? {
        val name = raw.replace("`", "")
        if (name.isEmpty() || !name.split('.').all { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) }) return null
        val file = context.containingFile as KtFile
        val first = name.substringBefore('.')
        // Lexically nested classes precede imports and package declarations.
        context.ancestors().filterIsInstance<KtClassOrObject>().forEach { owner ->
            owner.fqName?.asString()?.let { prefix ->
                val candidate = "$prefix.$name"
                if (candidate in classes || candidate in aliases) return candidate
                if (owner.name == first) {
                    val self = prefix + name.removePrefix(first)
                    if (self in classes || self in aliases) return self
                }
            }
        }
        val imported = file.importDirectives.filter { !it.isAllUnder && (it.aliasName ?: it.importedFqName?.shortName()?.asString()) == first }
            .mapNotNull { it.importedFqName?.asString()?.plus(name.removePrefix(first)) }.distinct()
        if (imported.size == 1) return imported.single()
        if (imported.size > 1) return null
        if ('.' in name && (name in classes || name in aliases || name in navigationNames)) return name
        val packageName = file.packageFqName.asString()
        val inPackage = if (packageName.isEmpty()) name else "$packageName.$name"
        if (inPackage in classes || inPackage in aliases || inPackage in functionsByName) return inPackage
        val stars = file.importDirectives.filter { it.isAllUnder }.mapNotNull { it.importedFqName?.asString() }
            .map { "$it.$name" }.filter { it in classes || it in aliases || it in navigationNames || it in functionsByName }.distinct()
        if (stars.size == 1) return stars.single()
        if (stars.size > 1) return null
        if (name.first().isLowerCase() && '.' in name) return name
        return null
    }

    fun type(reference: KtTypeReference?, substitutions: Map<String, KotlinType> = emptyMap(), seen: Set<String> = emptySet()): KotlinType? {
        if (reference == null) return null
        fun parse(element: KtTypeElement?): KotlinType? = when (element) {
            is KtNullableType -> parse(element.innerType)
            is KtUserType -> {
                val raw = generateSequence(element) { it.qualifier }.toList().asReversed().mapNotNull { it.referencedName }.joinToString(".")
                val args = element.typeArguments.map { type(it.typeReference, substitutions, seen) ?: KotlinType("*") }
                substitutions[raw] ?: run {
                    val parameter = reference.ancestors().filterIsInstance<KtTypeParameterListOwner>().any { owner -> owner.typeParameters.any { it.name == raw } }
                    if (parameter) KotlinType(raw, args) else {
                        val name = resolveName(raw, reference) ?: return@run null
                        val alias = aliases[name]?.singleOrNull()
                        if (alias != null && name !in seen) {
                            type(alias.getTypeReference(), alias.typeParameters.mapIndexedNotNull { index, p -> p.name?.let { it to args.getOrElse(index) { KotlinType("*") } } }.toMap(), seen + name)
                        } else if (name in aliases) null else KotlinType(name, args)
                    }
                }
            }
            else -> null
        }
        return parse(reference.typeElement)
    }

    fun isNavigationType(type: KotlinType?, expected: String): Boolean = asSupertype(type, expected) != null

    fun asSupertype(type: KotlinType?, expected: String): KotlinType? {
        checkCanceled()
        if (type == null) return null
        val key = type to expected
        if (supertypes.containsKey(key)) return supertypes[key]
        val pending = ArrayDeque<Pair<KotlinType, Set<String>>>()
        pending.add(type to emptySet())
        val visited = mutableSetOf<KotlinType>()
        val results = linkedSetOf<KotlinType>()
        while (pending.isNotEmpty()) {
            checkCanceled()
            val (current, ancestors) = pending.removeFirst()
            // Keep type arguments in the visited key, but cut invalid cycles even when they
            // keep changing those arguments (A<T> : A<Box<T>>).
            if (current.name in ancestors || !visited.add(current)) continue
            if (current.name == expected) {
                results += current
                continue
            }
            val knownNodeIndex = when (current.name) {
                NAV_COMPOSE + "ComposeNode", "dev.inmo.navigation.mvvm.compose.ComposeView", "dev.inmo.navigation.mvvm.View",
                "dev.inmo.navigation.mvvm.JvmComposeView", NAV_CORE + "fragments.NodeFragment" -> 0
                "dev.inmo.navigation.mvvm.ViewFragment" -> 1
                else -> null
            }
            if (expected == NAV_NODE && knownNodeIndex != null) {
                results += KotlinType(NAV_NODE, listOf(current.arguments.getOrElse(knownNodeIndex) { KotlinType("*") },
                    current.arguments.getOrElse(if (knownNodeIndex == 0) 1 else 2) { KotlinType("*") }))
                continue
            }
            classes[current.name].orEmpty().forEach { klass ->
                val parameters = klass.typeParameters.mapIndexedNotNull { index, p -> p.name?.let { it to current.arguments.getOrElse(index) { KotlinType("*") } } }.toMap()
                klass.superTypeListEntries.forEach { entry ->
                    checkCanceled()
                    this.type(entry.typeReference, parameters)?.let { pending.add(it to ancestors + current.name) }
                }
            }
        }
        // Store only a completed traversal; cancellation must not cache a partial answer.
        return results.singleOrNull().also { supertypes[key] = it }
    }

    fun isSubtype(name: String, parent: String): Boolean = asSupertype(KotlinType(name), parent) != null

    fun classType(klass: KtClassOrObject): KotlinType? = klass.fqName?.asString()?.let {
        KotlinType(it, klass.typeParameters.map { parameter -> KotlinType(parameter.name ?: "*") })
    }

    fun declaration(name: String, at: PsiElement): KtNamedDeclaration? {
        at.ancestors().forEach { owner ->
            when (owner) {
                is KtBlockExpression -> owner.statements.filterIsInstance<KtNamedDeclaration>()
                    .lastOrNull { it.name == name && (it is KtNamedFunction || it.textOffset < at.textOffset) }?.let { return it }
                is KtNamedFunction -> owner.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtFunctionLiteral -> owner.valueParameters.firstOrNull { it.name == name }?.let { return it }
                is KtClassOrObject -> {
                    (owner as? KtClass)?.primaryConstructorParameters?.firstOrNull { it.name == name }?.let { return it }
                    owner.declarations.filterIsInstance<KtNamedDeclaration>().firstOrNull { it.name == name }?.let { return it }
                }
            }
        }
        val file = at.containingFile as KtFile
        file.declarations.filterIsInstance<KtNamedDeclaration>().firstOrNull { it.name == name }?.let { return it }
        val fqn = resolveName(name, at) ?: return null
        return sources.asSequence().flatMap { it.file.declarations.asSequence() }.filterIsInstance<KtNamedDeclaration>()
            .filter { it.fqName?.asString() == fqn }.singleOrNull()
    }

    fun typeOf(expression: KtExpression?, depth: Int = 0): KotlinType? {
        if (expression == null || depth > 24) return null
        val expr = expression.unwrapped()
        if (types.containsKey(expr)) return types[expr]
        if (!inProgress.add(expr)) return null
        checkCanceled()
        val result = try { inferType(expr, depth) } finally { inProgress.remove(expr) }
        types[expr] = result
        return result
    }

    private fun inferType(expr: KtExpression, depth: Int): KotlinType? = when (expr) {
        is KtNameReferenceExpression -> when (val declaration = declaration(expr.getReferencedName(), expr)) {
            is KtParameter -> type(declaration.typeReference)
            is KtProperty -> propertyType(declaration, depth + 1)
            is KtClassOrObject -> classType(declaration)
            else -> (if (declaration == null) navigationProperty(implicitReceiver(expr), expr.getReferencedName()) else null)
                ?: resolveName(expr.text, expr)?.takeIf { it in classes }?.let(::KotlinType)
        }
        is KtThisExpression -> implicitReceiver(expr)
        is KtQualifiedExpression -> {
            val whole = resolveName(expr.text, expr)
            if (whole in classes) KotlinType(whole!!) else {
                val receiver = typeOf(expr.receiverExpression, depth + 1)
                val selector = expr.selectorExpression
                if (selector is KtCallExpression) typeOf(selector, depth + 1) else when (selector?.text) {
                    "chain", "config", "parentNode" -> navigationProperty(receiver, selector.text)
                    else -> {
                        val member = classes[receiver?.name].orEmpty().flatMap { klass -> klass.declarations.filterIsInstance<KtProperty>() }
                            .filter { it.name == selector?.text }.singleOrNull()
                        member?.let { propertyType(it, depth + 1) }
                    }
                }
            }
        }
        is KtCallExpression -> {
            val construction = constructionType(expr)
            val args = expr.typeArguments.map { type(it.typeReference) ?: KotlinType("*") }
            when {
                construction != null -> construction
                expr.simpleName() == "copy" -> typeOf(expr.receiver(), depth + 1)
                expr.simpleName() in setOf("also", "apply", "takeIf", "takeUnless") -> typeOf(expr.receiver(), depth + 1)
                expr.simpleName() in setOf("remember", "run", "let") -> typeOf(expr.lambdaArguments.lastOrNull()?.getLambdaExpression()?.bodyExpression?.statements?.lastOrNull(), depth + 1)
                expr.simpleName() == "get" && args.size == 1 &&
                    (expr.containingFile as KtFile).importDirectives.any { it.importedFqName?.asString()?.contains("koin") == true } &&
                    resolveFunctions(expr).isEmpty() -> args.single()
                else -> {
                    val receiver = typeOf(expr.receiver(), depth + 1)
                    val base = asSupertype(receiver, NAV_CHAIN)?.arguments?.firstOrNull()
                        ?: asSupertype(receiver, NAV_NODE)?.arguments?.getOrNull(1)
                    if (base != null && expr.simpleName() in setOf("rootChain", "findChain", "findChainInSubTree", "createEmptySubChain")) {
                        KotlinType(NAV_CHAIN, listOf(base))
                    } else if (base != null && expr.simpleName() == "findInSubTree" && typeOf(expr.valueArguments.firstOrNull()?.getArgumentExpression(), depth + 1)?.name == NAV_CORE + "NavigationChainId") {
                        KotlinType(NAV_CHAIN, listOf(base))
                    } else {
                        val function = resolveFunctions(expr).singleOrNull()
                        type(function?.typeReference) ?: function?.takeIf { !it.hasBlockBody() }?.bodyExpression?.let { typeOf(it, depth + 1) }
                    }
                }
            }
        }
        is KtBinaryExpressionWithTypeRHS -> type(expr.right)
        is KtPostfixExpression -> if (expr.operationToken == KtTokens.EXCLEXCL) typeOf(expr.baseExpression, depth + 1) else null
        is KtBinaryExpression -> if (expr.operationToken == KtTokens.ELVIS) {
            val left = typeOf(expr.left, depth + 1)
            if (expr.right is KtReturnExpression || expr.right is KtThrowExpression) left
            else left?.takeIf { it == typeOf(expr.right, depth + 1) }
        } else null
        is KtIfExpression -> listOfNotNull(typeOf(expr.then, depth + 1), typeOf(expr.`else`, depth + 1)).distinct().singleOrNull()
        is KtWhenExpression -> expr.entries.mapNotNull { typeOf(it.expression, depth + 1) }.distinct().singleOrNull()
        is KtBlockExpression -> typeOf(expr.statements.lastOrNull(), depth + 1)
        is KtReturnExpression -> typeOf(expr.returnedExpression, depth + 1)
        else -> null
    }

    private fun propertyType(property: KtProperty, depth: Int): KotlinType? {
        type(property.typeReference)?.let { return it }
        typeOf(property.initializer ?: property.getter?.bodyExpression, depth)?.let { return it }
        val delegate = property.delegateExpression as? KtCallExpression ?: return null
        if (delegate.simpleName() !in setOf("inject", "lazyInject") || delegate.typeArguments.size != 1) return null
        if ((property.containingFile as KtFile).importDirectives.none { it.importedFqName?.asString()?.contains("koin") == true }) return null
        if (resolveFunctions(delegate).isNotEmpty()) return null
        return type(delegate.typeArguments.single().typeReference)
    }

    private fun navigationProperty(receiver: KotlinType?, name: String): KotlinType? = when (name) {
        "chain" -> asSupertype(receiver, NAV_NODE)?.arguments?.getOrNull(1)?.let { KotlinType(NAV_CHAIN, listOf(it)) }
        "config" -> asSupertype(receiver, NAV_NODE)?.arguments?.firstOrNull()
        "parentNode" -> asSupertype(receiver, NAV_CHAIN)?.arguments?.firstOrNull()?.let { KotlinType(NAV_NODE, listOf(KotlinType("*"), it)) }
        else -> null
    }

    fun callFqn(call: KtCallExpression): String? {
        val receiver = call.receiver()
        if (receiver != null) {
            val qualified = resolveName(receiver.text + "." + call.simpleName(), call)
            if (qualified in classes || qualified in aliases || qualified in navigationNames) return qualified
        }
        // Instance-qualified extensions still resolve through their import, never through the text
        // of the receiver ("node.chain.pushInSubTree" is not a package-qualified function name).
        return resolveName(call.simpleName(), call)
    }

    fun constructionType(call: KtCallExpression): KotlinType? {
        if (hasCallableValue(call)) return null
        val constructed = declaredConstructionType(call) ?: return null
        // A class name can also name a factory function; syntax alone does not select its constructor.
        return constructed.takeIf { functionCandidates(call).isEmpty() }
    }

    private fun declaredConstructionType(call: KtCallExpression): KotlinType? {
        val name = callFqn(call) ?: return null
        val args = call.typeArguments.map { type(it.typeReference) ?: KotlinType("*") }
        val alias = aliases[name]?.singleOrNull()
        if (alias != null) {
            val parameters = alias.typeParameters.mapIndexedNotNull { index, p -> p.name?.let { it to args.getOrElse(index) { KotlinType("*") } } }.toMap()
            return type(alias.getTypeReference(), parameters, setOf(name))
        }
        return KotlinType(name, args).takeIf { name in classes || name in navigationNames && name.substringAfterLast('.').firstOrNull()?.isUpperCase() == true }
    }

    fun implicitReceiver(at: PsiElement): KotlinType? {
        at.ancestors().forEach { ancestor ->
            when (ancestor) {
                is KtLambdaExpression -> {
                    val outerCall = ancestor.ancestors().takeWhile { it !is KtLambdaExpression && it !is KtNamedFunction }.filterIsInstance<KtCallExpression>().firstOrNull()
                    if (outerCall != null) {
                        when (outerCall.simpleName()) {
                            "with" -> typeOf(outerCall.valueArguments.firstOrNull()?.getArgumentExpression())?.let { return it }
                            "run", "apply" -> typeOf(outerCall.receiver())?.let { return it }
                            "InjectNavigationChain", "SubChain" -> if (callFqn(outerCall) in setOf(NAV_COMPOSE + "InjectNavigationChain", NAV_COMPOSE + "SubChain")) {
                                return KotlinType(NAV_CHAIN, outerCall.typeArguments.map { type(it.typeReference) ?: KotlinType("*") })
                            }
                        }
                    }
                }
                is KtNamedFunction -> type(ancestor.receiverTypeReference)?.let { return it }
                is KtClassOrObject -> classType(ancestor)?.let { if (asSupertype(it, NAV_NODE) != null || asSupertype(it, NAV_CHAIN) != null) return it }
            }
        }
        return null
    }

    fun argument(call: KtCallExpression, parameter: KtParameter, index: Int): KtExpression? =
        call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == parameter.name }?.getArgumentExpression()
            ?: call.valueArguments.getOrNull(index)?.takeIf { !it.isNamed() }?.getArgumentExpression()
            ?: parameter.defaultValue

    fun resolveFunctions(call: KtCallExpression): List<KtNamedFunction> {
        if (hasCallableValue(call)) return emptyList()
        val candidates = functionCandidates(call)
        val constructed = declaredConstructionType(call)
        if (candidates.isNotEmpty() && constructed != null && classes[constructed.name].orEmpty().any { declaration ->
                val klass = declaration as? KtClass ?: return@any false
                (klass.primaryConstructor?.let { argumentsFit(call, it.valueParameters) }
                    ?: (klass.secondaryConstructors.isEmpty() && argumentsFit(call, emptyList()))) ||
                    klass.secondaryConstructors.any { argumentsFit(call, it.valueParameters) }
            }) return emptyList()
        return candidates
    }

    private fun hasCallableValue(call: KtCallExpression): Boolean {
        if (call.receiver() != null) return false
        val declaration = declaration(call.simpleName(), call)
        // In `val chain = chain()`, the initializer can still call the outer function.
        return declaration is KtParameter || declaration is KtProperty && call.ancestors().none { it === declaration }
    }

    private fun argumentsFit(call: KtCallExpression, parameters: List<KtParameter>): Boolean {
        val argumentNames = call.valueArguments.mapNotNull { it.getArgumentName()?.asName?.asString() }
        val required = parameters.count { !it.hasDefaultValue() && !it.isVarArg }
        val count = call.valueArguments.size + call.lambdaArguments.count { lambda -> call.valueArguments.none { it.asElement() === lambda } }
        return argumentNames.all { n -> parameters.any { it.name == n } } &&
            count >= required && (count <= parameters.size || parameters.any { it.isVarArg })
    }

    private fun functionCandidates(call: KtCallExpression): List<KtNamedFunction> {
        val name = call.simpleName()
        val receiver = call.receiver()
        fun fitting(candidates: List<KtNamedFunction>): List<KtNamedFunction> = candidates.filter { argumentsFit(call, it.valueParameters) }
        if (receiver == null) {
            call.ancestors().forEach { scope ->
                val local = when (scope) {
                    is KtBlockExpression -> scope.statements.filterIsInstance<KtNamedFunction>()
                    is KtClassOrObject -> scope.declarations.filterIsInstance<KtNamedFunction>()
                    else -> emptyList()
                }.filter { it.name == name }
                if (local.isNotEmpty()) return fitting(local)
            }
        } else {
            // A package-qualified top-level factory is neither a member nor an extension.
            // A lexical value with the qualifier's name still takes precedence over that package.
            if (declaration(receiver.text.substringBefore('.'), call) == null) {
                val qualified = resolveName(receiver.text + "." + name, call)
                val functions = functionsByName[qualified].orEmpty().filter { it.parent is KtFile && it.receiverTypeReference == null }
                if (functions.isNotEmpty()) return fitting(functions)
            }
            // Looking up a member does not recursively infer this very call's receiver type.
            val receiverType = typeOf(receiver)
            val members = classes[receiverType?.name].orEmpty().flatMap { it.declarations.filterIsInstance<KtNamedFunction>() }.filter { it.name == name }
            if (members.isNotEmpty()) return fitting(members)
        }
        val fqn = resolveName(name, call) ?: return emptyList()
        return fitting(functionsByName[fqn].orEmpty().filter { it.parent is KtFile && (receiver == null || it.receiverTypeReference != null) })
    }

    fun concreteType(type: KotlinType?, registered: Set<String>): String? {
        if (type == null) return null
        if (type.name in registered) return type.name
        val declarations = classes[type.name].orEmpty()
        return type.name.takeIf { declarations.isNotEmpty() && declarations.all { it !is KtClass || (!it.isInterface() && !it.hasModifier(KtTokens.ABSTRACT_KEYWORD)) } }
    }
}
