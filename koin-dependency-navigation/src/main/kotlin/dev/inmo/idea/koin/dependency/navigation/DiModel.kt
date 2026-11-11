package dev.inmo.idea.koin.dependency.navigation

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
