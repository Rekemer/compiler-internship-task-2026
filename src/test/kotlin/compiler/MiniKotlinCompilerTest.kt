package org.example.compiler

import MiniKotlinLexer
import MiniKotlinParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

class MiniKotlinCompilerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun parseString(source: String): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromString(source)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun parseFile(path: Path): MiniKotlinParser.ProgramContext {
        val input = CharStreams.fromPath(path)
        val lexer = MiniKotlinLexer(input)
        val tokens = CommonTokenStream(lexer)
        val parser = MiniKotlinParser(tokens)
        return parser.program()
    }

    private fun resolveStdlibPath(): Path? {
        val devPath = Paths.get("build", "stdlib")
        if (devPath.toFile().exists()) {
            val stdlibJar = devPath.toFile().listFiles()
                ?.firstOrNull { it.name.startsWith("stdlib") && it.name.endsWith(".jar") }
            if (stdlibJar != null) return stdlibJar.toPath()
        }
        return null
    }

    @Test
    fun `compile example_mini outputs 120 and 15`() {
        val examplePath = Paths.get("samples/test.mini")
        val program = parseFile(examplePath)

        val compiler = MiniKotlinCompiler()
        val javaCode = compiler.compile(program)

        val javaFile = tempDir.resolve("MiniProgram.java")
        Files.writeString(javaFile, javaCode)

        val javaCompiler = JavaRuntimeCompiler()
        val stdlibPath = resolveStdlibPath()
        val (compilationResult, executionResult) = javaCompiler.compileAndExecute(javaFile, stdlibPath)

        assertIs<CompilationResult.Success>(compilationResult)
        assertIs<ExecutionResult.Success>(executionResult)

        val output = executionResult.stdout
        assertTrue(output.contains("120"), "Expected output to contain factorial result 120, but got: $output")
        assertTrue(output.contains("15"), "Expected output to contain arithmetic result 15, but got: $output")
    }



    private fun extractExpectedOutput(source: String): String? {
        // Supports:
        // 1) // EXPECT: one line
        // 2) // EXPECT:\n// line1\n// line2\n
        val lines = source.lines()
        val idx = lines.indexOfFirst { it.trim().startsWith("// EXPECT") }
        if (idx == -1) return null

        val first = lines[idx].trim()

        if (first == "// EXPECT:" || first == "// EXPECT") {
            val out = StringBuilder()
            var j = idx + 1
            while (j < lines.size) {
                val t = lines[j].trim()
                if (!t.startsWith("//")) break
                out.append(t.removePrefix("//").trimStart()).append("\n")
                j++
            }
            return out.toString()
        }

        return null
    }

    @Test
    fun `run test folder`() {
        val dir = Paths.get("src/test", "srcTests")
        assertTrue(dir.toFile().exists(), "Missing folder: $dir")

        val files = Files.walk(dir)
            .filter { it.isRegularFile() && it.extension == "mini" }
            .toList()
            .sortedBy { it.name }

        assertTrue(files.isNotEmpty(), "No .mini files found in $dir")

        val stdlibPath = resolveStdlibPath()
        val javaCompiler = JavaRuntimeCompiler()

        for (mini in files) {
            val src = mini.readText()
            val expected = extractExpectedOutput(src)
                ?: error("No EXPECT comment found in ${mini.fileName}")

            val program = parseFile(mini)
            val compiler = MiniKotlinCompiler()
            val javaCode = compiler.compile(program)

            val javaFile = tempDir.resolve("MiniProgram.java")
            Files.writeString(javaFile, javaCode)

            val (compilationResult, executionResult) =
                javaCompiler.compileAndExecute(javaFile, stdlibPath)

            assertIs<CompilationResult.Success>(compilationResult, "Compilation failed for ${mini.fileName}")
            assertIs<ExecutionResult.Success>(executionResult, "Execution failed for ${mini.fileName}")

            val actual = executionResult.stdout

            val normActual = actual.replace("\r\n", "\n")
            val normExpected = expected.replace("\r\n", "\n")
            assertTrue(
                normActual.contains(normExpected),
                "Mismatch for ${mini.fileName}\nExpected to contain:\n$normExpected\nBut got:\n$normActual"
            )
            println("${mini.fileName} is completed")
        }
    }
}
