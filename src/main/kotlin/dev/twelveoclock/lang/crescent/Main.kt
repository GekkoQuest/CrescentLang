package dev.twelveoclock.lang.crescent

import dev.twelveoclock.lang.crescent.compiler.CrescentIRCompiler
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.translators.KotlinToCrescentTranslator
import dev.twelveoclock.lang.crescent.translators.PoderTechIrTranslator
import dev.twelveoclock.lang.crescent.vm.CrescentIRVM
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

object Main {

	@JvmStatic
	fun main(args: Array<String>) {
		if (args.isEmpty() || args[0] in setOf("help", "--help", "-h")) {
			printHelp()
			return
		}

		val knownCommands = setOf("run", "ir", "ptir", "kotlin-to-crescent")
		val command = args[0].takeIf { it in knownCommands } ?: "run"
		val pathIndex = if (command == args[0]) 1 else 0
		require(args.size > pathIndex) { "A source file or project directory is required" }
		val input = Path(args[pathIndex]).toAbsolutePath().normalize()

		when (command) {
			"kotlin-to-crescent" -> println(KotlinToCrescentTranslator.translate(input.readText()))
			else -> {
				val files = parseProject(input)
				val mainFile = files.singleOrNull { it.mainFunction != null }
					?: error("A project must contain exactly one main function")
				val separator = args.indexOf("--")
				val programArgs = if (separator == -1) emptyList() else args.drop(separator + 1)

				when (command) {
					"run" -> CrescentVM(files, mainFile).invoke(programArgs)
					"ir" -> CrescentIRVM(CrescentIRCompiler.invoke(files, mainFile)).invoke(programArgs)
					"ptir" -> println(PoderTechIrTranslator.translate(files))
				}
			}
		}
	}

	private fun parseProject(input: Path): List<Node.File> {
		require(Files.exists(input)) { "Source path does not exist: $input" }
		val sourcePaths = if (input.isDirectory()) {
			Files.walk(input).use { paths ->
				paths.filter(::isCrescentSource).sorted().toList()
			}
		} else {
			listOf(input)
		}
		require(sourcePaths.isNotEmpty()) { "No Crescent source files were found under $input" }
		return sourcePaths.map { path -> CrescentParser.invoke(path, CrescentLexer.invoke(path.readText())) }
	}

	private fun isCrescentSource(path: Path): Boolean {
		if (!Files.isRegularFile(path)) return false
		return path.fileName.toString().substringAfterLast('.', "") in setOf("crescent", "moon", "moo")
	}

	private fun printHelp() {
		println(
			"""
				CrescentLang

				  crescent run <file-or-directory> [-- program args]
				  crescent ir <file-or-directory> [-- program args]
				  crescent ptir <file-or-directory>
				  crescent kotlin-to-crescent <file.kt>
			""".trimIndent(),
		)
	}
}
