package dev.twelveoclock.lang.crescent.iterator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class PeekingCharIteratorTests {

	@Test
	fun boundariesAreExplicit() {
		val iterator = PeekingCharIterator("ab")

		assertEquals(0, iterator.position)
		assertFailsWith<IllegalArgumentException> { iterator.peekNextOrNull(0) }
		assertEquals('a', iterator.peekNextOrNull())
		assertEquals("ab", iterator.next(2))
		assertEquals(2, iterator.position)
		assertNull(iterator.peekNextOrNull())
		assertFailsWith<NoSuchElementException> { iterator.next() }
	}

}
