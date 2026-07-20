package dev.twelveoclock.lang.crescent.ptir

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.translators.KotlinToCrescentTranslator
import dev.twelveoclock.lang.crescent.translators.KotlinTranslationException
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KotlinTranslatorTest {
	@Test
	fun translatesRegularAndRawStringTemplatesExactly() {
		val rawDelimiter = "\"\"\""
		val translated = KotlinToCrescentTranslator.translate(
			"""
				fun main() {
					val name = "Ada"
					val count = 2
					println("Hello ${'$'}name ${'$'}{count + 1} \${'$'}")
					println(${rawDelimiter}Raw ${'$'}name ${'$'}{count + 2} ${'$'}{'${'$'}'} slash\path${rawDelimiter})
					println("Inner ${'$'}{"ok"}")
				}
			""".trimIndent(),
		)
		val parsed = CrescentParser.invoke(Path("templates.crescent"), CrescentLexer.invoke(translated))

		assertContains(translated, "${'$'}name")
		assertContains(translated, "${'$'}{count+(1 as I32)}")
		assertContains(translated, "\\${'$'}")
		assertEquals("Hello Ada 3 ${'$'}\nRaw Ada 4 ${'$'} slash\\path\nInner ok\n", collectSystemOut { CrescentVM(listOf(parsed), parsed).use { it.invoke() } })
	}

	@Test
	fun rejectsUnsupportedTemplateExpressionsAtTheirExactOperatorSpan() {
		val source = "fun bad(value: String) { println(\"${'$'}{value?.length}\") }"
		val failure = assertFailsWith<KotlinTranslationException> {
			KotlinToCrescentTranslator.translate(source, "template.kt")
		}
		val start = source.indexOf("?.")

		assertEquals(start, failure.startOffset)
		assertEquals(start + 2, failure.endOffset)
	}

	@Test
	fun translatesEnumsWhenAndReferenceEqualityWithoutSemanticApproximation() {
		val translated = KotlinToCrescentTranslator.translate(
			"""
				enum class Mode(val code: Int) {
					FAST(2), SLOW(3);
					fun doubled(): Int { return code * 2 }
				}
				fun main() {
					val selected: Mode = Mode.FAST
					when (selected) {
						Mode.FAST -> println(selected.doubled())
						else -> println(0)
					}
					val identity = arrayOf(1)
					println(identity === identity)
					println(identity !== arrayOf(1))
				}
			""".trimIndent(),
			"fixtures/mode.kt",
		)
		val parsed = CrescentParser.invoke(Path("mode.crescent"), CrescentLexer.invoke(translated))

		assertEquals(listOf("FAST", "SLOW"), parsed.enums.getValue("Mode").structs.map(Node.EnumEntry::name))
		assertEquals("4\ntrue\ntrue\n", collectSystemOut { CrescentVM(listOf(parsed), parsed).use { it.invoke() } })
	}

	@Test
	fun translatesSubjectlessWhenNamedSubjectsAndUnbracedRanges() {
		val translated = KotlinToCrescentTranslator.translate(
			"""
				fun main() {
					for (value in 1..2) println(value)
					when {
						false -> println(9)
						else -> println(3)
					}
					when (val chosen = 4) {
						4 -> println(chosen)
						else -> println(0)
					}
				}
			""".trimIndent(),
		)
		val parsed = CrescentParser.invoke(Path("control.crescent"), CrescentLexer.invoke(translated))

		assertContains(translated, "when(true)")
		assertContains(translated, "when(chosen=(4 as I32))")
		assertEquals("1\n2\n3\n4\n", collectSystemOut { CrescentVM(listOf(parsed), parsed).use { it.invoke() } })
	}

	@Test
	fun translatesTheDirectlyRepresentableSealedVariantShape() {
		val translated = KotlinToCrescentTranslator.translate(
			"""
				sealed class Choice {
					class Number(val value: Int) : Choice()
					object None : Choice() {}
				}
			""".trimIndent(),
		)
		val parsed = CrescentParser.invoke(Path("sealed.crescent"), CrescentLexer.invoke(translated))
		val sealed = parsed.sealeds.getValue("Choice")

		assertEquals(listOf("Number"), sealed.structs.map(Node.Struct::name))
		assertEquals(listOf("None"), sealed.objects.map(Node.Object::name))
	}

	@Test
	fun exposesCanonicalHalfOpenSpansForLexicalSyntaxAndSemanticFailures() {
		val cases = listOf(
			Triple("fun bad(value: String?) {}", "?", 1),
			Triple("fun `bad`() {}", "`", 1),
			Triple("fun bad() { return value?.size }", "?.", 2),
			Triple("fun bad(value: Any) { return value as? String }", "as?", 3),
			Triple("fun bad() { /* unfinished", "/* unfinished", "/* unfinished".length),
		)
		cases.forEach { (source, marker, width) ->
			val failure = assertFailsWith<KotlinTranslationException> {
				KotlinToCrescentTranslator.translate(source, "windows\\fixture.kt")
			}
			val start = source.indexOf(marker)
			assertEquals(start, failure.startOffset)
			assertEquals(start + width, failure.endOffset)
			assertEquals("windows/fixture.kt", failure.translationSpan.sourceId)
			assertIs<IllegalArgumentException>(failure)
		}
	}

	@Test
	fun computesUtf16LinesAndColumnsAcrossCrLf() {
		val source = "fun good() {}\r\nfun bad(value: String?) {}"
		val failure = assertFailsWith<KotlinTranslationException> {
			KotlinToCrescentTranslator.translate(source, "fixture.kt")
		}

		assertEquals(source.indexOf('?'), failure.startOffset)
		assertEquals(2, failure.translationSpan.start.line)
		assertEquals(22, failure.translationSpan.start.column)
		assertEquals(23, failure.translationSpan.end.column)
	}

	@Test
	fun retainsExplicitRejectionsForGenuineSemanticMismatches() {
		listOf(
			"fun nullable(value: String?): String = value",
			"fun safe(value: String): Int = value?.length ?: 0",
			"fun callback(block: (Int) -> Int): Int = block(1)",
			"fun exception(): Int { try { return 1 } catch (error: Any) { return 0 } }",
			"data class Synthesized(val value: Int)",
		).forEach { source ->
			assertFailsWith<KotlinTranslationException> { KotlinToCrescentTranslator.translate(source) }
		}
	}
}
