package dev.twelveoclock.lang.crescent.ptir

import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.translators.*
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import dev.twelveoclock.lang.crescent.vm.RuntimeIO
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import kotlin.io.path.Path
import kotlin.test.*

class PoderTechIrExecutorTest {
	@Test
	fun `PTIR matches direct and lowered execution for arguments functions loops and control flow`() {
		val file = parse(
			"parity.crescent",
			"""
				fun twice(value: I32) -> I32 { -> value * 2 }
				fun main(args: [String]) {
					var total: I32 = 0
					for index in 1..3 { total += index }
					if(total == 6) { println(args[0]); println(twice(total)) } else { println("bad") }
				}
			""".trimIndent(),
		)

		val direct = Streams()
		CrescentVM(listOf(file), file, direct.io).use { it.invoke(listOf("ptir")) }
		val lowered = Streams()
		CrescentIRVM(CrescentIRCompiler.invoke(file), lowered.io).use { it.invoke(listOf("ptir")) }
		val ptir = Streams()
		PoderTechIrExecutor(PoderTechIrTranslator.translate(file), ptir.io).invokeMain(listOf("ptir"))

		assertEquals("ptir\n12\n", direct.text())
		assertEquals(direct.text(), lowered.text())
		assertEquals(direct.text(), ptir.text())
	}

	@Test
	fun `PTIR matches injected IO arrays when and mutable assignment`() {
		val file = parse(
			"io.crescent",
			"""
				fun main {
					val answer = readInt("number")
					val values = [answer, answer + 1]
					values[0] += 2
					when(subject = values[0]) { 6 -> { println(subject) } else -> { println(0) } }
				}
			""".trimIndent(),
		)
		fun direct() = Streams("4\n").also { streams -> CrescentVM(listOf(file), file, streams.io).use { it.invoke() } }
		fun lowered() = Streams("4\n").also { streams -> CrescentIRVM(CrescentIRCompiler.invoke(file), streams.io).use { it.invoke() } }
		fun ptir() = Streams("4\n").also { streams -> PoderTechIrExecutor(PoderTechIrTranslator.translate(file), streams.io).invokeMain() }

		assertEquals("number\n6\n", direct().text())
		assertEquals(direct().text(), lowered().text())
		assertEquals(direct().text(), ptir().text())
	}

	@Test
	fun `executor freezes caller owned PTIR and validates source qualified module identity`() {
		val bodyNodes = mutableListOf<PoderTechIrNode>(PoderTechIrNode.Return(PoderTechIrNode.Literal("I32", 7)))
		val methods = mutableListOf(
			PoderTechIrMethod(
				name = "value", parameters = emptyList(), instructions = emptyList(),
				parameterDefinitions = emptyList(), returnType = PoderTechIrType.Named("I32"),
				body = PoderTechIrNode.Block(bodyNodes),
			),
		)
		val module = PoderTechIrModule("same", emptyList(), emptyList(), emptyList(), methods, "pkg", Path("one", "same.crescent"))
		val executor = PoderTechIrExecutor(PoderTechIrProgram(mutableListOf(module)))

		bodyNodes.clear()
		methods.clear()
		assertEquals(7, executor.invoke(module, "value"))
		val wrongSource = module.copy(sourcePath = Path("two", "same.crescent"))
		assertContains(assertFailsWith<PoderTechIrExecutionException> { executor.invoke(wrongSource, "value") }.message.orEmpty(), "does not contain source")
	}

	@Test
	fun `unqualified invocation rejects ambiguous entrypoints deterministically`() {
		fun module(path: String) = PoderTechIrTranslator.translate(parse(path, "fun main { }")).modules.single()
		val executor = PoderTechIrExecutor(PoderTechIrProgram(listOf(module("one/main.crescent"), module("two/main.crescent"))))
		val error = assertFailsWith<PoderTechIrExecutionException> { executor.invokeMain() }
		assertContains(error.message.orEmpty(), "Ambiguous PoderTechIR main")
		assertContains(error.message.orEmpty(), "one")
		assertContains(error.message.orEmpty(), "two")
	}

	@Test
	fun `manual malformed PTIR fails explicitly and executor has no VM dependency`() {
		val method = PoderTechIrMethod(
			"main", emptyList(), emptyList(), parameterDefinitions = emptyList(),
			body = PoderTechIrNode.Block(listOf(PoderTechIrNode.Operator("+"))),
		)
		val module = PoderTechIrModule("bad", emptyList(), emptyList(), emptyList(), listOf(method), sourcePath = Path("bad.crescent"))
		val error = assertFailsWith<PoderTechIrExecutionException> { PoderTechIrExecutor(PoderTechIrProgram(listOf(module))).invokeMain() }
		assertContains(error.message.orEmpty(), "Standalone operator")

		val fieldTypes = PoderTechIrExecutor::class.java.declaredFields.map { it.type.name }
		assertTrue(fieldTypes.none { it.endsWith(".CrescentVM") || it.endsWith(".CrescentIRVM") })
	}

