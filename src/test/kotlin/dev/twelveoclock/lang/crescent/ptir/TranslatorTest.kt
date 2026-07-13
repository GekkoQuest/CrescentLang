package dev.twelveoclock.lang.crescent.ptir

import dev.twelveoclock.lang.crescent.data.TestCode
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.translators.KotlinToCrescentTranslator
import dev.twelveoclock.lang.crescent.translators.PoderTechIrInstruction
import dev.twelveoclock.lang.crescent.translators.PoderTechIrTranslator
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TranslatorTest {

	@Test
	fun poderTechIrTranslatorLowersFunctionsAndCalls() {
		val file = CrescentParser.invoke(Path("hello.crescent"), CrescentLexer.invoke(TestCode.helloWorlds))
		val module = PoderTechIrTranslator.translate(file).modules.single()
		val main = module.methods.single { it.name == "main" }

		assertEquals("hello", module.name)
		assertEquals(3, main.instructions.filterIsInstance<PoderTechIrInstruction.Invoke>().count { it.name == "println" })
	}

	@Test
	fun kotlinTranslatorProducesParseableCrescent() {
		val kotlinSource =
			"""
				suspend fun answer(value: Int = 41): Int {
					return value + 1
				}

				fun main(args: Array<String>): Unit {
					val limit: Int = 1
					for (index in 0..limit) {
						println(index)
					}
				}
			""".trimIndent()

		val crescentSource = KotlinToCrescentTranslator.translate(kotlinSource)
		val file = CrescentParser.invoke(Path("translated.crescent"), CrescentLexer.invoke(crescentSource))
		val answer = file.functions.getValue("answer")

		assertTrue(CrescentToken.Modifier.ASYNC in answer.modifiers)
		assertIs<Node.Parameter.WithDefault>(answer.params.single())
		assertEquals(Node.Type.Basic("I32"), answer.returnType)
		assertEquals(Node.Type.Array(Node.Type.Basic("String")), file.mainFunction!!.params.single().let { (it as Node.Typed).type })
	}
}
