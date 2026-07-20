package dev.twelveoclock.lang.crescent

import dev.twelveoclock.lang.crescent.data.TestCode
import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.utils.fakeUserInput
import dev.twelveoclock.lang.crescent.utils.withSystemIo
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentRuntimeException
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal class CrescentVMTests {

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
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun argsHelloWorld() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.argsHelloWorld))

		assertEquals(
			"Hello World\n",
			collectSystemOut {
				CrescentVM(listOf(file), file).use { it.invoke(listOf("Hello World")) }
			}
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
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun ifStatement() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.ifStatement))

		assertEquals(
			"""
				Meow
				Meow
			
			""".trimIndent(),
			collectSystemOut {
				CrescentVM(listOf(file), file).use { it.invoke(listOf("true")) }
			}
		)

		assertEquals(
			"""
				Hiss
				Hiss
				
			""".trimIndent(),
			collectSystemOut {
				CrescentVM(listOf(file), file).use { it.invoke(listOf("false")) }
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
					CrescentVM(listOf(file), file).use { it.invoke() }
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
					CrescentVM(listOf(file), file).use { it.invoke() }
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
				CrescentVM(listOf(file), file).use { it.invoke() }
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
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun constantsAndObjects() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.constantsAndObject))

		assertEquals(
			""" 
				Mew
				Meow
				Mew
				Meow
				
			""".trimIndent(),
			collectSystemOut {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun struct() {

		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.struct))

		assertEquals(
			""" 
				Example(aNumber=1, aValue1=Mew, aValue2=Meow)
				1
				Mew
				Meow
				
			""".trimIndent(),
			collectSystemOut {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun implMethods() {
		val file = CrescentParser.invoke(Path("example.crescent"), CrescentLexer.invoke(TestCode.impl))

		assertEquals(
			"""
				1
				Meow
				Mew

				1
				Meow
				Mew
				3
				-1

			""".trimIndent(),
			collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } },
		)
	}

	@Test
	fun traitsEnumsDefaultsAsyncAndMultipleFiles() {
		val library = CrescentParser.invoke(
			Path("library.crescent"),
			CrescentLexer.invoke(
				"""
					trait Named {
						fun name() -> String
					}

					struct Cat(val value: String)

					impl Cat : Named {
						override fun name() -> String { -> value }
					}

					fun greet(name: String = "Moon") -> String { -> "Hello " + name }
					async fun answer() -> I32 { -> 42 }
				""".trimIndent(),
			),
		)
		val main = CrescentParser.invoke(
			Path("main.crescent"),
			CrescentLexer.invoke(
				"""
					enum Color(label: String) {
						RED("red")
						BLUE("blue")
					}

					fun main {
						val cat = Cat("Luna")
						println(cat.name())
						println(greet())
						println(await(answer()))
						val color = Color.RED
						println(color.label)
						when(color) {
							.RED -> { println("matched") }
							else -> { println("missed") }
						}
					}
				""".trimIndent(),
			),
		).importing(
			library,
			"Cat" to Node.ModuleSymbolKind.STRUCT,
			"greet" to Node.ModuleSymbolKind.FUNCTION,
			"answer" to Node.ModuleSymbolKind.FUNCTION,
		)

		assertEquals(
			"Luna\nHello Moon\n42\nred\nmatched\n",
			collectSystemOut { CrescentVM(listOf(main, library), main).use { it.invoke() } },
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
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		)
	}

	@Test
	fun defaultArgumentTypeErrorsAreSourceAware() {
		val file = CrescentParser.invoke(
			Path("defaults.crescent"),
			CrescentLexer.invoke(
				"""
					fun value(input: I32 = "wrong") -> I32 { -> input }
					fun main { println(value()) }
				""".trimIndent(),
			),
		)

		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(file), file).use { it.invoke() }
		}
		assertEquals(Path("defaults.crescent"), exception.sourcePath)
		assertTrue(exception.detail.contains("Parameter input expects"))
	}

	@Test
	fun functionsDoNotCaptureCallerLocals() {
		val file = CrescentParser.invoke(
			Path("frames.crescent"),
			CrescentLexer.invoke(
				"""
					fun leaked() -> I32 { -> secret }
					fun main {
						val secret = 7
						println(leaked())
					}
				""".trimIndent(),
			),
		)

		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(file), file).use { it.invoke() }
		}
		assertTrue(exception.detail.contains("Unknown identifier: secret"))
	}

	@Test
	fun forLoopEvaluatesBoundsAndPropagatesReturn() {
		val parsed = CrescentParser.invoke(
			Path("ranges.crescent"),
			CrescentLexer.invoke(
				"""
					fun find() -> I32 {
						val start = 1
						val end = 3
						for value in 0..0 {
							if (value == 2) { -> value }
						}
						-> -1
					}
					fun main { println(find()) }
				""".trimIndent(),
			),
		)
		val find = parsed.functions.getValue("find")
		val loop = find.innerCode.nodes[2] as Node.Statement.For
		val dynamicLoop = loop.copy(
			ranges = listOf(
				Node.Statement.Range(
					Node.Identifier("start"),
					Node.Identifier("end"),
				),
			),
		)
		val updatedFind = find.copy(
			innerCode = Node.Statement.Block(
				find.innerCode.nodes.toMutableList().also { it[2] = dynamicLoop },
			),
		)
		val file = parsed.copy(functions = parsed.functions + (updatedFind.name to updatedFind))

		assertEquals("2\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun forLoopConsumesContinueAndBreak() {
		val parsed = CrescentParser.invoke(
			Path("loop-control.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						for value in 0..4 {
							if (value == 1) { println("continue") }
							if (value == 3) { println("break") }
							println(value)
						}
					}
				""".trimIndent(),
			),
		)
		val main = parsed.mainFunction!!
		val loop = main.innerCode.nodes.single() as Node.Statement.For
		val continueIf = (loop.block.nodes[0] as Node.Statement.If).copy(
			block = Node.Statement.Block(listOf(CrescentToken.Keyword.CONTINUE)),
		)
		val breakIf = (loop.block.nodes[1] as Node.Statement.If).copy(
			block = Node.Statement.Block(listOf(CrescentToken.Keyword.BREAK)),
		)
		val controlledLoop = loop.copy(
			block = Node.Statement.Block(listOf(continueIf, breakIf, loop.block.nodes[2])),
		)
		val updatedMain = main.copy(innerCode = Node.Statement.Block(listOf(controlledLoop)))
		val file = parsed.copy(functions = parsed.functions + (updatedMain.name to updatedMain), mainFunction = updatedMain)

		assertEquals("0\n2\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun arrayAssignmentsEvaluateIndicesAndFileStateIsMutable() {
		val file = CrescentParser.invoke(
			Path("state.crescent"),
			CrescentLexer.invoke(
				"""
					var count = 1
					fun increment { count += 1 }
					fun main {
						var values = [1, 2]
						val index = 0
						values[index + 1] = 9
						increment()
						println(values[1])
						println(count)
					}
				""".trimIndent(),
			),
		)

		assertEquals("9\n2\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun awaitPreservesRuntimeFailureAndClosedVmRejectsWork() {
		val file = CrescentParser.invoke(
			Path("async.crescent"),
			CrescentLexer.invoke(
				"""
					async fun fail() -> I32 { -> missing }
					fun main { await(fail()) }
				""".trimIndent(),
			),
		)
		val vm = CrescentVM(listOf(file), file)
		val exception = assertFailsWith<CrescentRuntimeException> { vm.invoke() }
		assertEquals(Path("async.crescent"), exception.sourcePath)
		assertTrue(exception.detail.contains("Unknown identifier: missing"))

		vm.close()
		assertFailsWith<IllegalStateException> { vm.invokeAsync() }
	}

	@Test
	fun overlappingInvocationsShareOneLazyFileInitialization() {
		val file = CrescentParser.invoke(
			Path("concurrent-state.crescent"),
			CrescentLexer.invoke(
				"""
					val shared = readLine("initializing")
					fun main { println(shared) }
				""".trimIndent(),
			),
		)
		val input = PipedInputStream()
		val inputWriter = PipedOutputStream(input)
		val initializationStarted = CountDownLatch(1)
		val output = object : ByteArrayOutputStream() {
			@Synchronized
			override fun write(bytes: ByteArray, offset: Int, length: Int) {
				super.write(bytes, offset, length)
				if (toString().contains("initializing")) initializationStarted.countDown()
			}
		}

		try {
			PrintStream(output, true).use { captured ->
				withSystemIo(input, captured) {
					CrescentVM(listOf(file), file).use { vm ->
						val first = vm.invokeAsync()
						assertTrue(initializationStarted.await(5, TimeUnit.SECONDS))
						val second = vm.invokeAsync()
						inputWriter.write("ready\n".toByteArray())
						inputWriter.flush()
						first.get(5, TimeUnit.SECONDS)
						second.get(5, TimeUnit.SECONDS)
					}
				}
			}
		} finally {
			inputWriter.close()
			input.close()
		}

		assertEquals(1, Regex("initializing").findAll(output.toString()).count())
		assertEquals(2, Regex("ready").findAll(output.toString()).count())
	}

	@Test
	fun trueFileInitializationCycleRemainsDiagnostic() {
		val file = CrescentParser.invoke(
			Path("cyclic-state.crescent"),
			CrescentLexer.invoke(
				"""
					val first = second
					val second = first
					fun main { println(first) }
				""".trimIndent(),
			),
		)

		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(file), file).use { it.invoke() }
		}
		assertEquals(Path("cyclic-state.crescent"), exception.sourcePath)
		assertTrue(exception.detail.contains("Cyclic initialization of file variable first"))
	}

	@Test
	fun crossThreadFileInitializationCycleFailsInsteadOfDeadlocking() {
		val file = CrescentParser.invoke(
			Path("concurrent-cycle.crescent"),
			CrescentLexer.invoke(
				"""
					val first = initializeFirst()
					val second = initializeSecond()
					fun initializeFirst() -> String { readLine("first-started"); -> second }
					fun initializeSecond() -> String { readLine("second-started"); -> first }
					fun main(args: [String]) {
						if (args[0] == "first") { println(first) } else { println(second) }
					}
				""".trimIndent(),
			),
		)
		val input = PipedInputStream()
		val inputWriter = PipedOutputStream(input)
		val initializersStarted = CountDownLatch(2)
		val sawFirst = AtomicBoolean()
		val sawSecond = AtomicBoolean()
		val output = object : ByteArrayOutputStream() {
			@Synchronized
			override fun write(bytes: ByteArray, offset: Int, length: Int) {
				super.write(bytes, offset, length)
				val text = toString()
				if (text.contains("first-started") && sawFirst.compareAndSet(false, true)) initializersStarted.countDown()
				if (text.contains("second-started") && sawSecond.compareAndSet(false, true)) initializersStarted.countDown()
			}
		}

		try {
			PrintStream(output, true).use { captured ->
				withSystemIo(input, captured) {
					CrescentVM(listOf(file), file).use { vm ->
						val first = vm.invokeAsync(listOf("first"))
						val second = vm.invokeAsync(listOf("second"))
						assertTrue(initializersStarted.await(5, TimeUnit.SECONDS))
						inputWriter.write("one\ntwo\n".toByteArray())
						inputWriter.flush()
						val firstFailure = assertFailsWith<ExecutionException> { first.get(5, TimeUnit.SECONDS) }
						val secondFailure = assertFailsWith<ExecutionException> { second.get(5, TimeUnit.SECONDS) }
						assertTrue(firstFailure.cause is CrescentRuntimeException)
						assertTrue(secondFailure.cause is CrescentRuntimeException)
						assertTrue((firstFailure.cause as CrescentRuntimeException).detail.contains("Cyclic initialization"))
					}
				}
			}
		} finally {
			inputWriter.close()
			input.close()
		}
	}

	@Test
	fun overlappingInvocationsShareOneLazyObjectInitialization() {
		val file = CrescentParser.invoke(
			Path("concurrent-object.crescent"),
			CrescentLexer.invoke(
				"""
					object Shared { val value = readLine("object-started") }
					fun main { println(Shared.value) }
				""".trimIndent(),
			),
		)
		val input = PipedInputStream()
		val inputWriter = PipedOutputStream(input)
		val initializationStarted = CountDownLatch(1)
		val output = object : ByteArrayOutputStream() {
			@Synchronized
			override fun write(bytes: ByteArray, offset: Int, length: Int) {
				super.write(bytes, offset, length)
				if (toString().contains("object-started")) initializationStarted.countDown()
			}
		}

		try {
			PrintStream(output, true).use { captured ->
				withSystemIo(input, captured) {
					CrescentVM(listOf(file), file).use { vm ->
						val first = vm.invokeAsync()
						assertTrue(initializationStarted.await(5, TimeUnit.SECONDS))
						val second = vm.invokeAsync()
						inputWriter.write("shared\n".toByteArray())
						inputWriter.flush()
						first.get(5, TimeUnit.SECONDS)
						second.get(5, TimeUnit.SECONDS)
					}
				}
			}
		} finally {
			inputWriter.close()
			input.close()
		}

		assertEquals(1, Regex("object-started").findAll(output.toString()).count())
		assertEquals(2, Regex("shared").findAll(output.toString()).count())
	}

	@Test
	fun closeRacingInvokeAsyncHasOnlyDefinedOutcomes() {
		val file = CrescentParser.invoke(
			Path("close-race.crescent"),
			CrescentLexer.invoke("fun main {}"),
		)

		repeat(50) {
			val vm = CrescentVM(listOf(file), file)
			val start = CountDownLatch(1)
			val submission = java.util.concurrent.CompletableFuture.supplyAsync {
				start.await()
				runCatching { vm.invokeAsync() }
			}
			val closing = java.util.concurrent.CompletableFuture.runAsync {
				start.await()
				vm.close()
			}
			start.countDown()
			val result = submission.get(5, TimeUnit.SECONDS)
			closing.get(5, TimeUnit.SECONDS)
			result.fold(
				onSuccess = { it.get(5, TimeUnit.SECONDS) },
				onFailure = { assertTrue(it is IllegalStateException, "Unexpected close-race failure: $it") },
			)
		}
	}

	@Test
	fun compoundUpdatesAreAtomicAcrossGlobalsObjectFieldsAndAliasedArraySlots() {
		val file = CrescentParser.invoke(
			Path("atomic-compound.crescent"),
			CrescentLexer.invoke(
				"""
					var global: I32 = 0
					var indices: I32 = 0
					var values: [I32] = [0]
					val firstAlias = values
					val secondAlias = values
					object Shared { var value: I32 = 0 }
					fun nextIndex() -> I32 { indices += 1; -> 0 }
					fun main(args: [String]) {
						for iteration in 0..7 {
							global += 1
							Shared.value += 1
							if (args[0] == "first") { firstAlias[nextIndex()] += 1 }
							else { secondAlias[nextIndex()] += 1 }
						}
					}
					fun readGlobal() -> I32 { -> global }
					fun readObject() -> I32 { -> Shared.value }
					fun readArray() -> I32 { -> values[0] }
					fun readIndices() -> I32 { -> indices }
				""".trimIndent(),
			),
		)
		val workers = 128

		CrescentVM(listOf(file), file).use { vm ->
			val start = CountDownLatch(1)
			val submissions = (0 until workers).map {
				java.util.concurrent.CompletableFuture.supplyAsync {
					start.await()
					vm.invokeAsync(listOf(if (it % 2 == 0) "first" else "second")).get(10, TimeUnit.SECONDS)
				}
			}
			start.countDown()
			submissions.forEach { it.get(15, TimeUnit.SECONDS) }

			fun read(name: String): Node = vm.runFunction(
				file.functions.getValue(name),
				emptyList(),
				CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
			)

			val expected = Node.Primitive.Number.I32(workers * 8)
			assertEquals(expected, read("readGlobal"))
			assertEquals(expected, read("readObject"))
			assertEquals(expected, read("readArray"))
			assertEquals(expected, read("readIndices"), "The array index expression must run exactly once per compound update")
		}
	}

	@Test
	fun failedFileAndObjectInitializersAreStickyAcrossConcurrentAndRepeatedAccess() {
		val file = CrescentParser.invoke(
			Path("sticky-initialization.crescent"),
			CrescentLexer.invoke(
				"""
					var fileAttempts = 0
					var objectAttempts = 0
					val brokenFile = failFile()
					object Broken { val value = failObject() }
					fun failFile() -> I32 { fileAttempts += 1; -> missingFile }
					fun failObject() -> I32 { objectAttempts += 1; -> missingObject }
					fun main(args: [String]) {
						if (args[0] == "file") { println(brokenFile) } else { println(Broken.value) }
					}
					fun readFileAttempts() -> I32 { -> fileAttempts }
					fun readObjectAttempts() -> I32 { -> objectAttempts }
				""".trimIndent(),
			),
		)

		CrescentVM(listOf(file), file).use { vm ->
			fun failure(argument: String): CrescentRuntimeException {
				val exception = assertFailsWith<ExecutionException> {
					vm.invokeAsync(listOf(argument)).get(10, TimeUnit.SECONDS)
				}
				return exception.cause as CrescentRuntimeException
			}

			val fileFailures = (0 until 32).map { java.util.concurrent.CompletableFuture.supplyAsync { failure("file") } }
			fileFailures.forEach { assertTrue(it.get(15, TimeUnit.SECONDS).detail.contains("missingFile")) }
			assertTrue(failure("file").detail.contains("missingFile"))

			val objectFailures = (0 until 32).map { java.util.concurrent.CompletableFuture.supplyAsync { failure("object") } }
			objectFailures.forEach { assertTrue(it.get(15, TimeUnit.SECONDS).detail.contains("missingObject")) }
			assertTrue(failure("object").detail.contains("missingObject"))

			fun read(name: String): Node = vm.runFunction(
				file.functions.getValue(name),
				emptyList(),
				CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
			)
			assertEquals(Node.Primitive.Number.I32(1), read("readFileAttempts"))
			assertEquals(Node.Primitive.Number.I32(1), read("readObjectAttempts"))
		}
	}

	@Test
	fun asyncLaunchDuringInitializationFailsFast() {
		val file = CrescentParser.invoke(
			Path("initializer-async.crescent"),
			CrescentLexer.invoke(
				"""
					async fun work() -> I32 { -> 1 }
					val illegal = work()
					fun main { println(illegal) }
				""".trimIndent(),
			),
		)

		CrescentVM(listOf(file), file).use { vm ->
			val exception = assertFailsWith<ExecutionException> { vm.invokeAsync().get(5, TimeUnit.SECONDS) }
			val cause = exception.cause as CrescentRuntimeException
			assertEquals(Path("initializer-async.crescent"), cause.sourcePath)
			assertTrue(cause.detail.contains("cannot be launched during file or object initialization"))
		}
	}

	@Test
	fun futureRenderingNeverObservesOrJoinsUnresolvedCompletion() {
		val file = CrescentParser.invoke(
			Path("future-rendering.crescent"),
			CrescentLexer.invoke("fun main {}"),
		)

		CrescentVM(listOf(file), file).use { vm ->
			val unresolved = CompletableFuture<Node>()
			val rendering = CompletableFuture.supplyAsync {
				with(vm) { CrescentVM.Instance.Future(unresolved).asString() }
			}
			assertEquals("Future(pending)", rendering.get(1, TimeUnit.SECONDS))
			assertFalse(unresolved.isDone, "Rendering must neither observe nor complete/join the future")
		}
	}

	@Test
	fun awaitDuringInitializationRejectsBeforeEvaluatingItsArgument() {
		val file = CrescentParser.invoke(
			Path("initializer-await.crescent"),
			CrescentLexer.invoke(
				"""
					var attempts: I32 = 0
					async fun work() -> I32 { -> 1 }
					fun sideEffect() -> Future { attempts += 1; -> work() }
					val illegal = await(sideEffect())
					fun main { println(illegal) }
					fun readAttempts() -> I32 { -> attempts }
				""".trimIndent(),
			),
		)

		CrescentVM(listOf(file), file).use { vm ->
			val exception = assertFailsWith<ExecutionException> { vm.invokeAsync().get(5, TimeUnit.SECONDS) }
			val cause = exception.cause as CrescentRuntimeException
			assertTrue(cause.detail.contains("cannot be awaited during file or object initialization"))
			val attempts = vm.runFunction(
				file.functions.getValue("readAttempts"),
				emptyList(),
				CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
			)
			assertEquals(Node.Primitive.Number.I32(0), attempts)
		}
	}

	@Test
	fun recursiveCompositeRenderingAndEqualityAreCycleSafe() {
		val file = CrescentParser.invoke(
			Path("recursive-values.crescent"),
			CrescentLexer.invoke(
				"""
					struct Box(var value: Any)
					fun main {
						val first: [Any] = [0]
						first[0] = first
						val second: [Any] = [0]
						second[0] = second
						println(first)
						println(first == second)
						val wrapped: [Any] = [0]
						wrapped[0] = success(wrapped)
						println(wrapped)
						val firstBox = Box(0)
						firstBox.value = firstBox
						val secondBox = Box(0)
						secondBox.value = secondBox
						println(firstBox)
						println(firstBox == secondBox)
					}
				""".trimIndent(),
			),
		)

		assertEquals(
			"[<cycle>]\ntrue\n[Success(<cycle>)]\nBox(value=<cycle>)\ntrue\n",
			collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } },
		)
	}

	@Test
	fun astArrayLiteralDefensivelyCopiesItsElementsAndKeepsAStableHash() {
		val original = arrayOf<Node>(Node.Primitive.Number.I8(1))
		val literal = Node.Array(original)
		val equalLiteral = Node.Array(arrayOf(Node.Primitive.Number.I8(1)))
		val hash = literal.hashCode()

		original[0] = Node.Primitive.Number.I8(2)

		assertEquals(equalLiteral, literal)
		assertEquals(hash, literal.hashCode())
		assertEquals(Node.Primitive.Number.I8(1), literal.values.single())
		assertFailsWith<UnsupportedOperationException> {
			(literal.values as MutableList<Node>)[0] = Node.Primitive.Number.I8(3)
		}
	}

	@Test
	fun methodParametersAndLocalsShadowReceiverFieldsForReadsAndWrites() {
		val file = CrescentParser.invoke(
			Path("shadowing.crescent"),
			CrescentLexer.invoke(
				"""
					struct Box(val value: I32)
					impl Box {
						fun parameter(value: I32) -> I32 { -> value }
						fun local() -> I32 { var value = 4; value += 1; -> value }
					}
					fun main {
						val box = Box(10)
						println(box.parameter(3))
						println(box.local())
						println(box.value)
					}
				""".trimIndent(),
			),
		)

		assertEquals("3\n5\n10\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun mutableObjectFieldsCanBeReassignedByObjectFunctions() {
		val file = CrescentParser.invoke(
			Path("mutable-object.crescent"),
			CrescentLexer.invoke(
				"""
					object Counter {
						var value = 1
						fun increment { value += 1; println(value) }
					}
					fun main { Counter.increment() }
				""".trimIndent(),
			),
		)

		assertEquals("2\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun linkedFileVariablesUseTheSameBindingForReadAndWrite() {
		val library = CrescentParser.invoke(
			Path("library.crescent"),
			CrescentLexer.invoke(
				"""
					var shared = 1
					fun printShared { println(shared) }
				""".trimIndent(),
			),
		)
		val main = CrescentParser.invoke(
			Path("main.crescent"),
			CrescentLexer.invoke("fun main { shared += 1; printShared() }"),
		).importing(
			library,
			"shared" to Node.ModuleSymbolKind.VARIABLE,
			"printShared" to Node.ModuleSymbolKind.FUNCTION,
		)

		assertEquals("2\n", collectSystemOut { CrescentVM(listOf(main, library), main).use { it.invoke() } })
	}

	@Test
	fun duplicateLinkedVariablesAreDiagnosedInsteadOfDependingOnFileOrder() {
		val first = CrescentParser.invoke(Path("first.crescent"), CrescentLexer.invoke("val duplicate = 1"))
		val second = CrescentParser.invoke(Path("second.crescent"), CrescentLexer.invoke("val duplicate = 2"))
		val main = CrescentParser.invoke(
			Path("main.crescent"),
			CrescentLexer.invoke("fun main { println(duplicate) }"),
		)

		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(main, first, second), main).use { it.invoke() }
		}
		assertTrue(exception.detail.contains("Ambiguous linked variable duplicate"))
		assertTrue(exception.detail.contains("first.crescent"))
		assertTrue(exception.detail.contains("second.crescent"))
	}

	@Test
	fun vmConstructionUsesMainIdentityAndOneCrossKindPackageNamespace() {
		val main = CrescentParser.invoke(Path("main-identity.crescent"), CrescentLexer.invoke("fun main {}"))
		val structurallyEqualCopy = main.copy()
		assertEquals(main, structurallyEqualCopy)
		assertFailsWith<IllegalArgumentException> { CrescentVM(listOf(main), structurallyEqualCopy) }

		val functionFile = CrescentParser.invoke(
			Path("function.crescent"),
			CrescentLexer.invoke("fun collision {} fun main {}"),
		)
		val globalFile = CrescentParser.invoke(Path("global.crescent"), CrescentLexer.invoke("val collision = 1"))
		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(functionFile, globalFile), functionFile)
		}
		assertTrue(exception.detail.contains("Ambiguous linked declaration collision"))
		assertTrue(exception.detail.contains("function"))
		assertTrue(exception.detail.contains("global"))
	}

	@Test
	fun vmFileSetIsUnmodifiableAndIndependentOfTheCallerList() {
		val main = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke("fun main {}"))
		val library = CrescentParser.invoke(Path("library.crescent"), CrescentLexer.invoke("val value = 1"))
		val callerFiles = arrayListOf(main, library)

		CrescentVM(callerFiles, main).use { vm ->
			callerFiles.clear()
			assertEquals(listOf(main, library), vm.files)
			assertFailsWith<UnsupportedOperationException> { (vm.files as MutableList<Node.File>).clear() }
			vm.invoke()
		}
	}

	@Test
	fun runtimeCompositeMemberMapsCannotBeStructurallyMutatedByCallers() {
		val file = CrescentParser.invoke(
			Path("runtime-inspection.crescent"),
			CrescentLexer.invoke("struct Box(val value: I32) object State { var count: I32 = 0 } fun make() -> Box { -> Box(1) } fun main {}"),
		)

		CrescentVM(listOf(file), file).use { vm ->
			val box = vm.runFunction(
				file.functions.getValue("make"),
				emptyList(),
				CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
			) as CrescentVM.Instance.Struct
			assertEquals(setOf("value"), box.variables.keys)
			assertFailsWith<UnsupportedOperationException> {
				(box.variables as MutableMap<String, CrescentVM.Variable>).clear()
			}
			val state = vm.runObject(
				file.objects.getValue("State"),
				CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
			)
			assertFailsWith<UnsupportedOperationException> {
				(state.variables as MutableMap<String, CrescentVM.Variable>).clear()
			}
			assertFailsWith<UnsupportedOperationException> {
				(vm.objects as MutableMap<String, CrescentVM.Instance.Object>).clear()
			}
		}
	}

	@Test
	fun invokeAsyncSnapshotsCallerArgumentsBeforeSubmission() {
		val file = CrescentParser.invoke(
			Path("async-arguments.crescent"),
			CrescentLexer.invoke(
				"""
					fun main(args: [String]) {
						readLine("started")
						println(args[0])
					}
				""".trimIndent(),
			),
		)
		val input = PipedInputStream()
		val inputWriter = PipedOutputStream(input)
		val started = CountDownLatch(1)
		val output = object : ByteArrayOutputStream() {
			@Synchronized
			override fun write(bytes: ByteArray, offset: Int, length: Int) {
				super.write(bytes, offset, length)
				if (toString().contains("started")) started.countDown()
			}
		}

		try {
			PrintStream(output, true).use { captured ->
				withSystemIo(input, captured) {
					CrescentVM(listOf(file), file).use { vm ->
						val arguments = arrayListOf("captured")
						val invocation = vm.invokeAsync(arguments)
						assertTrue(started.await(5, TimeUnit.SECONDS))
						arguments[0] = "mutated"
						inputWriter.write("continue\n".toByteArray())
						inputWriter.flush()
						invocation.get(5, TimeUnit.SECONDS)
					}
				}
			}
		} finally {
			inputWriter.close()
			input.close()
		}

		assertTrue(output.toString().contains("captured"))
		assertFalse(output.toString().contains("mutated"))
	}

	@Test
	fun invalidExpressionAndBuiltinTypesAreSourceAware() {
		val invalidPrograms = listOf(
			"fun main { println(true + 1) }" to "must be numeric",
			"fun main { if (1) { println(1) } }" to "if predicate must be Boolean",
			"fun main { println(sqrt(\"nope\")) }" to "sqrt argument must be numeric",
			"fun main { 1 = 2 }" to "Invalid assignment target",
		)

		for ((source, expected) in invalidPrograms) {
			val file = CrescentParser.invoke(Path("invalid-expression.crescent"), CrescentLexer.invoke(source))
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertEquals(Path("invalid-expression.crescent"), exception.sourcePath)
			assertTrue(exception.detail.contains(expected), "Expected '$expected' in '${exception.detail}'")
		}

		val file = CrescentParser.invoke(Path("invalid-expression.crescent"), CrescentLexer.invoke("fun main {}"))
		CrescentVM(listOf(file), file).use { vm ->
			val exception = assertFailsWith<CrescentRuntimeException> {
				vm.runExpression(
					Node.Expression(listOf(Node.Primitive.Number.I32(1), CrescentToken.Operator.ADD)),
					CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf()),
				)
			}
			assertEquals(Path("invalid-expression.crescent"), exception.sourcePath)
			assertTrue(exception.detail.contains("missing a left operand"))
		}
	}

	@Test
	fun arrayIndicesMustBeIntegralAndFitTheRuntimeIndexRange() {
		val invalidIndices = listOf("0.5" to "must be an integer", "2147483648" to "outside the supported integer range")
		for ((index, expected) in invalidIndices) {
			val file = CrescentParser.invoke(
				Path("invalid-index.crescent"),
				CrescentLexer.invoke("fun main { val values = [1]; println(values[$index]) }"),
			)
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertTrue(exception.detail.contains(expected), "Expected '$expected' in '${exception.detail}'")
		}
	}

	@Test
	fun largeIntegerComparisonsDoNotLosePrecisionThroughDoubleConversion() {
		val file = CrescentParser.invoke(
			Path("large-integers.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						println(9007199254740992 == 9007199254740993)
						println(9007199254740992 < 9007199254740993)
					}
				""".trimIndent(),
			),
		)

		assertEquals("false\ntrue\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })

		CrescentVM(listOf(file), file).use { vm ->
			val context = CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf())
			val comparison = vm.runExpression(
				Node.Expression(
					listOf(
						Node.Primitive.Number.U64(ULong.MAX_VALUE),
						Node.Primitive.Number.I64(Long.MAX_VALUE),
						CrescentToken.Operator.GREATER_COMPARE,
					),
				),
				context,
			)
			assertEquals(Node.Primitive.Boolean(true), comparison)
		}
	}

	@Test
	fun unsignedLongMaximumLiteralCastsAndExecutesWithoutWrapping() {
		val file = CrescentParser.invoke(
			Path("u64-max.crescent"),
			CrescentLexer.invoke("fun main { println(18446744073709551615 as U64) }"),
		)
		assertEquals("18446744073709551615\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun implementedTraitsParticipateInExactRuntimeCastsAndTests() {
		val file = CrescentParser.invoke(
			Path("trait-cast.crescent"),
			CrescentLexer.invoke(
				"""
					trait Named { fun name() -> String }
					struct Cat(val value: String)
					impl Cat : Named { override fun name() -> String { -> value } }
					fun main {
						val cat = Cat("Luna")
						println(cat is Named)
						println((cat as Named) is Cat)
					}
				""".trimIndent(),
			),
		)
		assertEquals("true\ntrue\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun resultPropagationFromDefaultArgumentsStaysInsideTheCalleeFrame() {
		val file = CrescentParser.invoke(
			Path("result-default.crescent"),
			CrescentLexer.invoke(
				"""
					fun forwarded(value: I32 = failure("default")?) -> I32? { -> success(value) }
					fun main { println(forwarded()) }
				""".trimIndent(),
			),
		)
		assertEquals("Failure(default)\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun memberVisibilityUsesDeclaringFileAndPackageForReadsAndWrites() {
		val library = CrescentParser.invoke(
			Path("visibility-library.crescent"),
			CrescentLexer.invoke("struct Vault(private var secret: I32, internal var shared: I32)"),
		).copy(packageId = "example.lib")
		val allowed = CrescentParser.invoke(
			Path("visibility-sibling.crescent"),
			CrescentLexer.invoke("fun main { val vault = Vault(1, 2); vault.shared = 3; println(vault.shared) }"),
		).copy(packageId = "example.lib").importing(library, "Vault" to Node.ModuleSymbolKind.STRUCT)
		assertEquals("3\n", collectSystemOut { CrescentVM(listOf(allowed, library), allowed).use { it.invoke() } })

		val denied = CrescentParser.invoke(
			Path("visibility-denied.crescent"),
			CrescentLexer.invoke("fun main { val vault = Vault(1, 2); vault.secret = 3 }"),
		).copy(packageId = "example.lib").importing(library, "Vault" to Node.ModuleSymbolKind.STRUCT)
		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(denied, library), denied).use { it.invoke() }
		}
		assertTrue(exception.detail.contains("not accessible"))

		val crossPackage = CrescentParser.invoke(
			Path("visibility-external.crescent"),
			CrescentLexer.invoke("fun main { val vault = Vault(1, 2); println(vault.shared) }"),
		).copy(packageId = "example.app").importing(library, "Vault" to Node.ModuleSymbolKind.STRUCT)
		assertTrue(assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(crossPackage, library), crossPackage).use { it.invoke() }
		}.detail.contains("not accessible"))
	}

	@Test
	fun transitiveTraitsAndQualifiedSignatureTypesUseExactDeclarationIdentity() {
		val transitive = CrescentParser.invoke(
			Path("transitive-traits.crescent"),
			CrescentLexer.invoke(
				"""
					trait Root { fun value() -> I32 }
					trait Middle {}
					impl Middle : Root { override fun value() -> I32 { -> 1 } }
					struct Leaf()
					impl Leaf : Middle { override fun value() -> I32 { -> 2 } }
					fun main { println(Leaf() is Root) }
				""".trimIndent(),
			),
		)
		assertEquals("true\n", collectSystemOut { CrescentVM(listOf(transitive), transitive).use { it.invoke() } })

		val contract = CrescentParser.invoke(
			Path("contract.crescent"),
			CrescentLexer.invoke("struct Thing() trait Contract { fun accept(value: Thing) -> I32 }"),
		).copy(packageId = "one")
		val other = CrescentParser.invoke(Path("other.crescent"), CrescentLexer.invoke("struct Thing()" )).copy(packageId = "two")
		val implementation = CrescentParser.invoke(
			Path("implementation.crescent"),
			CrescentLexer.invoke("struct Box() impl Box : ContractAlias { override fun accept(value: OtherThing) -> I32 { -> 1 } } fun main {}"),
		).copy(packageId = "app")
			.importingAs(contract, "ContractAlias", "Contract", Node.ModuleSymbolKind.TRAIT)
			.importingAs(other, "OtherThing", "Thing", Node.ModuleSymbolKind.STRUCT)
		assertTrue(assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(implementation, contract, other), implementation)
		}.detail.contains("does not match"))
	}

	@Test
	fun sameNamedCrossPackageValuesDoNotCompareEqualAndPrivateMethodsDoNotEscape() {
		val first = CrescentParser.invoke(
			Path("first-values.crescent"),
			CrescentLexer.invoke("struct Box(val value: I32) enum Shade { A }"),
		).copy(packageId = "first")
		val second = CrescentParser.invoke(
			Path("second-values.crescent"),
			CrescentLexer.invoke("struct Box(val value: I32) enum Shade { A }"),
		).copy(packageId = "second")
		val main = CrescentParser.invoke(
			Path("compare-values.crescent"),
			CrescentLexer.invoke("fun main { println(BoxA(1) == BoxB(1)); println(ShadeA.A == ShadeB.A) }"),
		).copy(packageId = "app")
			.importingAs(first, "BoxA", "Box", Node.ModuleSymbolKind.STRUCT)
			.importingAs(second, "BoxB", "Box", Node.ModuleSymbolKind.STRUCT)
			.importingAs(first, "ShadeA", "Shade", Node.ModuleSymbolKind.ENUM)
			.importingAs(second, "ShadeB", "Shade", Node.ModuleSymbolKind.ENUM)
		assertEquals("false\nfalse\n", collectSystemOut { CrescentVM(listOf(main, first, second), main).use { it.invoke() } })

		val api = CrescentParser.invoke(
			Path("private-api.crescent"),
			CrescentLexer.invoke("object API { private fun secret() -> I32 { -> 1 } } struct Service() impl Service { private fun secret() -> I32 { -> 2 } }"),
		).copy(packageId = "api")
		for ((source, name, kind) in listOf(
			Triple("fun main { println(API.secret()) }", "API", Node.ModuleSymbolKind.OBJECT),
			Triple("fun main { println(Service().secret()) }", "Service", Node.ModuleSymbolKind.STRUCT),
		)) {
			val caller = CrescentParser.invoke(Path("private-caller.crescent"), CrescentLexer.invoke(source))
				.copy(packageId = "app").importing(api, name to kind)
			assertTrue(assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(caller, api), caller).use { it.invoke() }
			}.detail.contains("not accessible"))
		}
	}

	@Test
	fun invalidContainmentAndResultPropagationAreExplicit() {
		val sources = listOf(
			"fun main { println(1 !in 2) }" to "expects a String or Array",
			"fun main { println(1?) }" to "Result propagation expects Success or Failure",
		)
		for ((source, expected) in sources) {
			val file = CrescentParser.invoke(Path("unsupported-operator.crescent"), CrescentLexer.invoke(source))
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertTrue(exception.detail.contains(expected), "Expected '$expected' in '${exception.detail}'")
		}
	}

	@Test
	fun bitwiseOperatorsRejectFloatsAndPreserveWideIntegers() {
		val file = CrescentParser.invoke(
			Path("bitwise.crescent"),
			CrescentLexer.invoke("fun main { println(4294967296 shl 1) }"),
		)
		assertEquals("8589934592\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })

		val invalid = CrescentParser.invoke(
			Path("bitwise-invalid.crescent"),
			CrescentLexer.invoke("fun main { println(1.5 and 1) }"),
		)
		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(invalid), invalid).use { it.invoke() }
		}
		assertTrue(exception.detail.contains("integral operands"))
	}

	@Test
	fun typedMutableStorageKeepsItsDeclaredTypeAcrossAssignments() {
		val file = CrescentParser.invoke(
			Path("typed-storage.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						var value: I64 = 1
						value = 2
						value = 2147483648
						println(value)
					}
				""".trimIndent(),
			),
		)
		assertEquals("2147483648\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun duplicateLinkedTypesAndImplementationMembersAreRejected() {
		val firstType = CrescentParser.invoke(Path("first-type.crescent"), CrescentLexer.invoke("struct Duplicate(val value: I32)"))
		val secondType = CrescentParser.invoke(Path("second-type.crescent"), CrescentLexer.invoke("object Duplicate"))
		val main = CrescentParser.invoke(Path("main.crescent"), CrescentLexer.invoke("fun main {}"))
		val typeException = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(main, firstType, secondType), main)
		}
		assertTrue(typeException.detail.contains("Ambiguous linked declaration Duplicate"))

		val declarationAndImpl = CrescentParser.invoke(
			Path("first-impl.crescent"),
			CrescentLexer.invoke("struct Box(val value: I32) impl Box { fun show { println(value) } }"),
		)
		val secondImpl = CrescentParser.invoke(
			Path("second-impl.crescent"),
			CrescentLexer.invoke("impl Box { fun show { println(0) } }"),
		).importing(declarationAndImpl, "Box" to Node.ModuleSymbolKind.STRUCT)
		val methodException = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(main, declarationAndImpl, secondImpl), main)
		}
		assertTrue(methodException.detail.contains("Ambiguous linked implementation method"))
		assertTrue(methodException.detail.contains("show"))
	}

	@Test
	fun currentAndLinkedGlobalDuplicatesAreRejectedAtVmConstruction() {
		val main = CrescentParser.invoke(
			Path("main.crescent"),
			CrescentLexer.invoke("val shared = 1 fun main {}"),
		)
		val linked = CrescentParser.invoke(Path("linked.crescent"), CrescentLexer.invoke("var shared = 2"))
		val exception = assertFailsWith<CrescentRuntimeException> { CrescentVM(listOf(main, linked), main) }
		assertTrue(exception.detail.contains("Ambiguous linked variable shared"))
		assertTrue(exception.detail.contains("main.crescent"))
		assertTrue(exception.detail.contains("linked.crescent"))
	}

	@Test
	fun implementationReferencesAreValidatedAtVmConstruction() {
		val missingTarget = CrescentParser.invoke(
			Path("missing-target.crescent"),
			CrescentLexer.invoke("impl Missing {} fun main {}"),
		)
		val targetException = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(missingTarget), missingTarget)
		}
		assertEquals(Path("missing-target.crescent"), targetException.sourcePath)
		assertTrue(targetException.detail.contains("Unknown implementation target Missing"))

		val missingSupertype = CrescentParser.invoke(
			Path("missing-supertype.crescent"),
			CrescentLexer.invoke("struct Cat() impl Cat : Missing {} fun main {}"),
		)
		val supertypeException = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(missingSupertype), missingSupertype)
		}
		assertEquals(Path("missing-supertype.crescent"), supertypeException.sourcePath)
		assertTrue(supertypeException.detail.contains("Unknown implementation supertype Missing"))
	}

	@Test
	fun declaredAndBuiltInImplementationReferencesRemainValid() {
		val file = CrescentParser.invoke(
			Path("known-implementation-types.crescent"),
			CrescentLexer.invoke(
				"""
					sealed Animal { object Unknown }
					struct Cat()
					impl Cat : Animal {}
					impl String {}
					fun main {}
				""".trimIndent(),
			),
		)

		CrescentVM(listOf(file), file).close()
	}

	@Test
	fun constructorEnumAndObjectInitializersUseTheirDeclarationContext() {
		val library = CrescentParser.invoke(
			Path("defaults-library.crescent"),
			CrescentLexer.invoke(
				"""
					val seed = 7
					struct Pair(val first: I32 = seed, val second: I32 = first + 1)
					enum Choice(value: I32, next: I32 = value + 1) { A(seed) }
					object Config {
						val value: I32 = seed
						val next: I32 = value + 1
					}
				""".trimIndent(),
			),
		)
		val main = CrescentParser.invoke(
			Path("defaults-main.crescent"),
			CrescentLexer.invoke(
				"""
					fun emit(seed: I32) {
						val pair = Pair()
						println(pair.first)
						println(pair.second)
						println(Choice.A.value)
						println(Choice.A.next)
						println(Config.value)
						println(Config.next)
					}
					fun main { emit(99) }
				""".trimIndent(),
			),
		).importing(
			library,
			"Pair" to Node.ModuleSymbolKind.STRUCT,
			"Choice" to Node.ModuleSymbolKind.ENUM,
			"Config" to Node.ModuleSymbolKind.OBJECT,
		)

		assertEquals(
			"7\n8\n7\n8\n7\n8\n",
			collectSystemOut { CrescentVM(listOf(main, library), main).use { it.invoke() } },
		)
	}

	@Test
	fun arrayLiteralElementsAreEvaluatedRecursively() {
		val file = CrescentParser.invoke(
			Path("arrays.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						val base = 2
						val values: [I32] = [base + 1, 4 * 2]
						val nested = [[base as I32], [(3 + 4) as I32]]
						val first = nested[0]
						val second = nested[1]
						println(values[0])
						println(values[1])
						println(first[0])
						println(second[0])
					}
				""".trimIndent(),
			),
		)

		assertEquals("3\n8\n2\n7\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun constructorAndEnumDefaultsKeepArityAndTypeFailuresSourceAware() {
		val programs = listOf(
			"struct Need(val value: I32) fun main { Need() }",
			"struct One(val value: I32 = 1) fun main { One(1, 2) }",
			"struct Bad(val value: I32 = \"bad\") fun main { Bad() }",
			"enum Need(value: I32) { A } fun main { println(Need.A) }",
			"enum One(value: I32 = 1) { A } fun main { One.A(1, 2) }",
			"enum Bad(value: I32 = \"bad\") { A } fun main { println(Bad.A.value) }",
		)

		programs.forEachIndexed { index, source ->
			val path = Path("invalid-default-$index.crescent")
			val file = CrescentParser.invoke(path, CrescentLexer.invoke(source))
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertEquals(path, exception.sourcePath)
		}
	}

	@Test
	fun inputAndEmptyEnumFailuresAreSourceAware() {
		val path = Path("builtin-errors.crescent")
		val file = CrescentParser.invoke(path, CrescentLexer.invoke("enum Empty {} fun main {}"))
		CrescentVM(listOf(file), file).use { vm ->
			val context = CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf())
			val cases = listOf(
				"readLine" to "",
				"readBoolean" to "maybe\n",
				"readDouble" to "number\n",
				"readInt" to "1.5\n",
			)
			for ((operation, input) in cases) {
				val exception = assertFailsWith<CrescentRuntimeException> {
					collectSystemOut {
						fakeUserInput(input) {
							vm.runNode(Node.IdentifierCall(operation, listOf(Node.Primitive.String("prompt"))), context)
						}
					}
				}
				assertEquals(path, exception.sourcePath)
			}

			val enumException = assertFailsWith<CrescentRuntimeException> {
				vm.runNode(
					Node.IdentifierCall("random"),
					context.copy(holder = Node.Type.Basic("Empty")),
				)
			}
			assertEquals(path, enumException.sourcePath)
			assertTrue(enumException.detail.contains("empty enum Empty"))
		}
	}

	@Test
	fun linkedObjectInitializerFailuresUseTheDeclarationPath() {
		val libraryPath = Path("object-library.crescent")
		val library = CrescentParser.invoke(
			libraryPath,
			CrescentLexer.invoke("object Broken { val value: I32 = missing }"),
		)
		val main = CrescentParser.invoke(
			Path("object-main.crescent"),
			CrescentLexer.invoke("fun main { println(Broken.value) }"),
		).importing(library, "Broken" to Node.ModuleSymbolKind.OBJECT)

		val exception = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(main, library), main).use { it.invoke() }
		}
		assertEquals(libraryPath, exception.sourcePath)

		val forwardPath = Path("object-forward.crescent")
		val forward = CrescentParser.invoke(
			forwardPath,
			CrescentLexer.invoke(
				"object Forward { val first: I32 = second val second: I32 = 1 } fun main { println(Forward.first) }",
			),
		)
		val forwardException = assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(forward), forward).use { it.invoke() }
		}
		assertEquals(forwardPath, forwardException.sourcePath)
		assertTrue(forwardException.detail.contains("before it was initialized"))
	}

	@Test
	fun explicitVariableInitializersAreTypeCheckedSourceAware() {
		val programs = listOf(
			"fun main { val bad: I32 = \"bad\" }",
			"var bad: I32 = \"bad\" fun main { println(bad) }",
			"const bad: I32 = \"bad\" fun main { println(bad) }",
			"object Bad { val value: I32 = \"bad\" } fun main { println(Bad.value) }",
		)

		programs.forEachIndexed { index, source ->
			val path = Path("initializer-type-$index.crescent")
			val file = CrescentParser.invoke(path, CrescentLexer.invoke(source))
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertEquals(path, exception.sourcePath)
			assertTrue(exception.detail.contains("expects Basic(I32)"))
		}
	}

	@Test
	fun structDefaultsMustBeTrailingIncludingInsideSealedTypes() {
		val programs = listOf(
			"struct Bad(val optional: I32 = 1, val required: I32) fun main {}",
			"sealed Parent { struct Bad(val optional: I32 = 1, val required: I32) } fun main {}",
		)

		programs.forEachIndexed { index, source ->
			val path = Path("struct-default-order-$index.crescent")
			val file = CrescentParser.invoke(path, CrescentLexer.invoke(source))
			val exception = assertFailsWith<CrescentRuntimeException> { CrescentVM(listOf(file), file) }
			assertEquals(path, exception.sourcePath)
			assertTrue(exception.detail.contains("cannot follow a default field"))
		}
	}

	@Test
	fun enumCollectionBuiltinsRejectArguments() {
		for (operation in listOf("random", "values")) {
			val path = Path("enum-$operation-arity.crescent")
			val file = CrescentParser.invoke(
				path,
				CrescentLexer.invoke("enum Color { RED } fun main { Color.$operation(1) }"),
			)
			val exception = assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
			assertEquals(path, exception.sourcePath)
			assertTrue(exception.detail.contains("expects 0 arguments"))
		}
	}

	@Test
	fun staticImplsCanConstructStructsAndSealedObjectsCanCallMethods() {
		val file = CrescentParser.invoke(
			Path("static-and-sealed-object.crescent"),
			CrescentLexer.invoke(
				"""
					struct Box(val value: I32)
					impl static Box {
						fun make() -> Box { -> Box(7) }
					}
					sealed Family {
						object Tool {
							fun answer() -> I32 { -> 42 }
						}
					}
					fun main {
						println(Box.make().value)
						println(Tool.answer())
					}
				""".trimIndent(),
			),
		)

		assertEquals("7\n42\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun defaultArrayLiteralsAreFreshWhileStoredArrayMutationPersists() {
		val file = CrescentParser.invoke(
			Path("array-ownership.crescent"),
			CrescentLexer.invoke(
				"""
					fun bump(values: [I32] = [0]) -> I32 {
						values[0] = values[0] + 1
						-> values[0]
					}
					fun main {
						println(bump())
						println(bump())
						val stored = [0]
						stored[0] = stored[0] + 1
						stored[0] = stored[0] + 1
						println(stored[0])
					}
				""".trimIndent(),
			),
		)

		assertEquals("1\n1\n2\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun declaredNumericBoundariesNormalizeTheirRuntimeValues() {
		val file = CrescentParser.invoke(
			Path("numeric-boundaries.crescent"),
			CrescentLexer.invoke(
				"""
					struct Box(val value: I32)
					enum Choice(value: I32) { A(1) }
					object Holder { val value: I32 = 1 }
					fun parameter(value: I32 = 1) -> I32 { -> value }
					fun returned() -> I32 { -> 1 }
					fun share(values: [I32]) -> [I32] { -> values }
					fun main {
						var value: I32 = 1
						println(typeOf(value))
						value = 2
						println(typeOf(value))
						println(typeOf(parameter()))
						println(typeOf(returned()))
						println(typeOf(Box(1).value))
						println(typeOf(Choice.A.value))
						println(typeOf(Holder.value))
						var values: [I32] = [1]
						val other = share(values)
						other[0] = 2
						println(typeOf(values[0]))
						val nested: [[I32]] = [[1]]
						val first = nested[0]
						println(typeOf(first[0]))
					}
				""".trimIndent(),
			),
		)

		assertEquals(
			List(9) { "Basic(I32)" }.joinToString(separator = "\n", postfix = "\n"),
			collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } },
		)
	}

	@Test
	fun inferredArrayElementConstraintsPersistThroughAliases() {
		val valid = CrescentParser.invoke(
			Path("inferred-array-valid.crescent"),
			CrescentLexer.invoke(
				"""
					fun share(values: [I8]) -> [I8] { -> values }
					fun main {
						var values = [1]
						val other = share(values)
						other[0] = 2
						println(typeOf(values[0]))
					}
				""".trimIndent(),
			),
		)
		assertEquals(
			"Basic(I8)\n",
			collectSystemOut { CrescentVM(listOf(valid), valid).use { it.invoke() } },
		)

		val invalidWrites = listOf("\"wrong\"", "128")
		invalidWrites.forEachIndexed { index, value ->
			val file = CrescentParser.invoke(
				Path("inferred-array-invalid-$index.crescent"),
				CrescentLexer.invoke(
					"""
						fun share(values: [I8]) -> [I8] { -> values }
						fun main {
							var values = [1]
							val other = share(values)
							other[0] = $value
						}
					""".trimIndent(),
				),
			)
			assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		}

		val frozen = CrescentParser.invoke(
			Path("inferred-array-frozen.crescent"),
			CrescentLexer.invoke(
				"""
					fun share(values: [I8]) -> [I8] { -> values }
					fun main {
						val inferred = [1]
						val widened: [I32] = share(inferred)
						println(widened[0])
					}
				""".trimIndent(),
			),
		)
		assertFailsWith<CrescentRuntimeException> {
			CrescentVM(listOf(frozen), frozen).use { it.invoke() }
		}
	}

	@Test
	fun inferredNumericVariablesKeepTheirInitialRuntimeType() {
		val file = CrescentParser.invoke(
			Path("inferred-numeric.crescent"),
			CrescentLexer.invoke(
				"""
					fun main {
						var value = 1
						value = 2
						println(typeOf(value))
					}
				""".trimIndent(),
			),
		)
		assertEquals("Basic(I8)\n", collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } })
	}

	@Test
	fun checkedNumericBoundariesRejectOverflowAndFractionLoss() {
		val sources = listOf(
			"fun main { val value: I8 = 128 }",
			"fun main { val value: I32 = 1.5 }",
			"fun receive(value: I8) {} fun main { receive(128) }",
			"fun returned() -> I8 { -> 128 } fun main { returned() }",
			"struct Box(val value: I8) fun main { Box(128) }",
			"enum Choice(value: I8) { A(128) } fun main { println(Choice.A.value) }",
			"""
				fun main {
					val values: [I8] = [1]
					values[0] = 128
				}
			""".trimIndent(),
		)

		sources.forEachIndexed { index, source ->
			val file = CrescentParser.invoke(Path("numeric-boundary-invalid-$index.crescent"), CrescentLexer.invoke(source))
			assertFailsWith<CrescentRuntimeException> {
				CrescentVM(listOf(file), file).use { it.invoke() }
			}
		}
	}

	@Test
	fun directAndIrNumericBoundaryRepresentationsMatch() {
		val file = CrescentParser.invoke(
			Path("numeric-boundary-parity.crescent"),
			CrescentLexer.invoke(
				"""
					struct Box(val value: I32)
					fun identity(value: I32 = 1) -> I32 { -> value }
					fun share(values: [I32]) -> [I32] { -> values }
					fun accept(value: I32?) -> I32? { -> value }
					fun main {
						var value: I32 = 1
						value = 2
						var inferred = 1
						inferred = 2
						val values: [I32] = [1]
						val other = share(values)
						other[0] = 2
						val failed: I32? = failure("typed")
						val raw = failure("raw")
						val adopted: I32? = accept(raw)
						println(typeOf(value))
						println(typeOf(inferred))
						println(typeOf(identity()))
						println(typeOf(Box(1).value))
						println(typeOf(values[0]))
						println(typeOf(failed))
						println(typeOf(raw))
						println(typeOf(adopted))
					}
				""".trimIndent(),
			),
		)

		val direct = collectSystemOut { CrescentVM(listOf(file), file).use { it.invoke() } }
		val ir = collectSystemOut { CrescentIRVM(CrescentIRCompiler.invoke(file)).use { it.invoke() } }
		val directTypes = direct.lineSequence().filter(String::isNotEmpty).toList()
		val irTypes = ir.lineSequence().filter(String::isNotEmpty).toList()
		assertEquals(irTypes.take(5), directTypes.take(5))
		assertEquals(
			listOf("Result(type=Basic(I32))", "Result(type=Basic(Any))", "Result(type=Basic(I32))"),
			directTypes.takeLast(3),
		)
	}

	@Test
	fun invalidIntegralArithmeticIsSourceAware() {
		val file = CrescentParser.invoke(Path("arithmetic.crescent"), CrescentLexer.invoke("fun main {}"))
		CrescentVM(listOf(file), file).use { vm ->
			val context = CrescentVM.BlockContext(file, file, mutableMapOf(), mutableMapOf())
			val expressions = listOf(
				Node.Expression(listOf(Node.Primitive.Number.I32(1), Node.Primitive.Number.I32(0), CrescentToken.Operator.DIV)),
				Node.Expression(listOf(Node.Primitive.Number.I32(1), Node.Primitive.Number.I32(0), CrescentToken.Operator.REM)),
				Node.Expression(listOf(Node.Primitive.Number.U8(1u.toUByte()), Node.Primitive.Number.I8(1), CrescentToken.Operator.ADD)),
				Node.Expression(listOf(Node.Primitive.Number.I64(Long.MIN_VALUE), Node.Primitive.Number.I8(-1), CrescentToken.Operator.DIV)),
			)
			for (expression in expressions) {
				val exception = assertFailsWith<CrescentRuntimeException> { vm.runExpression(expression, context) }
				assertEquals(Path("arithmetic.crescent"), exception.sourcePath)
			}
		}
	}

	private fun Node.File.importing(source: Node.File, vararg symbols: Pair<String, Node.ModuleSymbolKind>): Node.File = copy(
		importedSymbols = importedSymbols + symbols.associate { (name, kind) ->
			name to Node.ModuleSymbol(source.packageId, source.path, name, kind, CrescentToken.Visibility.PUBLIC)
		},
	)

	private fun Node.File.importingAs(
		source: Node.File,
		alias: String,
		sourceName: String,
		kind: Node.ModuleSymbolKind,
	): Node.File = copy(
		importedSymbols = importedSymbols + (alias to Node.ModuleSymbol(
			source.packageId,
			source.path,
			sourceName,
			kind,
			CrescentToken.Visibility.PUBLIC,
		)),
	)

}
