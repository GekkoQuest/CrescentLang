package dev.twelveoclock.lang.crescent.ptir

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.translators.*
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import kotlin.io.path.Path
import kotlin.test.*

class TranslatorTest {
	@Test
	fun kotlinTranslatorStructurallyMapsCompilationUnitAndDeclarations() {
		val kotlin = """
			package demo.people
			import other.api.Widget as RemoteWidget

			interface Named {
				fun displayName(): String
			}

			class Person(public val name: String, var age: Int = 0) : Named {
				override fun displayName(): String { return name }
				companion object {
					@JvmStatic fun create(name: String): Person = Person(name)
				}
			}

			object Registry {
				private val count: Int = 0
				fun size(): Int { return count }
			}

			fun main() {
				for (index in 0..1) { println(index) }
				val numbers: IntArray = intArrayOf(3, 4)
				println(numbers[1])
			}
		""".trimIndent()

		val translated = KotlinToCrescentTranslator.translate(kotlin)
		val parsed = CrescentParser.invoke(Path("translated.crescent"), CrescentLexer.invoke(translated))

		assertContains(translated, "# Non-semantic Kotlin package provenance: demo.people")
		assertContains(translated, "import other.api::Widget as RemoteWidget")
		assertEquals(listOf("name", "age"), parsed.structs.getValue("Person").variables.map(Node.Variable::name))
		assertEquals("displayName", parsed.traits.getValue("Named").functionTraits.single().name)
		assertEquals(listOf(Node.Type.Basic("Named")), parsed.impls.getValue("Person").extends)
		assertTrue(CrescentToken.Modifier.OVERRIDE in parsed.impls.getValue("Person").functions.single().modifiers)
		assertEquals("create", parsed.staticImpls.getValue("Person").functions.single().name)
		assertEquals(CrescentToken.Visibility.PRIVATE, parsed.objects.getValue("Registry").variables.getValue("count").visibility)
		assertEquals("0\n1\n4\n", collectSystemOut { CrescentVM(listOf(parsed), parsed).use { it.invoke() } })
	}

	@Test
	fun kotlinTranslatorMapsRecursiveContainersQualifiedTypesAndResult() {
		val kotlin = """
			fun convert(values: List<Array<Int>>, remote: com.example.Widget): Result<Array<String>> {
				return Result.success(arrayOf("ok"))
			}
			fun fail(): kotlin.Result<String> { return kotlin.Result.failure("bad") }
		""".trimIndent()
		val translated = KotlinToCrescentTranslator.translate(kotlin)
		val parsed = CrescentParser.invoke(Path("types.crescent"), CrescentLexer.invoke(translated))
		val function = parsed.functions.getValue("convert")

		assertContains(translated, "import com.example::Widget")
		assertContains(translated, "success([\"ok\"])")
		assertContains(translated, "failure(\"bad\")")
		assertEquals(Node.Type.Array(Node.Type.Array(Node.Type.Basic("I32"))), (function.params[0] as Node.Typed).type)
		assertEquals(Node.Type.Basic("Widget"), (function.params[1] as Node.Typed).type)
		assertEquals(Node.Type.Result(Node.Type.Array(Node.Type.Basic("String"))), function.returnType)
	}

	@Test
	fun poderTechIrPreservesEveryCurrentModifierVisibilityAndTypeShape() {
		val local = Node.Variable.Local("inferred", Node.Type.Implicit, Node.Primitive.Number.I32(1), true)
		val method = function("all", local).copy(
			modifiers = CrescentToken.Modifier.entries,
			visibility = CrescentToken.Visibility.PRIVATE,
			params = listOf(Node.Parameter.Basic("matrix", Node.Type.Array(Node.Type.Array(Node.Type.Basic("I32"))))),
			returnType = Node.Type.Result(Node.Type.Basic("String")),
		)
		val program = PoderTechIrTranslator.translate(file(method)).modules.single().methods.single()

		assertEquals(PoderTechIrModifier.entries, program.modifiers)
		assertEquals(PoderTechIrVisibility.PRIVATE, program.visibility)
		assertEquals(
			PoderTechIrType.Array(PoderTechIrType.Array(PoderTechIrType.Named("I32"))),
			program.parameterDefinitions.single().type,
		)
		assertEquals(PoderTechIrType.Result(PoderTechIrType.Named("String")), program.returnType)
		val variable = assertIs<PoderTechIrNode.Variable>(program.body!!.nodes.single()).definition
		assertEquals(PoderTechIrType.Implicit, variable.type)
		assertNull(variable.visibility)
	}

