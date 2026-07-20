package dev.twelveoclock.lang.crescent.iterator

import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import dev.twelveoclock.lang.crescent.diagnostics.SourceText
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import java.nio.file.Path

interface TokenSourceMetadata {
	val statementBoundaries: Set<Int>
	val escapedDollarOffsets: Map<Int, Set<Int>>
	val stringContentSourceOffsets: Map<Int, IntArray> get() = emptyMap()
	val sourceText: SourceText? get() = null
	val tokenSpans: List<SourceSpan> get() = emptyList()
}

class PeekingTokenIterator(val input: List<CrescentToken>) : Iterator<CrescentToken> {
	companion object {
		fun excludingComments(input: List<CrescentToken>): PeekingTokenIterator = excludingComments(input, null)

		fun excludingComments(input: List<CrescentToken>, sourcePath: Path?): PeekingTokenIterator {
			val metadata = input as? TokenSourceMetadata
			if (metadata == null) return PeekingTokenIterator(input.filterNot { it is CrescentToken.Data.Comment })
			val sourceText = metadata.sourceText?.let { original ->
				if (sourcePath == null) original else SourceText(sourcePath, original.text)
			}
			val retainedBefore = IntArray(input.size + 1)
			val retained = mutableListOf<CrescentToken>()
			val originalToRetained = mutableMapOf<Int, Int>()
			input.forEachIndexed { index, token ->
				retainedBefore[index] = retained.size
				if (token !is CrescentToken.Data.Comment) {
					originalToRetained[index] = retained.size
					retained += token
				}
			}
			retainedBefore[input.size] = retained.size
			val filtered = MetadataTokenList(
				retained,
				metadata.statementBoundaries.mapTo(mutableSetOf()) { retainedBefore[it.coerceIn(0, input.size)] },
				metadata.escapedDollarOffsets.mapNotNull { (originalIndex, offsets) ->
					originalToRetained[originalIndex]?.let { it to offsets }
				}.toMap(),
				metadata.stringContentSourceOffsets.mapNotNull { (originalIndex, offsets) ->
					originalToRetained[originalIndex]?.let { it to offsets }
				}.toMap(),
				sourceText,
				metadata.tokenSpans.mapIndexedNotNull { originalIndex, span ->
					originalToRetained[originalIndex]?.let {
						if (sourceText == null) span else sourceText.span(span.start.offset, span.end.offset)
					}
				},
			)
			return PeekingTokenIterator(filtered)
		}
	}

	@PublishedApi
	internal var index = 0
	val position: Int get() = index
	private val sourceMetadata = input as? TokenSourceMetadata
	val isAtStatementBoundary: Boolean get() = index in sourceMetadata?.statementBoundaries.orEmpty()
	val sourceText: SourceText? get() = sourceMetadata?.sourceText
	val currentSpan: SourceSpan? get() = spanAt(index)
	val lastSpan: SourceSpan? get() = spanAt(index - 1)
	val eofSpan: SourceSpan? get() = sourceText?.point(sourceText!!.text.length)

	fun escapedDollarOffsetsForLastToken(): Set<Int> =
		sourceMetadata?.escapedDollarOffsets?.get(index - 1).orEmpty()
	fun stringContentSourceOffsetsForLastToken(): IntArray? =
		sourceMetadata?.stringContentSourceOffsets?.get(index - 1)

	fun spanAt(tokenIndex: Int): SourceSpan? = sourceMetadata?.tokenSpans?.getOrNull(tokenIndex)

	fun spanFrom(startTokenIndex: Int, endTokenIndexExclusive: Int = index): SourceSpan? {
		val start = spanAt(startTokenIndex) ?: return if (startTokenIndex >= input.size) eofSpan else null
		val end = spanAt(endTokenIndexExclusive - 1) ?: start
		return SourceSpan(start.sourceId, start.start, end.end)
	}

	fun diagnosticSpan(preferLastConsumed: Boolean = true): SourceSpan? = when {
		preferLastConsumed && index > 0 -> lastSpan
		hasNext() -> currentSpan
		else -> eofSpan
	}


	override fun hasNext(): Boolean {
		return index < input.size
	}

	override fun next(): CrescentToken {
		if (!hasNext()) throw NoSuchElementException("No token at index $index")
		return input[index++]
	}


	fun back(): CrescentToken {
		if (index == 0) throw NoSuchElementException("Cannot move before token index 0")
		return input[--index]
	}


	fun peekNext(amount: Int = 1): CrescentToken {
		require(amount > 0) { "amount must be positive" }
		return input.getOrElse(index + (amount - 1)) { CrescentToken.None }
	}


	inline fun nextUntil(predicate: (CrescentToken) -> Boolean): List<CrescentToken> {

		val tokens = mutableListOf<CrescentToken>()

		while (index < input.size && !predicate(input[index])) {
			tokens += input[index]
			index++
		}

		return tokens
	}

}

private data class MetadataTokenList(
	private val tokens: List<CrescentToken>,
	override val statementBoundaries: Set<Int>,
	override val escapedDollarOffsets: Map<Int, Set<Int>>,
	override val stringContentSourceOffsets: Map<Int, IntArray>,
	override val sourceText: SourceText?,
	override val tokenSpans: List<SourceSpan>,
) : AbstractList<CrescentToken>(), TokenSourceMetadata {
	override val size: Int get() = tokens.size
	override fun get(index: Int): CrescentToken = tokens[index]
}
