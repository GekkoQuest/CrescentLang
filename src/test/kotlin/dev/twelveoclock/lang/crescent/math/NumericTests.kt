package dev.twelveoclock.lang.crescent.math

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.Number.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NumericTests {

	@Test
	fun signedArithmeticPromotesConsistently() {
		assertEquals(I32(5), I8(2) + I16(3))
		assertEquals(I64(-1), I64(2) - I8(3))
		assertEquals(F64(2.5), I32(5) / F64(2.0))
		assertEquals(F32(1.5f), F32(5.5f) % I8(2))
	}

	@Test
	fun multiplicationAndPowerPreserveOperandOrder() {
		assertEquals(I32(12), I8(3).multiply(I16(4)))
		assertEquals(F64(512.0), I8(2).pow(I8(9)))
	}

	@Test
	fun unsignedArithmeticKeepsItsEstablishedPromotionRules() {
		assertEquals(U32(260u), U8(250u.toUByte()) + U8(10u.toUByte()))
		assertEquals(U64(0uL), U64(ULong.MAX_VALUE) + U8(1u.toUByte()))
		assertFailsWith<IllegalStateException> { U8(1u.toUByte()) + I8(1) }
	}

	@Test
	fun integerOverflowAndFloatPromotionRemainStable() {
		assertEquals(I32(Int.MIN_VALUE), I32(Int.MAX_VALUE) + I8(1))
		assertEquals(F32(3.5f), I64(2) + F32(1.5f))
	}
}
