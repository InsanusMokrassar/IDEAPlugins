package dev.inmo.idea.navigation.flow.diagram.analysis

/** Offsets are zero based; lines and columns are one based. Paths identify source files. */
data class SourceLocation(val path: String, val offset: Int, val line: Int, val column: Int)

data class Configuration(val name: String, val declaration: SourceLocation)

/** A configuration can have several platform-specific view implementations. */
data class ViewBinding(
    val viewName: String,
    val configName: String,
    val declaration: SourceLocation,
    val registration: SourceLocation? = null,
)

enum class TransitionKind { NAVIGATION, CONTAINMENT, BACK }

/** A null source/target means static analysis could not determine that endpoint. */
data class Transition(
    val sourceConfig: String?,
    val targetConfig: String?,
    val operation: String,
    val kind: TransitionKind,
    val location: SourceLocation,
)

data class Diagnostic(val location: SourceLocation?, val message: String)

data class NavigationGraph(
    val configurations: List<Configuration> = emptyList(),
    val views: List<ViewBinding> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    fun allocationsFor(configName: String): List<Transition> =
        transitions.filter { it.targetConfig == configName && it.kind != TransitionKind.BACK }
}
