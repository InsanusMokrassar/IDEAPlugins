package dev.inmo.idea.navigation.flow.diagram.analysis

/** Text exports share stable ordering; unknown endpoints remain comments, never invented nodes. */
object GraphRenderer {
    fun mermaid(graph: NavigationGraph): String = buildString {
        val ordered = OrderedGraph(graph)
        appendLine("flowchart LR")
        appendLine("    %% Solid arrows: navigation; dashed arrows: child containment.")
        ordered.nodeIds.forEach { (name, id) ->
            val labels = listOf(name) + ordered.views.filter { it.configName == name }.map { it.viewName }.distinct()
            appendLine("    $id[\"${labels.joinToString("<br/>", transform = ::mermaidText)}\"]")
        }
        ordered.declarationComments().forEach { appendLine("    %% ${commentText(it)}") }
        ordered.transitions.forEach { transition ->
            val source = ordered.nodeIds[transition.sourceConfig]
            val target = ordered.nodeIds[transition.targetConfig]
            if (source == null || target == null || transition.kind == TransitionKind.BACK) {
                appendLine("    %% ${commentText(transition.description())}")
            } else {
                val arrow = if (transition.kind == TransitionKind.CONTAINMENT) "-.->" else "-->"
                val label = mermaidText("${transition.operation} @ ${transition.location.display()}")
                appendLine("    $source $arrow|\"$label\"| $target")
            }
        }
        ordered.diagnostics.forEach { appendLine("    %% ${commentText(it.description())}") }
    }

    fun dot(graph: NavigationGraph): String = buildString {
        val ordered = OrderedGraph(graph)
        appendLine("digraph Navigation {")
        appendLine("    rankdir=LR;")
        appendLine("    node [shape=box];")
        appendLine("    // Solid arrows: navigation; dashed arrows: child containment.")
        ordered.nodeIds.forEach { (name, id) ->
            val labels = listOf(name) + ordered.views.filter { it.configName == name }.map { it.viewName }.distinct()
            appendLine("    $id [label=${dotString(labels.joinToString("\n"))}];")
        }
        ordered.declarationComments().forEach { appendLine("    // ${commentText(it)}") }
        ordered.transitions.forEach { transition ->
            val source = ordered.nodeIds[transition.sourceConfig]
            val target = ordered.nodeIds[transition.targetConfig]
            if (source == null || target == null || transition.kind == TransitionKind.BACK) {
                appendLine("    // ${commentText(transition.description())}")
            } else {
                val style = if (transition.kind == TransitionKind.CONTAINMENT) "dashed" else "solid"
                val label = dotString("${transition.operation} @ ${transition.location.display()}")
                appendLine("    $source -> $target [style=$style, label=$label];")
            }
        }
        ordered.diagnostics.forEach { appendLine("    // ${commentText(it.description())}") }
        appendLine("}")
    }

    /** Every declaration, registration, call site and diagnostic is retained in JSON. */
    fun json(graph: NavigationGraph): String {
        val ordered = OrderedGraph(graph)
        return jsonObject(
            "configurations" to jsonArray(ordered.configurations.map {
                jsonObject("name" to jsonString(it.name), "declaration" to it.declaration.json())
            }),
            "views" to jsonArray(ordered.views.map {
                jsonObject(
                    "viewName" to jsonString(it.viewName),
                    "configName" to jsonString(it.configName),
                    "declaration" to it.declaration.json(),
                    "registration" to (it.registration?.json() ?: "null"),
                )
            }),
            "transitions" to jsonArray(ordered.transitions.map {
                jsonObject(
                    "sourceConfig" to jsonString(it.sourceConfig),
                    "targetConfig" to jsonString(it.targetConfig),
                    "operation" to jsonString(it.operation),
                    "kind" to jsonString(it.kind.name),
                    "location" to it.location.json(),
                )
            }),
            "diagnostics" to jsonArray(ordered.diagnostics.map {
                jsonObject("location" to (it.location?.json() ?: "null"), "message" to jsonString(it.message))
            }),
        ) + "\n"
    }

