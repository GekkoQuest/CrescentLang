package dev.twelveoclock.lang.crescent.ir

import dev.twelveoclock.lang.crescent.language.ir.CrescentIR
import dev.twelveoclock.lang.crescent.language.ir.SectionedCrescentIR
import dev.twelveoclock.lang.crescent.parsers.CrescentIRParser
import dev.twelveoclock.lang.crescent.project.extensions.minimize
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.vm.CrescentIRExecutionException
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import java.util.LinkedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class LegacyCrescentIRTests {
	private fun assertNumberEquals(expected: Number, actual: Any) {
		assertIs<Number>(actual)
		assertEquals(expected.toDouble(), actual.toDouble())
	}

	private fun execute(vararg commands: CrescentIR.Command): Any {
		val stack = LinkedList<Any>()
		CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Fun("main")) + commands)).use {
			it.runFunction("main", stack)
		}
		return stack.peek()
	}

	@Test
	fun parserAcceptsWhitespaceCommentsAndAssignmentShorthand() {
		val ir = CrescentIRParser.invoke(
			"""
				# legacy program
				fun   main

				push  10
				assign total
				push 5
				addAssign total
				pushName total
				invoke println
			""".trimIndent(),
		)

		assertEquals("15\n", collectSystemOut { CrescentIRVM(ir).use { it.invoke() } })
	}

	@Test
	fun pushStringSerializationRoundTripsWithoutChangingItsTypeOrContents() {
		val values = listOf(
			"true",
			"1",
			"",
			" leading and trailing ",
			"say \"hello\" through \\ a path",
			"line\ncarriage\r tab\t backspace\b control\u0001 delete\u007f",
		)

		for (value in values) {
			val serialized = CrescentIR.Command.Push(value).toString()
			val reparsed = CrescentIRParser.invoke("fun main\n$serialized").commands.last()
			assertEquals(CrescentIR.Command.Push(value), reparsed, "Failed to round-trip $serialized")
		}
	}

	@Test
	fun supportedNonStringPushLiteralsRoundTripExactly() {
		val values = listOf<Any>(
			Long.MIN_VALUE,
			Long.MAX_VALUE,
			1.5f,
			Math.PI,
			'x',
			'\'',
			'\\',
			'\n',
			'\u0001',
		)

		for (value in values) {
			val serialized = CrescentIR.Command.Push(value).toString()
			val reparsed = assertIs<CrescentIR.Command.Push>(
				CrescentIRParser.invoke("fun main\n$serialized").commands.last(),
			)
			assertEquals(value, reparsed.value, "Failed to round-trip $serialized")
		}
	}

	@Test
	fun unsupportedPushValuesFailSerializationExplicitly() {
		for (value in listOf(Any(), Double.NaN, Float.POSITIVE_INFINITY)) {
			val failure = assertFailsWith<IllegalArgumentException> {
				CrescentIR.Command.Push(value).toString()
			}
			assertTrue(failure.message.orEmpty().startsWith("Legacy Push cannot serialize"))
		}
	}

	@Test
	fun parserRetainsBackwardCompatibleUnquotedPushValues() {
		val commands = CrescentIRParser.invoke(
			"""
				fun main
				push true
				push 1
				push legacy text
				push
			""".trimIndent(),
		).commands.filterIsInstance<CrescentIR.Command.Push>()

		assertEquals(true, commands[0].value)
		assertNumberEquals(1, commands[1].value)
		assertEquals("legacy text", commands[2].value)
		assertEquals("", commands[3].value)
	}

	@Test
	fun parserRejectsMalformedQuotedPushValuesWithLineDiagnostics() {
		val invalid = listOf(
			"push \"unterminated",
			"push \"bad\\q\"",
			"push \"bad\\u12\"",
			"push ''",
			"push 'two'",
		)
		for (line in invalid) {
			val failure = assertFailsWith<IllegalArgumentException> {
				CrescentIRParser.invoke("fun main\n$line")
			}
			assertTrue(failure.message.orEmpty().startsWith("Line 2:"))
		}
	}

	@Test
	fun parserSupportsEveryLegacyCommandSpelling() {
		val ir = CrescentIRParser.invoke(
			"""
				fun main
				add
				sub
				div
				mul
				rem
				or
				xor
				and
				shl
				shr
				ushr
				isLesser
				isGreater
				isLesserOrEqual
				isGreaterOrEqual
				isEqual
				isNotEqual
				andCompare
				orCompare
				push value with spaces
				pushName value
				jump 1
				jumpIf 1
				jumpIfFalse 1
				loadLibrary standard
				invoke helper
				createInstance Thing
				struct Thing
			""".trimIndent(),
		)

		assertIs<CrescentIR.Command.Fun>(ir.commands.first())
		assertIs<CrescentIR.Command.Struct>(ir.commands.last())
		assertTrue(ir.commands.any { it === CrescentIR.Command.IsLesser })
		assertTrue(ir.commands.any { it is CrescentIR.Command.CreateInstance })
	}

	@Test
	fun arithmeticBitwiseComparisonAndBooleanCommandsExecute() {
		assertNumberEquals(7, execute(CrescentIR.Command.Push(10), CrescentIR.Command.Push(3), CrescentIR.Command.Sub))
		assertNumberEquals(30, execute(CrescentIR.Command.Push(10), CrescentIR.Command.Push(3), CrescentIR.Command.Mul))
		assertNumberEquals(2.5, execute(CrescentIR.Command.Push(10), CrescentIR.Command.Push(4), CrescentIR.Command.Div))
		assertNumberEquals(1, execute(CrescentIR.Command.Push(10), CrescentIR.Command.Push(3), CrescentIR.Command.Rem))
		assertNumberEquals(6, execute(CrescentIR.Command.Push(2), CrescentIR.Command.Push(4), CrescentIR.Command.Or))
		assertNumberEquals(5, execute(CrescentIR.Command.Push(6), CrescentIR.Command.Push(3), CrescentIR.Command.Xor))
		assertNumberEquals(2, execute(CrescentIR.Command.Push(6), CrescentIR.Command.Push(3), CrescentIR.Command.And))
		assertNumberEquals(8, execute(CrescentIR.Command.Push(1), CrescentIR.Command.Push(3), CrescentIR.Command.ShiftLeft))
		assertNumberEquals(2, execute(CrescentIR.Command.Push(8), CrescentIR.Command.Push(2), CrescentIR.Command.ShiftRight))
		assertNumberEquals(
			Int.MAX_VALUE,
			execute(CrescentIR.Command.Push(-1), CrescentIR.Command.Push(1), CrescentIR.Command.UnsignedShiftRight),
		)
		assertEquals(true, execute(CrescentIR.Command.Push(2), CrescentIR.Command.Push(3), CrescentIR.Command.IsLesser))
		assertEquals(true, execute(CrescentIR.Command.Push(3), CrescentIR.Command.Push(3), CrescentIR.Command.IsLesserOrEqual))
		assertEquals(true, execute(CrescentIR.Command.Push(3), CrescentIR.Command.Push(2), CrescentIR.Command.IsGreater))
		assertEquals(true, execute(CrescentIR.Command.Push(3), CrescentIR.Command.Push(3), CrescentIR.Command.IsGreaterOrEqual))
		assertEquals(true, execute(CrescentIR.Command.Push(3), CrescentIR.Command.Push(3.0), CrescentIR.Command.IsEqual))
		assertEquals(true, execute(CrescentIR.Command.Push("a"), CrescentIR.Command.Push("b"), CrescentIR.Command.IsNotEqual))
		assertEquals(false, execute(CrescentIR.Command.Push(true), CrescentIR.Command.Push(false), CrescentIR.Command.AndCompare))
		assertEquals(true, execute(CrescentIR.Command.Push(true), CrescentIR.Command.Push(false), CrescentIR.Command.OrCompare))
	}

	@Test
	fun legacyIntegralOperationsRetainLongPrecision() {
		assertEquals(
			Long.MAX_VALUE,
			execute(CrescentIR.Command.Push(Long.MAX_VALUE), CrescentIR.Command.Push(0), CrescentIR.Command.Add),
		)
		assertEquals(
			Long.MIN_VALUE,
			execute(CrescentIR.Command.Push(Long.MIN_VALUE), CrescentIR.Command.Push(0), CrescentIR.Command.Sub),
		)
		assertEquals(
			Long.MAX_VALUE,
			execute(CrescentIR.Command.Push(Long.MAX_VALUE), CrescentIR.Command.Push(1), CrescentIR.Command.Mul),
		)
		assertEquals(
			Long.MAX_VALUE,
			execute(CrescentIR.Command.Push(Long.MAX_VALUE), CrescentIR.Command.Push(1), CrescentIR.Command.Div),
		)
		assertEquals(
			true,
			execute(
				CrescentIR.Command.Push(Long.MAX_VALUE - 1),
				CrescentIR.Command.Push(Long.MAX_VALUE),
				CrescentIR.Command.IsLesser,
			),
		)
		assertEquals(
			false,
			execute(
				CrescentIR.Command.Push(Long.MAX_VALUE - 1),
				CrescentIR.Command.Push(Long.MAX_VALUE),
				CrescentIR.Command.IsEqual,
			),
		)
		assertEquals(
			Long.MAX_VALUE,
			execute(
				CrescentIR.Command.Push(Long.MAX_VALUE),
				CrescentIR.Command.Push("total"),
				CrescentIR.Command.Assign,
				CrescentIR.Command.Push(0),
				CrescentIR.Command.Push("total"),
				CrescentIR.Command.AddAssign,
				CrescentIR.Command.PushNamedValue("total"),
			),
		)

		val overflow = assertFailsWith<CrescentIRExecutionException> {
			execute(CrescentIR.Command.Push(Long.MAX_VALUE), CrescentIR.Command.Push(1), CrescentIR.Command.Add)
		}
		assertTrue(overflow.message.orEmpty().contains("integer overflow"))
	}

	@Test
	fun legacyDivisionAndBitwiseBoundariesAreWidthSafe() {
		val divisionOverflow = assertFailsWith<CrescentIRExecutionException> {
			execute(CrescentIR.Command.Push(Long.MIN_VALUE), CrescentIR.Command.Push(-1), CrescentIR.Command.Div)
		}
		assertTrue(divisionOverflow.message.orEmpty().contains("integer overflow"))

		val highBit = 1L shl 40
		assertEquals(
			highBit or 1L,
			execute(CrescentIR.Command.Push(highBit), CrescentIR.Command.Push(1), CrescentIR.Command.Or),
		)
		assertEquals(
			highBit,
			execute(CrescentIR.Command.Push(highBit or 1L), CrescentIR.Command.Push(-2L), CrescentIR.Command.And),
		)
		assertEquals(
			highBit,
			execute(CrescentIR.Command.Push(1L), CrescentIR.Command.Push(40), CrescentIR.Command.ShiftLeft),
		)
		assertNumberEquals(
			1,
			execute(CrescentIR.Command.Push(Long.MIN_VALUE), CrescentIR.Command.Push(63), CrescentIR.Command.UnsignedShiftRight),
		)

		for (command in listOf(CrescentIR.Command.Or, CrescentIR.Command.ShiftLeft)) {
			val fractional = assertFailsWith<CrescentIRExecutionException> {
				execute(CrescentIR.Command.Push(1.5), CrescentIR.Command.Push(1), command)
			}
			assertTrue(fractional.message.orEmpty().contains("expected integral operands"))
		}
	}

	@Test
	fun legacyShiftsRejectCountsOutsideTheLeftOperandWidth() {
		assertEquals(
			Int.MIN_VALUE,
			execute(CrescentIR.Command.Push(1), CrescentIR.Command.Push(31), CrescentIR.Command.ShiftLeft),
		)
		assertEquals(
			Long.MIN_VALUE,
			execute(CrescentIR.Command.Push(1L), CrescentIR.Command.Push(63), CrescentIR.Command.ShiftLeft),
		)

		val invalidShifts = listOf(
			Triple(1, -1, CrescentIR.Command.ShiftLeft),
			Triple(1, 32, CrescentIR.Command.ShiftRight),
			Triple(1L, -1L, CrescentIR.Command.UnsignedShiftRight),
			Triple(1L, 64L, CrescentIR.Command.ShiftLeft),
		)
		for ((left, count, command) in invalidShifts) {
			val failure = assertFailsWith<CrescentIRExecutionException> {
				execute(CrescentIR.Command.Push(left), CrescentIR.Command.Push(count), command)
			}
			val maximum = if (left is Long) 63 else 31
			assertTrue(failure.message.orEmpty().contains("Legacy IR function 'main', command"))
			assertTrue(failure.message.orEmpty().contains("'$command'"))
			assertTrue(failure.message.orEmpty().contains("shift count $count"))
			assertTrue(failure.message.orEmpty().contains("0..$maximum"))
		}
	}

	@Test
	fun numberMinimizationPreservesExactAndExceptionalValues() {
		assertIs<Byte>(1.minimize())
		assertIs<Short>(128.minimize())
		assertIs<Int>(32_768L.minimize())
		assertIs<Long>((Int.MAX_VALUE.toLong() + 1).minimize())
		assertIs<Float>(1.5F.minimize())
		assertIs<Float>(0.5.minimize())
		assertIs<Double>(Double.MAX_VALUE.minimize())
		assertIs<Float>(Double.POSITIVE_INFINITY.minimize())
		assertTrue((Double.NaN.minimize() as Double).isNaN())
	}

	@Test
	fun legacyJumpsUsePositionsIncludingTheSectionMarker() {
		val ir = CrescentIRParser.invoke(
			"""
				fun main
				push false
				jumpIfFalse 4
				push skipped
				push kept
				invoke println
			""".trimIndent(),
		)

		assertEquals("kept\n", collectSystemOut { CrescentIRVM(ir).use { it.invoke() } })
	}

	@Test
	fun userFunctionInvocationUsesTheSharedLegacyStack() {
		val ir = CrescentIRParser.invoke(
			"""
				fun main
				push 4
				invoke double
				invoke println
				fun double
				push 2
				mul
			""".trimIndent(),
		)

		assertEquals("8\n", collectSystemOut { CrescentIRVM(ir).use { it.invoke() } })
	}

	@Test
	fun sectioningRejectsOrphansAndDuplicateNames() {
		val orphan = assertFailsWith<IllegalArgumentException> {
			SectionedCrescentIR.from(CrescentIR(listOf(CrescentIR.Command.Push(1))))
		}
		assertTrue(orphan.message.orEmpty().contains("before a function or struct section"))

		val duplicate = assertFailsWith<IllegalArgumentException> {
			SectionedCrescentIR.from(
				CrescentIR(
					listOf(
						CrescentIR.Command.Fun("main"),
						CrescentIR.Command.Fun("main"),
					),
				),
			)
		}
		assertTrue(duplicate.message.orEmpty().contains("Duplicate function section 'main'"))
	}

	@Test
	fun parserReportsLineAndArityErrors() {
		val missingArgument = assertFailsWith<IllegalArgumentException> {
			CrescentIRParser.invoke("fun main\ninvoke")
		}
		assertTrue(missingArgument.message.orEmpty().startsWith("Line 2:"))

		val badPosition = assertFailsWith<IllegalArgumentException> {
			CrescentIRParser.invoke("fun main\njump later")
		}
		assertTrue(badPosition.message.orEmpty().contains("Line 2: 'jump' requires an integer position"))

		val extraArgument = assertFailsWith<IllegalArgumentException> {
			CrescentIRParser.invoke("fun main\nadd unexpected")
		}
		assertTrue(extraArgument.message.orEmpty().contains("does not accept an argument"))
	}

	@Test
	fun executorReportsMissingSectionsStackTypesNamesAndTargets() {
		fun failure(source: String): CrescentIRExecutionException =
			assertFailsWith<CrescentIRExecutionException> { CrescentIRVM(CrescentIRParser.invoke(source)).use { it.invoke() } }

		assertTrue(failure("struct Thing").message.orEmpty().contains("does not define any functions"))
		assertTrue(failure("fun helper").message.orEmpty().contains("does not define function 'main'"))
		assertTrue(failure("fun main\nadd").message.orEmpty().contains("stack underflow"))
		assertTrue(failure("fun main\npush text\npush 1\nsub").message.orEmpty().contains("expected a number"))
		assertTrue(failure("fun main\npushName absent").message.orEmpty().contains("unknown named value 'absent'"))
		assertTrue(failure("fun main\njump 99").message.orEmpty().contains("jump target 99"))
		assertTrue(failure("fun main\npush 1\npush 0\ndiv").message.orEmpty().contains("division by zero"))
	}

	@Test
	fun unsupportedLegacyOperationsFailExplicitly() {
		val library = assertFailsWith<CrescentIRExecutionException> {
			CrescentIRVM(CrescentIRParser.invoke("fun main\nloadLibrary standard")).use { it.invoke() }
		}
		assertTrue(library.message.orEmpty().contains("no legacy runtime implementation"))

		val instance = assertFailsWith<CrescentIRExecutionException> {
			CrescentIRVM(CrescentIRParser.invoke("fun main\ncreateInstance Thing")).use { it.invoke() }
		}
		assertTrue(instance.message.orEmpty().contains("no legacy runtime implementation"))
	}
}
