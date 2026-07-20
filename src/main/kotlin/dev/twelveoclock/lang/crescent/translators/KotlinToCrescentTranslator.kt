package dev.twelveoclock.lang.crescent.translators

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import dev.twelveoclock.lang.crescent.diagnostics.SourceText

/** A precise, source-tied rejection of Kotlin that cannot be translated faithfully. */
class KotlinTranslationException internal constructor(
	diagnostic: Diagnostic,
) : DiagnosticException(diagnostic) {
	val translationSpan: SourceSpan get() = requireNotNull(span)
	val startOffset: Int get() = translationSpan.start.offset
	val endOffset: Int get() = translationSpan.end.offset
}

/**
 * Translates the structurally parsed Kotlin subset that has a faithful Crescent
 * representation. Unsupported Kotlin semantics fail with an exact half-open span.
 */
object KotlinToCrescentTranslator {
	fun translate(kotlinSource: String): String = translate(kotlinSource, DEFAULT_SOURCE_ID)

	fun translate(kotlinSource: String, sourceId: String): String = try {
		KotlinSubsetParser(kotlinSource).translate()
	} catch (failure: RawKotlinTranslationFailure) {
		val span = SourceText(sourceId, kotlinSource).span(failure.startOffset, failure.endOffset)
		throw KotlinTranslationException(
			Diagnostic(
				DiagnosticSeverity.ERROR,
				"${failure.detail} at offset ${failure.startOffset}..<${failure.endOffset}",
				span,
				code = "KOTLIN_TRANSLATION",
			),
		)
	}

	private const val DEFAULT_SOURCE_ID = "<kotlin>"
}
