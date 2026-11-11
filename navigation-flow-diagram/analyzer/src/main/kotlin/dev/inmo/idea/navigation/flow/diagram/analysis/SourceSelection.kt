package dev.inmo.idea.navigation.flow.diagram.analysis

/** Shared source filtering for the standalone scanner and IDE content roots. */
object SourceSelection {
    private val excludedDirectories = setOf("build", "out", "node_modules", "vendor")
    private val testDirectories = setOf("test", "tests", "testdata", "testfixtures")

    fun excludedDirectory(name: String, parentName: String?, includeTests: Boolean = false): Boolean {
        val lowerName = name.lowercase()
        return name.startsWith('.') || lowerName in excludedDirectories || (!includeTests && (
            lowerName in testDirectories || (parentName == "src" && lowerName.endsWith("test"))
        ))
    }

    /** [relativePath] is a normalized slash-separated path relative to the project/content root. */
    fun includes(relativePath: String, includeTests: Boolean = false): Boolean {
        val parts = relativePath.split('/')
        val fileName = parts.last()
        if (fileName.startsWith('.') || !(fileName.endsWith(".kt") || fileName.endsWith(".kts"))) return false
        return (0 until parts.lastIndex).none { index ->
            excludedDirectory(parts[index], parts.getOrNull(index - 1), includeTests)
        }
    }
}
