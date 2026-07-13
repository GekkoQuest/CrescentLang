package dev.twelveoclock.lang.crescent.translators

/**
 * Translates the Kotlin source subset shared with Crescent: functions, primitive
 * types, variables, returns, ranges, loops, objects, and data-class records.
 */
object KotlinToCrescentTranslator {

	private val typeMappings = linkedMapOf(
		"Array<String>" to "[String]",
		"Int" to "I32",
		"Long" to "I64",
		"Float" to "F32",
		"Double" to "F64",
		"Boolean" to "Boolean",
		"Char" to "Char",
		"String" to "String",
		"Any" to "Any",
		"Unit" to "Unit",
	)

	fun translate(kotlinSource: String): String = kotlinSource.lineSequence().map(::translateLine).joinToString("\n")

	private fun translateLine(sourceLine: String): String {
		val indent = sourceLine.takeWhile(Char::isWhitespace)
		var line = sourceLine.trimStart()
		if (line.startsWith("//")) return indent + "#" + line.removePrefix("//")

		line = line.replace(Regex("//(.*)$"), "#$1")
		line = line.replace(Regex("^suspend\\s+fun\\b"), "async fun")
		line = line.replace(Regex("^data\\s+class\\s+"), "struct ")
		line = line.replace(Regex("^return(?:@\\w+)?\\s*"), "-> ")
		line = line.replace(Regex("^for\\s*\\((.+)\\)\\s*\\{"), "for $1 {")

		val function = Regex("^(.*?)fun\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?::\\s*([^={]+))?\\s*\\{").find(line)
		if (function != null) {
			val (prefix, name, parameters, returnType) = function.destructured
			val mappedReturn = returnType.trim().takeIf(String::isNotEmpty)?.let(::mapTypes)
			line = buildString {
				append(prefix)
				append("fun ").append(name).append('(').append(mapTypes(parameters)).append(')')
				if (mappedReturn != null && mappedReturn != "Unit") append(" -> ").append(mappedReturn)
				append(" {")
			}
		}

		return indent + mapTypes(line)
	}

	private fun mapTypes(value: String): String {
		var result = value
		for ((kotlinType, crescentType) in typeMappings) {
			result = result.replace(Regex("(?<![A-Za-z0-9_])${Regex.escape(kotlinType)}(?![A-Za-z0-9_])"), crescentType)
		}
		return result
	}
}
