@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class, org.jetbrains.kotlin.psi.KtExperimentalApi::class)

package dev.inmo.dinavigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.psi.*

/** Only plain strings and data classes leave an Analysis API session. */
internal class DiResolver(private val recordDependency: (PsiElement) -> Unit = {}) {
    fun resolve(expression: KtExpression): DiCall? = analyze(expression) {
        ProgressManager.checkCanceled()
        when (expression) {
            is KtCallExpression -> {
                val resolved = expression.resolveCall() ?: return@analyze null
                val spec = specFor(resolved.signature.symbol, 0) ?: return@analyze null
                val type = beanType(resolved, spec.operation) ?: return@analyze null
                val primary = classKey(type) ?: return@analyze null
                val types = linkedSetOf(primary)
                var qualifier = instantiate(spec.qualifier, resolved).valueOrDynamic(expression)
                if (spec.operation.kind == DiKind.DEFINITION) {
                    types.addAll(spec.boundTypes)
                    if (spec.operation.autoBinds && resolved.argument("bindFilter") == null) {
                        type.allSupertypes.mapNotNullTo(types) { classKey(it) }
                    }
                    val decorations = definitionDecorations(expression, resolved)
                    types.addAll(decorations.types)
                    decorations.qualifier?.let { qualifier = it.valueOrDynamic(expression) }
                }
                DiCall(spec.operation.kind, types, qualifier, displayName(type))
            }
            is KtNameReferenceExpression -> {
                val resolved = expression.resolveCall() ?: return@analyze null
                val symbol = resolved.signature.symbol as? KaPropertySymbol ?: return@analyze null
                val spec = specFor(symbol, 0) ?: return@analyze null
                if (spec.operation.kind != DiKind.LOOKUP) return@analyze null
                val type = unwrapBeanType(resolved.signature.returnType, spec.operation) ?: return@analyze null
                val primary = classKey(type) ?: return@analyze null
                DiCall(DiKind.LOOKUP, setOf(primary), spec.qualifier.valueOrDynamic(expression), displayName(type))
            }
            else -> null
        }
    }

    private data class Spec(val operation: DiOperation, val qualifier: QualifierSource, val boundTypes: Set<String> = emptySet())
    private sealed interface QualifierSource {
        data object Random : QualifierSource
        data class Fixed(val value: DiQualifier) : QualifierSource
        data class Parameter(val name: String) : QualifierSource
        data class DefinitionParameter(val name: String) : QualifierSource

        fun valueOrDynamic(site: PsiElement): DiQualifier = when (this) {
            Random -> DiQualifier.Random(site.identity())
            is Fixed -> value
            is Parameter -> DiQualifier.Dynamic("parameter:$name:${site.identity()}")
            is DefinitionParameter -> DiQualifier.Dynamic("definition:$name:${site.identity()}")
        }
    }

    private fun KaSession.specFor(symbol: KaCallableSymbol, depth: Int): Spec? {
        if (depth > 6) return null
        ProgressManager.checkCanceled()
        val id = symbol.callableId
        if (id != null) {
            DiOperations.resolve(id.packageName.asString(), id.className?.asString(), id.callableName.asString())?.let { operation ->
                val qualifier = when {
                    operation.all -> QualifierSource.Fixed(DiQualifier.Any)
                    operation.random -> QualifierSource.Random
                    operation.kind == DiKind.LOOKUP && (symbol as? KaFunctionSymbol)?.valueParameters?.any { it.name.asString() == "definition" } == true ->
                        QualifierSource.DefinitionParameter("definition")
                    else -> QualifierSource.Parameter("qualifier")
                }
                return Spec(operation, qualifier)
            }
        }
        val source = symbol.psi ?: return null
        // Track ordinary functions/getters too: a body edit can turn a previously rejected call
        // into a DI wrapper without changing the project's known set of candidate names.
        recordDependency(source)
        val delegate = when (source) {
            is KtNamedFunction -> source.forwardedCall()
            is KtProperty -> source.forwardedCall()
            else -> null
        } ?: return null
        return analyze(delegate) {
            val call = delegate.resolveCall() ?: return@analyze null
            val spec = specFor(call.signature.symbol, depth + 1) ?: return@analyze null
            val decorations = if (spec.operation.kind == DiKind.DEFINITION) definitionDecorations(delegate, call) else Decorations()
            // A custom delegate filter must stay opaque through every wrapper in the chain.
            val operation = if (spec.operation.autoBinds && call.argument("bindFilter") != null)
                spec.operation.copy(autoBinds = false) else spec.operation
            Spec(operation, decorations.qualifier ?: instantiate(spec.qualifier, call), spec.boundTypes + decorations.types)
        }
    }

