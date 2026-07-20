package dev.twelveoclock.lang.crescent.language.ir

import java.util.Collections

/**
 * Named section view of serialized legacy IR. The legacy format has no source
 * locations, so execution diagnostics identify a function and command index.
 */
@JvmInline
value class SectionedCrescentIR(val sections: Map<Section, Map<String, List<CrescentIR.Command>>>) {

	companion object {

		fun from(crescentIR: CrescentIR): SectionedCrescentIR {
			val programCommands = crescentIR.commands.filterIsInstance<CrescentIR.Command.Program>()
			if (programCommands.isNotEmpty()) {
				require(crescentIR.commands.size == 1) {
					"A lowered Program command cannot be mixed with legacy serialized stack IR commands"
				}
				val program = programCommands.single()
				// CrescentProgramIR validates source uniqueness, deterministic ordering,
				// and that the exact main FunctionRef is declared by one source unit.
				program.program
				return SectionedCrescentIR(emptyMap())
			}

			var lastSection: Section? = null
			var lastName: String? = null

			var sectionCommands = mutableListOf<CrescentIR.Command>()
			val sections = mutableMapOf<Section, MutableMap<String, List<CrescentIR.Command>>>()
			fun flushSection() {
				val section = lastSection ?: return
				val name = requireNotNull(lastName)
				val namedSections = sections.getOrPut(section) { mutableMapOf() }
				require(name !in namedSections) {
					"Duplicate ${section.name.lowercase()} section '$name'"
				}
				namedSections[name] = sectionCommands.toList()
				sectionCommands = mutableListOf()
			}

			crescentIR.commands.forEach {
				when (it) {

					is CrescentIR.Command.Fun -> {
						require(it.name.isNotBlank()) { "Function section names cannot be blank" }

						flushSection()

						lastSection = Section.FUNCTION
						lastName = it.name
					}

					is CrescentIR.Command.Struct -> {
						require(it.name.isNotBlank()) { "Struct section names cannot be blank" }

						flushSection()

						lastSection = Section.STRUCT
						lastName = it.name
					}

					else -> {
						require(lastSection != null) {
							"Legacy IR command '$it' appears before a function or struct section"
						}
						sectionCommands.add(it)
					}
				}
			}

			flushSection()

			val frozen = sections.entries.associate { (section, namedSections) ->
				section to Collections.unmodifiableMap(namedSections.entries.associate { (name, commands) ->
					name to Collections.unmodifiableList(commands.toList())
				})
			}
			return SectionedCrescentIR(Collections.unmodifiableMap(frozen))
		}

	}

	enum class Section {
		STRUCT,
		FUNCTION,
	}

}

