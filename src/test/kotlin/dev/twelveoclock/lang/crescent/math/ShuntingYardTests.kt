package dev.twelveoclock.lang.crescent.math

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Identifier
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.Number.I8
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Type
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.TypeLiteral
import dev.twelveoclock.lang.crescent.language.token.CrescentToken.Operator.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShuntingYardTests {

	@Test
	fun exponentiationIsRightAssociative() {
		val a = Identifier("a")
		val b = Identifier("b")
		val c = Identifier("c")

		assertContentEquals(listOf(a, b, c, POW, POW), ShuntingYard.invoke(listOf(a, POW, b, POW, c)))
	}

	@Test
	fun greaterEqualsUsesComparisonPrecedence() {
		val a = Identifier("a")
		val b = Identifier("b")
		val c = Identifier("c")

		assertContentEquals(listOf(a, b, c, ADD, GREATER_EQUALS_COMPARE), ShuntingYard.invoke(listOf(a, GREATER_EQUALS_COMPARE, b, ADD, c)))
	}

	@Test
	fun castsAndRangesBindInsideComparisonsAndAssignments() {
		val x = Identifier("x")
		val y = Identifier("y")
		val z = Identifier("z")
		val i32 = TypeLiteral(Type.Basic("I32"))

		assertContentEquals(listOf(x, y, i32, AS, ASSIGN), ShuntingYard.invoke(listOf(x, ASSIGN, y, AS, i32)))
		assertContentEquals(listOf(x, i32, AS, y, EQUALS_COMPARE), ShuntingYard.invoke(listOf(x, AS, i32, EQUALS_COMPARE, y)))
		assertContentEquals(listOf(x, y, ADD, i32, AS), ShuntingYard.invoke(listOf(x, ADD, y, AS, i32)))
		assertContentEquals(
			listOf(x, y, ADD, z, RANGE_TO, i32, EQUALS_COMPARE),
			ShuntingYard.invoke(listOf(x, ADD, y, RANGE_TO, z, EQUALS_COMPARE, i32)),
		)
	}

	@Test
	fun unaryMinusIsIsolatedFromEarlierOperands() {
		val one = Identifier("one")
		val two = Identifier("two")
		assertContentEquals(listOf(one, I8(0), two, SUB, MUL), ShuntingYard.invoke(listOf(one, MUL, SUB, two)))
		assertContentEquals(
			listOf(I8(0), I8(0), two, SUB, SUB),
			ShuntingYard.invoke(listOf(SUB, SUB, two)),
		)
	}

	@Test
	fun malformedOperatorPositionsAreRejected() {
		val one = Identifier("one")
		assertTrue(assertFailsWith<IllegalArgumentException> { ShuntingYard.invoke(listOf(one, ADD)) }.message.orEmpty().contains("ends"))
		assertTrue(assertFailsWith<IllegalArgumentException> { ShuntingYard.invoke(listOf(one, NOT)) }.message.orEmpty().contains("precede"))
		assertTrue(assertFailsWith<IllegalArgumentException> { ShuntingYard.invoke(listOf(ADD, one)) }.message.orEmpty().contains("left operand"))
		assertTrue(assertFailsWith<IllegalArgumentException> { ShuntingYard.invoke(listOf(RESULT, one)) }.message.orEmpty().contains("missing an operand"))
		assertContentEquals(listOf(one, NOT, NOT), ShuntingYard.invoke(listOf(NOT, NOT, one)))
		assertContentEquals(listOf(one, RESULT), ShuntingYard.invoke(listOf(one, RESULT)))
	}
}