    private fun KaSession.instantiate(source: QualifierSource, call: KaFunctionCall<*>): QualifierSource = when (source) {
        QualifierSource.Random -> source
        is QualifierSource.Fixed -> source
        is QualifierSource.Parameter -> call.argumentOrDefault(source.name)?.let { qualifier(it, 0) }
            ?: QualifierSource.Fixed(DiQualifier.None)
        is QualifierSource.DefinitionParameter -> {
            val expression = call.argumentOrDefault(source.name)
            val reference = expression?.unwrapReference()
            val parameter = reference?.resolveCall()?.signature?.symbol as? KaValueParameterSymbol
            if (parameter != null) QualifierSource.DefinitionParameter(parameter.name.asString())
            else QualifierSource.Fixed(definitionQualifier(expression, 0) ?: DiQualifier.Dynamic("definition:${expression?.identity()}"))
        }
    }

    private fun KaFunctionCall<*>.argument(name: String): KtExpression? =
        valueArgumentMapping.entries.firstOrNull { it.value.symbol.name.asString() == name }?.key

    private fun KaFunctionCall<*>.argumentOrDefault(name: String): KtExpression? {
        argument(name)?.let { return it }
        val parameter = signature.symbol.valueParameters.firstOrNull { it.name.asString() == name }?.psi as? KtParameter
        // Decompiled default values are placeholders, not Kotlin expressions representing the default.
        return parameter?.takeUnless { it.containingKtFile.isCompiled }?.defaultValue
    }

    private fun KaSession.beanType(call: KaFunctionCall<*>, operation: DiOperation): KaType? {
        // WithBinds deliberately returns KoinDefinition<*>. The inferred substitution retains T.
        if (operation.kind == DiKind.DEFINITION) {
            unwrapBeanType(call.signature.returnType, operation)?.let { return it }
            return call.typeArgumentsMapping.entries.firstOrNull { it.key.name.asString() in setOf("T", "R") }?.value
        }
        return unwrapBeanType(call.signature.returnType, operation)
    }

    private fun KaSession.unwrapBeanType(type: KaType, operation: DiOperation): KaType? {
        val expanded = type.fullyExpandedType.lowerBoundIfFlexible()
        val classType = expanded as? KaClassType ?: return expanded
        val name = classType.classId.asSingleFqName().asString()
        val wrapper = if (operation.kind == DiKind.DEFINITION) name == "org.koin.core.definition.KoinDefinition"
            else name == operation.returnContainer
        return if (wrapper) (classType.typeArguments.firstOrNull() as? KaTypeArgumentWithVariance)?.type else expanded
    }

    private fun KaSession.classLiteralType(expression: KtClassLiteralExpression): String? = expression.receiverType?.let { classKey(it) }

    private data class Decorations(val types: MutableSet<String> = linkedSetOf(), var qualifier: QualifierSource? = null)

    private fun KaSession.definitionDecorations(call: KtCallExpression, resolved: KaFunctionCall<*>): Decorations {
        val result = Decorations()
        (resolved.argument("options") as? KtLambdaExpression)?.let { readOptions(it, result) }
        var current: PsiElement = call
        if (current.parent is KtQualifiedExpression && (current.parent as KtQualifiedExpression).selectorExpression == current) {
            current = current.parent
        }
        while (true) {
            val parent = current.parent
            if (parent is KtParenthesizedExpression) {
                current = parent
                continue
            }
            val decoration: KaFunctionCall<*>
            val arguments: List<KtExpression>
            when (parent) {
                is KtBinaryExpression -> {
                    if (parent.left != current) break
                    decoration = parent.operationReference.resolveCall() as? KaFunctionCall<*> ?: break
                    arguments = listOfNotNull(parent.right)
                }
                is KtQualifiedExpression -> {
                    if (parent.receiverExpression != current) break
                    val selector = parent.selectorExpression as? KtCallExpression ?: break
                    decoration = selector.resolveCall() ?: break
                    arguments = selector.valueArguments.mapNotNull { it.getArgumentExpression() }
                }
                else -> break
            }
            val id = decoration.signature.symbol.callableId ?: break
            val name = id.callableName.asString()
            when {
                id.packageName.asString() == "org.koin.dsl" && name in setOf("bind", "binds", "onClose", "override") -> {
                    if (name == "bind") decoration.typeArgumentsMapping.values.mapNotNullTo(result.types) { classKey(it) }
                    if (name == "bind" || name == "binds") arguments.forEach { addClassLiterals(it, result.types) }
                }
                id.packageName.asString() == "org.koin.core.module.dsl" && name in setOf("withOptions", "onOptions") -> {
                    arguments.filterIsInstance<KtLambdaExpression>().forEach { readOptions(it, result) }
                }
                else -> break
            }
            current = parent
        }
        return result
    }

    private fun KaSession.addClassLiterals(expression: KtExpression, destination: MutableSet<String>) {
        if (expression is KtClassLiteralExpression) classLiteralType(expression)?.let(destination::add)
        PsiTreeUtil.findChildrenOfType(expression, KtClassLiteralExpression::class.java)
            .mapNotNullTo(destination) { classLiteralType(it) }
    }

