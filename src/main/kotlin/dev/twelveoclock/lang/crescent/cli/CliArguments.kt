package dev.twelveoclock.lang.crescent.cli

import java.nio.file.InvalidPathException
import java.nio.file.Path

enum class CrescentCommand {
	RUN,
	IR,
	PTIR,
	PTIR_RUN,
	KOTLIN_TO_CRESCENT,
}

sealed interface CliRequest {
	data object Help : CliRequest

	data class Execute(
		val command: CrescentCommand,
		val input: Path,
		val programArgs: List<String>,
	) : CliRequest
}

class CliException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object CliArguments {

	private val commands = mapOf(
		"run" to CrescentCommand.RUN,
		"ir" to CrescentCommand.IR,
		"ptir" to CrescentCommand.PTIR,
		"ptir-run" to CrescentCommand.PTIR_RUN,
		"kotlin-to-crescent" to CrescentCommand.KOTLIN_TO_CRESCENT,
	)

	fun parse(args: List<String>): CliRequest {
		if (args.isEmpty()) return CliRequest.Help
		if (args.first() in setOf("help", "--help", "-h")) {
			if (args.size != 1) throw CliException("Help does not accept additional arguments")
			return CliRequest.Help
		}

		val separator = args.indexOf("--").takeIf { it >= 0 }
		val cliArgs = if (separator == null) args else args.take(separator)
		val programArgs = if (separator == null) emptyList() else args.drop(separator + 1)
		if (cliArgs.isEmpty()) throw CliException("A source file or project directory is required")

		val explicitCommand = commands[cliArgs.first()]
		val command = explicitCommand ?: CrescentCommand.RUN
		val inputs = if (explicitCommand == null) cliArgs else cliArgs.drop(1)
		if (inputs.size != 1) {
			throw CliException(
				if (inputs.isEmpty()) "A source file or project directory is required"
				else "Expected one source file or project directory, but received ${inputs.size}",
			)
		}
		if (separator != null && command !in setOf(CrescentCommand.RUN, CrescentCommand.IR, CrescentCommand.PTIR_RUN)) {
			throw CliException("${command.displayName} does not accept program arguments")
		}

		val input = try {
			Path.of(inputs.single())
		} catch (_: InvalidPathException) {
			throw CliException("Invalid input path: ${inputs.single()}")
		}
		return CliRequest.Execute(command, input, programArgs)
	}

	private val CrescentCommand.displayName: String
		get() = name.lowercase().replace('_', '-')
}
