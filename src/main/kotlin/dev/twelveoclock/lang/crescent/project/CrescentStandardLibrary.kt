package dev.twelveoclock.lang.crescent.project

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import java.nio.file.Path

object CrescentStandardLibrary {

	private const val MANIFEST = "crescent/stdlib/modules.list"

	fun load(classLoader: ClassLoader = CrescentStandardLibrary::class.java.classLoader): List<Node.File> {
		val manifest = classLoader.getResourceAsStream(MANIFEST)
			?: throw CrescentModuleResolutionException("Bundled standard-library manifest '$MANIFEST' is missing")
		val modules = manifest.bufferedReader().useLines { lines ->
			lines.map(String::trim)
				.filter { it.isNotEmpty() && !it.startsWith('#') }
				.map { line ->
					val parts = line.split('=', limit = 2)
					if (parts.size != 2 || parts.any(String::isBlank)) {
						throw CrescentModuleResolutionException("Invalid standard-library manifest entry: '$line'")
					}
					parts[0].trim() to parts[1].trim()
				}
				.toList()
		}
		val duplicateResource = modules.groupBy(Pair<String, String>::second).entries.firstOrNull { it.value.size > 1 }
		if (duplicateResource != null) {
			throw CrescentModuleResolutionException("Duplicate standard-library resource '${duplicateResource.key}' in $MANIFEST")
		}
		return modules.sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second })).map { (packageId, resource) ->
			if (!CrescentModuleResolver.isStandardLibraryPackage(packageId)) {
				throw CrescentModuleResolutionException("Standard-library manifest package '$packageId' is outside crescent.std")
			}
			val source = classLoader.getResourceAsStream(resource)?.bufferedReader()?.use { it.readText() }
				?: throw CrescentModuleResolutionException("Bundled standard-library source '$resource' is missing")
			val path = Path.of("classpath", *resource.split('/').toTypedArray()).normalize()
			try {
				CrescentParser.invoke(path, CrescentLexer.invoke(source)).copy(packageId = packageId)
			} catch (exception: Exception) {
				throw CrescentModuleResolutionException(
					"Could not parse bundled standard-library source '$resource': ${exception.message ?: exception::class.simpleName}",
					exception,
				)
			}
		}
	}
}