    private fun KaSession.readOptions(lambda: KtLambdaExpression, result: Decorations) {
        // Only direct option statements belong to this definition; nested registrations do not.
        for (statement in lambda.bodyExpression?.statements.orEmpty()) {
            val call = statement.asCall() ?: continue
            val resolved = call.resolveCall() ?: continue
            val id = resolved.signature.symbol.callableId ?: continue
            if (id.packageName.asString() != "org.koin.core.module.dsl") continue
            when (id.callableName.asString()) {
                "bind" -> resolved.typeArgumentsMapping.values.mapNotNullTo(result.types) { classKey(it) }
                "binds" -> call.valueArguments.mapNotNull { it.getArgumentExpression() }.forEach { addClassLiterals(it, result.types) }
                "named" -> {
                    val arg = call.valueArguments.firstOrNull()?.getArgumentExpression()
                    result.qualifier = if (arg != null) qualifier(arg, 0)
                    else resolved.typeArgumentsMapping.values.firstOrNull()?.let { classKey(it) }
                        ?.let { QualifierSource.Fixed(DiQualifier.Named(it)) }
                }
            }
        }
    }

    private fun KaSession.qualifier(expression: KtExpression, depth: Int): QualifierSource {
        if (depth > 8) return QualifierSource.Fixed(DiQualifier.Dynamic(expression.identity()))
        // Constant evaluation can return before the normal property-resolution path below.
        // Retain dependencies of const names and concatenations as well as qualifier objects.
        val references = PsiTreeUtil.findChildrenOfType(expression, KtNameReferenceExpression::class.java).toMutableSet()
        if (expression is KtNameReferenceExpression) references.add(expression)
        for (reference in references) {
            reference.resolveCall()?.signature?.symbol?.psi?.let(recordDependency)
        }
        val value = expression.evaluate()?.value
        if (value is String) return QualifierSource.Fixed(DiQualifier.Named(value))
        if (expression.text == "null") return QualifierSource.Fixed(DiQualifier.None)
        val call = expression.asCall()
        if (call != null) {
            val resolved = call.resolveCall()
            val id = resolved?.signature?.symbol?.callableId
            if (id?.packageName?.asString() == "org.koin.core.qualifier" &&
                (id.callableName.asString() in setOf("named", "qualifier", "StringQualifier", "TypeQualifier") ||
                    id.className?.asString() in setOf("StringQualifier", "TypeQualifier"))) {
                val arg = call.valueArguments.firstOrNull()?.getArgumentExpression()
                if (arg != null) return qualifier(arg, depth + 1)
                resolved.typeArgumentsMapping.values.firstOrNull()?.let { classKey(it) }?.let {
                    return QualifierSource.Fixed(DiQualifier.Named(it))
                }
            }
        }
        if (expression is KtClassLiteralExpression) {
            classLiteralType(expression)?.let { return QualifierSource.Fixed(DiQualifier.Named(it)) }
        }
        val reference = expression.unwrapReference()
        val symbol = reference?.resolveCall()?.signature?.symbol
        if (symbol is KaValueParameterSymbol) return QualifierSource.Parameter(symbol.name.asString())
        val property = symbol?.psi as? KtProperty
        if (property != null && !property.isVar) {
            recordDependency(property)
            property.initializer?.let { initializer ->
                val nested = analyze(initializer) { qualifier(initializer, depth + 1) }
                if (nested is QualifierSource.Fixed && nested.value !is DiQualifier.Dynamic) return nested
            }
            return QualifierSource.Fixed(DiQualifier.Dynamic(property.identity()))
        }
        return QualifierSource.Fixed(DiQualifier.Dynamic(symbol?.callableId?.toString() ?: expression.identity()))
    }

    private fun KaSession.definitionQualifier(expression: KtExpression?, depth: Int): DiQualifier? {
        if (expression == null || depth > 6) return null
        expression.asCall()?.let { call -> return resolve(call)?.takeIf { it.kind == DiKind.DEFINITION }?.qualifier }
        val symbol = expression.unwrapReference()?.resolveCall()?.signature?.symbol
        val initializer = (symbol?.psi as? KtProperty)?.initializer ?: return null
        recordDependency(initializer)
        return analyze(initializer) { definitionQualifier(initializer, depth + 1) }
    }

    private fun KtExpression.unwrapReference(): KtNameReferenceExpression? = when (this) {
        is KtNameReferenceExpression -> this
        is KtQualifiedExpression -> selectorExpression as? KtNameReferenceExpression
        is KtParenthesizedExpression -> expression?.unwrapReference()
        else -> null
    }

    private fun KaSession.classType(type: KaType): KaClassType? = type.fullyExpandedType.lowerBoundIfFlexible() as? KaClassType
    private fun KaSession.classKey(type: KaType): String? = classType(type)?.classId?.takeUnless { it.isLocal }?.asSingleFqName()?.asString()
    private fun KaSession.displayName(value: KaType): String {
        val type = classType(value) ?: return "?"
        val args = type.typeArguments
        return type.classId.shortClassName.asString() + if (args.isEmpty()) "" else args.joinToString(", ", "<", ">") {
            (it as? KaTypeArgumentWithVariance)?.type?.let { argument -> displayName(argument) } ?: "*"
        }
    }
}

private fun PsiElement.identity(): String = "${containingFile?.virtualFile?.url ?: containingFile?.name}:$textOffset"
