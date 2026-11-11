package dev.inmo.idea.koin.dependency.navigation

import dev.inmo.idea.koin.dependency.navigation.adapters.MicroUtilsOperations
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol

/** Semantic contracts for binary APIs whose implementation is unavailable to source analysis. */
internal data class DiOperation(
    val kind: DiKind,
    val all: Boolean = false,
    val random: Boolean = false,
    val supertypeBindingFilter: String? = null,
    val definitionParameter: String? = null,
    val beanTypeParameter: Int? = null,
    val returnContainer: String? = null,
)

internal interface DiOperationAdapter {
    val names: Set<String>
    fun resolve(symbol: KaCallableSymbol): DiOperation?
}

/** Names are only a candidate filter. Adapters apply only to the resolved library symbol,
 * including symbols supplied by transitive dependencies. Source wrappers use the same roots. */
internal object DiOperations {
    private val adapters = listOf(KoinOperations, MicroUtilsOperations)
    val names: Set<String> = adapters.flatMapTo(linkedSetOf()) { it.names }
    fun resolve(symbol: KaCallableSymbol): DiOperation? = adapters.firstNotNullOfOrNull { it.resolve(symbol) }
}

internal object KoinOperations : DiOperationAdapter {
    private val definitions = setOf("single", "factory", "scoped", "singleOf", "factoryOf", "scopedOf")
    private val lookups = setOf("get", "getOrNull", "getAll", "inject", "injectOrNull")
    override val names = definitions + lookups + "koinInject"

    override fun resolve(symbol: KaCallableSymbol): DiOperation? {
        val id = symbol.callableId ?: return null
        val packageName = id.packageName.asString()
        val className = id.className?.asString()
        val name = id.callableName.asString()
        if (name in definitions && (
            packageName == "org.koin.core.module" && className == "Module" ||
                packageName == "org.koin.dsl" && className == "ScopeDSL" ||
                packageName == "org.koin.core.module.dsl"
            )) return DiOperation(DiKind.DEFINITION)
        if (name in lookups && (
            packageName == "org.koin.core" && className == "Koin" ||
                packageName == "org.koin.core.scope" && className == "Scope" ||
                packageName == "org.koin.core.component" ||
                packageName == "org.koin.android.ext.android" ||
                packageName == "org.koin.ktor.ext"
            )) return DiOperation(DiKind.LOOKUP, all = name == "getAll",
                returnContainer = when (name) { "getAll" -> "kotlin.collections.List"; "inject", "injectOrNull" -> "kotlin.Lazy"; else -> null },
            )
        if (name == "koinInject" && packageName == "org.koin.compose") return DiOperation(DiKind.LOOKUP)
        return null
    }
}
