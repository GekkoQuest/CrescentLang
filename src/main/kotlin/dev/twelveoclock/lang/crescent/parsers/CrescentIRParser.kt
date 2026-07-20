package dev.twelveoclock.lang.crescent.parsers

import dev.twelveoclock.lang.crescent.language.ir.CrescentIR
import dev.twelveoclock.lang.crescent.project.extensions.minimize
import java.nio.file.Path
import kotlin.io.path.bufferedReader

object CrescentIRParser {

	fun invoke(input: String): CrescentIR {
		return invoke(input.lineSequence())
	}

	fun invoke(path: Path): CrescentIR {
		return try {
			path.bufferedReader().use {
				invoke(it.lineSequence())
			}
		} catch (exception: IllegalArgumentException) {
			throw IllegalArgumentException("$path: ${exception.message}", exception)
		}
	}

	fun invoke(lineSequence: Sequence<String>): CrescentIR {
		val commands = lineSequence.withIndex().flatMap { (index, sourceLine) ->
			val lineNumber = index + 1
			val line = sourceLine.trim()
			if (line.isEmpty() || line.startsWith("#")) return@flatMap emptySequence()

			val parts = line.split(Regex("\\s+"), limit = 2)
			val command = parts[0]
			val argument = parts.getOrNull(1)

			fun noArgument(value: CrescentIR.Command): Sequence<CrescentIR.Command> {
				require(argument == null) { "Line $lineNumber: '$command' does not accept an argument" }
				return sequenceOf(value)
			}

			fun requiredArgument(): String = requireNotNull(argument?.takeIf(String::isNotBlank)) {
				"Line $lineNumber: '$command' requires an argument"
			}

			fun symbolArgument(): String = requiredArgument().also {
				require(it.none(Char::isWhitespace)) {
					"Line $lineNumber: '$command' requires a single name"
				}
			}

			fun position(): Int = requiredArgument().toIntOrNull()
				?: throw IllegalArgumentException("Line $lineNumber: '$command' requires an integer position")

			try {
				when (command) {
				"addAssign" -> argument?.let {
					sequenceOf(CrescentIR.Command.Push(symbolArgument()), CrescentIR.Command.AddAssign)
				} ?: sequenceOf(CrescentIR.Command.AddAssign)
				"isLesser" -> noArgument(CrescentIR.Command.IsLesser)
				"isGreater" -> noArgument(CrescentIR.Command.IsGreater)
				"isLesserOrEqual" -> noArgument(CrescentIR.Command.IsLesserOrEqual)
				"isGreaterOrEqual" -> noArgument(CrescentIR.Command.IsGreaterOrEqual)
				"isEqual" -> noArgument(CrescentIR.Command.IsEqual)
				"isNotEqual" -> noArgument(CrescentIR.Command.IsNotEqual)
				"andCompare" -> noArgument(CrescentIR.Command.AndCompare)
				"orCompare" -> noArgument(CrescentIR.Command.OrCompare)
				"add" -> noArgument(CrescentIR.Command.Add)
				"sub" -> noArgument(CrescentIR.Command.Sub)
				"div" -> noArgument(CrescentIR.Command.Div)
				"mul" -> noArgument(CrescentIR.Command.Mul)
				"rem" -> noArgument(CrescentIR.Command.Rem)
				"or" -> noArgument(CrescentIR.Command.Or)
				"xor" -> noArgument(CrescentIR.Command.Xor)
				"and" -> noArgument(CrescentIR.Command.And)
				"shl" -> noArgument(CrescentIR.Command.ShiftLeft)
				"shr" -> noArgument(CrescentIR.Command.ShiftRight)
				"ushr" -> noArgument(CrescentIR.Command.UnsignedShiftRight)
				"fun" -> sequenceOf(CrescentIR.Command.Fun(symbolArgument()))
				"struct" -> sequenceOf(CrescentIR.Command.Struct(symbolArgument()))
				"push" -> sequenceOf(CrescentIR.Command.Push(argument?.asLegacyValue(lineNumber) ?: ""))
				"pushName" -> sequenceOf(CrescentIR.Command.PushNamedValue(symbolArgument()))
				"jump" -> sequenceOf(CrescentIR.Command.Jump(position()))
				"jumpIf" -> sequenceOf(CrescentIR.Command.JumpIf(position()))
				"jumpIfFalse" -> sequenceOf(CrescentIR.Command.JumpIfFalse(position()))
				"loadLibrary" -> sequenceOf(CrescentIR.Command.LoadLibrary(symbolArgument()))
				"invoke" -> sequenceOf(CrescentIR.Command.Invoke(symbolArgument()))
				"createInstance" -> sequenceOf(CrescentIR.Command.CreateInstance(symbolArgument()))
				"assign" -> argument?.let {
					sequenceOf(CrescentIR.Command.Push(symbolArgument()), CrescentIR.Command.Assign)
				} ?: sequenceOf(CrescentIR.Command.Assign)
				else -> throw IllegalArgumentException("Line $lineNumber: unexpected command '$command'")
				}
			} catch (exception: IllegalArgumentException) {
				if (exception.message?.startsWith("Line ") == true) throw exception
				throw IllegalArgumentException("Line $lineNumber: ${exception.message}", exception)
			}
		}.toList()

		return CrescentIR(commands)
	}

	private fun String.asTyped(): Any {
		return toBooleanStrictOrNull()
			?: toLongOrNull()?.minimize()
			?: toDoubleOrNull()?.minimize()
			?: this
	}

	private fun String.asLegacyValue(lineNumber: Int): Any {
		if (startsWith('"')) return decodeLegacyQuoted(lineNumber, '"', "string")
		if (startsWith('\'')) {
			val decoded = decodeLegacyQuoted(lineNumber, '\'', "character")
			require(decoded.length == 1) {
				"Line $lineNumber: quoted character push value must contain exactly one character"
			}
			return decoded.single()
		}
		return asTyped()
	}

	private fun String.decodeLegacyQuoted(lineNumber: Int, delimiter: Char, kind: String): String {
		require(length >= 2 && endsWith(delimiter)) {
			"Line $lineNumber: unterminated quoted $kind push value"
		}

		return buildString(length - 2) {
			var index = 1
			while (index < this@decodeLegacyQuoted.lastIndex) {
				val character = this@decodeLegacyQuoted[index++]
				require(character != delimiter) {
					"Line $lineNumber: unescaped quote in $kind push value"
				}
				if (character != '\\') {
					append(character)
					continue
				}

				require(index < this@decodeLegacyQuoted.lastIndex) {
					"Line $lineNumber: incomplete escape in $kind push value"
				}
				when (val escaped = this@decodeLegacyQuoted[index++]) {
					'\\' -> append('\\')
					'"' -> append('"')
					'\'' -> append('\'')
					'n' -> append('\n')
					'r' -> append('\r')
					't' -> append('\t')
					'b' -> append('\b')
					'u' -> {
						require(index + 4 <= this@decodeLegacyQuoted.lastIndex) {
							"Line $lineNumber: incomplete Unicode escape in push value"
						}
						val hex = this@decodeLegacyQuoted.substring(index, index + 4)
						val code = hex.toIntOrNull(16)
							?: throw IllegalArgumentException("Line $lineNumber: invalid Unicode escape '\\u$hex'")
						append(code.toChar())
						index += 4
					}
					else -> throw IllegalArgumentException(
						"Line $lineNumber: unsupported escape '\\$escaped' in push value",
					)
				}
			}
		}
	}
}
