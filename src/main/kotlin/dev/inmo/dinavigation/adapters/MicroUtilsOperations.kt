package dev.inmo.dinavigation.adapters

import dev.inmo.dinavigation.DiKind
import dev.inmo.dinavigation.DiOperation
import dev.inmo.dinavigation.DiOperationAdapter
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol

/** Compatibility with compiled extensions; no dependency on this library is bundled. */
internal object MicroUtilsOperations : DiOperationAdapter {
    private val definitions = setOf(
        "single", "factory", "singleWithBinds", "factoryWithBinds",
        "singleWithRandomQualifier", "factoryWithRandomQualifier",
        "singleWithRandomQualifierAndBinds", "factoryWithRandomQualifierAndBinds",
        "singleSuspend", "factorySuspend",
    )
    private val lookups = setOf("get", "getAllDistinct", "getAny", "lazyInject")
    override val names = definitions + lookups

    override fun resolve(symbol: KaCallableSymbol): DiOperation? {
        val id = symbol.callableId ?: return null
        if (id.packageName.asString() != "dev.inmo.micro_utils.koin" || id.className != null) return null
        val name = id.callableName.asString()
        if (name in definitions) return DiOperation(
            DiKind.DEFINITION,
            random = "RandomQualifier" in name,
            supertypeBindingFilter = "bindFilter".takeIf { "Binds" in name },
            // These APIs can erase the return type to KoinDefinition<*>.
            beanTypeParameter = 0,
        )
        if (name in lookups) return DiOperation(
            DiKind.LOOKUP,
            all = name in setOf("getAllDistinct", "getAny"),
            definitionParameter = "definition".takeIf {
                (symbol as? KaFunctionSymbol)?.valueParameters?.any { it.name.asString() == "definition" } == true
            },
            returnContainer = when (name) {
                "getAllDistinct" -> "kotlin.collections.List"
                "lazyInject" -> "kotlin.Lazy"
                else -> null
            },
        )
        return null
    }
}
