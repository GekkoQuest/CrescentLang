package dev.twelveoclock.lang.crescent.vm

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Text input and output supplied by a Crescent host.
 *
 * Stream-backed adapters decode input lines as UTF-8 and serialize input and output independently,
 * so a blocked read never prevents output. The caller retains ownership of both streams: adapters
 * and VMs never close them.
 */
interface RuntimeIO {

	fun readLine(): String?

	fun print(value: String)

	fun println(value: String)

	companion object {

		@JvmStatic
		fun system(): RuntimeIO = streams(System.`in`, System.out)

		/**
		 * Adapts caller-owned streams using UTF-8 line decoding and independent input/output locks.
		 * Neither stream is closed by the returned adapter or by a VM that uses it.
		 */
		@JvmStatic
		fun streams(input: InputStream, output: PrintStream): RuntimeIO = StreamRuntimeIO(input, output)
	}
}

private class StreamRuntimeIO(input: InputStream, private val output: PrintStream) : RuntimeIO {

	private val inputLock = Any()
	private val outputLock = Any()
	private val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))

	override fun readLine(): String? = synchronized(inputLock) { reader.readLine() }

	override fun print(value: String) {
		synchronized(outputLock) { output.print(value) }
	}

	override fun println(value: String) {
		synchronized(outputLock) { output.println(value) }
	}
}