    private val locationOrder = compareBy<SourceLocation>({ it.path }, { it.offset }, { it.line }, { it.column })

    private class OrderedGraph(graph: NavigationGraph) {
        val configurations = graph.configurations.sortedWith(
            compareBy<Configuration> { it.name }.thenBy(locationOrder) { it.declaration },
        )
        val views = graph.views.sortedWith(
            compareBy<ViewBinding>({ it.configName }, { it.viewName })
                .thenBy(locationOrder) { it.declaration }
                .thenBy(nullsFirst(locationOrder)) { it.registration },
        )
        val transitions = graph.transitions.sortedWith(
            compareBy(locationOrder, Transition::location)
                .thenBy { it.kind.name }.thenBy { it.operation }
                .thenBy { it.sourceConfig }.thenBy { it.targetConfig },
        )
        val diagnostics = graph.diagnostics.sortedWith(
            compareBy(nullsFirst(locationOrder), Diagnostic::location).thenBy { it.message },
        )
        val nodeIds = buildSet {
            configurations.forEach { add(it.name) }
            views.forEach { add(it.configName) }
            transitions.forEach {
                it.sourceConfig?.let(::add)
                if (it.kind != TransitionKind.BACK) it.targetConfig?.let(::add)
            }
        }.sorted().mapIndexed { index, name -> name to "n$index" }.toMap()

        fun declarationComments(): List<String> = buildList {
            configurations.forEach { add("configuration ${it.name} @ ${it.declaration.display()}") }
            views.forEach {
                add("view ${it.viewName} for ${it.configName} @ ${it.declaration.display()}")
                it.registration?.let { location ->
                    add("registration ${it.viewName} for ${it.configName} @ ${location.display()}")
                }
            }
        }
    }

    private fun SourceLocation.display(): String = "$path:$line:$column (offset $offset)"

    private fun Transition.description(): String {
        val destination = if (kind == TransitionKind.BACK) "runtime back destination" else targetConfig ?: "unresolved destination"
        return "$kind: ${sourceConfig ?: "unresolved source"} -> $destination via $operation @ ${location.display()}"
    }

    private fun Diagnostic.description(): String = "diagnostic: ${location?.let { "${it.display()}: " }.orEmpty()}$message"

    private fun SourceLocation.json(): String = jsonObject(
        "path" to jsonString(path), "offset" to offset.toString(), "line" to line.toString(), "column" to column.toString(),
    )

    private fun jsonObject(vararg properties: Pair<String, String>): String =
        properties.joinToString(",", "{", "}") { (name, value) -> "${jsonString(name)}:$value" }

    private fun jsonArray(values: List<String>): String = values.joinToString(",", "[", "]")

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            value.forEach { char ->
                append(when (char) {
                    '"' -> "\\\""
                    '\\' -> "\\\\"
                    '\b' -> "\\b"
                    '\u000C' -> "\\f"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> if (char < ' ' || char == '\u2028' || char == '\u2029') char.unicodeEscape() else char.toString()
                })
            }
            append('"')
        }
    }

    private fun dotString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            append(when (char) {
                '"' -> "\\\""
                '\\' -> "\\\\"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> " "
                else -> if (char < ' ' || char == '\u2028' || char == '\u2029') "\\${char.unicodeEscape()}" else char.toString()
            })
        }
        append('"')
    }

    private fun mermaidText(value: String): String = buildString {
        value.forEach { char ->
            append(when {
                char == '\n' -> "<br/>"
                char < ' ' || char == '\u2028' || char == '\u2029' || char in "\"#<>&|\\`%" -> "#${char.code};"
                else -> char.toString()
            })
        }
    }

    private fun commentText(value: String): String = buildString {
        value.forEach { char ->
            append(if (char < ' ' || char == '%' || char == '\u2028' || char == '\u2029') char.unicodeEscape() else char)
        }
    }

    private fun Char.unicodeEscape(): String = "\\u${code.toString(16).padStart(4, '0')}"
}
