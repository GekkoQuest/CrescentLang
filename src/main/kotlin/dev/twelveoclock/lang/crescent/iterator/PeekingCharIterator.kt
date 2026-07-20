package dev.twelveoclock.lang.crescent.iterator

class PeekingCharIterator(val input: String) : Iterator<Char> {

	@PublishedApi
	internal var index = 0
	val position: Int get() = index


	override fun hasNext(): Boolean {
		return index < input.length
	}

	override fun next(): Char {
		if (!hasNext()) throw NoSuchElementException("No character at offset $index")
		return input[index++]
	}


	fun next(size: Int): String {
		require(size >= 0) { "size must not be negative" }
		val available = input.length - index
		if (size > available) throw NoSuchElementException("Cannot read $size characters at offset $index; $available remain")
		index += size
		return input.substring(index - size, index)
	}

	fun nextUntil(chars: Set<Char>): String {
		return nextUntil {
			it in chars
		}
	}

	fun nextUntil(char: Char): String {
		return nextUntil {
			char == it
		}
	}


	inline fun nextUntil(predicate: (Char) -> Boolean): String {
		return buildString {
			while (index < input.length && !predicate(input[index])) {
				append(input[index])
				index++
			}
		}
	}


	fun peekNext(amount: Int = 1): Char {
		return peekNextOrNull(amount) ?: throw NoSuchElementException("No character $amount position(s) ahead of offset $index")
	}

	fun peekNextOrNull(amount: Int = 1): Char? {
		require(amount > 0) { "amount must be positive" }
		return input.getOrNull(index + amount - 1)
	}

}
