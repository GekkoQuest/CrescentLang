package dev.twelveoclock.lang.crescent.translators

import java.math.BigDecimal
import java.math.BigInteger

internal class KotlinSubsetParser(source: String) {
	private enum class Kind { WORD, NUMBER, STRING, CHAR, COMMENT, SYMBOL, NEWLINE, EOF }
	private data class Token(val kind: Kind, val text: String, val offset: Int)
	private data class AnnotationPrefix(val marker: Token, val rendered: String)
	private data class Prefix(val visibilityToken: Token?, val modifierTokens: List<Token>, val annotationPrefixes: List<AnnotationPrefix>) {
		val visibility: String? get() = visibilityToken?.text
		val modifiers: List<String> get() = modifierTokens.map(Token::text)
		val annotations: List<String> get() = annotationPrefixes.map(AnnotationPrefix::rendered)
		fun firstPrefixToken(): Token? = listOfNotNull(visibilityToken, modifierTokens.firstOrNull(), annotationPrefixes.firstOrNull()?.marker).minByOrNull(Token::offset)
	}
	private data class FunctionResult(val source: String, val abstractSignature: Boolean)

	private val tokens = lex(source)
	private var index = 0
	private val syntheticImports = linkedMapOf<String, String>()
	private val declaredImports = linkedMapOf<String, String>()

	fun translate(): String {
		val header = mutableListOf<String>()
		val declarations = mutableListOf<String>()
		while (!atEnd()) {
			skipNewlines()
			if (peek().kind == Kind.COMMENT) { declarations += comment(next()); continue }
			if (atEnd()) break
			when (peek().text) {
				"package" -> header += parsePackage()
				"import" -> header += parseImport()
				else -> declarations += parseDeclaration()
			}
		}
		val generated = syntheticImports.entries.map { (visible, qualified) ->
			val split = qualified.lastIndexOf('.')
			"import ${qualified.substring(0, split)}::${qualified.substring(split + 1)}" + if (visible == qualified.substring(split + 1)) "" else " as $visible"
		}
		return (header + generated + declarations).filter(String::isNotBlank).joinToString("\n\n").trim() + "\n"
	}

	private fun parsePackage(): String {
		val start = expect("package")
		val path = readQualifiedName(allowStar = false)
		consumeLineEnd()
		return "# Non-semantic Kotlin package provenance: $path (Crescent package identity is directory-derived)"
	}

	private fun parseImport(): String {
		expect("import")
		val qualified = readQualifiedName(allowStar = true)
		val alias = if (match("as")) expectWord("import alias").text else null
		consumeLineEnd()
		val split = qualified.lastIndexOf('.')
		if (split < 1) fail(peek(), "Kotlin import must contain a package and declaration")
		val packageId = qualified.substring(0, split)
		val name = qualified.substring(split + 1)
		val visible = alias ?: name
		val previous = declaredImports[visible]
		if (previous != null && previous != qualified) fail(peek(), "Ambiguous visible type '$visible' from '$previous' and '$qualified'")
		declaredImports[visible] = qualified
		return "import $packageId::$name" + alias?.let { " as $it" }.orEmpty()
	}

	private fun parseDeclaration(interfaceMember: Boolean = false): String {
		val prefix = parsePrefix()
		return when (peek().text) {
			"fun" -> parseFunction(prefix, interfaceMember).source
			"class" -> parseClass(prefix)
			"data" -> impossible(peek(), "data-class synthesized equality/hash/copy/component semantics")
			"interface" -> parseInterface(prefix)
			"object" -> parseObject(prefix)
			"val", "var", "const" -> parseState(prefix)
			"enum", "annotation", "value", "typealias" -> impossible(peek(), "${peek().text} declarations")
			else -> fail(peek(), "Expected a Kotlin declaration, got '${peek().text}'")
		}
	}

	private fun parsePrefix(): Prefix {
		val annotations = mutableListOf<AnnotationPrefix>()
		var visibility: Token? = null
		val modifiers = mutableListOf<Token>()
		while (true) {
			when (peek().text) {
				"@" -> annotations += parseAnnotation()
				"public", "private", "internal" -> { if (visibility != null) fail(peek(), "Multiple visibility modifiers"); visibility = next() }
				"protected" -> impossible(peek(), "protected visibility")
				"suspend", "override", "operator", "inline", "infix", "final", "abstract", "open" -> modifiers += next()
				"external", "tailrec", "lateinit", "expect", "actual" -> impossible(peek(), "'${peek().text}' modifier")
				else -> return Prefix(visibility, modifiers, annotations)
			}
		}
	}

	private fun parseAnnotation(): AnnotationPrefix {
		val marker = expect("@")
		val name = readQualifiedName(false)
		if (match(":")) impossible(marker, "annotation use-site targets")
		val arguments = if (peek().text == "(") readBalanced("(", ")").joinToString("") { it.text } else ""
		val simple = name.substringAfterLast('.')
		if (simple !in setOf("JvmStatic", "Suppress", "Deprecated")) impossible(marker, "semantic annotation @$name")
		return AnnotationPrefix(marker, "@$name$arguments")
	}