	@Test
	fun `source tied PTIR failures expose canonical diagnostics`() {
		val file = parse("located.crescent", "fun main { missing() }")
		val failure = assertFailsWith<PoderTechIrExecutionException> {
			PoderTechIrExecutor(PoderTechIrTranslator.translate(file)).invokeMain()
		}
		assertNotNull(failure.span)
		assertEquals(failure.span, failure.diagnostic.span)
		assertTrue(failure.message.orEmpty().startsWith("located.crescent:"))
		assertContains(failure.message.orEmpty(), ": error: Unknown PoderTechIR function 'missing'")
	}

	@Test
	fun `qualified enum and sealed values execute with direct and lowered parity`() {
		val file = parse(
			"qualified-values.crescent",
			"""
				enum Choice(value: I32) { A(7) }
				sealed Family {
					struct Item(val value: I32)
					object Empty { fun value() -> I32 { -> 9 } }
				}
				fun main {
					println(Choice.A.value)
					println(Item(8).value)
					println(Empty.value())
				}
			""".trimIndent(),
		)
		assertParity(file, "7\n8\n9\n")

		val qualified = parse(
			"qualified-sealed-ptir.crescent",
			"sealed Family { struct Item(val value: I32) object Empty { fun value() -> I32 { -> 9 } } } fun main { println(Family.Item(8).value); println(Family.Empty.value()) }",
		)
		val streams = Streams()
		PoderTechIrExecutor(PoderTechIrTranslator.translate(qualified), streams.io).invokeMain()
		assertEquals("8\n9\n", streams.text())
	}

	@Test
	fun `composite equality and integral boundaries match both runtimes`() {
		val file = parse(
			"structural-equality.crescent",
			"""
				struct Box(val value: I32)
				enum Choice(value: I32) { A(1) }
				fun main {
					println(Box(1) == Box(1))
					println(Choice.A == Choice.A)
					println([Box(1)] == [Box(1)])
					println(success([1, 2]) == success([1, 2]))
					println(9007199254740992 == 9007199254740993)
					println(9007199254740992 < 9007199254740993)
					println(9223372036854775806 < 9223372036854775807)
					println((18446744073709551614 as U64) < (18446744073709551615 as U64))
				}
			""".trimIndent(),
		)
		assertParity(file, "true\ntrue\ntrue\ntrue\nfalse\ntrue\ntrue\ntrue\n")
	}

	@Test
	fun `structural equality includes source identity`() {
		fun value(path: String) = PoderTechIrStructValue(
			"Box", PoderTechIrSourceIdentity("pkg", Path(path)), linkedMapOf("value" to Cell(1, false)),
		)
		fun result(left: Any, right: Any): Boolean {
			val expression = PoderTechIrNode.Expression(listOf(PoderTechIrNode.Literal("Any", left), PoderTechIrNode.Literal("Any", right), PoderTechIrNode.Operator("==")))
			val method = PoderTechIrMethod("main", emptyList(), emptyList(), parameterDefinitions = emptyList(), body = PoderTechIrNode.Block(listOf(expression)))
			return PoderTechIrExecutor(PoderTechIrProgram(listOf(PoderTechIrModule("identity", emptyList(), emptyList(), emptyList(), listOf(method))))).invokeMain() as Boolean
		}
		assertTrue(result(value("same.crescent"), value("same.crescent")))
		assertFalse(result(value("one.crescent"), value("two.crescent")))

		val left = value("same.crescent")
		val right = value("same.crescent")
		val equality = PoderTechIrNode.Expression(listOf(PoderTechIrNode.Literal("Box", left), PoderTechIrNode.Literal("Box", right), PoderTechIrNode.Operator("==")))
		val equalsMethod = PoderTechIrMethod(
			"Box.equals", listOf("other"), emptyList(), owner = "Box", kind = PoderTechIrMethodKind.INSTANCE_IMPL,
			modifiers = listOf(PoderTechIrModifier.OPERATOR), parameterDefinitions = listOf(PoderTechIrParameter("other", PoderTechIrType.Named("Box"))),
			returnType = PoderTechIrType.Named("Boolean"), body = PoderTechIrNode.Block(listOf(PoderTechIrNode.Return(PoderTechIrNode.Literal("Boolean", false)))),
		)
		val main = PoderTechIrMethod("main", emptyList(), emptyList(), parameterDefinitions = emptyList(), body = PoderTechIrNode.Block(listOf(equality)))
		val module = PoderTechIrModule("operator", emptyList(), emptyList(), emptyList(), listOf(main, equalsMethod))
		assertFalse(PoderTechIrExecutor(PoderTechIrProgram(listOf(module))).invokeMain() as Boolean)
	}

