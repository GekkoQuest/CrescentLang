package dev.twelveoclock.lang.crescent.lexers

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import dev.twelveoclock.lang.crescent.diagnostics.SourceText
import dev.twelveoclock.lang.crescent.iterator.PeekingCharIterator
import dev.twelveoclock.lang.crescent.iterator.TokenSourceMetadata
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.project.extensions.minimize
import java.math.BigInteger
import java.nio.file.Path

object CrescentLexer {

	private val symbolicTokens: Map<String, CrescentToken> = mapOf(
		"!==" to CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE,
		"===" to CrescentToken.Operator.EQUALS_REFERENCE_COMPARE,
		"::" to CrescentToken.Operator.IMPORT_SEPARATOR,
		"->" to CrescentToken.Operator.RETURN,
		".." to CrescentToken.Operator.RANGE_TO,
		"+=" to CrescentToken.Operator.ADD_ASSIGN,
		"-=" to CrescentToken.Operator.SUB_ASSIGN,
		"*=" to CrescentToken.Operator.MUL_ASSIGN,
		"/=" to CrescentToken.Operator.DIV_ASSIGN,
		"%=" to CrescentToken.Operator.REM_ASSIGN,
		"^=" to CrescentToken.Operator.POW_ASSIGN,
		"||" to CrescentToken.Operator.OR_COMPARE,
		"&&" to CrescentToken.Operator.AND_COMPARE,
		"<=" to CrescentToken.Operator.LESSER_EQUALS_COMPARE,
		">=" to CrescentToken.Operator.GREATER_EQUALS_COMPARE,
		"==" to CrescentToken.Operator.EQUALS_COMPARE,
		"!=" to CrescentToken.Operator.NOT_EQUALS_COMPARE,
		"(" to CrescentToken.Parenthesis.OPEN,
		")" to CrescentToken.Parenthesis.CLOSE,
		"{" to CrescentToken.Bracket.OPEN,
		"}" to CrescentToken.Bracket.CLOSE,
		"[" to CrescentToken.SquareBracket.OPEN,
		"]" to CrescentToken.SquareBracket.CLOSE,
		"!" to CrescentToken.Operator.NOT,
		"+" to CrescentToken.Operator.ADD,
		"-" to CrescentToken.Operator.SUB,
		"*" to CrescentToken.Operator.MUL,
		"/" to CrescentToken.Operator.DIV,
		"%" to CrescentToken.Operator.REM,
		"^" to CrescentToken.Operator.POW,
		"=" to CrescentToken.Operator.ASSIGN,
		"<" to CrescentToken.Operator.LESSER_COMPARE,
		">" to CrescentToken.Operator.GREATER_COMPARE,
		":" to CrescentToken.Operator.TYPE_PREFIX,
		"?" to CrescentToken.Operator.RESULT,
		"," to CrescentToken.Operator.COMMA,
		"." to CrescentToken.Operator.DOT,
	)
	private val symbolicTokenEntries = symbolicTokens.entries.sortedByDescending { it.key.length }

	private val wordTokens: Map<String, CrescentToken> = mapOf(
		"in" to CrescentToken.Operator.CONTAINS,
		"as" to CrescentToken.Operator.AS,
		"is" to CrescentToken.Operator.INSTANCE_OF,
		"shr" to CrescentToken.Operator.BIT_SHIFT_RIGHT,
		"shl" to CrescentToken.Operator.BIT_SHIFT_LEFT,
		"ushr" to CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT,
		"and" to CrescentToken.Operator.BIT_AND,
		"or" to CrescentToken.Operator.BIT_OR,
		"xor" to CrescentToken.Operator.BIT_XOR,
		"var" to CrescentToken.Variable.VAR,
		"val" to CrescentToken.Variable.VAL,
		"const" to CrescentToken.Variable.CONST,
		"struct" to CrescentToken.Type.STRUCT,
		"impl" to CrescentToken.Type.IMPL,
		"trait" to CrescentToken.Type.TRAIT,
		"object" to CrescentToken.Type.OBJECT,
		"enum" to CrescentToken.Type.ENUM,
		"sealed" to CrescentToken.Type.SEALED,
		"else" to CrescentToken.Statement.ELSE,
		"import" to CrescentToken.Statement.IMPORT,
		"if" to CrescentToken.Statement.IF,
		"when" to CrescentToken.Statement.WHEN,
		"while" to CrescentToken.Statement.WHILE,
		"for" to CrescentToken.Statement.FOR,
		"fun" to CrescentToken.Statement.FUN,
		"async" to CrescentToken.Modifier.ASYNC,
		"override" to CrescentToken.Modifier.OVERRIDE,
		"operator" to CrescentToken.Modifier.OPERATOR,
		"inline" to CrescentToken.Modifier.INLINE,
		"static" to CrescentToken.Modifier.STATIC,
		"infix" to CrescentToken.Modifier.INFIX,
		"public" to CrescentToken.Visibility.PUBLIC,
		"internal" to CrescentToken.Visibility.INTERNAL,
		"private" to CrescentToken.Visibility.PRIVATE,
		"break" to CrescentToken.Keyword.BREAK,
		"continue" to CrescentToken.Keyword.CONTINUE,
	)