	private fun parseFunction(prefix: Prefix, abstractAllowed: Boolean, staticContext: Boolean = false): FunctionResult {
		val start = expect("fun")
		if (peek().text == "<") impossible(peek(), "generic function type parameters")
		val name = expectWord("function name").text
		prefix.modifierTokens.firstOrNull { it.text == "open" }?.let { impossible(it, "open-function override eligibility") }
		if (!abstractAllowed) prefix.modifierTokens.firstOrNull { it.text == "abstract" }?.let { impossible(it, "abstract function without a trait contract") }
		if (name == "main") prefix.modifierTokens.firstOrNull { it.text == "suspend" }?.let { impossible(it, "suspend main entrypoint") }
		if (peek().text == ".") impossible(peek(), "extension function receivers")
		val params = parseParameters()
		skipNewlines()
		val returnType = if (match(":")) parseType() else "Unit"
		skipNewlines()
		val annotations = prefix.annotations.filter { staticContext.not() || !it.startsWith("@JvmStatic") }.map { "# Non-semantic Kotlin annotation provenance: $it" }
		val modifiers = prefix.modifiers.mapNotNull {
			when (it) { "suspend" -> "async"; "override", "operator", "inline", "infix" -> it; "final", "open", "abstract" -> null; else -> null }
		}
		val signature = buildString {
			prefix.visibility?.let { append(it).append(' ') }
			if (modifiers.isNotEmpty()) append(modifiers.joinToString(" ")).append(' ')
			append("fun ").append(name).append('(').append(params).append(')')
			if (returnType != "Unit") append(" -> ").append(returnType)
		}
		val body = when {
			peek().text == "{" -> " " + parseBlock()
			match("=") -> " { -> ${renderExpressionUntil(setOf("\n", ";", "}"))} }"
			abstractAllowed || "abstract" in prefix.modifiers -> ""
			else -> fail(start, "Function '$name' requires a body")
		}
		consumeOptional(";")
		return FunctionResult((annotations + (signature + body)).joinToString("\n"), body.isEmpty())
	}

	private fun parseParameters(): String {
		expect("(")
		val result = mutableListOf<String>()
		while (true) {
			skipNewlines()
			if (match(")")) break
			val prefix = parsePrefix()
			prefix.firstPrefixToken()?.let { impossible(it, "annotated or modified parameters") }
			if (match("val") || match("var")) fail(previous(), "Constructor property markers are only valid in a class primary constructor")
			val name = expectWord("parameter name").text
			expect(":")
			val type = parseType()
			val default = if (match("=")) " = ${renderExpressionUntil(setOf(",", ")"))}" else ""
			result += "$name: $type$default"
			if (!match(",")) { expect(")"); break }
		}
		return result.joinToString(", ")
	}

	private fun parseClass(prefix: Prefix): String { expect("class"); return parseClassAfterKeyword(prefix) }
	private fun parseClassAfterKeyword(prefix: Prefix): String {
		prefix.modifierTokens.firstOrNull { it.text in setOf("open", "abstract") }?.let { impossible(it, "${it.text}-class inheritance semantics") }
		if (peek().text == "<") impossible(peek(), "generic class type parameters")
		val name = expectWord("class name").text
		val fields = parseConstructorProperties()
		skipNewlines()
		val supers = if (match(":")) parseSuperTypes() else emptyList()
		skipNewlines()
		val instanceMethods = mutableListOf<String>()
		val staticMethods = mutableListOf<String>()
		if (match("{")) {
			while (!match("}")) {
				skipNewlines()
				if (match("}")) break
				if (peek().kind == Kind.COMMENT) { next(); continue }
				val memberPrefix = parsePrefix()
				when {
					peek().text == "companion" -> {
						memberPrefix.firstPrefixToken()?.let { impossible(it, "prefix on companion object") }
						next(); expect("object"); staticMethods += parseCompanionBody(name)
					}
					peek().text == "fun" -> instanceMethods += parseFunction(memberPrefix, false).source
					peek().text in setOf("val", "var", "const") -> impossible(peek(), "class-body state (use primary-constructor properties)")
					peek().text in setOf("constructor", "init", "class", "object", "interface") -> impossible(peek(), "${peek().text} inside class")
					else -> fail(peek(), "Unsupported class member '${peek().text}'")
				}
			}
		}
		val visibility = prefix.visibility?.let { "$it " }.orEmpty()
		val provenance = prefix.annotations.map { "# Non-semantic Kotlin annotation provenance: $it" }
		val struct = "$visibility" + "struct $name(${fields.joinToString(", ")})"
		val instance = if (instanceMethods.isNotEmpty() || supers.isNotEmpty()) buildString {
			append("impl ").append(name); if (supers.isNotEmpty()) append(" : ").append(supers.joinToString(", ")); append(" {\n"); append(instanceMethods.joinToString("\n").prependIndent("    ")); append("\n}")
		} else null
		val static = if (staticMethods.isNotEmpty()) "impl static $name {\n${staticMethods.joinToString("\n").prependIndent("    ")}\n}" else null
		return (provenance + listOfNotNull(struct, instance, static)).joinToString("\n\n")
	}

	private fun parseConstructorProperties(): List<String> {
		if (!match("(")) return emptyList()
		val fields = mutableListOf<String>()
		while (true) {
			skipNewlines()
			if (match(")")) break
			val prefix = parsePrefix()
			prefix.annotationPrefixes.firstOrNull()?.let { impossible(it.marker, "annotation on constructor property") }
			prefix.modifierTokens.firstOrNull()?.let { impossible(it, "modifier on constructor property") }
			val marker = when { match("val") -> "val"; match("var") -> "var"; else -> impossible(peek(), "non-property primary constructor parameter") }
			val name = expectWord("constructor property name").text
			expect(":")
			val type = parseType()
			val default = if (match("=")) " = ${renderExpressionUntil(setOf(",", ")"))}" else ""
			val visibility = prefix.visibility?.let { "$it " }.orEmpty()
			fields += "$visibility$marker $name: $type$default"
			if (!match(",")) { expect(")"); break }
		}
		return fields
	}

	private fun parseSuperTypes(): List<String> {
		val result = mutableListOf<String>()
		do {
			val token = peek()
			val type = parseType()
			if (peek().text == "(") impossible(token, "class-superconstructor inheritance")
			if (match("by")) impossible(previous(), "delegated inheritance")
			result += type
		} while (match(","))
		return result
	}

	private fun parseCompanionBody(owner: String): List<String> {
		if (peek().kind == Kind.WORD && peek(1).text == "{") impossible(peek(), "named companion object identity")
		expect("{")
		val methods = mutableListOf<String>()
		while (!match("}")) {
			skipNewlines(); if (match("}")) break
			val prefix = parsePrefix()
			when (peek().text) {
				"fun" -> methods += parseFunction(prefix, false, staticContext = true).source
				"val", "var", "const" -> impossible(peek(), "companion state; static impl currently contains functions only")
				else -> fail(peek(), "Unsupported companion member '${peek().text}' in $owner")
			}
		}
		return methods
	}

	private fun parseInterface(prefix: Prefix): String {
		prefix.modifierTokens.firstOrNull()?.let { impossible(it, "modifier on interface declaration") }
		expect("interface")
		if (peek().text == "<") impossible(peek(), "generic interface type parameters")
		val name = expectWord("interface name").text
		val supers = if (match(":")) parseSuperTypes() else emptyList()
		skipNewlines()
		expect("{")
		val signatures = mutableListOf<String>()
		while (!match("}")) {
			skipNewlines(); if (match("}")) break
			val memberPrefix = parsePrefix()
			memberPrefix.firstPrefixToken()?.let { impossible(it, "modifier, visibility, or annotation on interface signature") }
			if (peek().text != "fun") impossible(peek(), "interface state or nested declaration")
			val function = parseFunction(memberPrefix, true)
			if (!function.abstractSignature) impossible(previous(), "interface method body")
			signatures += function.source
		}
		val visibility = prefix.visibility?.let { "$it " }.orEmpty()
		val trait = "$visibility" + "trait $name {\n${signatures.joinToString("\n").prependIndent("    ")}\n}"
		val inheritance = if (supers.isNotEmpty()) "\n\nimpl $name : ${supers.joinToString(", ")} {}" else ""
		val provenance = prefix.annotations.map { "# Non-semantic Kotlin annotation provenance: $it" }
		return (provenance + (trait + inheritance)).joinToString("\n")
	}

	private fun parseObject(prefix: Prefix): String {
		prefix.annotationPrefixes.firstOrNull()?.let { impossible(it.marker, "annotation on object declaration") }
		prefix.modifierTokens.firstOrNull()?.let { impossible(it, "modifier on object declaration") }
		expect("object")
		val name = expectWord("object name").text
		skipNewlines()
		expect("{")
		val members = mutableListOf<String>()
		while (!match("}")) {
			skipNewlines(); if (match("}")) break
			if (peek().kind == Kind.COMMENT) { members += comment(next()); continue }
			val memberPrefix = parsePrefix()
			members += when (peek().text) {
				"fun" -> parseFunction(memberPrefix, false).source
				"val", "var", "const" -> parseState(memberPrefix)
				else -> impossible(peek(), "nested declaration inside object")
			}
		}
		val visibility = prefix.visibility?.let { "$it " }.orEmpty()
		return "$visibility" + "object $name {\n${members.joinToString("\n").prependIndent("    ")}\n}"
	}

	private fun parseState(prefix: Prefix): String {
		prefix.modifierTokens.firstOrNull()?.let { impossible(it, "modifier on state declaration") }
		val constant = match("const")
		val kind = when { constant -> { expect("val"); "const" }; match("val") -> "val"; match("var") -> "var"; else -> fail(peek(), "Expected state declaration") }
		val name = expectWord("state name").text
		val type = if (match(":")) ": ${parseType()}" else ""
		expect("=")
		val value = renderExpressionUntil(setOf("\n", ";", "}"))
		if (value.isBlank()) fail(peek(), "State '$name' requires an initializer")
		consumeOptional(";")
		val visibility = prefix.visibility?.let { "$it " }.orEmpty()
		return prefix.annotations.map { "# Non-semantic Kotlin annotation provenance: $it" }.plus("$visibility$kind $name$type = $value").joinToString("\n")
	}

	private fun parseBlock(): String {
		expect("{")
		val output = StringBuilder("{")
		var lineStart = true
		while (!match("}")) {
			if (atEnd()) fail(previous(), "Unterminated block; expected '}'")
			val token = peek()
			when {
				token.kind == Kind.NEWLINE -> { next(); output.append('\n'); lineStart = true }
				token.kind == Kind.COMMENT -> { val commentToken = next(); if (commentToken.text.startsWith("//")) { if (!lineStart) output.append(' '); output.append(comment(commentToken)); lineStart = false } else output.append(' ') }
				token.text == "return" -> { next(); if (peek().text == "@") impossible(peek(), "labeled return"); if (!lineStart) output.append(' '); output.append("-> "); val value = renderExpressionUntil(setOf("\n", ";", "}")); if (value.isBlank()) impossible(token, "bare return"); output.append(value); lineStart = false }
				token.text in setOf("val", "var", "const", "public", "private", "internal") -> { if (!lineStart) output.append(' '); output.append(parseState(parsePrefix())).append('\n'); lineStart = true }
				token.text in prefixKeywords -> {
					val prefix = parsePrefix()
					if (peek().text !in setOf("val", "var", "const")) impossible(token, "modifier on local declaration")
					if (!lineStart) output.append(' ')
					output.append(parseState(prefix)).append('\n'); lineStart = true
				}
				token.text == "if" -> { if (!lineStart) output.append(' '); output.append(parseIf()); lineStart = false }
				token.text == "while" -> { if (!lineStart) output.append(' '); output.append(parseWhile()); lineStart = false }
				token.text == "for" -> { if (!lineStart) output.append(' '); output.append(parseFor()); lineStart = false }
				token.text == "fun" -> impossible(token, "local functions")
				token.text in setOf("try", "catch", "finally", "throw") -> impossible(token, "exception semantics (${token.text})")
				token.text == "do" -> impossible(token, "do-while control flow")
				token.text == "{" -> { if (isTrailingLambdaStart()) impossible(token, "trailing lambda"); if (!lineStart) output.append(' '); output.append(parseBlock()); lineStart = false }
				token.text == "-" && peek(1).let(::isIntMinimumMagnitude) && isUnaryPosition(previousOrNull()) -> { if (!lineStart) output.append(' '); output.append("((-2147483647 as I32) - (1 as I32))"); next(); next(); lineStart = false }
				token.text == "-" && peek(1).let(::isLongMinimumMagnitude) && isUnaryPosition(previousOrNull()) -> { if (!lineStart) output.append(' '); output.append("((-9223372036854775807 as I64) - (1 as I64))"); next(); next(); lineStart = false }
				token.text in arrayFactories && peek(1).text == "<" -> impossible(peek(1), "generic collection or array factory type arguments")
				token.text in arrayFactories && peek(1).text == "(" -> {
					val close = matchingClose(tokens, index + 1)
					val arguments = splitTopLevel(tokens.subList(index + 2, close), ",")
					if (!lineStart) output.append(' ')
					output.append(arguments.joinToString(prefix = "[", postfix = "]") { renderTokens(it) })
					index = close + 1; lineStart = false
				}
				token.text in setOf("as", "is", "!is") -> {
					next()
					if (token.text == "as" && peek().text == "?") impossible(token, "Kotlin safe cast")
					val (type, nextPosition) = renderTypeTokens(tokens, index)
					output.append(' ').append(token.text).append(' ').append(type)
					index = nextPosition; lineStart = false
				}
				else -> { validateExpressionToken(token); if (needsSpace(previousOrNull(), token)) output.append(' '); output.append(mapExpressionToken(next())); lineStart = false }
			}
		}
		return output.append('}').toString()
	}

	private fun parseIf(): String {
		expect("if")
		val predicate = readBalanced("(", ")")
		predicate.drop(1).dropLast(1).forEach(::validateExpressionToken)
		val renderedPredicate = renderTokens(predicate)
		val thenBlock = parseControlBody()
		skipNewlines()
		if (!match("else")) return "if $renderedPredicate $thenBlock"
		skipNewlines()
		val elseBlock = if (peek().text == "if") "{\n${parseIf()}\n}" else parseControlBody()
		return "if $renderedPredicate $thenBlock else $elseBlock"
	}

	private fun parseWhile(): String {
		expect("while")
		val predicate = readBalanced("(", ")")
		predicate.drop(1).dropLast(1).forEach(::validateExpressionToken)
		val renderedPredicate = renderTokens(predicate)
		return "while $renderedPredicate ${parseControlBody()}"
	}

	private fun parseControlBody(): String {
		skipNewlines()
		if (peek().text == "{") return parseBlock()
		val start = peek()
		val statement = when (start.text) {
			"if" -> parseIf()
			"while" -> parseWhile()
			"for" -> impossible(start, "unbraced for-loop body")
			"return" -> {
				next()
				if (peek().text == "@") impossible(peek(), "labeled return")
				val value = renderExpressionUntil(setOf("\n", ";", "}", "else"))
				if (value.isBlank()) impossible(start, "bare return")
				"-> $value"
			}
			"break", "continue" -> next().text
			"val", "var", "const", "public", "private", "internal" -> impossible(start, "declaration as an unbraced control-flow body")
			else -> renderExpressionUntil(setOf("\n", ";", "}", "else")).also {
				if (it.isBlank()) fail(start, "Expected unbraced control-flow body")
			}
		}
		consumeOptional(";")
		return "{\n$statement\n}"
	}

	private fun isTrailingLambdaStart(): Boolean {
		val previousIndex = index - 1
		if (previousIndex < 0) return false
		val previous = tokens[previousIndex]
		if (previous.kind == Kind.WORD) return previous.text !in setOf("else", "if", "while", "for", "when")
		if (previous.text != ")") return false
		var depth = 0
		for (position in previousIndex downTo 0) {
			when (tokens[position].text) {
				")" -> depth += 1
				"(" -> {
					depth -= 1
					if (depth == 0) {
						val owner = tokens.getOrNull(position - 1)
						return owner?.kind == Kind.WORD && owner.text !in setOf("if", "while", "for", "when")
					}
				}
			}
		}
		return false
	}

	private fun parseFor(): String {
		val start = expect("for")
		val header = readBalanced("(", ")").drop(1).dropLast(1)
		if (header.any { it.text == "(" || it.text == ")" || it.text == "{" }) impossible(start, "destructuring or complex for-loop header")
		header.firstOrNull { it.text in setOf("..<", "until") }?.let { impossible(it, "half-open for-loop range") }
		header.firstOrNull { it.text in setOf("step", "downTo") }?.let { impossible(it, "stepped or descending for-loop range") }
		if (header.none { it.text == "in" } || header.none { it.text == ".." }) impossible(start, "collection iteration that is not an inclusive range")
		val rendered = renderTokens(header)
		skipNewlines()
		return "for $rendered ${parseBlock()}"
	}

	private fun parseType(): String {
		val start = peek()
		if (match("(")) impossible(start, "function types")
		val parts = mutableListOf(expectWord("type name").text)
		while (match(".")) parts += expectWord("qualified type segment").text
		val qualified = parts.joinToString(".")
		val base = qualified.substringAfterLast('.')
		val arguments = if (match("<")) {
			val args = mutableListOf<String>()
			if (peek().text == ">") impossible(peek(), "empty generic arguments")
			do { if (peek().text in setOf("in", "out", "*")) impossible(peek(), "generic variance or star projection"); args += parseType() } while (match(","))
			expect(">"); args
		} else emptyList()
		val primitive = primitiveTypes[base]
		var rendered = when {
			base in primitiveArrays -> { if (arguments.isNotEmpty()) impossible(start, "type arguments on primitive array"); "[${primitiveArrays.getValue(base)}]" }
			base in setOf("Array", "List", "MutableList") -> { if (arguments.size != 1) impossible(start, "$base requires exactly one type argument"); "[${arguments.single()}]" }
			base == "Result" -> { if (arguments.size != 1) impossible(start, "Result requires exactly one type argument"); "${arguments.single()}?" }
			arguments.isNotEmpty() -> impossible(start, "arbitrary generic type '$qualified'")
			primitive != null && (parts.size == 1 || parts.dropLast(1) == listOf("kotlin")) -> primitive
			parts.size > 1 -> { registerImport(base, qualified); base }
			else -> base
		}
		if (peek().text == "?") impossible(peek(), "Kotlin nullable type (Crescent '?' is Result propagation, not nullability)")
		if (peek().text == "->") impossible(peek(), "function types")
		return rendered
	}

	private fun renderExpressionUntil(stops: Set<String>): String {
		val selected = mutableListOf<Token>()
		var parens = 0; var brackets = 0; var braces = 0
		while (!atEnd()) {
			val token = peek()
			if (parens == 0 && brackets == 0 && braces == 0 && token.text in stops) break
			validateExpressionToken(token)
			when (token.text) { "(" -> parens++; ")" -> if (parens == 0) break else parens--; "[" -> brackets++; "]" -> brackets--; "{" -> braces++; "}" -> if (braces == 0) break else braces-- }
			selected += next()
		}
		if (parens != 0 || brackets != 0 || braces != 0) fail(selected.firstOrNull() ?: peek(), "Unbalanced expression delimiters")
		return renderTokens(selected)
	}

	private fun validateExpressionToken(token: Token) {
		when (token.text) {
			"null" -> impossible(token, "Kotlin null literal")
			"?." -> impossible(token, "safe-call operator")
			"?:" -> impossible(token, "Elvis operator")
			"!!" -> impossible(token, "non-null assertion")
			"::" -> impossible(token, "function reference")
			"===", "!==" -> impossible(token, "Kotlin referential equality")
			"..<", "until" -> impossible(token, "half-open range expression")
			"downTo", "step" -> impossible(token, "stepped or descending range expression")
			"++", "--" -> impossible(token, "increment/decrement operator")
			"@" -> impossible(token, "labeled control flow")
			"->", "{" -> impossible(token, "lambda expression")
			"try", "catch", "finally", "throw" -> impossible(token, "exception semantics")
		}
	}

	private fun renderTokens(values: List<Token>): String = buildString {
		var previous: Token? = null
		var position = 0
		while (position < values.size) {
			validateExpressionToken(values[position])
			if (values[position].text in arrayFactories && values.getOrNull(position + 1)?.text == "<") impossible(values[position + 1], "generic collection or array factory type arguments")
			if (values[position].text == "-" && values.getOrNull(position + 1)?.let(::hasUnsignedSuffix) == true && isUnaryPosition(previous)) impossible(values[position], "unary minus on unsigned literal")
			if (values[position].text in setOf("as", "is", "!is")) {
				val operator = values[position]
				if (operator.text == "as" && values.getOrNull(position + 1)?.text == "?") impossible(operator, "Kotlin safe cast")
				val (type, nextPosition) = renderTypeTokens(values, position + 1)
				append(' ').append(operator.text).append(' ').append(type)
				previous = Token(Kind.WORD, type, values[nextPosition - 1].offset)
				position = nextPosition
				continue
			}
			if (values[position].text == "-" && values.getOrNull(position + 1)?.let(::isIntMinimumMagnitude) == true && isUnaryPosition(previous)) {
				if (needsSpace(previous, values[position])) append(' ')
				append("((-2147483647 as I32) - (1 as I32))")
				previous = Token(Kind.SYMBOL, ")", values[position].offset)
				position += 2
				continue
			}
			if (values[position].text == "-" && values.getOrNull(position + 1)?.let(::isLongMinimumMagnitude) == true && isUnaryPosition(previous)) {
				if (needsSpace(previous, values[position])) append(' ')
				append("((-9223372036854775807 as I64) - (1 as I64))")
				previous = Token(Kind.SYMBOL, ")", values[position].offset)
				position += 2
				continue
			}
			if (values[position].text in arrayFactories && values.getOrNull(position + 1)?.text == "(") {
				val close = matchingClose(values, position + 1)
				val arguments = splitTopLevel(values.subList(position + 2, close), ",")
				if (needsSpace(previous, values[position])) append(' ')
				append(arguments.joinToString(prefix = "[", postfix = "]") { renderTokens(it) })
				previous = Token(Kind.SYMBOL, "]", values[close].offset)
				position = close + 1
				continue
			}
			if (position + 2 < values.size && values[position].text in setOf("Result", "kotlin") &&
				((values[position].text == "Result" && values[position + 1].text == "." && values[position + 2].text in setOf("success", "failure")) ||
					(position + 4 < values.size && values[position].text == "kotlin" && values[position + 1].text == "." && values[position + 2].text == "Result" && values[position + 3].text == "." && values[position + 4].text in setOf("success", "failure")))) {
				val methodPosition = if (values[position].text == "Result") position + 2 else position + 4
				if (needsSpace(previous, values[methodPosition])) append(' ')
				append(values[methodPosition].text)
				previous = values[methodPosition]
				position = methodPosition + 1
				continue
			}
			val token = values[position++]
			if (token.kind == Kind.NEWLINE) { append(' '); previous = null; continue }
			if (token.kind == Kind.COMMENT) { if (token.text.startsWith("//")) append(' ').append(comment(token)); else append(' '); previous = token; continue }
			if (needsSpace(previous, token)) append(' ')
			append(mapExpressionToken(token)); previous = token
		}
	}.trim()

	private fun renderTypeTokens(values: List<Token>, start: Int): Pair<String, Int> {
		var position = start
		val first = values.getOrNull(position) ?: fail(values.lastOrNull() ?: peek(), "Expected cast/type-test target type")
		if (first.kind != Kind.WORD) fail(first, "Expected cast/type-test target type")
		val parts = mutableListOf(values[position++].text)
		while (values.getOrNull(position)?.text == ".") {
			val segment = values.getOrNull(position + 1) ?: fail(values[position], "Expected qualified type segment")
			if (segment.kind != Kind.WORD) fail(segment, "Expected qualified type segment")
			parts += segment.text
			position += 2
		}
		val arguments = mutableListOf<String>()
		if (values.getOrNull(position)?.text == "<") {
			position += 1
			while (true) {
				if (values.getOrNull(position)?.text in setOf("in", "out", "*")) impossible(values[position], "generic variance or star projection")
				val (argument, next) = renderTypeTokens(values, position)
				arguments += argument
				position = next
				if (values.getOrNull(position)?.text == ",") { position += 1; continue }
				val close = values.getOrNull(position) ?: fail(first, "Unterminated cast/type-test generic target")
				if (close.text != ">") fail(close, "Expected '>' in cast/type-test target")
				position += 1
				break
			}
		}
		val qualified = parts.joinToString(".")
		val base = parts.last()
		val primitive = primitiveTypes[base]
		val rendered = when {
			base in primitiveArrays -> { if (arguments.isNotEmpty()) impossible(first, "type arguments on primitive array"); "[${primitiveArrays.getValue(base)}]" }
			base in setOf("Array", "List", "MutableList") -> { if (arguments.size != 1) impossible(first, "$base requires exactly one type argument"); "[${arguments.single()}]" }
			base == "Result" -> { if (arguments.size != 1) impossible(first, "Result requires exactly one type argument"); "${arguments.single()}?" }
			arguments.isNotEmpty() -> impossible(first, "arbitrary generic type '$qualified'")
			primitive != null && (parts.size == 1 || parts.dropLast(1) == listOf("kotlin")) -> primitive
			parts.size > 1 -> { registerImport(base, qualified); base }
			else -> base
		}
		values.getOrNull(position)?.takeIf { it.text == "?" }?.let { impossible(it, "Kotlin nullable cast/type-test target") }
		return rendered to position
	}

	private fun isLongMinimumMagnitude(token: Token): Boolean {
		if (token.kind != Kind.NUMBER || token.text.lastOrNull()?.lowercaseChar() in setOf('u', 'f', 'd')) return false
		val digits = if (token.text.lastOrNull()?.lowercaseChar() == 'l') token.text.dropLast(1) else token.text
		if (digits.lastOrNull()?.lowercaseChar() == 'u') return false
		if (digits.startsWith("0x", true) || digits.startsWith("0b", true)) return false
		validateDigitSeparators(token, digits, 10)
		return digits.replace("_", "") == "9223372036854775808"
	}

	private fun isIntMinimumMagnitude(token: Token): Boolean {
		if (token.kind != Kind.NUMBER || token.text.lastOrNull()?.lowercaseChar() in setOf('u', 'l', 'f', 'd')) return false
		if (token.text.startsWith("0x", true) || token.text.startsWith("0b", true)) return false
		validateDigitSeparators(token, token.text, 10)
		return token.text.replace("_", "") == "2147483648"
	}

	private fun hasUnsignedSuffix(token: Token): Boolean {
		if (token.kind != Kind.NUMBER) return false
		val lower = token.text.lowercase()
		return lower.endsWith("u") || lower.endsWith("ul")
	}

	private fun isUnaryPosition(previous: Token?): Boolean = previous == null || previous.text in setOf("(", "[", "{", ",", "=", "+", "-", "*", "/", "%", "&&", "||", "!", "?:", "->")

	private fun matchingClose(values: List<Token>, openIndex: Int): Int {
		var depth = 0
		for (position in openIndex until values.size) {
			when (values[position].text) {
				"(" -> depth += 1
				")" -> if (--depth == 0) return position
			}
		}
		fail(values[openIndex], "Unterminated arrayOf call")
	}

	private fun splitTopLevel(values: List<Token>, separator: String): List<List<Token>> {
		if (values.isEmpty()) return emptyList()
		val result = mutableListOf<List<Token>>()
		var start = 0
		var round = 0
		var square = 0
		var brace = 0
		for (position in values.indices) {
			when (values[position].text) {
				"(" -> round += 1
				")" -> round -= 1
				"[" -> square += 1
				"]" -> square -= 1
				"{" -> brace += 1
				"}" -> brace -= 1
				separator -> if (round == 0 && square == 0 && brace == 0) { result += values.subList(start, position); start = position + 1 }
			}
		}
		result += values.subList(start, values.size)
		return result
	}

	private fun mapExpressionToken(token: Token): String = when (token.kind) {
		Kind.NUMBER -> normalizeNumber(token)
		Kind.STRING, Kind.CHAR -> normalizeQuoted(token)
		else -> when (token.text) { "return" -> "->"; else -> token.text }
	}

	private fun normalizeNumber(token: Token): String {
		val source = token.text
		val lower = source.lowercase()
		val basePrefixed = lower.startsWith("0x") || lower.startsWith("0b")
		val integerSuffix = when {
			lower.endsWith("ul") -> source.takeLast(2)
			lower.endsWith("u") || lower.endsWith("l") -> source.takeLast(1)
			else -> ""
		}
		val floatingSuffix = source.lastOrNull()?.takeIf { !basePrefixed && it.lowercaseChar() == 'f' }
		if (!basePrefixed && source.lastOrNull()?.lowercaseChar() == 'd') fail(token, "Kotlin does not support a D floating suffix")
		val floating = floatingSuffix != null || '.' in source || 'e' in lower
		if (floating) {
			if (integerSuffix.isNotEmpty()) fail(token, "Unsupported Kotlin floating suffix in '$source'")
			val body = if (floatingSuffix != null) source.dropLast(1) else source
			validateDecimalFloat(token, body)
			val decimal = body.replace("_", "").toBigDecimalOrNull() ?: fail(token, "Unsupported Kotlin floating literal '$source'")
			val target = if (floatingSuffix?.lowercaseChar() == 'f') "F32" else "F64"
			val finite = if (target == "F32") decimal.toFloat().isFinite() else decimal.toDouble().isFinite()
			if (!finite) fail(token, "Kotlin floating literal '$source' overflows $target")
			var rendered = decimal.stripTrailingZeros().toPlainString()
			if ('.' !in rendered) rendered += ".0"
			return "($rendered as $target)"
		}

		val suffix = integerSuffix.lowercase()
		val rawBody = source.dropLast(integerSuffix.length)
		val radix = when { rawBody.startsWith("0x", true) -> 16; rawBody.startsWith("0b", true) -> 2; else -> 10 }
		val separatedDigits = if (radix == 10) rawBody else rawBody.drop(2)
		validateDigitSeparators(token, separatedDigits, radix)
		val digits = separatedDigits.replace("_", "")
		val value = try { BigInteger(digits, radix) } catch (_: NumberFormatException) { fail(token, "Unsupported Kotlin integer literal '$source'") }
		val target = when (suffix) {
			"l" -> "I64"
			"u" -> if (value <= UINT_MAX) "U32" else "U64"
			"ul" -> "U64"
			else -> if (value <= INT_MAX) "I32" else "I64"
		}
		val maximum = when (target) { "I32" -> INT_MAX; "U32" -> UINT_MAX; "U64" -> ULONG_MAX; else -> LONG_MAX }
		if (value < BigInteger.ZERO || value > maximum) fail(token, "Kotlin integer literal '$source' cannot be represented by Crescent $target")
		return "(${value.toString()} as $target)"
	}

	private fun validateDecimalFloat(token: Token, source: String) {
		val exponentAt = source.indexOfFirst { it == 'e' || it == 'E' }
		if (exponentAt >= 0 && source.substring(exponentAt + 1).any { it == 'e' || it == 'E' }) fail(token, "Unsupported Kotlin floating literal '${token.text}'")
		val mantissa = if (exponentAt < 0) source else source.substring(0, exponentAt)
		val exponent = if (exponentAt < 0) null else source.substring(exponentAt + 1).removePrefix("+").removePrefix("-")
		val dot = mantissa.indexOf('.')
		if (dot >= 0 && mantissa.indexOf('.', dot + 1) >= 0) fail(token, "Unsupported Kotlin floating literal '${token.text}'")
		val whole = if (dot < 0) mantissa else mantissa.substring(0, dot)
		val fraction = if (dot < 0) null else mantissa.substring(dot + 1)
		validateDigitSeparators(token, whole, 10)
		fraction?.let { validateDigitSeparators(token, it, 10) }
		exponent?.let { validateDigitSeparators(token, it, 10) }
	}

	private fun validateDigitSeparators(token: Token, source: String, radix: Int) {
		fun valid(char: Char) = char.digitToIntOrNull(radix) != null
		if (source.isEmpty()) fail(token, "Missing digits in Kotlin numeric literal '${token.text}'")
		var position = 0
		while (position < source.length) {
			if (valid(source[position])) { position += 1; continue }
			if (source[position] != '_' || position == 0 || !valid(source[position - 1])) fail(token.copy(offset = token.offset + token.text.indexOf(source) + position), "Invalid Kotlin numeric separator")
			while (position < source.length && source[position] == '_') position += 1
			if (position == source.length || !valid(source[position])) fail(token.copy(offset = token.offset + token.text.indexOf(source) + position.coerceAtMost(source.lastIndex)), "Invalid Kotlin numeric separator")
		}
	}

	private fun normalizeQuoted(token: Token): String {
		if (token.kind == Kind.STRING && token.text.startsWith("\"\"\"")) {
			val body = token.text.removePrefix("\"\"\"").removeSuffix("\"\"\"")
			val interpolation = body.indexOf('$')
			if (interpolation >= 0) fail(token.copy(offset = token.offset + 3 + interpolation), "Kotlin string interpolation has no faithful Crescent representation")
			return "\"${escapeCrescent(body)}\""
		}
		val quote = token.text.first()
		val body = token.text.substring(1, token.text.length - 1)
		val output = StringBuilder()
		var position = 0
		while (position < body.length) {
			val char = body[position++]
			if (char != '\\') {
				if (token.kind == Kind.STRING && char == '$') fail(token.copy(offset = token.offset + position), "Kotlin string interpolation has no faithful Crescent representation")
				output.append(char)
				continue
			}
			val escapeToken = token.copy(offset = token.offset + position)
			if (position == body.length) fail(escapeToken, "Unterminated Kotlin escape")
			val escaped = body[position++]
			when (escaped) {
				'n', 'r', 't', '0', '\\', '"', '\'', '$' -> output.append('\\').append(escaped)
				'b' -> fail(escapeToken, "Kotlin backspace escape has no faithful Crescent representation")
				'u' -> {
					if (position + 4 > body.length) fail(escapeToken, "Incomplete Kotlin Unicode escape")
					val code = body.substring(position, position + 4).toIntOrNull(16) ?: fail(escapeToken, "Invalid Kotlin Unicode escape")
					position += 4
					val decoded = code.toChar()
					output.append(if (quote == '\'' && decoded == '\'') "\\'" else escapeCrescent(decoded.toString()))
				}
				else -> fail(escapeToken, "Unsupported Kotlin escape '\\$escaped'")
			}
		}
		return "$quote$output$quote"
	}

	private fun escapeCrescent(value: String): String = buildString {
		for (char in value) append(
			when (char) { '\\' -> "\\\\"; '"' -> "\\\""; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '\u0000' -> "\\0"; else -> char.toString() },
		)
	}
	private fun needsSpace(left: Token?, right: Token): Boolean {
		if (left == null) return false
		if (right.text in setOf(")", "]", "}", ",", ".", ";")) return false
		if (left.text in setOf("(", "[", "{", ".", "@")) return false
		return left.kind in setOf(Kind.WORD, Kind.NUMBER, Kind.STRING, Kind.CHAR) && right.kind in setOf(Kind.WORD, Kind.NUMBER, Kind.STRING, Kind.CHAR)
	}

	private fun readBalanced(open: String, close: String): List<Token> {
		val start = expect(open)
		val result = mutableListOf(start)
		var depth = 1
		while (depth > 0) {
			if (atEnd()) fail(start, "Unterminated '$open'; expected '$close'")
			val token = next(); result += token
			if (token.text == open) depth++ else if (token.text == close) depth--
		}
		return result
	}

	private fun readQualifiedName(allowStar: Boolean): String {
		val parts = mutableListOf(expectWord("qualified name").text)
		while (match(".")) {
			if (allowStar && match("*")) { parts += "*"; break }
			parts += expectWord("qualified name segment").text
		}
		return parts.joinToString(".")
	}

	private fun registerImport(visible: String, qualified: String) {
		val previous = declaredImports[visible] ?: syntheticImports[visible]
		if (previous != null && previous != qualified) fail(peek(), "Ambiguous visible type '$visible' from '$previous' and '$qualified'")
		if (index > 0 && previous == null && qualified.substringAfterLast('.') != "*") syntheticImports[visible] = qualified
		declaredImports[visible] = qualified
	}

	private fun comment(token: Token): String = token.text.removePrefix("//").removePrefix("/*").removeSuffix("*/").lineSequence().joinToString("\n") { "# ${it.trim()}" }
	private fun consumeLineEnd() { while (match(";")) { /* consume */ }; if (peek().kind == Kind.NEWLINE) index += 1 }
	private fun consumeOptional(text: String) { if (peek().text == text) index += 1 }
	private fun skipNewlines() { while (peek().kind == Kind.NEWLINE) index += 1 }
	private fun atEnd() = peek().kind == Kind.EOF
	private fun peek(distance: Int = 0) = tokens[(index + distance).coerceAtMost(tokens.lastIndex)]
	private fun previous() = tokens[(index - 1).coerceAtLeast(0)]
	private fun previousOrNull() = tokens.getOrNull(index - 1)
	private fun next() = tokens[index++]
	private fun match(text: String): Boolean = if (peek().text == text) { index++; true } else false
	private fun expect(text: String): Token = if (match(text)) previous() else fail(peek(), "Expected '$text', got '${peek().text}'")
	private fun expectWord(context: String): Token = if (peek().kind == Kind.WORD) next() else fail(peek(), "Expected $context, got '${peek().text}'")
	private fun fail(token: Token, message: String): Nothing = throw IllegalArgumentException("$message at offset ${token.offset}")
	private fun impossible(token: Token, construct: String): Nothing = fail(token, "$construct has no faithful Crescent representation")

	companion object {
		private val primitiveTypes = mapOf("UByte" to "U8", "UShort" to "U16", "UInt" to "U32", "ULong" to "U64", "Byte" to "I8", "Short" to "I16", "Int" to "I32", "Long" to "I64", "Float" to "F32", "Double" to "F64", "Boolean" to "Boolean", "Char" to "Char", "String" to "String", "Any" to "Any", "Unit" to "Unit")
		private val primitiveArrays = primitiveTypes.filterKeys { it !in setOf("Any", "Unit") }.mapKeys { "${it.key}Array" }
		private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
		private val UINT_MAX = BigInteger("4294967295")
		private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
		private val ULONG_MAX = BigInteger("18446744073709551615")
		private val arrayFactories = setOf("arrayOf", "listOf", "mutableListOf", "emptyList", "booleanArrayOf", "byteArrayOf", "shortArrayOf", "intArrayOf", "longArrayOf", "ubyteArrayOf", "ushortArrayOf", "uintArrayOf", "ulongArrayOf", "charArrayOf", "floatArrayOf", "doubleArrayOf")
		private val prefixKeywords = setOf("protected", "suspend", "override", "operator", "inline", "infix", "final", "abstract", "open", "external", "tailrec", "lateinit", "expect", "actual")
		private val multiSymbols = listOf("===", "!==", "!is", "..<", "?.", "?:", "!!", "::", "->", "..", "++", "--", "<=", ">=", "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "%=")

		private fun lex(source: String): List<Token> {
			val result = mutableListOf<Token>(); var i = 0
			while (i < source.length) {
				val start = i; val ch = source[i]
				when {
					ch == '\r' -> { i++; if (i < source.length && source[i] == '\n') i++; result += Token(Kind.NEWLINE, "\n", start) }
					ch == '\n' -> { i++; result += Token(Kind.NEWLINE, "\n", start) }
					ch.isWhitespace() -> i++
					source.startsWith("//", i) -> { i += 2; while (i < source.length && source[i] !in "\r\n") i++; result += Token(Kind.COMMENT, source.substring(start, i), start) }
					source.startsWith("/*", i) -> { i += 2; var depth = 1; while (i < source.length && depth > 0) { when { source.startsWith("/*", i) -> { depth++; i += 2 }; source.startsWith("*/", i) -> { depth--; i += 2 }; else -> i++ } }; if (depth != 0) throw IllegalArgumentException("Unterminated block comment at offset $start"); result += Token(Kind.COMMENT, source.substring(start, i), start) }
					ch == '"' || ch == '\'' -> {
						val quote = ch
						val triple = quote == '"' && source.startsWith("\"\"\"", i)
						if (triple) i += 3 else i++
						var escaped = false
						var terminated = false
						while (i < source.length) {
							if (triple && source.startsWith("\"\"\"", i)) { i += 3; terminated = true; break }
							val current = source[i++]
							if (!triple && current == quote && !escaped) { terminated = true; break }
							escaped = current == '\\' && !escaped
							if (current != '\\') escaped = false
						}
						if (!terminated) throw IllegalArgumentException("Unterminated quoted literal at offset $start")
						result += Token(if (quote == '\'') Kind.CHAR else Kind.STRING, source.substring(start, i), start)
					}
					ch == '`' -> throw IllegalArgumentException("Kotlin escaped identifiers have no faithful Crescent representation at offset $start")
					ch.isLetter() || ch == '_' -> { i++; while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++; result += Token(Kind.WORD, source.substring(start, i), start) }
					ch.isDigit() -> {
						i++
						if (ch == '0' && source.getOrNull(i)?.lowercaseChar() in setOf('x', 'b')) {
							i++
							while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
						} else {
							while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
							if (i < source.length && source[i] == '.' && source.getOrNull(i + 1) != '.') {
								i++
								while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
							}
							if (i < source.length && source[i].lowercaseChar() == 'e') {
								i++
								if (source.getOrNull(i) in setOf('+', '-')) i++
								while (i < source.length && (source[i].isDigit() || source[i] == '_')) i++
							}
							if (source.getOrNull(i)?.lowercaseChar() in setOf('f', 'd', 'u', 'l')) {
								i++
								if (source.getOrNull(i)?.lowercaseChar() in setOf('u', 'l')) i++
							}
						}
						result += Token(Kind.NUMBER, source.substring(start, i), start)
					}
					else -> { val symbol = multiSymbols.firstOrNull { source.startsWith(it, i) } ?: ch.toString(); i += symbol.length; result += Token(Kind.SYMBOL, symbol, start) }
				}
			}
			result += Token(Kind.EOF, "<eof>", source.length); return result
		}
	}
}
