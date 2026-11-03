package dev.inmo.dinavigation

/** Values detached from an Analysis API session; safe to cache with PSI modification tracking. */
internal enum class DiKind { DEFINITION, LOOKUP }

internal sealed interface DiQualifier {
    data object None : DiQualifier
    data object Any : DiQualifier
    data class Random(val definitionIdentity: String) : DiQualifier
    data class Named(val value: String) : DiQualifier
    data class Dynamic(val identity: String) : DiQualifier
}

internal data class DiCall(
    val kind: DiKind,
    val types: Set<String>,
    val qualifier: DiQualifier,
    val displayType: String,
) {
    fun matches(other: DiCall): Boolean {
        if (kind == other.kind || types.intersect(other.types).isEmpty()) return false
        val lookup = if (kind == DiKind.LOOKUP) this else other
        val definition = if (kind == DiKind.DEFINITION) this else other
        return lookup.qualifier == DiQualifier.Any || definition.qualifier == lookup.qualifier
    }
}

internal data class DiOperation(
    val kind: DiKind,
    val all: Boolean = false,
    val random: Boolean = false,
    val autoBinds: Boolean = false,
    val returnContainer: String? = null,
)

/** Symbol ownership is checked after this inexpensive name filter. */
internal object DiOperations {
    private val definitions = setOf("single", "factory", "scoped", "singleOf", "factoryOf", "scopedOf")
    private val microDefinitions = setOf(
        "single", "factory", "singleWithBinds", "factoryWithBinds",
        "singleWithRandomQualifier", "factoryWithRandomQualifier",
        "singleWithRandomQualifierAndBinds", "factoryWithRandomQualifierAndBinds",
        "singleSuspend", "factorySuspend",
    )
    private val lookups = setOf("get", "getOrNull", "getAll", "inject", "injectOrNull")
    private val microLookups = setOf("get", "getAllDistinct", "getAny", "lazyInject")
    val names: Set<String> = definitions + microDefinitions + lookups + microLookups + "koinInject"

    fun resolve(packageName: String, className: String?, name: String): DiOperation? {
        if (packageName == "dev.inmo.micro_utils.koin") {
            if (name in microDefinitions) return DiOperation(
                DiKind.DEFINITION, random = "RandomQualifier" in name, autoBinds = "Binds" in name,
            )
            if (name in microLookups) return DiOperation(DiKind.LOOKUP,
                all = name in setOf("getAllDistinct", "getAny"),
                returnContainer = when (name) { "getAllDistinct" -> "kotlin.collections.List"; "lazyInject" -> "kotlin.Lazy"; else -> null },
            )
        }
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
