package dev.twelveoclock.lang.crescent.diagnostics

import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.iterator.PeekingTokenIterator
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.project.CrescentModuleResolutionException
import dev.twelveoclock.lang.crescent.project.CrescentModuleResolver
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentRuntimeException
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import java.lang.ref.WeakReference
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceDiagnosticsTest {
	@Test
	fun sourceTextUsesUtf16HalfOpenPositionsAndSingleCrLfNewlines() {
		val source = SourceText("folder\\sample.crescent", "a\r\n\uD83D\uDE00x")
		val span = source.span(3, 6)
		assertEquals("folder/sample.crescent:2:1-2:4", span.renderLocation())
		assertEquals(3, span.start.offset)
		assertEquals(6, span.end.offset)
	}

	@Test
	fun diagnosticRenderingIsStableAndSourceFreeDiagnosticsDoNotInventLocations() {
		val span = SourceText("sample.crescent", "bad").span(0, 3)
		assertEquals("sample.crescent:1:1-1:4: error: broken", Diagnostic(DiagnosticSeverity.ERROR, "broken", span).render())
		assertEquals("error: broken", Diagnostic(DiagnosticSeverity.ERROR, "broken").render())
	}

	@Test
	fun sourceLocationsUseWeakReferentialIdentity() {
		val first = Node.IdentifierCall("same")
		val second = Node.IdentifierCall("same")
		assertEquals(first, second)
		assertNotEquals(System.identityHashCode(first), System.identityHashCode(second))
		val firstSpan = SourceText("first.crescent", "same()").span(0, 4)
		val secondSpan = SourceText("second.crescent", "same()").span(0, 6)
		SourceLocations.attach(first, firstSpan)
		SourceLocations.attach(second, secondSpan)
		assertEquals(firstSpan, SourceLocations.spanOf(first))
		assertEquals(secondSpan, SourceLocations.spanOf(second))

		var disposable: Any? = Node.Identifier("temporary")
		SourceLocations.attach(disposable!!, firstSpan)
		val reference = WeakReference(disposable)
		disposable = null
		repeat(100) {
			if (reference.get() == null) return@repeat
			System.gc()
			Thread.yield()
		}
		assertNull(reference.get(), "The source-location sidecar must not strongly retain nodes")
		SourceLocations.trackedLocationCount()
	}

	@Test
	fun lexerAndCommentFilteringKeepExactTokenSpans() {
		val tokens = CrescentLexer.invoke(Path.of("src", "sample.crescent"), "val x = 1 # note\r\nprintln(x)")
		val iterator = PeekingTokenIterator.excludingComments(tokens)
		assertEquals("src/sample.crescent:1:1-1:4", iterator.spanAt(0)?.renderLocation())
		while (iterator.hasNext()) iterator.next()
		assertEquals("src/sample.crescent:2:10-2:11", iterator.lastSpan?.renderLocation())
	}

	@Test
	fun lexerAndParserFailuresRetainLegacyDetailsWithAuthoritativeSpans() {
		val lexical = assertFailsWith<DiagnosticException> {
			CrescentLexer.invoke(Path.of("bad.crescent"), "val x = @")
		}
		assertTrue(lexical.message.orEmpty().startsWith("bad.crescent:1:9-1:10: error:"))
		assertTrue(lexical.message.orEmpty().contains("offset 8"))

		val parse = assertFailsWith<DiagnosticException> {
			CrescentParser.invoke(Path.of("parse.crescent"), CrescentLexer.invoke("fun 123 {}"))
		}
		assertTrue(parse.message.orEmpty().startsWith("parse.crescent:1:5-1:8: error:"))
		assertTrue(parse.message.orEmpty().contains("token index 2"))
	}

	@Test
	fun directRuntimeAndLoweringFailuresExposeSourceSpans() {
		val file = CrescentParser.invoke(Path.of("runtime.crescent"), CrescentLexer.invoke("fun main { missing }"))
		val runtime = assertFailsWith<CrescentRuntimeException> { CrescentVM(listOf(file), file).use { it.invoke() } }
		assertEquals("runtime.crescent", runtime.sourceSpan?.sourceId)
		assertTrue(runtime.message.orEmpty().startsWith("runtime.crescent:"))

		val lowering = assertFailsWith<DiagnosticException> { CrescentIRCompiler.invoke(file) }
		assertEquals("runtime.crescent", lowering.span?.sourceId)
	}

	@Test
	fun resolverAndIrRuntimeFailuresExposeSourceSpans() {
		val path = Path.of("build", "diagnostic-project", "main.crescent").toAbsolutePath().normalize()
		val imported = CrescentParser.invoke(path, CrescentLexer.invoke("import missing::Thing fun main { }"))
		val resolver = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(path.parent, listOf(imported))
		}
		assertEquals(SourceSpan.normalizeSourceId(path), resolver.sourceSpan?.sourceId)
		assertTrue(resolver.message.orEmpty().contains(":1:1-1:22: error:"))

		val runtimeFile = CrescentParser.invoke(
			Path.of("lowered-runtime.crescent"),
			CrescentLexer.invoke("fun main { val values = [1]; println(values[2]) }"),
		)
		val runtime = assertFailsWith<CrescentRuntimeException> {
			CrescentIRVM(CrescentIRCompiler.invoke(runtimeFile)).use { it.invoke() }
		}
		assertEquals("lowered-runtime.crescent", runtime.sourceSpan?.sourceId)
		assertTrue(runtime.message.orEmpty().contains(": error:"))
	}

	@Test
	fun compilerTypeAndProgramValidationFailuresRemainSourceTied() {
		val path = Path.of("build", "diagnostic-project", "unknown-type.crescent").toAbsolutePath().normalize()
		val unknownType = CrescentParser.invoke(
			path,
			CrescentLexer.invoke("val value: Missing = 1 fun main { }"),
		)
		val linked = CrescentModuleResolver.link(path.parent, listOf(unknownType)).single()
		val typeFailure = assertFailsWith<DiagnosticException> { CrescentIRCompiler.invoke(linked) }
		assertEquals(SourceSpan.normalizeSourceId(path), typeFailure.span?.sourceId)
		assertTrue(typeFailure.message.orEmpty().contains(":1:12-1:19: error:"))

		val duplicate = linked.copy()
		SourceLocations.copy(linked, duplicate)
		val validation = assertFailsWith<DiagnosticException> {
			CrescentIRCompiler.invoke(listOf(linked, duplicate), linked)
		}
		assertEquals(SourceSpan.normalizeSourceId(path), validation.span?.sourceId)
	}

	@Test
	fun nestedTypeFailuresKeepTheInnermostSpanAndOneDiagnosticPrefix() {
		val source = "val value: [Missing] = [1] fun main { }"
		val path = Path.of("build", "diagnostic-project", "nested-type.crescent").toAbsolutePath().normalize()
		val parsed = CrescentParser.invoke(path, CrescentLexer.invoke(source))
		val linked = CrescentModuleResolver.link(path.parent, listOf(parsed)).single()

		val failure = assertFailsWith<DiagnosticException> { CrescentIRCompiler.invoke(linked) }
		val start = source.indexOf("Missing")
		assertEquals(SourceText(path, source).span(start, start + "Missing".length), failure.span)
		assertEquals(1, Regex("error:").findAll(failure.message.orEmpty()).count(), failure.message)
	}

	@Test
	fun parserAttachesExactSpansToPreviouslyUnlocatedDeclarations() {
		val source = "enum Choice(value: I32) { A(1) } trait Named { fun name(input: I32) } sealed Family { object Only } val first, second = 1 fun main { for item in 0..1 {} }"
		val path = Path.of("declarations.crescent")
		val text = SourceText(path, source)
		val file = CrescentParser.invoke(path, CrescentLexer.invoke(source))

		fun expected(fragment: String, startAt: Int = 0): SourceSpan {
			val start = source.indexOf(fragment, startAt)
			return text.span(start, start + fragment.length)
		}

		val enum = file.enums.getValue("Choice")
		assertEquals(text.span(0, source.indexOf(" trait")), SourceLocations.spanOf(enum))
		assertEquals(expected("value: I32"), SourceLocations.spanOf(enum.parameters.single()))
		assertEquals(expected("A(1)"), SourceLocations.spanOf(enum.structs.single()))

		val trait = file.traits.getValue("Named")
		assertEquals(
			text.span(source.indexOf("trait Named"), source.indexOf(" sealed")),
			SourceLocations.spanOf(trait),
		)
		assertEquals(expected("input: I32"), SourceLocations.spanOf(trait.functionTraits.single().params.single()))

		val sealed = file.sealeds.getValue("Family")
		assertEquals(
			text.span(source.indexOf("sealed Family"), source.indexOf(" val")),
			SourceLocations.spanOf(sealed),
		)
		val variableDeclaration = expected("val first, second = 1")
		assertEquals(variableDeclaration, SourceLocations.spanOf(file.variables.getValue("first")))
		assertEquals(variableDeclaration, SourceLocations.spanOf(file.variables.getValue("second")))

		val loop = file.mainFunction!!.innerCode.nodes.filterIsInstance<Node.Statement.For>().single()
		assertEquals(expected("0..1"), SourceLocations.spanOf(loop.ranges.single()))
	}

	@Test
	fun resolverDuplicateDeclarationsAlwaysExposeCanonicalSourceSpans() {
		val root = Path.of("build", "diagnostic-duplicates").toAbsolutePath().normalize()
		fun parse(relative: String, source: String): Node.File {
			val path = root.resolve(relative)
			return CrescentParser.invoke(path, CrescentLexer.invoke(source))
		}

		val enum = parse("types/first.crescent", "enum Shared { One }")
		val trait = parse("types/second.crescent", "trait Shared { }")
		val typeDuplicate = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(enum, trait))
		}
		assertEquals(SourceText(trait.path, "trait Shared { }").span(0, 16), typeDuplicate.sourceSpan)

		val firstGlobal = parse("globals/first.crescent", "val clash = 1")
		val secondGlobal = parse("globals/second.crescent", "val clash = 2")
		val globalDuplicate = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(firstGlobal, secondGlobal))
		}
		assertEquals(SourceText(secondGlobal.path, "val clash = 2").span(0, 13), globalDuplicate.sourceSpan)
	}

	@Test
	fun interpolationNestedLexingUsesOriginalSourceAndAbsoluteUtf16Offsets() {
		val path = Path.of("nested-interpolation.crescent")
		val source = "fun main {\r\n println(\"😀 value ${'$'}{@}\")\r\n}"
		val failure = assertFailsWith<DiagnosticException> {
			CrescentParser.invoke(path, CrescentLexer.invoke(path, source))
		}
		val invalidOffset = source.indexOf('@')
		assertEquals(SourceText(path, source).span(invalidOffset, invalidOffset + 1), failure.span)
		assertTrue(failure.message.orEmpty().contains("offset $invalidOffset"))

		val validSource = "fun main {\r\n println(\"😀 value ${'$'}{missing}\")\r\n}"
		val parsed = CrescentParser.invoke(path, CrescentLexer.invoke(path, validSource))
		val lowering = assertFailsWith<DiagnosticException> { CrescentIRCompiler.invoke(parsed) }
		val identifierOffset = validSource.indexOf("missing")
		assertEquals(SourceText(path, validSource).span(identifierOffset, identifierOffset + "missing".length), lowering.span)
	}

	@Test
	fun unterminatedInterpolationIsACanonicalExactSpanDiagnostic() {
		val path = Path.of("unterminated-interpolation.crescent")
		val source = "fun main { println(\"value ${'$'}{missing\") }"
		val failure = assertFailsWith<DiagnosticException> {
			CrescentParser.invoke(path, CrescentLexer.invoke(path, source))
		}
		val interpolationStart = source.indexOf("${'$'}{")
		val interpolationEnd = source.indexOf('"', interpolationStart)
		assertEquals(SourceText(path, source).span(interpolationStart, interpolationEnd), failure.span)
		assertTrue(failure.message.orEmpty().startsWith("unterminated-interpolation.crescent:"))
		assertTrue(failure.message.orEmpty().contains("Unterminated string interpolation starting at offset $interpolationStart"))
	}

	@Test
	fun sharedSingletonNodesNeverAcquireOccurrenceSpans() {
		val firstSpan = SourceText("first.crescent", "+").span(0, 1)
		val secondSpan = SourceText("second.crescent", "+").span(0, 1)
		SourceLocations.attach(CrescentToken.Operator.ADD, firstSpan)
		SourceLocations.attach(CrescentToken.Operator.ADD, secondSpan)
		SourceLocations.attach(Node.Type.Implicit, firstSpan)
		SourceLocations.attach(Node.Type.Implicit, secondSpan)
		assertNull(SourceLocations.spanOf(CrescentToken.Operator.ADD))
		assertNull(SourceLocations.spanOf(Node.Type.Implicit))

		val first = CrescentParser.invoke(Path.of("first.crescent"), CrescentLexer.invoke("val value = 1"))
		val second = CrescentParser.invoke(Path.of("second.crescent"), CrescentLexer.invoke("val value = 2"))
		assertEquals("first.crescent", SourceLocations.spanOf(first.variables.getValue("value"))?.sourceId)
		assertEquals("second.crescent", SourceLocations.spanOf(second.variables.getValue("value"))?.sourceId)
	}
}
