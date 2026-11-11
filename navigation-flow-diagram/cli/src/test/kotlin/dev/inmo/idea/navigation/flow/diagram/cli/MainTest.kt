package dev.inmo.idea.navigation.flow.diagram.cli

import dev.inmo.idea.navigation.flow.diagram.analysis.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

class MainTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun `discovery sorts Kotlin files and excludes generated hidden and test directories`() {
        val root = temporary.newFolder("project").toPath()
        val production = listOf("z.kt", "a.kts", "app/src/commonMain/kotlin/App.kt")
        val excluded = listOf(
            ".secrets/Private.kt", "build/Generated.kt", "out/Output.kt", "node_modules/Dependency.kt", "vendor/Library.kt",
            "app/src/commonTest/Test.kt", "app/src/androidInstrumentedTest/Test.kt", "test/RootTest.kt", "testData/Example.kts",
        )
        (production + excluded + "notes.txt").forEach { write(root, it) }
        assertEquals(production.sorted(), discoverKotlinFiles(root, false).map { root.relativize(it).toString().replace('\\', '/') })
        assertEquals(
            (production + excluded.filter { it.contains("Test/") || it.startsWith("test") }).sorted(),
            discoverKotlinFiles(root, true).map { root.relativize(it).toString().replace('\\', '/') },
        )
    }

    @Test
    fun `discovery skips symlink files and directory cycles`() {
        val root = temporary.newFolder("project").toPath()
        val outside = temporary.newFile("Outside.kt").toPath()
        write(root, "Inside.kt")
        try {
            Files.createSymbolicLink(root.resolve("cycle"), root)
            Files.createSymbolicLink(root.resolve("Linked.kt"), outside)
        } catch (exception: UnsupportedOperationException) {
            assumeNoException(exception)
        } catch (exception: IOException) {
            assumeNoException(exception)
        }
        assertEquals(listOf(root.resolve("Inside.kt")), discoverKotlinFiles(root, true))
    }

    @Test
    fun `project root names and production package names never imply test sources`() {
        val root = temporary.newFolder("test", "NavigationTest").toPath()
        val names = listOf("Main.kt", "src/main/kotlin/FooTest/Screen.kt", "FooTest/Config.kt")
        names.forEach { write(root, it) }
        write(root, "src/commonTest/OnlyInTests.kt")
        assertEquals(names.sorted(), discoverKotlinFiles(root, false).map { root.relativize(it).toString().replace('\\', '/') })
        names.forEach { assertTrue(it, SourceSelection.includes(it)) }
        assertFalse(SourceSelection.includes("src/commonTest/OnlyInTests.kt"))
    }

    @Test
    fun `shared filter excludes hidden vendor and test fixtures consistently`() {
        val root = temporary.newFolder("project").toPath()
        val alwaysExcluded = listOf(".hidden/Config.kt", "src/main/.Secret.kt", "vendor/Dependency.kt", "Build/Generated.kts")
        val onlyTests = listOf("testfixtures/Fixture.kt", "module/src/testFixtures/Fixture.kt", "module/src/nativeTest/Test.kt")
        (alwaysExcluded + onlyTests + "src/main/App.kt").forEach { write(root, it) }
        assertEquals(listOf(root.resolve("src/main/App.kt")), discoverKotlinFiles(root, false))
        assertEquals((onlyTests + "src/main/App.kt").sorted(), discoverKotlinFiles(root, true).map { root.relativize(it).toString().replace('\\', '/') })
        alwaysExcluded.forEach {
            assertFalse(it, SourceSelection.includes(it))
            assertFalse(it, SourceSelection.includes(it, includeTests = true))
        }
        onlyTests.forEach {
            assertFalse(it, SourceSelection.includes(it))
            assertTrue(it, SourceSelection.includes(it, includeTests = true))
        }
        assertFalse(SourceSelection.includes("settings.properties"))
    }

    @Test
    fun `help succeeds without a project`() {
        val result = invoke("--help")
        assertEquals(0, result.status)
        assertTrue(result.stdout.startsWith("Usage: navigation-flow-diagram"))
        assertEquals("", result.stderr)
    }

    @Test
    fun `invalid arguments fail with actionable errors`() {
        listOf(
            emptyArray(), arrayOf("--unknown"), arrayOf("--format"), arrayOf(".", "--format", "svg"),
            arrayOf(".", "--output"), arrayOf(".", "another-project"), arrayOf(".", "--format", "dot", "--format", "json"),
        ).forEach { arguments ->
            val result = invoke(*arguments)
            assertEquals(arguments.joinToString(" "), 2, result.status)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("Run navigation-flow-diagram --help for usage."))
        }
    }

    @Test
    fun `missing or nondirectory project fails before analysis`() {
        listOf(temporary.root.resolve("missing"), temporary.newFile("not-a-directory")).forEach { path ->
            val result = invoke(path.toString())
            assertEquals(2, result.status)
            assertTrue(result.stderr.contains("not a directory"))
        }
    }

    @Test
    fun `empty project emits graph and warning with success status`() {
        val result = invoke(temporary.newFolder("empty").absolutePath, "--format", "json")
        assertEquals(0, result.status)
        assertTrue(result.stdout.contains("\"configurations\":[]"))
        assertTrue(result.stdout.contains("No Kotlin source files found"))
        assertTrue(result.stderr.startsWith("warning: No Kotlin source files found"))
    }

    @Test
    fun `standalone PSI parsing runs without an IDE project`() {
        val root = temporary.newFolder("project").toPath()
        write(root, "src/main/kotlin/Sample.kt", "package sample\nfun greeting() = \"Привет\"\n")
        val result = invoke(root.toString(), "--format", "json")
        assertEquals(result.stderr, 0, result.status)
        assertTrue(result.stdout.contains("\"configurations\":[]"))
    }

    @Test
    fun `output creates parent folders and keeps stdout empty`() {
        val root = temporary.newFolder("project").toPath()
        val output = temporary.root.toPath().resolve("exports/nested/diagram.dot")
        val result = invoke(root.toString(), "--format", "dot", "--output", output.toString())
        assertEquals(0, result.status)
        assertEquals("", result.stdout)
        assertTrue(Files.readString(output, UTF_8).startsWith("digraph Navigation"))
    }

    @Test
    fun `output I O failure returns a nonzero status`() {
        val root = temporary.newFolder("project").toPath()
        val output = temporary.newFolder("output").toPath()
        val result = invoke(root.toString(), "--output", output.toString())
        assertEquals(1, result.status)
        assertTrue(result.stderr.contains("navigation-flow-diagram:"))
        assertFalse(result.stderr.contains("Exception in thread"))
    }

    private fun write(root: Path, name: String, contents: String = "// source\n") {
        val file = root.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, contents, UTF_8)
    }

    private data class Result(val status: Int, val stdout: String, val stderr: String)

    private fun invoke(vararg arguments: String): Result {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val status = runCli(arrayOf(*arguments), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8))
        return Result(status, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }
}