	@Test
	fun `escaped loop control and mixed signedness fail explicitly`() {
		fun program(node: PoderTechIrNode) = PoderTechIrProgram(listOf(PoderTechIrModule(
			"manual", emptyList(), emptyList(), emptyList(), listOf(PoderTechIrMethod(
				"main", emptyList(), emptyList(), parameterDefinitions = emptyList(), body = PoderTechIrNode.Block(listOf(node)),
			)), sourcePath = Path("manual.crescent"),
		)))
		assertContains(assertFailsWith<PoderTechIrExecutionException> { PoderTechIrExecutor(program(PoderTechIrNode.Break)).invokeMain() }.detail, "break cannot escape")
		assertContains(assertFailsWith<PoderTechIrExecutionException> { PoderTechIrExecutor(program(PoderTechIrNode.Continue)).invokeMain() }.detail, "continue cannot escape")
		val comparison = PoderTechIrNode.Expression(listOf(
			PoderTechIrNode.Literal("U64", ULong.MAX_VALUE), PoderTechIrNode.Literal("I64", Long.MAX_VALUE), PoderTechIrNode.Operator(">"),
		))
		assertContains(assertFailsWith<PoderTechIrExecutionException> { PoderTechIrExecutor(program(comparison)).invokeMain() }.detail, "signed and unsigned")
	}

	@Test
	fun `floating and unsigned comparisons reject before conversion`() {
		fun invoke(left: Any, right: Any, operator: String): Any? {
			val expression = PoderTechIrNode.Expression(listOf(
				PoderTechIrNode.Literal(runtimeKind(left), left), PoderTechIrNode.Literal(runtimeKind(right), right), PoderTechIrNode.Operator(operator),
			))
			val method = PoderTechIrMethod("main", emptyList(), emptyList(), parameterDefinitions = emptyList(), body = PoderTechIrNode.Block(listOf(expression)))
			return PoderTechIrExecutor(PoderTechIrProgram(listOf(PoderTechIrModule("mixed", emptyList(), emptyList(), emptyList(), listOf(method))))).invokeMain()
		}
		val pairs = listOf(1.0f to 1u, 1u to 1.0f, 1.0 to 1uL, 1uL to 1.0)
		for ((left, right) in pairs) {
			assertFalse(invoke(left, right, "==") as Boolean)
			assertContains(assertFailsWith<PoderTechIrExecutionException> { invoke(left, right, "<") }.detail, "signed and unsigned")
		}
	}

	@Test
	@Suppress("UNCHECKED_CAST")
	fun `literal payload snapshot detaches aliases collections arrays and cycles`() {
		val shared = mutableListOf<Any?>(1)
		val mapping = linkedMapOf<String, Any?>("shared" to shared)
		val members = linkedSetOf<Any?>("first")
		val array = arrayOfNulls<Any?>(1).also { it[0] = shared }
		val root = mutableListOf<Any?>()
		root.add(shared)
		root.add(mapping)
		root.add(array)
		root.add(members)
		root.add(root)
		val executor = literalExecutor(root)

		shared[0] = 99
		mapping["late"] = 2
		members += "late"
		array[0] = "late"
		root.clear()

		val frozen = executor.invokeMain() as List<*>
		assertEquals(5, frozen.size)
		assertEquals(1, (frozen[0] as List<*>).single())
		assertSame(frozen[0], (frozen[1] as Map<*, *>)["shared"])
		assertSame(frozen[0], (frozen[2] as Array<*>)[0])
		assertEquals(setOf("first"), frozen[3])
		assertSame(frozen, frozen[4])
		assertFailsWith<UnsupportedOperationException> { (frozen as MutableList<Any?>)[0] = "mutated" }
	}

	@Test
	fun `literal payload snapshot rejects arbitrary mutable objects precisely`() {
		val error = assertFailsWith<PoderTechIrExecutionException> { literalExecutor(StringBuilder("mutable")) }
		assertContains(error.detail, "Unsupported PoderTechIR literal payload")
		assertContains(error.detail, "java.lang.StringBuilder")
	}

	private fun literalExecutor(value: Any): PoderTechIrExecutor {
		val method = PoderTechIrMethod(
			"main", emptyList(), emptyList(), parameterDefinitions = emptyList(),
			body = PoderTechIrNode.Block(listOf(PoderTechIrNode.Literal("Any", value))),
		)
		return PoderTechIrExecutor(PoderTechIrProgram(listOf(PoderTechIrModule("literal", emptyList(), emptyList(), emptyList(), listOf(method)))))
	}

	private fun runtimeKind(value: Any) = when (value) {
		is Float -> "F32"; is Double -> "F64"; is UInt -> "U32"; is ULong -> "U64"; else -> error("unsupported test value")
	}

	private fun assertParity(file: dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.File, expected: String) {
		val direct = Streams()
		CrescentVM(listOf(file), file, direct.io).use { it.invoke() }
		val lowered = Streams()
		CrescentIRVM(CrescentIRCompiler.invoke(file), lowered.io).use { it.invoke() }
		val ptir = Streams()
		PoderTechIrExecutor(PoderTechIrTranslator.translate(file), ptir.io).invokeMain()
		assertEquals(expected, direct.text())
		assertEquals(direct.text(), lowered.text())
		assertEquals(direct.text(), ptir.text())
	}

	private fun parse(name: String, source: String) = CrescentParser.invoke(Path(name), CrescentLexer.invoke(source))

	private class Streams(input: String = "") {
		private val output = ByteArrayOutputStream()
		val io = RuntimeIO.streams(
			ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8)),
			PrintStream(output, true, StandardCharsets.UTF_8),
		)
		fun text() = output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n")
	}
}
