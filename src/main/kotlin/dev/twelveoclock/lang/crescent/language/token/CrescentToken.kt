package dev.twelveoclock.lang.crescent.language.token

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST

// The legacy token API does not yet carry source spans; lexer/parser diagnostics track offsets separately.
interface CrescentToken {

	// Used only by the token iterator
	object None : CrescentToken

	@JvmInline
	value class Key(
		val string: String
	) : CrescentToken {

		override fun toString(): String {
			return string
		}

	}

	interface Data : CrescentToken {

		@JvmInline
		value class Number(
			val number: kotlin.Number
		) : Data {

			override fun toString(): kotlin.String {
				return "Number(${number} ${number::class.simpleName})"
			}
		}

		@JvmInline
		value class String(
			val kotlinString: kotlin.String
		) : Data

		@JvmInline
		value class Boolean(
			val kotlinBoolean: kotlin.Boolean
		) : Data

		@JvmInline
		value class Char(
			val kotlinChar: kotlin.Char
		) : Data

		@JvmInline
		value class Comment(
			val string: kotlin.String
		) : Data

	}

	enum class Parenthesis : CrescentToken {

		OPEN,
		CLOSE,
		;

		override fun toString(): String {
			return if (this == OPEN) "(" else ")"
		}
	}

	enum class Bracket : CrescentToken {

		OPEN,
		CLOSE,
		;

		override fun toString(): String {
			return if (this == OPEN) "{" else "}"
		}
	}

	enum class SquareBracket : CrescentToken {

		OPEN,
		CLOSE,
		;

		override fun toString(): String {
			return if (this == OPEN) "[" else "]"
		}
	}

	enum class Variable : CrescentToken {
		VAL,
		VAR,
		CONST,
	}

	enum class Type : CrescentToken {
		STRUCT,
		IMPL,
		TRAIT,
		OBJECT,
		ENUM,
		SEALED,
	}

	enum class Statement : CrescentToken {
		IMPORT,
		WHILE,
		WHEN,
		FOR,
		IF,
		FUN,
		ELSE,
	}

	enum class Visibility : CrescentToken {
		PRIVATE,
		INTERNAL,
		PUBLIC,
	}

	enum class Modifier : CrescentToken {
		ASYNC,
		OVERRIDE,
		OPERATOR,
		INLINE,
		STATIC,
		INFIX,
	}


	enum class Keyword(val literal: String) : CrescentToken, CrescentAST.Node {
		BREAK("break"),
		CONTINUE("continue"),
	}

	// Expression precedence and associativity are centralized in ShuntingYard.
	enum class Operator(val literal: String) : CrescentToken, CrescentAST.Node {
		NOT("!"),
		ADD("+"),
		SUB("-"),
		MUL("*"),
		DIV("/"),
		POW("^"),
		REM("%"),
		ASSIGN("="),
		ADD_ASSIGN("+="),
		SUB_ASSIGN("-="),
		MUL_ASSIGN("*="),
		DIV_ASSIGN("/="),
		REM_ASSIGN("%="),
		POW_ASSIGN("^="),
		OR_COMPARE("||"),
		AND_COMPARE("&&"),
		EQUALS_COMPARE("=="),
		LESSER_COMPARE("<"),
		GREATER_COMPARE(">"),
		LESSER_EQUALS_COMPARE("<="),
		GREATER_EQUALS_COMPARE(">="),
		BIT_SHIFT_RIGHT("shr"),
		BIT_SHIFT_LEFT("shl"),
		UNSIGNED_BIT_SHIFT_RIGHT("ushr"),
		BIT_OR("or"),
		BIT_XOR("xor"),
		BIT_AND("and"),
		EQUALS_REFERENCE_COMPARE("==="),
		NOT_EQUALS_COMPARE("!="),
		NOT_EQUALS_REFERENCE_COMPARE("!=="),
		CONTAINS("in"),
		NOT_CONTAINS("!in"),
		RANGE_TO(".."),
		TYPE_PREFIX(":"),
		RETURN("->"),
		RESULT("?"),
		COMMA(","),
		DOT("."),
		AS("as"),
		IMPORT_SEPARATOR("::"),
		INSTANCE_OF("is"),
		NOT_INSTANCE_OF("!is"),
	}

}
