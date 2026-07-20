package dev.twelveoclock.lang.crescent.cli

import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.project.CrescentModuleResolutionException
import dev.twelveoclock.lang.crescent.ptir.PoderTechIrExecutionException
import dev.twelveoclock.lang.crescent.ptir.PoderTechIrExecutor
import dev.twelveoclock.lang.crescent.translators.KotlinToCrescentTranslator
import dev.twelveoclock.lang.crescent.translators.PoderTechIrTranslator
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentRuntimeException
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import dev.twelveoclock.lang.crescent.vm.RuntimeIO
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.readText

class CliExecutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object CrescentCli {

	const val HELP = """CrescentLang

  crescent run <file-or-directory> [-- program args]
  crescent <file-or-directory> [-- program args]
  crescent ir <file-or-directory> [-- program args]
  crescent ptir <file-or-directory>
  crescent ptir-run <file-or-directory> [-- program args]
  crescent kotlin-to-crescent <file.kt>"""

	@JvmOverloads
	fun run(
		args: Array<String>,
		out: PrintStream = System.out,
		err: PrintStream = System.err,
	): Int = run(args, out, err, System.`in`)

	fun run(
		args: Array<String>,
		out: PrintStream,
		err: PrintStream,
		input: InputStream,
	): Int {
		return try {
			when (val request = CliArguments.parse(args.toList())) {
				CliRequest.Help -> out.println(HELP)
				is CliRequest.Execute -> execute(request, out, input)
			}
			0
		} catch (exception: CliException) {
			err.println("error: ${exception.message}")
			err.println("Run 'crescent --help' for usage.")
			2
		} catch (exception: DiagnosticException) {
			err.println(exception.diagnostic.render())
			1
		} catch (exception: CrescentModuleResolutionException) {
			err.println(exception.diagnostic.render())
			1
		} catch (exception: CrescentRuntimeException) {
			err.println(exception.diagnostic.render())
			1
		} catch (exception: PoderTechIrExecutionException) {
			err.println(exception.diagnostic.render())
			1
		} catch (exception: Exception) {
			err.println("error: ${exception.message ?: exception::class.simpleName ?: "Program execution failed"}")
			1
		}
	}

	private fun execute(request: CliRequest.Execute, out: PrintStream, input: InputStream) {
		when (request.command) {
			CrescentCommand.RUN -> {
				val project = CrescentProjectLoader.load(request.input)
				executeDirect(project, request.programArgs, RuntimeIO.streams(input, out))
			}
			CrescentCommand.IR -> {
				val project = CrescentProjectLoader.load(request.input)
				val program = CrescentIRCompiler.invoke(project.files, project.mainFile)
				CrescentIRVM(program, RuntimeIO.streams(input, out)).use { it.invoke(request.programArgs) }
			}
			CrescentCommand.PTIR -> {
				val sourceSet = CrescentProjectLoader.loadSourceSet(request.input)
				out.println(PoderTechIrTranslator.translate(sourceSet.files))
			}
			CrescentCommand.PTIR_RUN -> {
				val project = CrescentProjectLoader.load(request.input)
				val program = PoderTechIrTranslator.translate(project.files)
				PoderTechIrExecutor(program, RuntimeIO.streams(input, out)).invokeMain(request.programArgs)
			}
			CrescentCommand.KOTLIN_TO_CRESCENT -> executeKotlinTranslation(request, out)
		}
	}

	private fun executeKotlinTranslation(request: CliRequest.Execute, out: PrintStream) {
		val input = request.input.toAbsolutePath().normalize()
		if (!Files.isRegularFile(input)) throw CliExecutionException("Kotlin source is not a file: $input")
		if (!input.fileName.toString().endsWith(".kt", ignoreCase = true)) {
			throw CliExecutionException("Kotlin source must use the .kt extension: $input")
		}
		out.println(KotlinToCrescentTranslator.translate(input.readText(), input.toString()))
	}

	private fun executeDirect(project: CrescentProject, args: List<String>, io: RuntimeIO) {
		CrescentVM(project.files, project.mainFile, io).use { it.invoke(args) }
	}
}