@JvmInline
value class CrescentIR(val commands: List<Command>) {

	sealed interface Command {

		/**
		 * A linked, AST-free lowered Crescent program. Stack commands remain supported
		 * solely as the serialized legacy IR format.
		 */
		data class Program(
			val program: CrescentProgramIR,
		) : Command

		object AddAssign : Command {
			override fun toString(): String {
				return "addAssign"
			}
		}

		object IsLesser : Command {
			override fun toString(): String {
				return "isLesser"
			}
		}

		object IsGreater : Command {
			override fun toString(): String {
				return "isGreater"
			}
		}


		object IsLesserOrEqual : Command {
			override fun toString(): String {
				return "isLesserOrEqual"
			}
		}

		object IsGreaterOrEqual : Command {
			override fun toString(): String {
				return "isGreaterOrEqual"
			}
		}

		object IsEqual : Command {
			override fun toString(): String {
				return "isEqual"
			}
		}

		object IsNotEqual : Command {
			override fun toString(): String {
				return "isNotEqual"
			}
		}

		object AndCompare : Command {
			override fun toString(): String {
				return "andCompare"
			}
		}

		object OrCompare : Command {
			override fun toString(): String {
				return "orCompare"
			}
		}

		object Add : Command {
			override fun toString(): String {
				return "add"
			}
		}

		object Sub : Command {
			override fun toString(): String {
				return "sub"
			}
		}

		object Div : Command {
			override fun toString(): String {
				return "div"
			}
		}

		object Mul : Command {
			override fun toString(): String {
				return "mul"
			}
		}

		object Rem : Command {
			override fun toString(): String {
				return "rem"
			}
		}

		object Or : Command {
			override fun toString(): String {
				return "or"
			}
		}

		object Xor : Command {
			override fun toString(): String {
				return "xor"
			}
		}

		object And : Command {
			override fun toString(): String {
				return "and"
			}
		}

		object ShiftLeft : Command {
			override fun toString(): String {
				return "shl"
			}
		}

		object ShiftRight : Command {
			override fun toString(): String {
				return "shr"
			}
		}

		object UnsignedShiftRight : Command {
			override fun toString(): String {
				return "ushr"
			}
		}

		/** Pops a variable name, then its new value, from the legacy stack. */
		object Assign : Command {
			override fun toString(): String {
				return "assign"
			}
		}


		@JvmInline
		value class Fun(
			val name: String,
		) : Command {
			override fun toString(): String {
				return "fun $name"
			}
		}

		@JvmInline
		value class Struct(
			val name: String,
		) : Command {
			override fun toString(): String {
				return "struct $name"
			}
		}

		/**
		 * Pushes a legacy literal. Serialization supports strings, characters,
		 * booleans, signed JVM integer types, and finite [Float]/[Double] values.
		 * Numeric wrapper types are canonicalized to the smallest lossless type by
		 * the parser; unsupported values fail explicitly when serialized.
		 */
		@JvmInline
		value class Push(
			val value: Any,
		) : Command {
			override fun toString(): String {
				val literal = when (value) {
					is String -> "\"${value.toLegacyEscaped()}\""
					is Char -> "'${value.toString().toLegacyEscaped(escapeSingleQuote = true)}'"
					is Boolean,
					is Byte,
					is Short,
					is Int,
					is Long,
					-> value.toString()
					is Float -> value.takeIf(Float::isFinite)?.toString()
					is Double -> value.takeIf(Double::isFinite)?.toString()
					else -> null
				} ?: throw IllegalArgumentException(
					"Legacy Push cannot serialize a value of type ${value::class.qualifiedName}",
				)
				return "push $literal"
			}
		}

		@JvmInline
		value class PushNamedValue(
			val name: String,
		) : Command {
			override fun toString(): String {
				return "pushName $name"
			}
		}

		@JvmInline
		value class Jump(
			val position: Int,
		) : Command {
			override fun toString(): String {
				return "jump $position"
			}
		}

		@JvmInline
		value class JumpIf(
			val position: Int,
		) : Command {
			override fun toString(): String {
				return "jumpIf $position"
			}
		}

		@JvmInline
		value class JumpIfFalse(
			val position: Int,
		) : Command {
			override fun toString(): String {
				return "jumpIfFalse $position"
			}
		}

		/** Retained for serialized compatibility; the legacy VM reports it as unsupported. */
		@JvmInline
		value class LoadLibrary(
			val name: String,
		) : Command {
			override fun toString(): String {
				return "loadLibrary $name"
			}
		}

		/** Invokes a builtin or named function using the shared legacy stack. */
		@JvmInline
		value class Invoke(
			val name: String,
		) : Command {
			override fun toString(): String {
				return "invoke $name"
			}
		}

		/** Retained for serialized compatibility; the legacy VM reports it as unsupported. */
		@JvmInline
		value class CreateInstance(
			val structName: String,
		) : Command {
			override fun toString(): String {
				return "createInstance $structName"
			}
		}

	}

}

private fun String.toLegacyEscaped(escapeSingleQuote: Boolean = false): String = buildString(length) {
	for (character in this@toLegacyEscaped) {
		when (character) {
			'\\' -> append("\\\\")
			'"' -> append("\\\"")
			'\'' -> if (escapeSingleQuote) append("\\'") else append(character)
			'\n' -> append("\\n")
			'\r' -> append("\\r")
			'\t' -> append("\\t")
			'\b' -> append("\\b")
			else -> if (character.code in 0x00..0x1f || character.code in 0x7f..0x9f) {
				append("\\u")
				append(character.code.toString(16).padStart(4, '0'))
			} else {
				append(character)
			}
		}
	}
}
