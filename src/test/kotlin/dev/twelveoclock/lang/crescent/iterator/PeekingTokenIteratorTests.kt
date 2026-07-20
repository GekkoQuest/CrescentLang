package dev.twelveoclock.lang.crescent.iterator

import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class PeekingTokenIteratorTests {

	@Test
	fun backCannotMoveBeforeStart() {
		assertFailsWith<NoSuchElementException> { PeekingTokenIterator(emptyList()).back() }
	}

	@Test
	fun lookaheadAmountMustBePositive() {
		val iterator = PeekingTokenIterator(listOf(CrescentToken.Key("value")))
		assertFailsWith<IllegalArgumentException> { iterator.peekNext(0) }
	}
}
