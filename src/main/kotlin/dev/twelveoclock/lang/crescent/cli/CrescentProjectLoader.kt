package dev.twelveoclock.lang.crescent.cli

import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import dev.twelveoclock.lang.crescent.project.CrescentModuleResolver
import dev.twelveoclock.lang.crescent.project.CrescentModuleResolutionException
import dev.twelveoclock.lang.crescent.project.CrescentStandardLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

data class CrescentProject(
	val files: List<Node.File>,
	val mainFile: Node.File,
	val userFiles: List<Node.File> = files,
)

data class CrescentSourceSet(
	val files: List<Node.File>,
	val userFiles: List<Node.File>,
)

object CrescentProjectLoader {

	private val sourceExtensions = setOf("crescent", "moon", "moo")

	fun discoverSources(input: Path): List<Path> {
		val normalized = input.toAbsolutePath().normalize()
		if (!Files.exists(normalized)) throw CliExecutionException("Source path does not exist: $normalized")

		return try {
			if (!normalized.isDirectory()) {
				if (!isCrescentSource(normalized)) {
					throw CliExecutionException("Unsupported Crescent source extension: $normalized")
				}
				listOf(normalized)
			} else {
				val sources = Files.walk(normalized).use { paths ->
					paths.filter(::isCrescentSource).sorted().toList()
				}
				if (sources.isEmpty()) {
					throw CliExecutionException("No Crescent source files were found under $normalized")
				}
				sources
			}
		} catch (exception: CliExecutionException) {
			throw exception
		} catch (exception: Exception) {
			throw CliExecutionException(
				"Could not read source path $normalized: ${exception.message ?: exception::class.simpleName}",
				exception,
			)
		}
	}

	fun loadSourceSet(input: Path): CrescentSourceSet = link(parseSources(input))

	fun load(input: Path): CrescentProject {
		val parsed = parseSources(input)
		val mainFiles = parsed.userFiles.filter { it.mainFunction != null }
		val mainFile = when (mainFiles.size) {
			0 -> throw CliExecutionException("Project does not declare a main function")
			1 -> mainFiles.single()
			else -> throw CliExecutionException(
				"Project declares multiple main functions: ${mainFiles.joinToString { it.path.toString() }}",
			)
		}
		val sourceSet = link(parsed)
		val linkedMain = sourceSet.userFiles.single {
			it.path.toAbsolutePath().normalize() == mainFile.path.toAbsolutePath().normalize()
		}
		return CrescentProject(sourceSet.files, linkedMain, sourceSet.userFiles)
	}

	private fun parseSources(input: Path): ParsedSourceSet {
		val normalized = input.toAbsolutePath().normalize()
		val sources = discoverSources(normalized)
		val parsedUserFiles = sources.map(::parse)
		val projectRoot = if (normalized.isDirectory()) normalized else normalized.parent
		return ParsedSourceSet(normalized, projectRoot, parsedUserFiles)
	}

	private fun link(parsed: ParsedSourceSet): CrescentSourceSet {
		val files = try {
			CrescentModuleResolver.link(parsed.projectRoot, parsed.userFiles, CrescentStandardLibrary.load())
		} catch (exception: DiagnosticException) {
			throw exception
		} catch (exception: CrescentModuleResolutionException) {
			throw exception
		} catch (exception: Exception) {
			throw CliExecutionException(
				"Could not link Crescent project at ${parsed.input}: ${exception.message ?: exception::class.simpleName}",
				exception,
			)
		}
		val userPaths = parsed.userFiles.map { it.path.toAbsolutePath().normalize() }.toSet()
		val linkedUserFiles = files.filter { it.path.toAbsolutePath().normalize() in userPaths }
		return CrescentSourceSet(immutableList(files), immutableList(linkedUserFiles))
	}

	private data class ParsedSourceSet(
		val input: Path,
		val projectRoot: Path,
		val userFiles: List<Node.File>,
	)

	private fun parse(path: Path): Node.File = try {
		CrescentParser.invoke(path, CrescentLexer.invoke(path, path.readText()))
	} catch (exception: DiagnosticException) {
		throw exception
	} catch (exception: Exception) {
		throw CliExecutionException("Could not parse $path: ${exception.message ?: exception::class.simpleName}", exception)
	}

	private fun isCrescentSource(path: Path): Boolean =
		Files.isRegularFile(path) && path.fileName.toString().substringAfterLast('.', "").lowercase() in sourceExtensions

	private fun <T> immutableList(values: Collection<T>): List<T> =
		Collections.unmodifiableList(ArrayList(values))
}
