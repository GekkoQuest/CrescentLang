package dev.twelveoclock.lang.crescent.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CliArgumentsTest {

	@Test
	fun `empty arguments and help flags show help`() {
		assertEquals(CliRequest.Help, CliArguments.parse(emptyList()))
		assertEquals(CliRequest.Help, CliArguments.parse(listOf("--help")))
	}

	@Test
	fun `source path without a command is an implicit run`() {
		val request = assertIs<CliRequest.Execute>(CliArguments.parse(listOf("hello.moo")))
		assertEquals(CrescentCommand.RUN, request.command)
		assertEquals("hello.moo", request.input.toString())
	}

	@Test
	fun `program arguments are forwarded only after separator`() {
		val request = assertIs<CliRequest.Execute>(
			CliArguments.parse(listOf("ir", "project", "--", "one", "--flag", "--")),
		)
		assertEquals(CrescentCommand.IR, request.command)
		assertEquals(listOf("one", "--flag", "--"), request.programArgs)
	}

	@Test
	fun `extra input tokens are rejected`() {
		val error = assertFailsWith<CliException> {
			CliArguments.parse(listOf("run", "one.moo", "two.moo"))
		}
		assertEquals("Expected one source file or project directory, but received 2", error.message)
	}

	@Test
	fun `translation commands reject argument separators`() {
		assertFailsWith<CliException> {
			CliArguments.parse(listOf("ptir", "hello.moo", "--", "unused"))
		}
		assertFailsWith<CliException> {
			CliArguments.parse(listOf("kotlin-to-crescent", "Hello.kt", "--"))
		}
	}

	@Test
	fun `invalid paths are reported as CLI errors`() {
		val error = assertFailsWith<CliException> { CliArguments.parse(listOf("bad\u0000path.moo")) }
		assertEquals("Invalid input path: bad\u0000path.moo", error.message)
	}
}
