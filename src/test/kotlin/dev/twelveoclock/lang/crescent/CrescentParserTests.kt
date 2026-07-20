package dev.twelveoclock.lang.crescent

import dev.twelveoclock.lang.crescent.data.TestCode
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.*
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Enum
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Function
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.Char
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.Number.I16
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.Number.I8
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive.String
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Statement.When
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.language.token.CrescentToken.Operator.*
import dev.twelveoclock.lang.crescent.language.token.CrescentToken.Visibility
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class CrescentParserTests {

	@Test
	fun dynamicRangesAndPrefixOperatorsExecuteFromSource() {
		val source = """
			fun main {
				val start = 1
				val end = 3
				for i in start..end { print(i) }
				println()
				for i in (start + 1)..(end + 1) { print(i) }
				println()
				println(-2)
				println(1 * -2)
				println(1 * (-2))
				println(-(1 + 2))
				println(--2)
				val flag = false
				println(!flag)
				println("a" !in "bc")
				println("a" !is String)
			}
		""".trimIndent()
		val file = CrescentParser.invoke(Path.of("operators.crescent"), CrescentLexer.invoke(source))
		assertEquals(
			"123\n234\n-2\n-2\n-2\n-3\n2\ntrue\ntrue\nfalse\n",
			collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } },
		)
	}

    @Test
    fun helloWorld() {

        val tokens = CrescentLexer.invoke(TestCode.helloWorlds)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(
                IdentifierCall("println", listOf(String("Hello World"))),
                IdentifierCall("println", listOf(String("Hello World"))),
                IdentifierCall("println", listOf(String("Hello World"))),
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun argsHelloWorld() {
        val file = CrescentParser.invoke(
            Path.of("example.crescent"),
            CrescentLexer.invoke(TestCode.argsHelloWorld),
        )
        val main = assertNotNull(file.mainFunction)

        assertEquals(Parameter.Basic("args", Type.Array(Type.Basic("String"))), main.params.single())
        assertContentEquals(
            listOf(IdentifierCall("println", listOf(GetCall("args", listOf(I8(0)))))),
            main.innerCode.nodes,
        )
    }

    @Test
    fun funThing() {
		val file = CrescentParser.invoke(Path.of("example.crescent"), CrescentLexer.invoke(TestCode.funThing))
		assertNotNull(file.mainFunction)
		assertContentEquals((1..8).map { "funThing$it" }, file.functions.keys.filter { it != "main" })
    }

	@Test
	fun expressionStatementStartingWithCallIsParsedAsOneExpression() {
		val file = CrescentParser.invoke(Path.of("example.crescent"), CrescentLexer.invoke("fun main { foo() + bar }"))
		val main = assertNotNull(file.mainFunction)

		assertContentEquals(
			listOf(Expression(listOf(IdentifierCall("foo"), Identifier("bar"), ADD))),
			main.innerCode.nodes,
		)
	}

	@Test
	fun statementBoundariesPreventAccidentalInfixCapture() {
		val file = CrescentParser.invoke(
			Path.of("statement-boundary.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						val values = [1]
						val other = values
						println(other[0])
					}
				""".trimIndent(),
			),
		)
		val nodes = assertNotNull(file.mainFunction).innerCode.nodes
		assertEquals(3, nodes.size)
		assertEquals(Identifier("values"), (nodes[1] as Variable.Basic).value)
		assertEquals(IdentifierCall("println", listOf(GetCall("other", listOf(I8(0))))), nodes[2])
	}

	@Test
	fun callsGetsAndGroupedExpressionsAreInfixReceiversOnlyOnTheSameLogicalLine() {
		val file = CrescentParser.invoke(
			Path.of("infix-receivers.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						make() matches other
						values[0] matches other
						(left + right) matches other
						make()
						next()
					}
				""".trimIndent(),
			),
		)
		val nodes = assertNotNull(file.mainFunction).innerCode.nodes
		assertEquals(InfixCall(IdentifierCall("make"), "matches", Identifier("other")), nodes[0])
		assertEquals(InfixCall(GetCall("values", listOf(I8(0))), "matches", Identifier("other")), nodes[1])
		assertEquals(
			InfixCall(Expression(listOf(Identifier("left"), Identifier("right"), ADD)), "matches", Identifier("other")),
			nodes[2],
		)
		assertEquals(IdentifierCall("make"), nodes[3])
		assertEquals(IdentifierCall("next"), nodes[4])
	}

	@Test
	fun whenPredicatesAndGroupedPostfixReceiversParseCompletely() {
		val file = CrescentParser.invoke(
			Path.of("primary-chains.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						when(2) { 1 + 1 -> {} else -> {} }
						println((Box()).value)
						println((Box()).items[0])
						val combined = (Box()).value combine 2
					}
				""".trimIndent(),
			),
		)
		val nodes = assertNotNull(file.mainFunction).innerCode.nodes
		val statement = nodes[0] as When
		assertEquals(Expression(listOf(I8(1), I8(1), ADD)), statement.predicateToBlock[0].ifExpressionNode)
		assertEquals(
			IdentifierCall("println", listOf(DotChain(listOf(IdentifierCall("Box"), Identifier("value"))))),
			nodes[1],
		)
		assertEquals(
			IdentifierCall("println", listOf(DotChain(listOf(IdentifierCall("Box"), GetCall("items", listOf(I8(0))))))),
			nodes[2],
		)
		assertEquals(
			InfixCall(DotChain(listOf(IdentifierCall("Box"), Identifier("value"))), "combine", I8(2)),
			(nodes[3] as Variable.Basic).value,
		)
		val expression = (statement.predicateToBlock[0].ifExpressionNode as Expression).nodes
		assertTrue(DOT !in expression)
	}

	@Test
	fun interpolationPreservesEscapeProvenanceAndConsumesItsWholeBody() {
		val file = CrescentParser.invoke(
			Path.of("interpolation-escapes.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						println("\\${'$'}name")
						println("\${'$'}name")
					}
				""".trimIndent(),
			),
		)
		val nodes = assertNotNull(file.mainFunction).innerCode.nodes
		assertEquals(
			IdentifierCall("println", listOf(Expression(listOf(String("\\"), Identifier("name"), ADD)))),
			nodes[0],
		)
		assertEquals(IdentifierCall("println", listOf(String("${'$'}name"))), nodes[1])

		val failure = assertFailsWith<IllegalArgumentException> {
			CrescentParser.invoke(
				Path.of("interpolation-trailing.crescent"),
				CrescentLexer.invoke("fun main { println(\"${'$'}{1 2}\") }"),
			)
		}
		assertTrue(failure.message.orEmpty().contains("String interpolation must contain exactly one complete expression"))
	}

	@Test
	fun rootWildcardImportsAndDeclarationSeparatorsAreValidated() {
		val file = CrescentParser.invoke(Path.of("root-wildcard.crescent"), CrescentLexer.invoke("import ::*"))
		assertEquals(Import("", "*", null), file.imports.single())

		mapOf(
			"import ::* as everything" to "Wildcard imports cannot have an alias",
			"val value,, other = 1" to "Expected variable name after ','",
			"val value, = 1" to "Expected variable name after ','",
			"impl Box { public {} }" to "Visibility/modifier is not followed by a declaration in impl",
			"impl Box { async {} }" to "Visibility/modifier is not followed by a declaration in impl",
		).forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid-adversarial.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}

		val adjacent = CrescentParser.invoke(
			Path.of("grouped-names.crescent"),
			CrescentLexer.invoke("val first second, third = 1"),
		)
		assertContentEquals(listOf("first", "second", "third"), adjacent.variables.keys)
	}

	@Test
	fun trailingDollarInStringIsLiteral() {
		val file = CrescentParser.invoke(Path.of("example.crescent"), CrescentLexer.invoke("fun main { println(\"cost \$\") }"))
		val main = assertNotNull(file.mainFunction)
		assertContentEquals(listOf(IdentifierCall("println", listOf(String("cost \$")))), main.innerCode.nodes)
	}

	@Test
	fun interpolationIgnoresBracesInsideQuotedLiterals() {
		val source = "fun main { println(\"\${\\\"}\\\"}\") }"
		val file = CrescentParser.invoke(Path.of("example.crescent"), CrescentLexer.invoke(source))
		val main = assertNotNull(file.mainFunction)
		assertContentEquals(listOf(IdentifierCall("println", listOf(Expression(listOf(String(""), String("}"), ADD))))), main.innerCode.nodes)
	}

	@Test
	fun astArraysUseStructuralEqualityAndConstantsNameTheirDeclaredType() {
		assertEquals(Array(arrayOf(I8(1))), Array(arrayOf(I8(1))))
		assertEquals(
			"const answer: Basic(I32) = 42",
			Variable.Constant("answer", Type.Basic("I32"), I8(42), Visibility.PUBLIC).toString(),
		)
	}

	@Test
	fun duplicateAndIgnoredDeclarationsFailContextually() {
		val cases = mapOf(
			"fun same {} fun same {}" to "Duplicate function 'same'",
			"struct Same() object Same" to "Duplicate type 'Same'",
			"val value = 1 const value = 2" to "Duplicate global 'value'",
			"object Box { val value = 1 fun value {} }" to "Duplicate member 'value'",
			"struct Pair(val value: I32, val value: I32)" to "Duplicate field 'value'",
			"impl Thing { fun same {} fun same {} }" to "Duplicate function 'same' in impl",
			"enum Choice { A A }" to "Duplicate enum entry 'A'",
			"sealed Family { fun invalid {} }" to "inside sealed 'Family'",
			"fun main {} fun main {}" to "Duplicate main function",
			"fun main {} private" to "not followed by a declaration",
			"object Box { private }" to "not followed by an object member",
		)

		cases.forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("token index"), failure.message)
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}
	}

	@Test
	fun malformedOperatorPositionsFailDuringParsing() {
		mapOf(
			"fun main { println(1 +) }" to "ends with an operator",
			"fun main { println(true !) }" to "must precede its operand",
			"fun main { println(+ 1) }" to "missing a left operand",
		).forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid-expression.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("token index"), failure.message)
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}
	}

	@Test
	fun declarationPrefixesAreExplicitAndUnambiguous() {
		mapOf(
			"public public fun main {}" to "Multiple visibility modifiers",
			"public private fun main {}" to "Multiple visibility modifiers",
			"async async fun main {}" to "Duplicate modifier",
			"object Box { public public val value = 1 }" to "Multiple visibility modifiers",
			"object Box { async async fun work {} }" to "Duplicate modifier",
			"impl public Box {}" to "Visibility is not supported before an impl target",
			"impl async Box {}" to "Only the static modifier is supported before an impl target",
			"impl static static Box {}" to "Duplicate modifier",
			"impl Box { public public fun work {} }" to "Multiple visibility modifiers",
			"impl Box { async async fun work {} }" to "Duplicate modifier",
			"struct Broken(val value: I32" to "Unterminated struct 'Broken'",
			"fun duplicate(value value: I32) {}" to "Duplicate parameter 'value'",
			"fun duplicate(value: I32, value: I32) {}" to "Duplicate parameter 'value'",
			"trait Named { fun name(value: I32, value: I32) -> String }" to "Duplicate parameter 'value'",
			"enum Choice(value: I32, value: I32) { ONE(1, 2) }" to "Duplicate parameter 'value'",
		).forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid-prefix.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("token index"), failure.message)
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}

		val valid = CrescentParser.invoke(
			Path.of("valid-prefix.crescent"),
			CrescentLexer.invoke(
				"public async inline fun work {} object Box { private async fun work {} } impl static Box { async fun make {} }",
			),
		)
		assertContentEquals(listOf(CrescentToken.Modifier.ASYNC, CrescentToken.Modifier.INLINE), valid.functions.getValue("work").modifiers)
		assertEquals(Visibility.PUBLIC, valid.functions.getValue("work").visibility)
		assertContentEquals(listOf(CrescentToken.Modifier.ASYNC), valid.objects.getValue("Box").functions.getValue("work").modifiers)
		assertEquals(Visibility.PRIVATE, valid.objects.getValue("Box").functions.getValue("work").visibility)
		assertContentEquals(listOf(CrescentToken.Modifier.STATIC), valid.staticImpls.getValue("Box").modifiers)
		assertContentEquals(listOf(CrescentToken.Modifier.ASYNC), valid.staticImpls.getValue("Box").functions.single().modifiers)
	}

	@Test
	fun declarationsRequireInitializersExceptForStructFields() {
		mapOf(
			"val value" to "Variable declaration requires an initializer",
			"const value" to "Constant 'value' requires an initializer",
			"fun main { val value }" to "Variable declaration requires an initializer",
			"object Box { val value }" to "Variable 'value' requires an initializer",
			"object Box { const value }" to "Constant 'value' requires an initializer",
			"val value =" to "Expected expression",
			"const value =" to "Expected expression",
			"fun main { val value = }" to "Expected expression",
			"val value, value = 1" to "Duplicate variable name 'value'",
			"fun main { val value value = 1 }" to "Duplicate variable name 'value'",
			"struct Box(const value: I32)" to "Constants are not supported as struct fields",
			"fun main { when {} }" to "When expression requires a parenthesized subject",
			"fun main { when() {} }" to "When expression requires a nonempty subject",
			"val value: = 1" to "Expected type, got ASSIGN",
		).forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid-initializer.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("token index"), failure.message)
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}

		val file = CrescentParser.invoke(
			Path.of("struct-initializers.crescent"),
			CrescentLexer.invoke("struct Box(val required: I32, val optional: I32 = 2)"),
		)
		val fields = file.structs.getValue("Box").variables
		assertEquals(Expression(emptyList()), fields[0].value)
		assertEquals(I8(2), fields[1].value)

		val objectVariable = CrescentParser.invoke(
			Path.of("mutable-object.crescent"),
			CrescentLexer.invoke("object Counter { var value = 1 }"),
		).objects.getValue("Counter").variables.getValue("value")
		assertEquals(false, objectVariable.isFinal)

		val arrayFile = CrescentParser.invoke(
			Path.of("array-expressions.crescent"),
			CrescentLexer.invoke("fun main { val values = [1 + 2, [3 * 4]] }"),
		)
		assertNotNull(arrayFile.mainFunction)
	}

	@Test
	fun nestedTypesAndControlFlowNamesAreUnambiguous() {
		mapOf(
			"val values: [] = []" to "Expected type, got ]",
			"val values: [I32 = []" to "array type closing bracket",
			"fun main { for value, value in 0..1 {} }" to "Duplicate for-loop identifier 'value'",
			"fun main { when(1) { else -> {} else -> {} } }" to "Multiple else clauses",
			"fun main { when(1) { else -> {} 1 -> {} } }" to "When else clause must be last",
		).forEach { (source, expected) ->
			val failure = assertFailsWith<IllegalArgumentException>(source) {
				CrescentParser.invoke(Path.of("invalid-control-flow.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("token index"), failure.message)
			assertTrue(failure.message.orEmpty().contains(expected), failure.message)
		}

		val file = CrescentParser.invoke(
			Path.of("nested-types.crescent"),
			CrescentLexer.invoke(
				"val matrix: [[I32]] = [[1]] val optional: [I32]? = [1] " +
					"fun main { for x, y in 0..1, 2..3 {} when(1) { 1 -> {} else -> {} } }",
			),
		)
		assertEquals(Type.Array(Type.Array(Type.Basic("I32"))), file.variables.getValue("matrix").type)
		assertEquals(Type.Result(Type.Array(Type.Basic("I32"))), file.variables.getValue("optional").type)
		assertNotNull(file.mainFunction)
	}

	@Test
	fun malformedFunctionNameReportsTokenIndex() {
		val failure = assertFailsWith<IllegalArgumentException> {
			CrescentParser.invoke(Path.of("example.crescent"), CrescentLexer.invoke("fun 123 {}"))
		}
		assertTrue(failure.message.orEmpty().contains("token index 2"))
	}

    @Test
    fun ifStatement() {

        val tokens = CrescentLexer.invoke(TestCode.ifStatement)
        val parsed = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        val mainFunction = assertNotNull(
            parsed.mainFunction,
            "No main function found"
        )


        assertContentEquals(
            listOf(
                Statement.If(
                    predicate = Expression(listOf(
                        GetCall("args", listOf(I8(0))), String("true"), EQUALS_COMPARE
                    )),
                    block = Statement.Block(listOf(
                        IdentifierCall("println", listOf(String("Meow")))
                    )),
                    elseBlock = Statement.Block(listOf(
                        IdentifierCall("println", listOf(String("Hiss")))
                    )),
                ),
            ),
            parsed.functions["test1"]!!.innerCode.nodes,
        )

        assertContentEquals(
            listOf(
                Statement.If(
                    predicate = Expression(listOf(
                        GetCall("args", listOf(I8(0))), String("true"), EQUALS_COMPARE
                    )),
                    block = Statement.Block(listOf(
                        Return(String("Meow"))
                    )),
                    elseBlock = Statement.Block(listOf(
                        Return(String("Hiss"))
                    )),
                ),
                IdentifierCall("println", listOf(String("This shouldn't be printed")))
            ),
            parsed.functions["test2"]!!.innerCode.nodes,
        )


        assertContentEquals(
            listOf(Parameter.Basic("args", Type.Array(Type.Basic("String")))),
            mainFunction.params
        )

        assertContentEquals(
            listOf(
                IdentifierCall("test1", listOf(Identifier("args"))),
                IdentifierCall("println", listOf(IdentifierCall("test2", listOf(Identifier("args")))))
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun ifInputStatement() {

        val tokens = CrescentLexer.invoke(TestCode.ifInputStatement)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            emptyList(),
            mainFunction.params
        )

        assertContentEquals(
            listOf(
                Variable.Basic(
                    "input", Type.Implicit, IdentifierCall(
                        "readBoolean",
                        listOf(String("Enter a boolean value [true/false]"))
                    ), true, Visibility.PUBLIC
                ),
                Statement.If(
                    Identifier("input"),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(String("Meow")))
                    )),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(String("Hiss")))
                    )),
                ),
            ),
            mainFunction.innerCode.nodes,
        )
    }


    @Test
    fun stringInterpolation() {

        val tokens = CrescentLexer.invoke(TestCode.stringInterpolation)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(
                Variable.Basic("x", Type.Implicit, I8(0), true, Visibility.PUBLIC),
                Variable.Basic("y", Type.Implicit, I8(0), true, Visibility.PUBLIC),
                Variable.Basic("z", Type.Implicit, I8(0), true, Visibility.PUBLIC),

                IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD)))),
                IdentifierCall("println", listOf(Expression(listOf(String("Hello "), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD, String(" Hello"), ADD)))),
                IdentifierCall("println", listOf(Expression(listOf(String("Hello "), Identifier("x"), ADD, String(" Hello "), ADD, Identifier("y"), ADD, String(" Hello "), ADD, Identifier("z"), ADD, String(" Hello"), ADD)))),

                IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD)))),
                IdentifierCall("println", listOf(Expression(listOf(String("Hello "), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD, String(" Hello"), ADD)))),
                IdentifierCall("println", listOf(Expression(listOf(String("Hello "), Identifier("x"), ADD, String("Hello"), ADD, Identifier("y"), ADD, String("Hello"), ADD, Identifier("z"), ADD, String(" Hello"), ADD)))),

                IdentifierCall("println", listOf(Expression(listOf(String(""), Char('$'), ADD)))),
                IdentifierCall("println", listOf(String("\$x"))),

                IdentifierCall("println", listOf(String("$ x"))),
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun forLoop1() {

        val tokens = CrescentLexer.invoke(TestCode.forLoop1)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(

                Variable.Basic("x", Type.Implicit, I8(0), true, Visibility.PUBLIC),
                Variable.Basic("y", Type.Implicit, I8(0), true, Visibility.PUBLIC),
                Variable.Basic("z", Type.Implicit, I8(0), true, Visibility.PUBLIC),

				// Interpolation with operators remains an Expression argument in postfix order.
                IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD)))),

                Statement.For(
                    listOf(Identifier("x")),
                    listOf(Statement.Range(I8(0), I8(9))),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD)))),
                    ))
                ),

                Statement.For(
                    listOf(Identifier("x"), Identifier("y"), Identifier("z")),
                    listOf(Statement.Range(I8(0), I8(9))),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD)))),
                    ))
                ),
                Statement.For(
                    listOf(Identifier("x"), Identifier("y"), Identifier("z")),
                    listOf(Statement.Range(I8(0), I8(9)), Statement.Range(I8(0), I8(9)), Statement.Range(I8(0), I8(9))),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(Expression(listOf(String(""), Identifier("x"), ADD, Identifier("y"), ADD, Identifier("z"), ADD)))),
                    ))
                ),
                IdentifierCall("println", listOf(String("Hello World")))
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun whileLoop() {

        val tokens = CrescentLexer.invoke(TestCode.whileLoop)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(
                Variable.Basic("x", Type.Implicit, I8(1), false, Visibility.PUBLIC),
                Statement.While(
                    Expression(listOf(
                        Identifier("x"), I8(10), LESSER_EQUALS_COMPARE
                    )),
                    Statement.Block(listOf(
                        IdentifierCall("println", listOf(Identifier("x"))),
                        Expression(listOf(Identifier("x"), I8(1), ADD_ASSIGN)),
                    ))
                )

            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun calculator() {

        val tokens = CrescentLexer.invoke(TestCode.calculator)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            emptyList(),
            mainFunction.params
        )

        assertContentEquals(
            listOf(
                Variable.Basic(
                    "input1",
                    Type.Implicit,
                    IdentifierCall("readDouble", listOf(String("Enter your first number"))),
                    true,
                    Visibility.PUBLIC
                ),
                Variable.Basic(
                    "input2",
                    Type.Implicit,
                    IdentifierCall("readDouble", listOf(String("Enter your second number"))),
                    true,
                    Visibility.PUBLIC
                ),
                Variable.Basic(
                    "operation",
                    Type.Implicit,
                    IdentifierCall("readLine", listOf(String("Enter an operation [+, -, *, /]"))),
                    true,
                    Visibility.PUBLIC
                ),
                Variable.Basic(
                    "result", Type.Implicit,
                    When(
                        Identifier("operation"),
                        listOf(
                            When.Clause(
                                Char('+'),
                                Statement.Block(listOf(Expression(listOf(
                                    Identifier("input1"), Identifier("input2"), ADD
                                ))))
                            ),
                            When.Clause(
                                Char('-'),
                                Statement.Block(listOf(Expression(listOf(
                                    Identifier("input1"), Identifier("input2"), SUB
                                ))))
                            ),
                            When.Clause(
                                Char('*'),
                                Statement.Block(listOf(Expression(listOf(
                                    Identifier("input1"), Identifier("input2"), MUL
                                ))))
                            ),
                            When.Clause(
                                Char('/'),
                                Statement.Block(listOf(Expression(listOf(
                                    Identifier("input1"), Identifier("input2"), DIV
                                ))))
                            )
                        )
                    ),
                    true,
                    Visibility.PUBLIC,
                ),
                IdentifierCall("println", listOf(Identifier("result")))
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun constantsAndObject() {

        val tokens = CrescentLexer.invoke(TestCode.constantsAndObject)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        assertContentEquals(
            listOf(Variable.Constant("thing1", Type.Implicit, String("Mew"), Visibility.PUBLIC)),
            crescentFile.constants.values,
            "Variables not as expected"
        )

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        val constantsObject = assertNotNull(
            crescentFile.objects["Constants"],
            "Could not find Constants object"
        )

        assertContentEquals(
            listOf(
                Variable.Constant("thing2", Type.Implicit, String("Meow"), Visibility.PUBLIC),
            ),
            constantsObject.constants.values,
        )

        assertContentEquals(
            listOf(
                IdentifierCall("println", listOf(Identifier("thing1"))),
                IdentifierCall("println", listOf(Identifier("thing2")))
            ),
            constantsObject.functions["printThings"]!!.innerCode.nodes
        )
        assertContentEquals(
            listOf(
                DotChain(listOf(Identifier("Constants"), IdentifierCall("printThings"))),
                IdentifierCall("println", listOf(Identifier("thing1"))),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("Constants"), Identifier("thing2"))))),
            ),
            mainFunction.innerCode.nodes
        )
    }

    @Test
    fun impl() {

        val tokens = CrescentLexer.invoke(TestCode.impl)
        val parsed = CrescentParser.invoke(Path.of("example.crescent"), tokens)
        val mainFunction = assertNotNull(parsed.mainFunction, "No main function found")

        assertContentEquals(
            listOf(
                Struct("Example", listOf(
                    Variable.Basic("aNumber", Type.Basic("I32"), Expression(emptyList()), true, Visibility.PUBLIC),
                    Variable.Basic("aValue1", Type.Implicit, String(""), true, Visibility.PUBLIC),
                    Variable.Basic("aValue2", Type.Implicit, String(""), true, Visibility.PUBLIC),
                ))
            ),
            parsed.structs.values,
        )

        assertContentEquals(
            listOf(
                Variable.Basic(
                    "example",
                    Type.Implicit,
                    IdentifierCall("Example", listOf(I8(1), String("Meow"), String("Mew"))),
                    true,
                    Visibility.PUBLIC
                ),
                DotChain(listOf(Identifier("example"), IdentifierCall("printValues"))),
                IdentifierCall("println"),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("example"), Identifier("aNumber"))))),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("example"), Identifier("aValue1"))))),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("example"), Identifier("aValue2"))))),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("Example"), IdentifierCall("add", listOf(I8(1), I8(2))))))),
                IdentifierCall("println", listOf(DotChain(listOf(Identifier("Example"), IdentifierCall("sub", listOf(I8(1), I8(2))))))),
            ),
            mainFunction.innerCode.nodes,
        )

        assertContentEquals(
            listOf(
                Impl(
                    type = Type.Basic("Example"),
                    modifiers = emptyList(),
                    functions = listOf(
                        Function(
                            name = "printValues",
                            modifiers = emptyList(),
                            visibility = Visibility.PUBLIC,
                            params = emptyList(),
                            returnType = Type.unit,
                            innerCode = Statement.Block(listOf(
                                IdentifierCall("println", listOf(Identifier("aNumber"))),
                                IdentifierCall("println", listOf(Identifier("aValue1"))),
                                IdentifierCall("println", listOf(Identifier("aValue2"))),
                            ))
                        ),
                    ),
                    extends = emptyList(),
                ),
            ),
            parsed.impls.values,
        )

        assertContentEquals(
            listOf(
                Impl(
                    type = Type.Basic("Example"),
                    modifiers = listOf(CrescentToken.Modifier.STATIC),
                    functions = listOf(
                        Function(
                            name = "add",
                            modifiers = emptyList(),
                            visibility = Visibility.PUBLIC,
                            params = listOf(Parameter.Basic("value1", Type.Basic("I32")), Parameter.Basic("value2", Type.Basic("I32"))),
                            returnType = Type.Basic("I32"),
                            innerCode = Statement.Block(listOf(
                                Return(Expression(listOf(Identifier("value1"), Identifier("value2"), ADD)))
                            ))
                        ),
                        Function(
                            name = "sub",
                            modifiers = emptyList(),
                            visibility = Visibility.PUBLIC,
                            params = listOf(Parameter.Basic("value1", Type.Basic("I32")), Parameter.Basic("value2", Type.Basic("I32"))),
                            returnType = Type.Basic("I32"),
                            innerCode = Statement.Block(listOf(
                                Return(Expression(listOf(Identifier("value1"), Identifier("value2"), SUB)))
                            ))
                        ),
                    ),
                    extends = emptyList(),
                )
            ),
            parsed.staticImpls.values,
        )
    }



    @Test
    fun math() {

        val tokens = CrescentLexer.invoke(TestCode.math)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            emptyList(),
            mainFunction.params
        )

        assertContentEquals(
            listOf(
                IdentifierCall("println", listOf(Expression(listOf(
                    I8(1), I8(1), ADD,
                    I8(1), I8(10), DIV, ADD, I16(1000), I8(10), MUL, I8(11), I8(10), POW, DIV, ADD
                )))),
                IdentifierCall("println", listOf(Expression(listOf(
                    I8(4), I8(3), MUL, I8(1), ADD
                )))),
            ),
            mainFunction.innerCode.nodes,
        )
    }

    @Test
    fun sealed() {

        val tokens = CrescentLexer.invoke(TestCode.sealed)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        val sealedExample = assertNotNull(
            crescentFile.sealeds["Example"],
            "Could not find Constants object"
        )

        assertContentEquals(
            listOf(
                Struct("Thing1", listOf(Variable.Basic(
                    "name",
                    Type.Basic("String"),
                    Expression(emptyList()),
                    true,
                    Visibility.PUBLIC
                ))),
                Struct("Thing2", listOf(Variable.Basic(
                    "id",
                    Type.Basic("i32"),
                    Expression(emptyList()),
                    true,
                    Visibility.PUBLIC
                ))),
            ),
            sealedExample.structs,
        )

        assertContentEquals(
            listOf(
                Object("Thing3", emptyMap(), emptyMap(), emptyMap())
            ),
            sealedExample.objects,
        )
    }


    @Test
    fun enum() {

        val tokens = CrescentLexer.invoke(TestCode.enum)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        val mainFunction = assertNotNull(
            CrescentParser.invoke(Path.of("example.crescent"), tokens).mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(
                Enum(
                    name = "Color",
                    parameters = listOf(Parameter.Basic("name", Type.Basic("String"))),
                    structs = listOf(
                        EnumEntry("RED", listOf(String("Red"))),
                        EnumEntry("GREEN", listOf(String("Green"))),
                        EnumEntry("BLUE", listOf(String("Blue"))),
                    ),
                ),
            ), crescentFile.enums.values,
        )

        assertContentEquals(
            listOf(
                Variable.Basic(
                    "color",
                    Type.Implicit,
                    DotChain(listOf(Identifier("Color"), IdentifierCall("random", emptyList()))),
                    true,
                    Visibility.PUBLIC
                ),
                When(
                    Identifier("color"),
                    listOf(
                        When.Clause(
                            When.EnumShortHand("RED"),
                            Statement.Block(listOf(
                                IdentifierCall("println", listOf(String("Meow")))
                            ))
                        ),
                        When.Clause(
                            When.EnumShortHand("GREEN"),
                            Statement.Block(emptyList())
                        ),
                        When.Clause(
                            null,
                            Statement.Block(emptyList())
                        ),
                    )),
				// A named when subject is represented explicitly rather than as an assignment expression.
				When(DotChain(listOf(Identifier("color"), Identifier("name"))), listOf(
                    When.Clause(
                        String("Red"),
                        Statement.Block(listOf(
                            IdentifierCall("println", listOf(Identifier("name"))),
                        ))
                    ),
                    When.Clause(
                        String("Green"),
                        Statement.Block(emptyList())
                    ),
                    When.Clause(
                        null,
                        Statement.Block(emptyList())
                    ),
				), subjectName = "name"),
            ),
            mainFunction.innerCode.nodes,
        )

    }

    @Test
    fun comments() {

        val tokens = CrescentLexer.invoke(TestCode.comments)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        val mainFunction = assertNotNull(
            crescentFile.mainFunction,
            "No main function found"
        )

        assertContentEquals(
            listOf(
                Identifier("println"),
                String("#meow"),
				Expression(listOf(I8(1), I8(1), ADD, I8(1), I8(1), DIV, I8(1), MUL, SUB, I8(1), ASSIGN)),
            ),
            mainFunction.innerCode.nodes,
        )

    }

    @Test
    fun imports() {

        val tokens = CrescentLexer.invoke(TestCode.imports)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        assertContentEquals(
            listOf(
                Import("crescent.examples", "Thing"),
                Import("crescent.examples", "Thing2", "Thing3"),
                Import("crescent.examples", "*"),

                Import("", "Thing"),
                Import("", "Thing2", "Thing3"),
            ),
            crescentFile.imports
        )
    }

	@Test
	fun `top-level type declarations retain visibility`() {
		val source = "private struct Secret() internal object Shared public enum Choice { Yes }"
		val file = CrescentParser.invoke(Path.of("visibility.moo"), CrescentLexer.invoke(source))

		assertEquals(Visibility.PRIVATE, file.structs.getValue("Secret").visibility)
		assertEquals(Visibility.INTERNAL, file.objects.getValue("Shared").visibility)
		assertEquals(Visibility.PUBLIC, file.enums.getValue("Choice").visibility)
	}

	@Test
	fun `wildcard imports cannot be aliased`() {
		val error = assertFailsWith<IllegalArgumentException> {
			CrescentParser.invoke(
				Path.of("wildcard.moo"),
				CrescentLexer.invoke("import crescent.std.core::* as standard fun main {}"),
			)
		}
		assertTrue(error.message.orEmpty().contains("Wildcard imports cannot have an alias"))
	}

    @Test
    fun nateTriangle() {

        val tokens = CrescentLexer.invoke(TestCode.nateTriangle)
        val crescentFile = CrescentParser.invoke(Path.of("example.crescent"), tokens)

        assertContentEquals(
            listOf(
                Statement.If(
                    predicate = Expression(listOf(Identifier("n"), I8(0), GREATER_EQUALS_COMPARE)),
                    block = Statement.Block(listOf(

                        IdentifierCall("triangle", listOf(
                            Expression(listOf(Identifier("n"), I8(1), SUB)),
                            Expression(listOf(Identifier("k"), I8(1), ADD)),
                        )),

                        Variable.Basic("x", Type.Basic("I32"), I8(0), false, Visibility.PUBLIC),
                        Variable.Basic("y", Type.Basic("I32"), I8(0), false, Visibility.PUBLIC),

                        Statement.While(
                            predicate = Expression(listOf(Identifier("x"), Identifier("k"), LESSER_COMPARE)),
                            block = Statement.Block(listOf(
                                IdentifierCall("print", listOf(String(" "))),
                                Expression(listOf(Identifier("x"), Identifier("x"), I8(1), ADD, ASSIGN))
                            ))
                        ),

                        Statement.While(
                            predicate = Expression(listOf(Identifier("y"), Identifier("n"), LESSER_COMPARE)),
                            block = Statement.Block(listOf(
                                IdentifierCall("print", listOf(String("* "))),
                                Expression(listOf(Identifier("y"), Identifier("y"), I8(1), ADD, ASSIGN))
                            ))
                        ),

                        IdentifierCall("println", emptyList())
                    )),
                    elseBlock = null
                )
            ),
            crescentFile.functions["triangle"]!!.innerCode.nodes
        )

    }

}
