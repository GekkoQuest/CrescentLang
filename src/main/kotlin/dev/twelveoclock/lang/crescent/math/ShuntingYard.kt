package dev.twelveoclock.lang.crescent.math

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST
import dev.twelveoclock.lang.crescent.language.token.CrescentToken

object ShuntingYard {

	fun invoke(input: List<CrescentAST.Node>): List<CrescentAST.Node> {
		val output = ArrayList<CrescentAST.Node>(input.size)
		val operators = ArrayDeque<OperatorEntry>()
		var expectingOperand = true

		for (node in input) {
			when (node) {
				is CrescentAST.Node.Expression -> {
					if (!expectingOperand) throw IllegalArgumentException("Expression is missing an operator before $node")
					output.addAll(node.nodes)
					expectingOperand = false
				}
				is CrescentToken.Operator -> {
					if (node in postfixOperators) {
						if (expectingOperand) throw IllegalArgumentException("Operator '$node' is missing an operand")
						output += node
						expectingOperand = false
						continue
					}
					val isPrefix = expectingOperand && node in prefixOperators
					if (expectingOperand && !isPrefix) {
						throw IllegalArgumentException("Operator '$node' is missing a left operand")
					}
					if (!expectingOperand && node == CrescentToken.Operator.NOT) {
						throw IllegalArgumentException("Operator '$node' must precede its operand")
					}
					val incoming = OperatorEntry(
						operator = node,
						unary = isPrefix && node == CrescentToken.Operator.SUB,
						operandStart = output.size,
					)
					while (operators.isNotEmpty() && shouldPop(operators.last(), incoming)) {
						val popped = operators.removeLast()
						popOperator(popped, output)
					}
					operators.addLast(incoming)
					expectingOperand = true
				}
				else -> {
					if (!expectingOperand) throw IllegalArgumentException("Operand '$node' is missing an operator")
					output += node
					expectingOperand = false
				}
			}
		}
		if (input.isNotEmpty() && expectingOperand) {
			throw IllegalArgumentException("Expression ends with an operator")
		}

		while (operators.isNotEmpty()) {
			val popped = operators.removeLast()
			popOperator(popped, output)
		}
		return output
	}

	private data class OperatorEntry(
		val operator: CrescentToken.Operator,
		val unary: Boolean,
		val operandStart: Int,
	)

	private fun popOperator(entry: OperatorEntry, output: MutableList<CrescentAST.Node>) {
		if (!entry.unary) {
			output += entry.operator
			return
		}

		if (output.size <= entry.operandStart) {
			throw IllegalArgumentException("Unary '${entry.operator}' is missing an operand")
		}
		output.add(entry.operandStart, CrescentAST.Node.Primitive.Number.I8(0))
		output += entry.operator
	}

	private fun shouldPop(stacked: OperatorEntry, incoming: OperatorEntry): Boolean {
		val stackedPrecedence = if (stacked.unary) 0 else precedence(stacked.operator)
		val incomingPrecedence = if (incoming.unary) 0 else precedence(incoming.operator)
		return stackedPrecedence < incomingPrecedence ||
			stackedPrecedence == incomingPrecedence && (incoming.unary || incoming.operator in rightAssociative).not()
	}

	// Lower values bind more tightly.
	fun precedence(operator: CrescentToken.Operator): Int = when (operator) {
		CrescentToken.Operator.NOT -> 0
		CrescentToken.Operator.POW -> 1
		CrescentToken.Operator.MUL,
		CrescentToken.Operator.DIV,
		CrescentToken.Operator.REM -> 2
		CrescentToken.Operator.ADD,
		CrescentToken.Operator.SUB -> 3
		CrescentToken.Operator.BIT_SHIFT_LEFT,
		CrescentToken.Operator.BIT_SHIFT_RIGHT,
		CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT -> 4
		CrescentToken.Operator.BIT_AND -> 5
		CrescentToken.Operator.BIT_XOR -> 6
		CrescentToken.Operator.BIT_OR -> 7
		CrescentToken.Operator.AS -> 8
		CrescentToken.Operator.RANGE_TO -> 9
		CrescentToken.Operator.INSTANCE_OF,
		CrescentToken.Operator.NOT_INSTANCE_OF,
		CrescentToken.Operator.CONTAINS,
		CrescentToken.Operator.NOT_CONTAINS,
		CrescentToken.Operator.LESSER_EQUALS_COMPARE,
		CrescentToken.Operator.LESSER_COMPARE,
		CrescentToken.Operator.GREATER_EQUALS_COMPARE,
		CrescentToken.Operator.GREATER_COMPARE,
		CrescentToken.Operator.EQUALS_COMPARE,
		CrescentToken.Operator.NOT_EQUALS_COMPARE,
		CrescentToken.Operator.EQUALS_REFERENCE_COMPARE,
		CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE -> 10
		CrescentToken.Operator.AND_COMPARE -> 11
		CrescentToken.Operator.OR_COMPARE -> 12
		CrescentToken.Operator.ASSIGN,
		CrescentToken.Operator.ADD_ASSIGN,
		CrescentToken.Operator.SUB_ASSIGN,
		CrescentToken.Operator.MUL_ASSIGN,
		CrescentToken.Operator.DIV_ASSIGN,
		CrescentToken.Operator.REM_ASSIGN,
		CrescentToken.Operator.POW_ASSIGN -> 13
		else -> 14
	}

	private val rightAssociative = setOf(
		CrescentToken.Operator.NOT,
		CrescentToken.Operator.POW,
		CrescentToken.Operator.ASSIGN,
		CrescentToken.Operator.ADD_ASSIGN,
		CrescentToken.Operator.SUB_ASSIGN,
		CrescentToken.Operator.MUL_ASSIGN,
		CrescentToken.Operator.DIV_ASSIGN,
		CrescentToken.Operator.REM_ASSIGN,
		CrescentToken.Operator.POW_ASSIGN,
	)

	private val prefixOperators = setOf(
		CrescentToken.Operator.NOT,
		CrescentToken.Operator.SUB,
	)

	private val postfixOperators = setOf(
		CrescentToken.Operator.RESULT,
	)
}
