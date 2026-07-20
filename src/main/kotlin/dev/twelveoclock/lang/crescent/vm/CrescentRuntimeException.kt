package dev.twelveoclock.lang.crescent.vm

import java.nio.file.Path

class CrescentRuntimeException(
	val sourcePath: Path,
	val detail: String,
	cause: Throwable? = null,
) : IllegalStateException("$sourcePath: $detail", cause)
