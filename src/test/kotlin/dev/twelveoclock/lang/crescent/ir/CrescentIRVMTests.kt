package dev.twelveoclock.lang.crescent.ir

import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.data.TestCode
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.ir.*
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.utils.fakeUserInput
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import dev.twelveoclock.lang.crescent.vm.awaitFutureUninterruptibly
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class CrescentIRVMTests {
	private fun compileProgram(source: String): CrescentIR = CrescentIRCompiler.invoke(
		CrescentParser.invoke(Path("adversarial.crescent"), CrescentLexer.invoke(source)),
	)

	@Test
	fun loweredCarrierUsesSourceQualifiedAstFreeIdentities() {
		val source = SourceId("sample.tools", "sample/tools/main.crescent")
		val mainSymbol = SymbolRef(source, SymbolKind.FUNCTION, "main")
		val main = FunctionIR(
			symbol = mainSymbol,
			owner = null,
			visibility = IRVisibility.PUBLIC,
			modifiers = emptySet(),
			parameters = emptyList(),
			returnType = IRType.Builtin("Unit"),
			body = IRBlock(listOf(IRStatement.Return(null))),
		)
		val unit = SourceUnitIR(
			source = source,
			imports = emptyMap(),
			structs = emptyList(),
			sealeds = emptyList(),
			traits = emptyList(),
			objects = emptyList(),
			enums = emptyList(),
			globals = emptyList(),
			functions = listOf(main),
			implementations = emptyList(),
		)
		val program = CrescentProgramIR(listOf(unit), FunctionRef(mainSymbol))

		assertEquals(source, program.main.symbol.source)
		assertEquals("sample/tools/main.crescent", program.sourceUnits.single().source.sourcePath)
		assertTrue(program.sourceUnits.single().functions.single().body.statements.single() is IRStatement.Return)
		assertFailsWith<IllegalArgumentException> { SourceId("sample", "sample\\main.crescent") }

		val reachableTypes = mutableSetOf<Class<*>>()
		val visited = java.util.IdentityHashMap<Any, Boolean>()
		fun visit(value: Any?) {
			if (value == null || value is String || value is Number || value is Boolean || value is Char ||
				value.javaClass.isEnum || visited.put(value, true) != null
			) return
			reachableTypes += value.javaClass
			when (value) {
				is Iterable<*> -> value.forEach(::visit)
				is Map<*, *> -> value.forEach { (key, entryValue) -> visit(key); visit(entryValue) }
				else -> value.javaClass.declaredFields
					.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
					.forEach { field -> field.trySetAccessible(); visit(field.get(value)) }
			}
		}
		visit(program)
		val compiledFile = CrescentParser.invoke(Path("compiled-proof.crescent"), CrescentLexer.invoke("fun main { println(\"ok\") }"))
		visit(CrescentIRCompiler.invoke(compiledFile))

		assertTrue(reachableTypes.none { it.name.contains(".language.ast.") })
		assertTrue(reachableTypes.none { it.name.contains(".language.token.") })
		assertTrue(reachableTypes.none { java.nio.file.Path::class.java.isAssignableFrom(it) })
	}

	@Test
	fun compilerRejectsForgedImportMetadataAndPrivateSiblingBypass() {
		val reserved = CrescentParser.invoke(Path("reserved.crescent"), CrescentLexer.invoke("fun println { } fun main { }"))
		assertTrue(assertFailsWith<IllegalArgumentException> { CrescentIRCompiler.invoke(reserved) }.message.orEmpty().contains("reserved builtin"))

		val library = CrescentParser.invoke(Path("library.crescent"), CrescentLexer.invoke("public fun helper { }")).copy(packageId = "sample")
		val parsedMain = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke("fun main { helper() }")).copy(packageId = "sample")
		assertFailsWith<IllegalArgumentException> { CrescentIRCompiler.invoke(listOf(library, parsedMain), parsedMain) }

		val forged = parsedMain.copy(importedSymbols = mapOf("helper" to Node.ModuleSymbol(
			packageId = "sample",
			declarationPath = library.path,
			sourceName = "helper",
			kind = Node.ModuleSymbolKind.FUNCTION,
			visibility = CrescentToken.Visibility.PRIVATE,
		)))
		val failure = assertFailsWith<IllegalArgumentException> { CrescentIRCompiler.invoke(listOf(library, forged), forged) }
		assertTrue(failure.message.orEmpty().contains("forged visibility"))
	}

	@Test
	fun compilerIsDeterministicAndRequiredStructFieldsRemainUninitialized() {
		val main = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke("fun main { }")).copy(packageId = "app")
		val model = CrescentParser.invoke(Path("model.crescent"), CrescentLexer.invoke("struct Point(val x: I32)")).copy(packageId = "model")
		val first = CrescentIRCompiler.invoke(listOf(main, model), main)
		val second = CrescentIRCompiler.invoke(listOf(model, main), main)
		assertEquals(first, second)
		val command = assertIs<CrescentIR.Command.Program>(first.commands.single())
		assertEquals(null, command.program.sourceUnits.single { it.source.packageId == "model" }.structs.single().fields.single().initializer)
	}

	@Test
	fun vmCloseIsIdempotentAndRejectsPostCloseInvocation() {
		val file = CrescentParser.invoke(Path("close.crescent"), CrescentLexer.invoke("fun main { }"))
		val vm = CrescentIRVM(CrescentIRCompiler.invoke(file))
		vm.close()
		vm.close()
		assertFailsWith<IllegalStateException> { vm.invoke() }
	}

	@Test
	fun namedWhenBindsOnceAndObjectIsASingletonWithinAnInvocation() {
		val namedWhen = CrescentParser.invoke(Path("when.crescent"), CrescentLexer.invoke(
			"fun main { when(value = 2) { 2 -> { println(value) } else -> { println(0) } } }",
		))
		assertEquals("2\n", collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(namedWhen)).use { it.invoke() } })

		val objects = CrescentParser.invoke(Path("object.crescent"), CrescentLexer.invoke(TestCode.constantsAndObject))
		assertEquals("Mew\nMeow\nMew\nMeow\n", collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(objects)).use { it.invoke() } })
	}

	@Test
	fun loweringPreservesScopeOrderOwnerShadowingDefaultsAndEnumOrder() {
		val selfReference = CrescentParser.invoke(Path("self.crescent"), CrescentLexer.invoke("fun main { val x = x }"))
		assertFailsWith<IllegalArgumentException> { CrescentIRCompiler.invoke(selfReference) }

		val ordered = CrescentParser.invoke(Path("ordered.crescent"), CrescentLexer.invoke(
			"""
				const value = "global"
				object Box {
					const value = "member"
					fun show { println(value) }
				}
				struct Pair(val first: I32, val second: I32 = first)
				enum Color(name: String) { RED("r") BLUE("b") GREEN("g") }
				fun main {
					val bound = 2
					for bound in bound..bound { println(bound) }
					when(subject = bound) { 2 -> { println(subject) } else -> {} }
					Box.show()
					println(Pair(4).second)
				}
			""".trimIndent(),
		))
		val ir = CrescentIRCompiler.invoke(ordered)
		val program = assertIs<CrescentIR.Command.Program>(ir.commands.single()).program
		assertEquals(listOf("RED", "BLUE", "GREEN"), program.sourceUnits.single().enums.single().entries.map { it.name })
		assertEquals("2\n2\nmember\n4\n", collectSystemOut { CrescentIRVM(ir).use { it.invoke() } })
	}

	@Test
	fun resultCastUnsignedAndMemberVisibilityMatchDirectRuntimeContracts() {
		fun run(source: String): String {
			val file = CrescentParser.invoke(Path("parity.crescent"), CrescentLexer.invoke(source))
			return collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(file)).use { it.invoke() } }
		}
		assertEquals("18446744073709551615\n", run("fun main { println(18446744073709551615 as U64) }"))
		assertEquals("Failure(default)\n", run(
			"fun forwarded(value: I32 = failure(\"default\")?) -> I32? { -> success(value) } fun main { println(forwarded()) }",
		))
		assertEquals("true\ntrue\n", run(
			"trait Named { fun name() -> String } struct Cat(val value: String) impl Cat : Named { override fun name() -> String { -> value } } fun main { val cat = Cat(\"Luna\"); println(cat is Named); println((cat as Named) is Cat) }",
		))

		val library = CrescentParser.invoke(Path("visibility-library.crescent"), CrescentLexer.invoke(
			"struct Vault(private var secret: I32, internal var shared: I32)",
		)).copy(packageId = "example.lib")
		fun imported(source: String) = CrescentParser.invoke(Path("visibility-client.crescent"), CrescentLexer.invoke(source)).copy(
			packageId = "example.lib",
			importedSymbols = mapOf("Vault" to Node.ModuleSymbol(
				"example.lib", library.path, "Vault", Node.ModuleSymbolKind.STRUCT, CrescentToken.Visibility.PUBLIC,
			)),
		)
		val allowed = imported("fun main { val vault = Vault(1, 2); vault.shared = 3; println(vault.shared) }")
		assertEquals("3\n", collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(listOf(allowed, library), allowed)).use { it.invoke() } })
		val denied = imported("fun main { val vault = Vault(1, 2); vault.secret = 3 }")
		val error = assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIRCompiler.invoke(listOf(denied, library), denied)).use { it.invoke() }
		}
		assertTrue(error.message.orEmpty().contains("not accessible"))
	}

	@Test
	fun assignmentsReturnUnitImplicitTypesFreezeAndFuturesRequireAwait() {
		fun compiled(source: String): CrescentIR {
			val file = CrescentParser.invoke(Path("runtime-contract.crescent"), CrescentLexer.invoke(source))
			return CrescentIRCompiler.invoke(file)
		}
		assertEquals("Basic(Unit)\n42\n", collectSystemOut {
			CrescentIRVM(compileProgram(
				"fun assigned() -> Any { var x = 1; x = 2 } async fun answer() -> I32 { -> 42 } fun main { println(assigned()); println(await(answer())) }",
			)).use { it.invoke() }
		})
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(compiled("fun main { var inferred = 1; inferred = \"wrong\" }")).use { it.invoke() }
		}
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(compiled("async fun answer() -> I32 { -> 42 } fun main { println(answer() + 1) }")).use { it.invoke() }
		}
	}

	@Test
	fun futuresAreNeverImplicitlyAwaitedAndBindingsRemainImmutable() {
		fun compile(source: String): CrescentIR {
			val file = CrescentParser.invoke(Path("adversarial.crescent"), CrescentLexer.invoke(source))
			return CrescentIRCompiler.invoke(file)
		}
		val output = collectSystemOut {
			CrescentIRVM(compile(
				"async fun answer() -> I32 { println(\"ran\"); -> 42 } fun main { val pending = answer(); println(pending); println(pending is Future); println(pending as Future); println(await(pending)) }",
			)).use { it.invoke() }
		}
		assertEquals(2, output.lineSequence().count { it == "Future(pending)" })
		assertTrue("true\n" in output)
		assertTrue("ran\n" in output)
		assertTrue(output.endsWith("42\n"))

		assertFailsWith<IllegalStateException> {
			CrescentIRVM(compile("fun change(value: I32) { value = 2 } fun main { change(1) }")).use { it.invoke() }
		}
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(compile("fun main { for index in 1..1 { index = 2 } }")).use { it.invoke() }
		}
	}

	@Test
	fun repeatedInvocationsPreserveGlobalsAndDoNotReplayInitialization() {
		val file = CrescentParser.invoke(Path("persistent.crescent"), CrescentLexer.invoke(
			"fun initialize() -> I32 { println(\"init\"); -> 0 } var count: I32 = initialize() fun main { count += 1; println(count) }",
		))
		assertEquals("init\n1\n2\n", collectSystemOut {
			CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke(); vm.invoke() }
		})
	}

	@Test
	fun manuallyConstructedIrCannotBypassDeclarationVisibility() {
		val library = SourceId("library", "library/private.crescent")
		val client = SourceId("client", "client/main.crescent")
		val privateStruct = SymbolRef(library, SymbolKind.STRUCT, "Secret")
		val privateObject = SymbolRef(library, SymbolKind.OBJECT, "Hidden")
		fun program(expression: IRExpression, struct: Boolean): CrescentIR {
			val mainSymbol = SymbolRef(client, SymbolKind.FUNCTION, "main")
			val main = FunctionIR(mainSymbol, null, IRVisibility.PUBLIC, emptySet(), emptyList(), IRType.Builtin("Unit"), IRBlock(listOf(IRStatement.Evaluate(expression))))
			val libraryUnit = SourceUnitIR(
				library, emptyMap(),
				if (struct) listOf(StructIR(privateStruct, IRVisibility.PRIVATE, emptyList())) else emptyList(),
				emptyList(), emptyList(),
				if (struct) emptyList() else listOf(ObjectIR(privateObject, IRVisibility.PRIVATE, emptyList(), emptyList())),
				emptyList(), emptyList(), emptyList(), emptyList(),
			)
			val clientUnit = SourceUnitIR(client, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(main), emptyList())
			return CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(listOf(clientUnit, libraryUnit).sortedWith(compareBy({ it.source.packageId }, { it.source.sourcePath })), FunctionRef(mainSymbol)))))
		}
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(program(IRExpression.Call(CallTargetIR.Constructor(privateStruct), emptyList()), true)).use { it.invoke() }
		}
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(program(IRExpression.TypeValue(IRType.Declared(privateObject)), false)).use { it.invoke() }
		}
		val phantom = SymbolRef(library, SymbolKind.STRUCT, "Phantom")
		val phantomType = assertFailsWith<IllegalStateException> {
			CrescentIRVM(program(IRExpression.TypeValue(IRType.Declared(phantom)), false)).use { it.invoke() }
		}
		assertTrue(phantomType.message.orEmpty().contains("does not resolve"))
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(program(IRExpression.Call(CallTargetIR.Constructor(phantom), emptyList()), false)).use { it.invoke() }
		}

		val privateGlobal = SymbolRef(library, SymbolKind.GLOBAL, "secret")
		val mainSymbol = SymbolRef(client, SymbolKind.FUNCTION, "main")
		val main = FunctionIR(mainSymbol, null, IRVisibility.PUBLIC, emptySet(), emptyList(), IRType.Builtin("Unit"), IRBlock(listOf(
			IRStatement.Evaluate(IRExpression.Variable(VariableRefIR.Global(privateGlobal))),
		)))
		val leak = GlobalIR(privateGlobal, IRVisibility.PRIVATE, IRType.Builtin("Unit"), false, IRExpression.Call(
			CallTargetIR.Builtin("println"), listOf(IRExpression.Literal(IRLiteral.String("leak"))),
		))
		val libraryUnit = SourceUnitIR(library, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(leak), emptyList(), emptyList())
		val clientUnit = SourceUnitIR(client, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(main), emptyList())
		val tampered = CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(
			listOf(clientUnit, libraryUnit).sortedWith(compareBy({ it.source.packageId }, { it.source.sourcePath })), FunctionRef(mainSymbol),
		))))
		var denied: Throwable? = null
		val output = collectSystemOut { denied = runCatching { CrescentIRVM(tampered).use { it.invoke() } }.exceptionOrNull() }
		assertIs<IllegalStateException>(denied)
		assertEquals("", output, "Denied private global access must not execute its initializer")
	}

	@Test
	fun compilerProducesAnOwnedLoweredProgramAndValidatesItsMainFile() {
		val mainFile = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke(TestCode.helloWorlds))
		val libraryFile = CrescentParser.invoke(Path("library.crescent"), CrescentLexer.invoke(""))
		val input = mutableListOf(mainFile, libraryFile)

		val command = assertIs<CrescentIR.Command.Program>(
			CrescentIRCompiler.invoke(input, mainFile).commands.single(),
		)
		input.clear()

		assertEquals(listOf("library.crescent", "main.crescent"), command.program.sourceUnits.map { it.source.sourcePath.substringAfterLast('/') })
		assertEquals("main", command.program.main.symbol.name)
		assertEquals("main.crescent", command.program.main.symbol.source.sourcePath)
		assertFailsWith<IllegalArgumentException> {
			CrescentIRCompiler.invoke(listOf(libraryFile), mainFile)
		}
	}

	@Test
	fun highLevelProgramCannotBeMixedWithLegacyCommands() {
		val file = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke(TestCode.helloWorlds))
		val program = assertIs<CrescentIR.Command.Program>(CrescentIRCompiler.invoke(file).commands.single())
		val mixed = CrescentIR(
			listOf(
				program,
				CrescentIR.Command.Fun("main"),
			),
		)

		val failure = assertFailsWith<IllegalArgumentException> { CrescentIRVM(mixed) }
		assertEquals(
			"A lowered Program command cannot be mixed with legacy serialized stack IR commands",
			failure.message,
		)
	}


	@Test
	fun helloWorld() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.helloWorlds))

		assertEquals(
			"""
				Hello World
				Hello World
				Hello World
				
			""".trimIndent(),
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
			}
		)
	}

	@Test
	fun argsHelloWorld() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.argsHelloWorld))

		assertEquals(
			"Hello World\n",
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke(listOf("Hello World")) }
			}
		)
	}

	@Test
	fun highLevelProgramCanBeInvokedRepeatedly() {
		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.argsHelloWorld))
		assertEquals(
			"first\nsecond\n",
			collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm ->
				vm.invoke(listOf("first"))
				vm.invoke(listOf("second"))
			} },
		)
	}


	@Test
	fun funThing() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.funThing))

		assertEquals(
			"""
				I am a fun thing :)
				Meow
				Meow
				Meow
				-5
				Meow
				Meow
				Cats
				Basic(Unit)
				
			""".trimIndent(),
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
			}
		)
	}

	@Test
	fun ifStatement() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.ifStatement))

		assertEquals(
			"Meow\nMeow\n",
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke(listOf("true")) }
			}
		)

		assertEquals(
			"Hiss\nHiss\n",
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke(listOf("false")) }
			}
		)
	}

	@Test
	fun ifInputStatement() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.ifInputStatement))

		assertEquals(
			"""
				Enter a boolean value [true/false]
				Meow
				
			""".trimIndent(),
			collectSystemOut {
				fakeUserInput("true") {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
				}
			},
		)

		assertEquals(
			"""
				Enter a boolean value [true/false]
				Hiss
				
			""".trimIndent(),
			collectSystemOut {
				fakeUserInput("false") {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
				}
			}
		)
	}

	@Test
	fun stringInterpolation() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.stringInterpolation))

		assertEquals(
			"""
				000
				Hello 000 Hello
				Hello 0 Hello 0 Hello 0 Hello
				000
				Hello 000 Hello
				Hello 0Hello0Hello0 Hello
				$
				${'$'}x
				$ x
				
			""".trimIndent(),
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
			}
		)
	}

	@Test
	fun forLoop1() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.forLoop1))

		val firstLoop = (0..9).joinToString("\n")

		val secondAndThirdLoop =
			(0..9).flatMap { x ->
				(0..9).flatMap { y ->
					(0..9).map { z ->
						"$x$y$z"
					}
				}
			}.joinToString("\n")

		assertEquals(
			"""
				|000
			    |${firstLoop}
			    |${secondAndThirdLoop}
			    |${secondAndThirdLoop}
			    |Hello World
				|
			""".trimMargin(),
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
			}
		)
	}


	@Test
	fun nateTriangle() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.nateTriangle))

		assertEquals(
			""" 
			      
				    * 
				   * * 
				  * * * 
				 * * * * 
				* * * * * 
			
			""".trimIndent(),
			collectSystemOut {
				CrescentIRVM(CrescentIRCompiler.invoke(file)).use { vm -> vm.invoke() }
			}
		)
	}

	@Test
	fun resultMetadataIsImmutableAcrossTypedBoundaries() {
		val output = collectSystemOut {
			CrescentIRVM(compileProgram(
				"fun main { val raw = failure(\"no\"); val alias = raw; val typed: I32? = raw; println(typeOf(alias)); println(typeOf(typed)); println(alias is I32?); println(typed is I32?) }",
			)).use { it.invoke() }
		}
		assertEquals("Result<Basic(Any)>\nResult<Basic(I32)>\nfalse\ntrue\n", output)
	}

	@Test
	fun compilerRejectsKnownOwnerTypos() {
		assertFailsWith<IllegalArgumentException> {
			compileProgram("object Box { fun broken { println(missing) } } fun main {}")
		}
	}

	@Test
	fun vmSnapshotsManuallyAssembledProgramGraphs() {
		val source = SourceId("snapshot", "main.crescent")
		val symbol = SymbolRef(source, SymbolKind.FUNCTION, "main")
		val statements = mutableListOf<IRStatement>(IRStatement.Return(null))
		val function = FunctionIR(symbol, null, IRVisibility.PUBLIC, emptySet(), emptyList(), IRType.Builtin("Unit"), IRBlock(statements))
		val unit = SourceUnitIR(source, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(function), emptyList())
		val vm = CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(listOf(unit), FunctionRef(symbol))))))
		statements[0] = IRStatement.Return(IRExpression.Literal(IRLiteral.String("mutated")))
		vm.use { it.invoke() }
	}

	@Test
	fun manualIrRejectsForgedNestedStructOwnershipAndMutableLegacyPushes() {
		assertFailsWith<IllegalArgumentException> { IRType.Builtin("Phantom") }
		val source = SourceId("ownership", "main.crescent")
		val mainSymbol = SymbolRef(source, SymbolKind.FUNCTION, "main")
		val main = FunctionIR(mainSymbol, null, IRVisibility.PUBLIC, emptySet(), emptyList(), IRType.Builtin("Unit"), IRBlock(listOf(IRStatement.Return(null))))
		val sealedSymbol = SymbolRef(source, SymbolKind.SEALED, "Actual")
		val forgedParent = SymbolRef(source, SymbolKind.SEALED, "Forged")
		val sealed = SealedIR(sealedSymbol, IRVisibility.PUBLIC, listOf(
			SealedStructIR(NestedSymbolRef(forgedParent, SymbolKind.STRUCT, "Child"), IRVisibility.PUBLIC, emptyList()),
		), emptyList())
		val unit = SourceUnitIR(source, emptyMap(), emptyList(), listOf(sealed), emptyList(), emptyList(), emptyList(), emptyList(), listOf(main), emptyList())
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(listOf(unit), FunctionRef(mainSymbol))))))
		}
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Fun("main"), CrescentIR.Command.Push(mutableListOf(1)))))
		}
		val phantom = IRType.Declared(SymbolRef(source, SymbolKind.STRUCT, "Missing"))
		val phantomUnit = SourceUnitIR(source, emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(main),
			listOf(ImplementationIR(phantom, emptySet(), emptyList(), emptyList())))
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(listOf(phantomUnit), FunctionRef(mainSymbol))))))
		}
		val unnamedUnit = phantomUnit.copy(implementations = listOf(
			ImplementationIR(IRType.Array(IRType.Builtin("I32")), emptySet(), emptyList(), emptyList()),
		))
		assertFailsWith<IllegalStateException> {
			CrescentIRVM(CrescentIR(listOf(CrescentIR.Command.Program(CrescentProgramIR(listOf(unnamedUnit), FunctionRef(mainSymbol))))))
		}
	}

	@Test
	fun customContainmentUnaryAndIndexOperatorsMatchDirectDispatch() {
		val source = """
			struct Box(var value: I32)
			impl Box {
				operator fun contains(needle: I32) -> Boolean { -> needle == value }
				operator fun not() -> Boolean { -> false }
				operator fun unaryMinus() -> Box { -> Box(0 - value) }
				operator fun get(index: I32) -> I32 { -> value + index }
				operator fun set(index: I32, replacement: I32) { value = replacement }
			}
			fun main { val box = Box(3); println(3 in box); println(!box); println(-box); println(box[2]); box[0] = 9; println(box.value) }
		""".trimIndent()
		assertEquals("true\nfalse\nBox(value=-3)\n5\n9\n", collectSystemOut { CrescentIRVM(compileProgram(source)).use { it.invoke() } })
	}

	@Test
	fun awaitGuardRunsBeforeItsArgumentInBothVms() {
		val source = "async fun work() -> I32 { println(\"launched\"); -> 1 } val value = await(work()) fun main { println(value) }"
		val directOutput = collectSystemOut {
			val file = CrescentParser.invoke(Path("await-guard.crescent"), CrescentLexer.invoke(source))
			assertFailsWith<IllegalStateException> { CrescentVM(listOf(file), file).use { it.invoke() } }
		}
		val irOutput = collectSystemOut {
			assertFailsWith<IllegalStateException> { CrescentIRVM(compileProgram(source)).use { it.invoke() } }
		}
		assertEquals("", directOutput)
		assertEquals(directOutput, irOutput)
	}

	@Test
	fun explicitAwaitIsUninterruptibleRestoresStatusAndUnwrapsFailure() {
		var calls = 0
		val interruptedOnce = object : Future<Int> {
			override fun cancel(mayInterruptIfRunning: Boolean) = false
			override fun isCancelled() = false
			override fun isDone() = calls >= 2
			override fun get(): Int {
				calls++
				if (calls == 1) throw InterruptedException("synthetic interrupt")
				return 42
			}
			override fun get(timeout: Long, unit: TimeUnit): Int = get()
		}
		Thread.interrupted()
		try {
			assertEquals(42, awaitFutureUninterruptibly(interruptedOnce))
			assertEquals(2, calls)
			assertTrue(Thread.currentThread().isInterrupted)
		} finally {
			Thread.interrupted()
		}

		val original = IllegalStateException("original async failure")
		val failed = object : Future<Int> {
			override fun cancel(mayInterruptIfRunning: Boolean) = false
			override fun isCancelled() = false
			override fun isDone() = true
			override fun get(): Int = throw ExecutionException(original)
			override fun get(timeout: Long, unit: TimeUnit): Int = get()
		}
		assertSame(original, assertFailsWith<IllegalStateException> { awaitFutureUninterruptibly(failed) })
	}

	@Test
	fun traitRequirementsAndOverrideSignaturesMatchDirectValidation() {
		val invalid = listOf(
			"trait Named { fun name() -> String } struct Cat() impl Cat : Named {} fun main {}",
			"struct Cat() impl Cat { override fun name() -> String { -> \"cat\" } } fun main {}",
			"trait Named { fun name(value: I32 = 1) -> String } struct Cat() impl Cat : Named { override fun name(value: I32) -> String { -> \"cat\" } } fun main {}",
			"trait A {} trait B {} impl A : B {} impl B : A {} fun main {}",
		)
		invalid.forEach { source ->
			assertFailsWith<IllegalStateException> {
				val file = CrescentParser.invoke(Path("trait-direct.crescent"), CrescentLexer.invoke(source))
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertFailsWith<IllegalStateException> { CrescentIRVM(compileProgram(source)).use { it.invoke() } }
		}

		val valid = "trait Named { fun name() -> String } struct Cat() impl Cat : Named { override fun name() -> String { -> \"cat\" } } fun main { println(Cat().name()) }"
		val directOutput = collectSystemOut {
			val file = CrescentParser.invoke(Path("trait-valid.crescent"), CrescentLexer.invoke(valid))
			CrescentVM(listOf(file), file).use { it.invoke() }
		}
		assertEquals("cat\n", directOutput)
		assertEquals(directOutput, collectSystemOut { CrescentIRVM(compileProgram(valid)).use { it.invoke() } })

		val validDefault = "trait Named { fun name(value: I32 = 1) -> String } struct Cat() impl Cat : Named { override fun name(value: I32 = 1) -> String { -> \"cat\" } } fun main { println(Cat().name()) }"
		val defaultDirect = collectSystemOut {
			val file = CrescentParser.invoke(Path("trait-default.crescent"), CrescentLexer.invoke(validDefault))
			CrescentVM(listOf(file), file).use { it.invoke() }
		}
		assertEquals("cat\n", defaultDirect)
		assertEquals(defaultDirect, collectSystemOut { CrescentIRVM(compileProgram(validDefault)).use { it.invoke() } })
	}

	@Test
	fun mainMustRemainPublicAndModifierFreeInBothVms() {
		listOf("async fun main {}", "private fun main {}").forEach { source ->
			assertFailsWith<IllegalStateException> {
				val file = CrescentParser.invoke(Path("invalid-main.crescent"), CrescentLexer.invoke(source))
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertFailsWith<IllegalStateException> { CrescentIRVM(compileProgram(source)).use { it.invoke() } }
		}
	}

	@Test
	fun duplicateModifiersCannotBeLostDuringLowering() {
		listOf(
			"async async fun work {} fun main {}",
			"struct Box() impl static static Box {} fun main {}",
		).forEach { source ->
			val failure = assertFailsWith<IllegalArgumentException> {
				CrescentParser.invoke(Path("duplicate-modifier.crescent"), CrescentLexer.invoke(source))
			}
			assertTrue(failure.message.orEmpty().contains("Duplicate modifier"))
		}
	}

}
