package dev.twelveoclock.lang.crescent.vm

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import java.nio.file.Path

class CrescentRuntimeException(
	val sourcePath: Path,
	val detail: String,
	cause: Throwable? = null,
) : IllegalStateException("$sourcePath: $detail", cause) {
	@Volatile
	var sourceSpan: SourceSpan? = null
		private set

	val diagnostic: Diagnostic
		get() = Diagnostic(DiagnosticSeverity.ERROR, detail, sourceSpan)

	constructor(sourcePath: Path, sourceSpan: SourceSpan, detail: String, cause: Throwable? = null) :
		this(sourcePath, detail, cause) {
		this.sourceSpan = sourceSpan
	}

	fun withSourceSpan(span: SourceSpan?): CrescentRuntimeException {
		if (span != null && sourceSpan == null) sourceSpan = span
		return this
	}

	override val message: String
		get() = sourceSpan?.let { diagnostic.render() } ?: super.message.orEmpty()
}
