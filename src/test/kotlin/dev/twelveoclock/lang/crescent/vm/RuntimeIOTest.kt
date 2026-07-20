package dev.twelveoclock.lang.crescent.vm

import dev.twelveoclock.lang.crescent.cli.CrescentCli
import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentIRParser
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeIOTest {

	@TempDir
	lateinit var temporaryDirectory: Path

	@Test
	fun `direct and lowered runtimes use injected UTF-8 input and output`() {
		val file = parse(
			"injected-io.moo",
			"fun main { val value = readLine(\"prompt λ\"); print(\"echo=\"); println(value) }",
		)

		val direct = TestStreams("猫\n")
		CrescentVM(listOf(file), file, direct.io).use { it.invoke() }
		assertEquals("prompt λ\necho=猫\n", direct.text())

		val lowered = TestStreams("猫\n")
		CrescentIRVM(CrescentIRCompiler.invoke(file), lowered.io).use { it.invoke() }
		assertEquals("prompt λ\necho=猫\n", lowered.text())
	}

	@Test
	fun `legacy runtime uses injected input and output`() {
		val program = CrescentIRParser.invoke("fun main\ninvoke readLine\ninvoke println")
		val streams = TestStreams("legacy\n")

		CrescentIRVM(program, streams.io).use { it.invoke() }

		assertEquals("legacy\n", streams.text())
	}

	@Test
	fun `CLI run and IR commands isolate program input and output`() {
		val source = temporaryDirectory.resolve("main.moo")
		source.writeText("fun main { println(readLine(\"prompt\")) }")

		for (arguments in listOf(arrayOf(source.toString()), arrayOf("ir", source.toString()))) {
			val streams = TestStreams("answer\n")
			val errors = ByteArrayOutputStream()
			val exitCode = CrescentCli.run(
				arguments,
				out = streams.output,
				err = PrintStream(errors, true, StandardCharsets.UTF_8),
				input = streams.input,
			)

			assertEquals(0, exitCode)
			assertEquals("prompt\nanswer\n", streams.text())
			assertEquals("", errors.toString(StandardCharsets.UTF_8))
		}
	}

	@Test
	fun `EOF diagnostics remain explicit in direct lowered and legacy runtimes`() {
		val file = parse("eof.moo", "fun main { println(readLine(\"prompt\")) }")
		val direct = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(file), file, TestStreams("").io).use { it.invoke() }
		}
		assertTrue(direct.message.orEmpty().contains("readLine reached end of input"))

		val lowered = assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIRCompiler.invoke(file), TestStreams("").io).use { it.invoke() }
		}
		assertTrue(lowered.message.orEmpty().contains("readLine reached end of input"))

		val legacyProgram = CrescentIRParser.invoke("fun main\ninvoke readLine")
		val legacy = assertFailsWith<CrescentIRExecutionException> {
			CrescentIRVM(legacyProgram, TestStreams("").io).use { it.invoke() }
		}
		assertTrue(legacy.message.orEmpty().contains("readLine reached end of input"))
	}

	@Test
	fun `blocked input does not block output from another runtime thread`() {
		val readStarted = CountDownLatch(1)
		val releaseRead = CountDownLatch(1)
		val blockingInput = object : InputStream() {
			override fun read(): Int {
				readStarted.countDown()
				check(releaseRead.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release input" }
				return -1
			}
		}
		val bytes = ByteArrayOutputStream()
		val io = RuntimeIO.streams(
			blockingInput,
			PrintStream(bytes, true, StandardCharsets.UTF_8),
		)
		val reader = Thread.startVirtualThread { io.readLine() }
		try {
			assertTrue(readStarted.await(5, TimeUnit.SECONDS))
			val outputFinished = CountDownLatch(1)
			Thread.startVirtualThread {
				io.println("available")
				outputFinished.countDown()
			}
			assertTrue(outputFinished.await(2, TimeUnit.SECONDS), "Output was blocked behind readLine")
			assertEquals("available\n", bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"))
		} finally {
			releaseRead.countDown()
			reader.join(5_000)
		}
	}

	@Test
	fun `closing direct and IR runtimes leaves caller streams open`() {
		val file = parse("ownership.moo", "fun main {}")
		val executions = listOf<(RuntimeIO) -> Unit>(
			{ io -> CrescentVM(listOf(file), file, io).use { it.invoke() } },
			{ io -> CrescentIRVM(CrescentIRCompiler.invoke(file), io).use { it.invoke() } },
		)

		for (execute in executions) {
			val streams = CallerOwnedStreams()
			execute(streams.io)

			assertFalse(streams.input.closed, "VM closed the caller's input stream")
			assertFalse(streams.outputBytes.closed, "VM closed the caller's output stream")
			streams.output.println("still open")
			assertEquals("still open\n", streams.text())
		}
	}

	@Test
	fun `old JVM constructor and CLI signatures remain available`() {
		val file = parse("compatibility.moo", "fun main {}")
		CrescentVM(listOf(file), file).close()
		CrescentIRVM(CrescentIRCompiler.invoke(file)).close()

		assertNotNull(CrescentVM::class.java.getConstructor(List::class.java, Node.File::class.java))
		assertNotNull(
			CrescentCli::class.java.getMethod(
				"run",
				emptyArray<String>().javaClass,
				PrintStream::class.java,
				PrintStream::class.java,
			),
		)
		assertNotNull(
			CrescentCli::class.java.getDeclaredMethod(
				"run\$default",
				CrescentCli::class.java,
				emptyArray<String>().javaClass,
				PrintStream::class.java,
				PrintStream::class.java,
				Int::class.javaPrimitiveType!!,
				Any::class.java,
			),
		)
		assertNotNull(
			CrescentCli::class.java.getMethod(
				"run",
				emptyArray<String>().javaClass,
				PrintStream::class.java,
				PrintStream::class.java,
				InputStream::class.java,
			),
		)
	}

	private fun parse(name: String, source: String): Node.File =
		CrescentParser.invoke(Path(name), CrescentLexer.invoke(source))

	private class TestStreams(inputText: String) {
		val input = ByteArrayInputStream(inputText.toByteArray(StandardCharsets.UTF_8))
		private val bytes = ByteArrayOutputStream()
		val output = PrintStream(bytes, true, StandardCharsets.UTF_8)
		val io: RuntimeIO = RuntimeIO.streams(input, output)

		fun text(): String = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
	}

	private class CallerOwnedStreams {
		val input = CloseTrackingInputStream()
		val outputBytes = CloseTrackingOutputStream()
		val output = PrintStream(outputBytes, true, StandardCharsets.UTF_8)
		val io: RuntimeIO = RuntimeIO.streams(input, output)

		fun text(): String = outputBytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
	}

	private class CloseTrackingInputStream : ByteArrayInputStream(byteArrayOf()) {
		var closed = false
			private set

		override fun close() {
			closed = true
			super.close()
		}
	}

	private class CloseTrackingOutputStream : ByteArrayOutputStream() {
		var closed = false
			private set

		override fun close() {
			closed = true
			super.close()
		}
	}
}
