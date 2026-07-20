package dev.twelveoclock.lang.crescent.cli

import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrescentCliTest {
	@TempDir
	lateinit var tempDirectory: Path

	@Test
	fun `help is successful and printed to stdout`() {
		val output = ByteArrayOutputStream()
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(
			arrayOf("--help"),
			PrintStream(output),
			PrintStream(errors),
		)

		assertEquals(0, exitCode)
		assertTrue(output.toString().contains("crescent <file-or-directory>"))
		assertEquals("", errors.toString())
	}

	@Test
	fun `usage failures are concise and return status two`() {
		val output = ByteArrayOutputStream()
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(
			arrayOf("run"),
			PrintStream(output),
			PrintStream(errors),
		)

		assertEquals(2, exitCode)
		assertEquals("", output.toString())
		assertTrue(errors.toString().startsWith("error: A source file or project directory is required"))
	}

	@Test
	fun `direct and high level IR commands execute a minimal project`() {
		val source = tempDirectory.resolve("main.moo")
		source.writeText("fun main {}")

		assertEquals(0, CrescentCli.run(arrayOf(source.toString())))
		assertEquals(0, CrescentCli.run(arrayOf("run", source.toString())))
		assertEquals(0, CrescentCli.run(arrayOf("ir", source.toString())))
	}

	@Test
	fun `ptir translates a linked library without requiring main`() {
		val source = tempDirectory.resolve("library.moo")
		source.writeText("public fun helper {}")
		val output = ByteArrayOutputStream()
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(
			arrayOf("ptir", source.toString()),
			out = PrintStream(output),
			err = PrintStream(errors),
		)

		assertEquals(0, exitCode)
		assertTrue(output.toString().contains("name=helper"), output.toString())
		assertEquals("", errors.toString())
	}

	@Test
	fun `invalid source is a program failure without a usage hint`() {
		val source = tempDirectory.resolve("broken.moo")
		source.writeText("fun 123 {}")
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(arrayOf(source.toString()), err = PrintStream(errors))

		assertEquals(1, exitCode)
		assertTrue(errors.toString().startsWith("error: Could not parse"))
		assertTrue("--help" !in errors.toString())
	}

	@Test
	fun `missing Kotlin source is an input failure without a usage hint`() {
		val errors = ByteArrayOutputStream()
		val missing = tempDirectory.resolve("missing.kt")

		val exitCode = CrescentCli.run(
			arrayOf("kotlin-to-crescent", missing.toString()),
			err = PrintStream(errors),
		)

		assertEquals(1, exitCode)
		assertTrue(errors.toString().startsWith("error: Kotlin source is not a file:"))
		assertTrue("--help" !in errors.toString())
	}

	@Test
	fun `wrong Kotlin extension is an input failure without a usage hint`() {
		val source = tempDirectory.resolve("source.txt")
		source.writeText("fun main() = Unit")
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(
			arrayOf("kotlin-to-crescent", source.toString()),
			err = PrintStream(errors),
		)

		assertEquals(1, exitCode)
		assertTrue(errors.toString().startsWith("error: Kotlin source must use the .kt extension:"))
		assertTrue("--help" !in errors.toString())
	}

	@Test
	fun `unexpected interpreter failures stay inside the CLI boundary`() {
		val source = tempDirectory.resolve("args.moo")
		source.writeText("fun main(args: [String]) { println(args[0]) }")
		val errors = ByteArrayOutputStream()

		val exitCode = CrescentCli.run(arrayOf(source.toString()), err = PrintStream(errors))

		assertEquals(1, exitCode)
		assertTrue(errors.toString().startsWith("error:"))
		assertTrue("--help" !in errors.toString())
	}

}
