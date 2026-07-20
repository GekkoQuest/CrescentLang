package dev.twelveoclock.lang.crescent.utils

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream

private val systemIoLock = Any()

internal fun collectSystemOut(block: () -> Unit): String =
	synchronized(systemIoLock) {
		val original = System.out
		val bytes = ByteArrayOutputStream()
		PrintStream(bytes).use { captured ->
			try {
				System.setOut(captured)
				block()
			} finally {
				System.setOut(original)
			}
		}

		bytes.toString().replace("\r\n", "\n")
	}

internal fun fakeUserInput(input: String, block: () -> Unit) {
	synchronized(systemIoLock) {
		val original = System.`in`
		ByteArrayInputStream(input.toByteArray()).use { replacement ->
			try {
				System.setIn(replacement)
				block()
			} finally {
				System.setIn(original)
			}
		}
	}
}

internal fun withSystemIo(input: InputStream, output: PrintStream, block: () -> Unit) {
	synchronized(systemIoLock) {
		val originalInput = System.`in`
		val originalOutput = System.out
		try {
			System.setIn(input)
			System.setOut(output)
			block()
		} finally {
			System.setIn(originalInput)
			System.setOut(originalOutput)
		}
	}
}
