package dev.inmo.idea.navigation.flow.diagram.cli

import com.intellij.openapi.util.Disposer
import dev.inmo.idea.navigation.flow.diagram.analysis.Diagnostic
import dev.inmo.idea.navigation.flow.diagram.analysis.GraphRenderer
import dev.inmo.idea.navigation.flow.diagram.analysis.KotlinSource
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationAnalyzer
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationGraph
import dev.inmo.idea.navigation.flow.diagram.analysis.SourceSelection
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.system.exitProcess

private const val HELP = """Usage: navigation-flow-diagram <project-directory> [options]

Analyze Kotlin sources without launching IntelliJ IDEA.

Options:
  --format mermaid|dot|json  Output format (default: mermaid).
  --output <path>           Write UTF-8 output to a file (default: stdout).
  --include-tests          Include test directories and Kotlin test source sets.
  -h, --help               Show this help.

Hidden directories, build, out, node_modules, vendor, and nested symbolic links are skipped.
Back operations and unresolved endpoints are recorded without invented destinations.
"""

fun main(args: Array<String>) {
    val status = runCli(args, PrintStream(System.out, true, UTF_8), PrintStream(System.err, true, UTF_8))
    if (status != 0) exitProcess(status)
}

internal fun runCli(args: Array<String>, stdout: PrintStream, stderr: PrintStream): Int {
    if (args.any { it == "--help" || it == "-h" }) {
        stdout.print(HELP)
        return 0
    }
    val options = try {
        parseOptions(args)
    } catch (exception: IllegalArgumentException) {
        stderr.println("navigation-flow-diagram: ${exception.message}")
        stderr.println("Run navigation-flow-diagram --help for usage.")
        return 2
    }
    return try {
        if (!Files.isDirectory(options.project)) {
            stderr.println("navigation-flow-diagram: Project directory does not exist or is not a directory: ${options.project}")
            return 2
        }
        val project = options.project.toRealPath()
        val files = discoverKotlinFiles(project, options.includeTests)
        val graph = analyze(project, files)
        graph.diagnostics.forEach { diagnostic ->
            val location = diagnostic.location?.let { "${it.path}:${it.line}:${it.column}: " }.orEmpty()
            stderr.println("warning: $location${diagnostic.message}")
        }
        val result = when (options.format) {
            "dot" -> GraphRenderer.dot(graph)
            "json" -> GraphRenderer.json(graph)
            else -> GraphRenderer.mermaid(graph)
        }
        if (options.output == null) {
            stdout.print(result)
            if (stdout.checkError()) throw IOException("Cannot write output to stdout.")
        } else {
            options.output.parent?.let(Files::createDirectories)
            Files.writeString(options.output, result, UTF_8)
        }
        0
    } catch (exception: IOException) {
        stderr.println("navigation-flow-diagram: ${exception.message ?: "I/O error"}")
        1
    } catch (exception: SecurityException) {
        stderr.println("navigation-flow-diagram: Access denied: ${exception.message}")
        1
    }
}

private data class Options(val project: Path, val format: String, val output: Path?, val includeTests: Boolean)

private fun parseOptions(args: Array<String>): Options {
    var project: String? = null
    var format: String? = null
    var output: String? = null
    var includeTests = false
    var positionalOnly = false
    var index = 0
    while (index < args.size) {
        val argument = args[index++]
        fun value(): String {
            require(index < args.size && !args[index].startsWith("--")) { "Missing value for $argument." }
            return args[index++]
        }
        when {
            positionalOnly -> {
                require(project == null) { "Only one project directory can be analyzed at a time." }
                project = argument
            }
            argument == "--" -> positionalOnly = true
            argument == "--format" -> {
                require(format == null) { "--format can only be specified once." }
                format = value()
                require(format in setOf("mermaid", "dot", "json")) { "Unsupported format '$format'. Expected mermaid, dot, or json." }
            }
            argument == "--output" -> {
                require(output == null) { "--output can only be specified once." }
                output = value()
                require(output.isNotBlank()) { "Output path cannot be empty." }
            }
            argument == "--include-tests" -> includeTests = true
            argument.startsWith("-") -> throw IllegalArgumentException("Unknown option '$argument'.")
            else -> {
                require(project == null) { "Only one project directory can be analyzed at a time." }
                project = argument
            }
        }
    }
    require(!project.isNullOrBlank()) { "A project directory is required." }
    try {
        return Options(Path.of(project).toAbsolutePath().normalize(), format ?: "mermaid", output?.let { Path.of(it).toAbsolutePath().normalize() }, includeTests)
    } catch (exception: InvalidPathException) {
        throw IllegalArgumentException("Invalid path: ${exception.input}", exception)
    }
}

/** Only source files are read. Directory links are never followed, so cycles cannot recur. */
internal fun discoverKotlinFiles(project: Path, includeTests: Boolean): List<Path> {
    val files = mutableListOf<Path>()
    Files.walkFileTree(project, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (directory == project) return FileVisitResult.CONTINUE
            val parentName = directory.parent?.takeUnless { it == project }?.fileName?.toString()
            return if (SourceSelection.excludedDirectory(directory.fileName.toString(), parentName, includeTests)) {
                FileVisitResult.SKIP_SUBTREE
            } else FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            val relativePath = project.relativize(file).toString().replace('\\', '/')
            if (attributes.isRegularFile && SourceSelection.includes(relativePath, includeTests)) files.add(file)
            return FileVisitResult.CONTINUE
        }
    })
    return files.sortedBy { project.relativize(it).toString().replace('\\', '/') }
}

@OptIn(CompilerConfiguration.Internals::class, org.jetbrains.kotlin.K1Deprecation::class)
private fun analyze(project: Path, files: List<Path>): NavigationGraph {
    if (files.isEmpty()) return NavigationGraph(diagnostics = listOf(Diagnostic(null, "No Kotlin source files found in $project.")))
    val disposable = Disposer.newDisposable("navigation-flow-diagram")
    try {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "navigation-flow-diagram")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        val environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory = KtPsiFactory(environment.project, false)
        val sources = files.map { path ->
            val relativePath = project.relativize(path).toString().replace('\\', '/')
            KotlinSource(relativePath, factory.createFile(path.fileName.toString(), Files.readString(path, UTF_8)))
        }
        return NavigationAnalyzer().analyze(sources)
    } finally {
        Disposer.dispose(disposable)
    }
}
