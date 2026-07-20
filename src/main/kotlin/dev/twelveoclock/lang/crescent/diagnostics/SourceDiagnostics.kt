package dev.twelveoclock.lang.crescent.diagnostics

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

data class SourcePosition(
	val offset: Int,
	val line: Int,
	val column: Int,
) {
	init {
		require(offset >= 0) { "Source offsets cannot be negative" }
		require(line >= 1) { "Source lines are one-based" }
		require(column >= 1) { "Source columns are one-based" }
	}
}

data class SourceSpan(
	val sourceId: String,
	val start: SourcePosition,
	val end: SourcePosition,
) {
	init {
		require(sourceId.isNotBlank()) { "A source identifier cannot be blank" }
		require('\\' !in sourceId) { "Source identifiers must use '/' separators" }
		require(start.offset <= end.offset) { "A source span end cannot precede its start" }
		require(start.line < end.line || start.line == end.line && start.column <= end.column) {
			"A source span end position cannot precede its start position"
		}
		require(start.offset != end.offset || start.line == end.line && start.column == end.column) {
			"Equal source offsets must identify the same position"
		}
	}

	fun renderLocation(): String =
		"$sourceId:${start.line}:${start.column}-${end.line}:${end.column}"

	companion object {
		fun normalizeSourceId(sourceId: String): String = sourceId.replace('\\', '/')
		fun normalizeSourceId(path: Path): String = normalizeSourceId(path.normalize().toString())
	}
}

/** Immutable source text used for the canonical UTF-16 offset-to-position conversion. */
class SourceText(sourceId: String, val text: String) {
	val sourceId: String = SourceSpan.normalizeSourceId(sourceId)
	private val lineStarts: IntArray = buildList {
		add(0)
		var offset = 0
		while (offset < text.length) {
			when (text[offset]) {
				'\r' -> {
					offset += if (offset + 1 < text.length && text[offset + 1] == '\n') 2 else 1
					add(offset)
				}
				'\n' -> {
					offset++
					add(offset)
				}
				else -> offset++
			}
		}
	}.toIntArray()

	constructor(path: Path, text: String) : this(SourceSpan.normalizeSourceId(path), text)

	init {
		require(this.sourceId.isNotBlank()) { "A source identifier cannot be blank" }
	}

	fun position(offset: Int): SourcePosition {
		require(offset in 0..text.length) { "Source offset $offset is outside 0..${text.length}" }
		var low = 0
		var high = lineStarts.lastIndex
		while (low <= high) {
			val middle = (low + high).ushr(1)
			if (lineStarts[middle] <= offset) low = middle + 1 else high = middle - 1
		}
		val lineIndex = high.coerceAtLeast(0)
		return SourcePosition(offset, lineIndex + 1, offset - lineStarts[lineIndex] + 1)
	}

	fun span(startOffset: Int, endOffset: Int): SourceSpan {
		require(startOffset <= endOffset) { "A source span end cannot precede its start" }
		return SourceSpan(sourceId, position(startOffset), position(endOffset))
	}

	fun point(offset: Int): SourceSpan = span(offset, offset)
}

enum class DiagnosticSeverity(val label: String) {
	ERROR("error"),
	WARNING("warning"),
	INFO("info"),
}

data class Diagnostic(
	val severity: DiagnosticSeverity,
	val message: String,
	val span: SourceSpan? = null,
	val code: String? = null,
) {
	init {
		require(message.isNotBlank()) { "A diagnostic message cannot be blank" }
		require(code == null || code.isNotBlank()) { "A diagnostic code cannot be blank" }
	}

	fun render(): String = buildString {
		span?.let { append(it.renderLocation()).append(": ") }
		append(severity.label).append(": ").append(message)
	}
}

open class DiagnosticException(
	val diagnostic: Diagnostic,
	cause: Throwable? = null,
) : IllegalArgumentException(diagnostic.render(), cause) {
	val span: SourceSpan? get() = diagnostic.span
	val detail: String get() = diagnostic.message
}

/**
 * Weak, referential-identity sidecar for compatibility-preserving AST/IR spans.
 * Equal-but-distinct objects are independent and neither keys nor values retain nodes.
 */
object SourceLocations {
	private val queue = ReferenceQueue<Any>()
	private val locations = ConcurrentHashMap<IdentityWeakReference, SourceSpan>()

	fun <T : Any> attach(value: T, span: SourceSpan?): T {
		drain()
		if (span != null && !isSharedSingleton(value)) locations[IdentityWeakReference(value, queue)] = span
		return value
	}

	fun spanOf(value: Any?): SourceSpan? {
		if (value == null) return null
		drain()
		return locations[IdentityWeakReference(value)]
	}

	fun <T : Any> copy(from: Any?, to: T): T = attach(to, spanOf(from))

	fun <T : Any> copyOrAttach(from: Any?, to: T, fallback: SourceSpan?): T =
		attach(to, spanOf(from) ?: fallback)

	internal fun trackedLocationCount(): Int {
		drain()
		return locations.size
	}

	private fun drain() {
		while (true) locations.remove(queue.poll() as? IdentityWeakReference ?: break)
	}

	private fun isSharedSingleton(value: Any): Boolean {
		if (value is Enum<*>) return true
		return runCatching {
			value.javaClass.getField("INSTANCE").get(null) === value
		}.getOrDefault(false)
	}

	private class IdentityWeakReference : WeakReference<Any> {
		private val identityHash: Int

		constructor(value: Any, queue: ReferenceQueue<Any>) : super(value, queue) {
			identityHash = System.identityHashCode(value)
		}

		constructor(value: Any) : super(value) {
			identityHash = System.identityHashCode(value)
		}

		override fun hashCode(): Int = identityHash

		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (other !is IdentityWeakReference || identityHash != other.identityHash) return false
			val left = get()
			return left != null && left === other.get()
		}
	}
}

fun Any?.sourceSpanOrNull(): SourceSpan? = SourceLocations.spanOf(this)