	@Test
	fun kotlinTranslatorRejectsTargetLanguageImpossibilitiesAtOffsets() {
		val cases = listOf(
			"fun value(input: String?): String { return input }" to "nullable type",
			"fun size(input: String): Int { return input?.length ?: 0 }" to "safe-call",
			"fun force(input: String): String { return input!! }" to "non-null",
			"fun nil(): String { return null }" to "null literal",
			"fun apply(fn: (Int) -> Int): Int { return fn(1) }" to "function types",
			"fun names(values: Set<String>) {}" to "arbitrary generic",
			"fun broken() { try { work() } catch (error: Any) {} }" to "exception",
			"fun each(values: List<Int>) { for (value in values) {} }" to "collection iteration",
			"data class Value(val number: Int)" to "data-class",
			"open class Base(val number: Int)" to "open-class",
			"class Child(val number: Int) : Base(number)" to "class-superconstructor",
			"suspend fun main() {}" to "suspend main",
			"abstract fun missing(): Int" to "abstract function",
			"object Outer { class Nested }" to "nested declaration",
		)
		cases.forEach { (source, diagnostic) ->
			val exception = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(source) }
			assertContains(exception.message.orEmpty(), diagnostic, ignoreCase = true)
			assertContains(exception.message.orEmpty(), "offset")
		}
	}

	@Test
	fun kotlinTranslatorRejectsSemanticLossBoundariesAtExactOffsets() {
		fun boundary(source: String, diagnostic: String, marker: String, adjustment: Int = 0) = Triple(source, diagnostic, source.indexOf(marker) + adjustment)
		val cases = listOf(
			boundary("fun value(): Int { return@value 1 }", "labeled return", "@"),
			boundary("class Value(@Deprecated val number: Int)", "annotation on constructor property", "@"),
			boundary("class Value(final val number: Int)", "modifier on constructor property", "final"),
			boundary("class Value { private companion object {} }", "prefix on companion object", "private"),
			boundary("class Value { companion object Factory {} }", "named companion", "Factory"),
			boundary("final object Value {}", "modifier on object", "final"),
			boundary("@Deprecated object Value {}", "annotation on object", "@"),
			boundary("object Value { override val number: Int = 1 }", "modifier on state", "override"),
			boundary("fun convert(value: Any): Int { return value as? Int }", "safe cast", " as? ", 1),
			boundary("fun apply() { val transform = { value: Int -> value } }", "lambda", "{ value"),
			boundary("fun apply() { run { work() } }", "trailing lambda", " { work", 1),
			boundary("fun stop() { break@loop }", "labeled control", "@"),
			boundary("fun stop() { loop@ while (ready) {} }", "labeled control", "@"),
		)
		cases.forEach { (source, diagnostic, offset) ->
			val error = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(source) }
			assertContains(error.message.orEmpty(), diagnostic)
			assertContains(error.message.orEmpty(), "offset $offset")
		}
	}

	@Test
	fun kotlinTranslatorMapsSupportedCastsAndRuntimeTypeTestsStructurally() {
		val executable = KotlinToCrescentTranslator.translate(
			"fun main() { val value: Any = \"ok\"; println(value as String); println(value is String); println(value !is Int) }",
		)
		val executableFile = CrescentParser.invoke(Path("casts.crescent"), CrescentLexer.invoke(executable))
		assertEquals("ok\ntrue\ntrue\n", collectSystemOut { CrescentVM(listOf(executableFile), executableFile).use { it.invoke() } })

		val structural = KotlinToCrescentTranslator.translate(
			"fun array(value: Any): Array<Int> { return value as Array<Int> }\n" +
				"fun result(value: Any): Result<String> { return value as Result<String> }\n" +
				"fun remote(value: Any): Boolean { return value is com.example.Widget }",
		)
		assertContains(structural, "value as [I32]")
		assertContains(structural, "value as String?")
		assertContains(structural, "value is Widget")
		assertContains(structural, "import com.example::Widget")
		CrescentParser.invoke(Path("structural-casts.crescent"), CrescentLexer.invoke(structural))
	}

	@Test
	fun kotlinTranslatorSafelyLowersCommentsCollectionsAndIntegerMinimum() {
		val translated = KotlinToCrescentTranslator.translate(
			"""
				@Deprecated fun sum(): Int { return 1 /* inline block comment */ + 2 }
				fun main() {
					val values: List<Int> = listOf(4, 5)
					val mutable: MutableList<Int> = mutableListOf(6)
					val empty: List<Int> = emptyList()
					println(sum())
					println(values[1])
					println(mutable[0])
					println(1 /* inline expression comment */ + 2)
					println(-2147483648)
				}
			""".trimIndent(),
		)
		assertContains(translated, "# Non-semantic Kotlin annotation provenance: @Deprecated")
		assertFalse(translated.contains("inline block comment"))
		assertFalse(translated.contains("inline expression comment"))
		assertContains(translated, "[I32] = [(4 as I32), (5 as I32)]")
		assertContains(translated, "[I32] = []")
		assertContains(translated, "((-2147483647 as I32) - (1 as I32))")
		val file = CrescentParser.invoke(Path("collections.crescent"), CrescentLexer.invoke(translated))
		assertEquals("3\n5\n6\n3\n-2147483648\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun kotlinTranslatorRejectsAdversarialSyntaxAtExactOffsets() {
		fun boundary(source: String, diagnostic: String, marker: String) = Triple(source, diagnostic, source.indexOf(marker))
		listOf(
			boundary("fun `when`(): Int { return 1 }", "escaped identifiers", "`"),
			Triple(
				"fun values(): Array<Int> { return arrayOf<Int>(1) }",
				"generic collection or array factory",
				"fun values(): Array<Int> { return arrayOf<Int>(1) }".lastIndexOf('<'),
			),
			boundary("fun main() { for (i in 0 until 3) {} }", "half-open", "until"),
			boundary("fun main() { for (i in 0..<3) {} }", "half-open", "..<"),
			boundary("fun main() { for (i in 0..6 step 2) {} }", "stepped or descending", "step"),
			boundary("fun main() { for (i in 6 downTo 0) {} }", "stepped or descending", "downTo"),
			boundary("interface Named { suspend fun name(): String }", "interface signature", "suspend"),
		).forEach { (source, diagnostic, offset) ->
			val error = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(source) }
			assertContains(error.message.orEmpty(), diagnostic)
			assertContains(error.message.orEmpty(), "offset $offset")
		}
	}

	@Test
	fun kotlinTranslatorPreservesCommentsAndQuotedTextWithoutRewritingThem() {
		val translated = KotlinToCrescentTranslator.translate(
			"/* Int return */\nfun main() { val text: String = \"Int // return\" // Int return\n}",
		)
		assertContains(translated, "# Int return")
		assertContains(translated, "\"Int // return\"")
		CrescentParser.invoke(Path("comments.crescent"), CrescentLexer.invoke(translated))
	}

	@Test
	fun kotlinTranslatorNormalizesTypedNumericAndKotlinStringLiterals() {
		val source = """
			fun literals(): F64 {
				val longValue: Long = 0x7fff_ffffL
				val unsignedValue: UInt = 0b1111u
				val floatValue: Float = 1.25e2f
				-> 1e2
			}
			fun main() { println(127 + 1); println(18446744073709551615UL); println(-9223372036854775808L) }
		""".trimIndent().replace("-> 1e2", "return 1e2")
		val translated = KotlinToCrescentTranslator.translate(source)
		assertContains(translated, "(2147483647 as I64)")
		assertContains(translated, "(15 as U32)")
		assertContains(translated, "(125.0 as F32)")
		assertContains(translated, "(100.0 as F64)")
		assertContains(translated, "(127 as I32)")
		assertContains(translated, "(1 as I32)")
		assertContains(translated, "(18446744073709551615 as U64)")
		assertContains(translated, "((-9223372036854775807 as I64) - (1 as I64))")

		val file = CrescentParser.invoke(Path("numeric.crescent"), CrescentLexer.invoke(translated))
		assertEquals("128\n18446744073709551615\n-9223372036854775808\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })

		val rawSource = "fun text(): String { return " + "\"\"\"" + "line\\n\"quoted\" tail" + "\"\"\"" + " }"
		val raw = KotlinToCrescentTranslator.translate(rawSource)
		CrescentParser.invoke(Path("raw.crescent"), CrescentLexer.invoke(raw))
		val escapeSource = "fun text(): String { return \"\\b\" }"
		val escape = assertFailsWith<IllegalArgumentException> {
			KotlinToCrescentTranslator.translate(escapeSource)
		}
		assertContains(escape.message.orEmpty(), "backspace")
		assertContains(escape.message.orEmpty(), "offset ${escapeSource.indexOf("\\b")}")
		listOf(
			Triple("fun tooLarge(): Float { return 1e999f }", "overflows", "1e999f"),
			Triple("fun invalid(): UInt { return 12_U }", "separator", "_"),
		).forEach { (unsupported, diagnostic, offendingText) ->
			val error = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(unsupported) }
			assertContains(error.message.orEmpty(), diagnostic)
			assertContains(error.message.orEmpty(), "offset ${unsupported.indexOf(offendingText)}")
		}
	}

	@Test
	fun kotlinTranslatorLowersSignedExponentsAndUnbracedControlFlow() {
		val translated = KotlinToCrescentTranslator.translate(
			"""
				fun main() {
					var value: Int = 0
					if (true) value += 1 else if (false) value += 100 else value += 2
					while (value < 3) value += 1
					if (value == 3)
						println(1e-3)
					else if (true)
						println(9.0)
					else
						println(8.0)
					if (false) println(7.0) else if (true) println(1E+3) else println(6.0)
					println(value)
				}
			""".trimIndent(),
		)

		assertContains(translated, "(0.001 as F64)")
		assertContains(translated, "(1000.0 as F64)")
		assertContains(translated, "else {\nif")
		val file = CrescentParser.invoke(Path("unbraced-control.crescent"), CrescentLexer.invoke(translated))
		assertEquals("0.001\n1000.0\n3\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun kotlinTranslatorRejectsUnsupportedExpressionOperatorsGloballyAtExactOffsets() {
		fun boundary(source: String, diagnostic: String, marker: String) = Triple(source, diagnostic, source.indexOf(marker))
		listOf(
			boundary("fun range(): Any { return 0..<3 }", "half-open range expression", "..<"),
			boundary("fun range(): Any { return 0 until 3 }", "half-open range expression", "until"),
			boundary("fun range(): Any { return 3 downTo 0 }", "stepped or descending range expression", "downTo"),
			boundary("fun range(): Any { return 0..4 step 2 }", "stepped or descending range expression", "step"),
			boundary("fun update(value: Int) { value++ }", "increment/decrement operator", "++"),
			boundary("fun update(value: Int) { --value }", "increment/decrement operator", "--"),
		).forEach { (source, diagnostic, offset) ->
			val error = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(source) }
			assertContains(error.message.orEmpty(), diagnostic)
			assertContains(error.message.orEmpty(), "offset $offset")
		}
	}

	@Test
	fun kotlinTranslatorRejectsMalformedAndBareReturnsPrecisely() {
		val bare = "fun stop() { return }"
		val bareError = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(bare) }
		assertContains(bareError.message.orEmpty(), "bare return")
		assertContains(bareError.message.orEmpty(), "offset")
		val labeled = "fun stop() { return@stop\n}"
		val labeledError = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate(labeled) }
		assertContains(labeledError.message.orEmpty(), "labeled return")
		assertContains(labeledError.message.orEmpty(), "offset ${labeled.indexOf('@')}")
		val unbalanced = assertFailsWith<IllegalArgumentException> { KotlinToCrescentTranslator.translate("fun bad(value: Array<Int) {}") }
		assertContains(unbalanced.message.orEmpty(), "offset")
	}

	@Test
	fun poderTechIrStructuralModelPreservesAllDeclarationSemantics() {
		val one = Node.Expression(listOf(Node.Primitive.Number.I32(1)))
		val field = Node.Variable.Basic("value", Node.Type.Basic("I32"), one, true, CrescentToken.Visibility.PRIVATE)
		val constant = Node.Variable.Constant("LIMIT", Node.Type.Basic("I32"), one, CrescentToken.Visibility.INTERNAL)
		val defaultParameter = Node.Parameter.WithDefault("count", Node.Type.Basic("I32"), one)
		val signature = Node.FunctionTrait("run", listOf(defaultParameter), Node.Type.Result(Node.Type.Basic("String")))
		val method = function(
			"work",
			Node.TypeLiteral(Node.Type.Array(Node.Type.Basic("I32"))),
			Node.InfixCall(Node.Identifier("left"), "combine", Node.Identifier("right")),
			Node.Statement.If(Node.Identifier("ready"), Node.Statement.Block(listOf(Node.IdentifierCall("yes"))), Node.Statement.Block(listOf(Node.IdentifierCall("no")))),
			Node.Statement.While(Node.Identifier("ready"), Node.Statement.Block(listOf(CrescentToken.Keyword.BREAK))),
			Node.Statement.For(listOf(Node.Identifier("i")), listOf(Node.Statement.Range(Node.Primitive.Number.I32(0), Node.Primitive.Number.I32(2))), Node.Statement.Block(listOf(CrescentToken.Keyword.CONTINUE))),
			Node.Statement.When(Node.Identifier("value"), listOf(Node.Statement.When.Clause(Node.Primitive.Number.I32(1), Node.Statement.Block(listOf(Node.IdentifierCall("one")))), Node.Statement.When.Clause(null, Node.Statement.Block(listOf(Node.IdentifierCall("other"))))), subjectName = "matched"),
		).copy(modifiers = listOf(CrescentToken.Modifier.ASYNC, CrescentToken.Modifier.INFIX), params = listOf(defaultParameter), returnType = Node.Type.Result(Node.Type.Basic("String")))
		val objectNode = Node.Object("State", mapOf(field.name to field), mapOf(constant.name to constant), mapOf(method.name to method), CrescentToken.Visibility.INTERNAL)
		val struct = Node.Struct("Box", listOf(field), CrescentToken.Visibility.PRIVATE)
		val sealed = Node.Sealed("Choice", listOf(struct), listOf(objectNode), CrescentToken.Visibility.PUBLIC)
		val enum = Node.Enum("Mode", listOf(defaultParameter), listOf(Node.EnumEntry("FAST", listOf(Node.Primitive.Number.I32(2)))), CrescentToken.Visibility.INTERNAL)
		val impl = Node.Impl(Node.Type.Basic("Box"), emptyList(), listOf(Node.Type.Basic("Named")), listOf(method))
		val symbol = Node.ModuleSymbol("other", Path("other", "Widget.crescent"), "Widget", Node.ModuleSymbolKind.STRUCT, CrescentToken.Visibility.PUBLIC, enclosingTypeName = "Envelope")
		val file = file(method).copy(
			path = Path("pkg", "module.crescent"), packageId = "pkg", imports = listOf(Node.Import("other", "Widget", "RemoteWidget")), importedSymbols = mapOf("RemoteWidget" to symbol),
			structs = mapOf(struct.name to struct), sealeds = mapOf(sealed.name to sealed), traits = mapOf("Runnable" to Node.Trait("Runnable", listOf(signature), CrescentToken.Visibility.PRIVATE)),
			objects = mapOf(objectNode.name to objectNode), enums = mapOf(enum.name to enum), variables = mapOf(field.name to field), constants = mapOf(constant.name to constant), impls = mapOf("Box" to impl),
		)

		val module = PoderTechIrTranslator.translate(file).modules.single()
		assertEquals("pkg", module.packageId)
		assertEquals(file.path, module.sourcePath)
		assertEquals("Widget", module.importedSymbols.getValue("RemoteWidget").sourceName)
		assertEquals(PoderTechIrSymbolKind.STRUCT, module.importedSymbols.getValue("RemoteWidget").kind)
		assertEquals("Envelope", module.importedSymbols.getValue("RemoteWidget").enclosingTypeName)
		assertEquals(PoderTechIrVisibility.PRIVATE, module.structs.single().visibility)
		assertIs<PoderTechIrNode.Expression>(module.structs.single().fieldDefinitions.single().initializer)
		assertEquals(PoderTechIrType.Result(PoderTechIrType.Named("String")), module.traits.single().signatures.single().returnType)
		assertEquals(1, module.enums.single().entryDefinitions.single().arguments.size)
		assertEquals(PoderTechIrVisibility.INTERNAL, module.objects.single().visibility)
		assertEquals("Choice", module.sealedTypes.single().structs.single().enclosingTypeName)
		assertEquals("Choice", module.sealedTypes.single().objects.single().enclosingTypeName)
		assertEquals(listOf(PoderTechIrType.Named("Named")), module.implementations.single { !it.static }.extends)
		val body = module.methods.single { it.name == "work" }.body!!
		assertIs<PoderTechIrNode.TypeLiteral>(body.nodes[0])
		assertIs<PoderTechIrNode.InfixCall>(body.nodes[1])
		assertTrue(body.nodes.any { it is PoderTechIrNode.If })
		assertTrue(body.nodes.any { it is PoderTechIrNode.For })
		assertEquals("matched", body.nodes.filterIsInstance<PoderTechIrNode.When>().single().subjectName)
	}

	@Test
	fun poderTechIrAllowsSameBasenameWhenSourceIdentityDiffers() {
		val first = file(function("first")).copy(path = Path("one", "shared.crescent"), packageId = "one")
		val second = file(function("second")).copy(path = Path("two", "shared.crescent"), packageId = "two")
		val modules = PoderTechIrTranslator.translate(listOf(first, second)).modules
		assertEquals(listOf("one", "two"), modules.map(PoderTechIrModule::packageId))
		assertEquals(listOf("shared", "shared"), modules.map(PoderTechIrModule::name))
	}

	@Test
	fun poderTechIrAuthoritativeCollectionsAreTranslationTimeSnapshots() {
		val sourceNodes = mutableListOf<Node>(Node.Identifier("before"))
		val sourceFunctions = mutableMapOf("work" to function("work").copy(innerCode = Node.Statement.Block(sourceNodes)))
		val source = file().copy(functions = sourceFunctions)
		val translated = PoderTechIrTranslator.translate(source)

		sourceNodes += Node.Identifier("after")
		sourceFunctions.clear()
		val module = translated.modules.single()
		assertEquals(listOf("work"), module.methods.map(PoderTechIrMethod::name))
		assertEquals(1, module.methods.single().body!!.nodes.size)
	}

	@Test
	fun poderTechIrUsesCanonicalNumericKindsAndSemanticCallableIdentity() {
		val literalProgram = PoderTechIrTranslator.translate(file(function("literal", Node.Primitive.Number.I32(7))))
		val literal = assertIs<PoderTechIrNode.Literal>(literalProgram.modules.single().methods.single().body!!.nodes.single())
		assertEquals("I32", literal.kind)

		fun overload(parameterName: String, type: Node.Type) = function("call").copy(params = listOf(Node.Parameter.Basic(parameterName, type)))
		val duplicate = Node.Impl(Node.Type.Basic("Box"), emptyList(), emptyList(), listOf(overload("left", Node.Type.Basic("I32")), overload("right", Node.Type.Basic("I32"))))
		val duplicateError = assertFailsWith<PoderTechIrTranslationException> {
			PoderTechIrTranslator.translate(file().copy(impls = mapOf("Box" to duplicate)))
		}
		assertContains(duplicateError.message.orEmpty(), "method identity")

		val distinct = duplicate.copy(functions = listOf(overload("value", Node.Type.Basic("I32")), overload("value", Node.Type.Basic("String"))))
		assertEquals(2, PoderTechIrTranslator.translate(file().copy(impls = mapOf("Box" to distinct))).modules.single().methods.size)
		val one = overload("value", Node.Type.Basic("I32"))
		assertEquals(
			2,
			PoderTechIrTranslator.translate(file().copy(impls = mapOf("Box" to duplicate.copy(functions = listOf(one))), staticImpls = mapOf("Box" to duplicate.copy(functions = listOf(one))))).modules.single().methods.size,
		)
	}

	@Test
	fun poderTechIrLegacyWhenLabelsEscapeStringAndCharLiterals() {
		val whenNode = Node.Statement.When(
			Node.Identifier("value"),
			listOf(
				Node.Statement.When.Clause(Node.Primitive.String("line\n\"quote"), Node.Statement.Block(emptyList())),
				Node.Statement.When.Clause(Node.Primitive.Char('\''), Node.Statement.Block(emptyList())),
			),
		)
		val instruction = assertIs<PoderTechIrInstruction.When>(
			PoderTechIrTranslator.translate(file(function("labels", whenNode))).modules.single().methods.single().instructions.single(),
		)
		assertEquals("\"line\\n\\\"quote\"", instruction.clauses[0].first)
		assertEquals("'\\''", instruction.clauses[1].first)
	}

	@Test
	fun poderTechIrRejectsExactDuplicateSourceIdentity() {
		val duplicate = file().copy(path = Path("same.crescent"), packageId = "pkg")
		val error = assertFailsWith<PoderTechIrTranslationException> { PoderTechIrTranslator.translate(listOf(duplicate, duplicate)) }
		assertContains(error.message.orEmpty(), "source identity")
	}

	@Test
	fun poderTechIrCompatibilityProjectionAndEntrypointsRemainStable() {
		val predicate = Node.Expression(listOf(Node.Identifier("value"), CrescentToken.Operator.LESSER_COMPARE, Node.Identifier("limit")))
		val source = file(function("zeta"), function("alpha", Node.Statement.While(predicate, Node.Statement.Block(listOf(Node.IdentifierCall("tick")))), Node.Return(Node.Identifier("value"))))
		val program = PoderTranslator.translate(source)
		assertEquals(listOf("alpha", "zeta"), program.modules.single().methods.map(PoderTechIrMethod::name))
		assertIs<PoderTechIrInstruction.While>(program.modules.single().methods.first().instructions.first())
		assertEquals(program.modules, CrescentToPTIR().translate(Path("."), source))
	}

	@Test
	fun poderTechIrRejectsUnknownFutureNodeWithoutSilentLoss() {
		val unknown = object : Node {}
		val error = assertFailsWith<PoderTechIrTranslationException> { PoderTechIrTranslator.translate(file(function("broken", unknown))) }
		assertContains(error.message.orEmpty(), "Unsupported PoderTechIR AST node")
	}

	@Test
	fun crescentToPtirRejectsPathsOutsideNormalizedProjectRoot() {
		val root = Path("project").toAbsolutePath()
		val outside = file().copy(path = Path("..", "outside.crescent"))
		assertFailsWith<IllegalArgumentException> { CrescentToPTIR().translate(root, outside) }
	}

	private fun function(name: String, vararg nodes: Node) = Node.Function(
		name, emptyList(), CrescentToken.Visibility.PUBLIC, emptyList(), Node.Type.unit, Node.Statement.Block(nodes.toList()),
	)

	private fun file(vararg functions: Node.Function) = Node.File(
		Path("module.crescent"), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), functions.associateBy(Node.Function::name), null,
	)
}