	fun invoke(input: String): List<CrescentToken> = invoke("<input>", input)

	fun invoke(sourcePath: Path, input: String): List<CrescentToken> =
		invoke(SourceSpan.normalizeSourceId(sourcePath), input)

	fun invoke(sourceId: String, input: String): List<CrescentToken> {
		val source = SourceText(sourceId, input)
		return invoke(source, input, IntArray(input.length + 1) { it })
	}

	internal fun invoke(source: SourceText, input: String, sourceOffsets: IntArray): List<CrescentToken> {
		require(sourceOffsets.size == input.length + 1) { "A source boundary is required for every UTF-16 input offset" }
		require((0 until sourceOffsets.lastIndex).all { index -> sourceOffsets[index] <= sourceOffsets[index + 1] }) {
			"Source boundaries must be monotonic"
		}
		require(sourceOffsets.first() >= 0 && sourceOffsets.last() <= source.text.length) { "Source boundaries are outside the source text" }
		val tokens = mutableListOf<CrescentToken>()
		val tokenSpans = mutableListOf<SourceSpan>()
		val statementBoundaries = mutableSetOf<Int>()
		val escapedDollarOffsets = mutableMapOf<Int, Set<Int>>()
		val stringContentSourceOffsets = mutableMapOf<Int, IntArray>()
		val iterator = PeekingCharIterator(input)

		while (iterator.hasNext()) {
			val tokenStart = iterator.position
			val tokenCount = tokens.size
			val current = iterator.peekNext()
			val negatedWord = if (current == '!') {
				listOf("in", "is").firstOrNull { word ->
					word.indices.all { index -> iterator.peekNextOrNull(index + 2) == word[index] } &&
					iterator.peekNextOrNull(word.length + 2)?.let(::isIdentifierPart) != true
				}
			} else {
				null
			}
			when {
				current.isWhitespace() || current == ';' -> {
					iterator.next()
					if (current == '\n' || current == '\r' || current == ';') statementBoundaries += tokens.size
				}
				current == '#' -> {
					iterator.next()
					tokens += CrescentToken.Data.Comment(iterator.nextUntil('\n').trim())
				}
				current == '"' -> {
					val quoted = readQuoted(iterator, '"', source, sourceOffsets)
					val tokenIndex = tokens.size
					tokens += CrescentToken.Data.String(quoted.value)
					if (quoted.escapedDollarOffsets.isNotEmpty()) escapedDollarOffsets[tokenIndex] = quoted.escapedDollarOffsets
					stringContentSourceOffsets[tokenIndex] = quoted.sourceOffsets
				}
				current == '\'' -> {
					val offset = iterator.position
					val value = readQuoted(iterator, '\'', source, sourceOffsets).value
					if (value.length != 1) source.fail(sourceOffsets[offset], sourceOffsets[iterator.position], "Character literal at offset ${sourceOffsets[offset]} must contain exactly one character")
					tokens += CrescentToken.Data.Char(value.single())
				}
				current.isDigit() || current == '.' && iterator.peekNextOrNull(2)?.isDigit() == true -> {
					val literal = readNumber(iterator)
					val number: Number = if ('.' in literal) {
						literal.toDoubleOrNull()?.takeIf { it.isFinite() }
					} else {
						literal.toBigIntegerOrNull()?.takeIf { it <= MAX_UNSIGNED_INTEGER }
					} ?: source.fail(sourceOffsets[iterator.position - literal.length], sourceOffsets[iterator.position], "Invalid number '$literal' at offset ${sourceOffsets[iterator.position - literal.length]}")
					tokens += CrescentToken.Data.Number(number.minimize())
				}
				negatedWord != null -> {
					iterator.next(negatedWord.length + 1)
					tokens += when (negatedWord) {
						"in" -> CrescentToken.Operator.NOT_CONTAINS
						"is" -> CrescentToken.Operator.NOT_INSTANCE_OF
						else -> error("Unknown negated word operator: $negatedWord")
					}
				}
				isIdentifierStart(current) -> {
					val word = iterator.nextUntil { !isIdentifierPart(it) }
					tokens.add(wordTokens[word] ?: when (word) {
						"true" -> CrescentToken.Data.Boolean(true)
						"false" -> CrescentToken.Data.Boolean(false)
						else -> CrescentToken.Key(word)
					})
				}
				else -> {
					val match = symbolicTokenEntries.firstOrNull { (symbol) ->
						symbol.indices.all { index -> iterator.peekNextOrNull(index + 1) == symbol[index] }
					}
					if (match == null) {
						source.fail(sourceOffsets[iterator.position], sourceOffsets[iterator.position + 1], "Unexpected character '$current' at offset ${sourceOffsets[iterator.position]}")
					} else {
						iterator.next(match.key.length)
						tokens.add(match.value)
					}
				}
			}
			if (tokens.size > tokenCount) tokenSpans += source.span(sourceOffsets[tokenStart], sourceOffsets[iterator.position])
		}

		return LexedTokens(tokens.toList(), statementBoundaries.toSet(), escapedDollarOffsets.toMap(), stringContentSourceOffsets.toMap(), source, tokenSpans.toList())
	}

	fun readNumber(charIterator: PeekingCharIterator): String {
		val start = charIterator.position
		var sawDecimal = false
		while (charIterator.hasNext()) {
			when {
				charIterator.peekNext().isDigit() -> charIterator.next()
				charIterator.peekNext() == '.' && !sawDecimal && charIterator.peekNextOrNull(2) != '.' -> {
					sawDecimal = true
					charIterator.next()
				}
				else -> break
			}
		}
		return charIterator.input.substring(start, charIterator.position)
	}

	private data class QuotedValue(
		val value: String,
		val escapedDollarOffsets: Set<Int> = emptySet(),
		val sourceOffsets: IntArray,
	)

	private data class LexedTokens(
		private val tokens: List<CrescentToken>,
		override val statementBoundaries: Set<Int>,
		override val escapedDollarOffsets: Map<Int, Set<Int>>,
		override val stringContentSourceOffsets: Map<Int, IntArray>,
		override val sourceText: SourceText,
		override val tokenSpans: List<SourceSpan>,
	) : AbstractList<CrescentToken>(), TokenSourceMetadata {
		override val size: Int get() = tokens.size
		override fun get(index: Int): CrescentToken = tokens[index]
	}

	private fun readQuoted(iterator: PeekingCharIterator, quote: Char, source: SourceText, sourceOffsets: IntArray): QuotedValue {
		val start = iterator.position
		iterator.next()

		if (quote == '"' && iterator.peekNextOrNull() == '"' && iterator.peekNextOrNull(2) == '"') {
			iterator.next(2)
			val result = StringBuilder()
			val resultOffsets = mutableListOf(sourceOffsets[iterator.position])
			while (iterator.hasNext()) {
				if (iterator.peekNext() == '"' && iterator.peekNextOrNull(2) == '"' && iterator.peekNextOrNull(3) == '"') {
					iterator.next(3)
					return QuotedValue(result.toString(), sourceOffsets = resultOffsets.toIntArray())
				}
				result.append(iterator.next())
				resultOffsets += sourceOffsets[iterator.position]
			}
			source.fail(sourceOffsets[start], sourceOffsets[iterator.position], "Unterminated string literal at offset ${sourceOffsets[start]}")
		}

		val result = StringBuilder()
		val resultOffsets = mutableListOf(sourceOffsets[iterator.position])
		val escapedDollarOffsets = mutableSetOf<Int>()
		while (iterator.hasNext()) {
			val next = iterator.next()
			if (next == quote) return QuotedValue(result.toString(), escapedDollarOffsets, resultOffsets.toIntArray())
			if (next != '\\') {
				result.append(next)
				resultOffsets += sourceOffsets[iterator.position]
				continue
			}

			val escaped = iterator.peekNextOrNull()
				?: source.fail(sourceOffsets[iterator.position - 1], sourceOffsets[iterator.position], "Unterminated escape sequence at offset ${sourceOffsets[iterator.position - 1]}")
			iterator.next()
			result.append(
				when (escaped) {
					'n' -> '\n'
					'r' -> '\r'
					't' -> '\t'
					'0' -> '\u0000'
					'\\' -> '\\'
					'"' -> '"'
					'\'' -> '\''
					'$' -> {
						if (quote == '"') escapedDollarOffsets += result.length
						'$'
					}
					else -> source.fail(sourceOffsets[iterator.position - 2], sourceOffsets[iterator.position], "Unknown escape sequence '\\$escaped' at offset ${sourceOffsets[iterator.position - 2]}")
				}
			)
			resultOffsets += sourceOffsets[iterator.position]
		}

		source.fail(sourceOffsets[start], sourceOffsets[iterator.position], "Unterminated ${if (quote == '"') "string" else "character"} literal at offset ${sourceOffsets[start]}")
	}

	private fun SourceText.fail(startOffset: Int, endOffset: Int, message: String): Nothing =
		throw DiagnosticException(Diagnostic(DiagnosticSeverity.ERROR, message, span(startOffset, endOffset)))

	private fun isIdentifierStart(char: Char): Boolean = char == '_' || char.isLetter()
	private fun isIdentifierPart(char: Char): Boolean = char == '_' || char.isLetterOrDigit()

	private val MAX_UNSIGNED_INTEGER = BigInteger("18446744073709551615")
}
