package dev.twelveoclock.lang.crescent.parsers

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import dev.twelveoclock.lang.crescent.iterator.PeekingCharIterator
import dev.twelveoclock.lang.crescent.iterator.PeekingTokenIterator
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.math.ShuntingYard
import java.nio.file.Path

object CrescentParser {

	fun invoke(filePath: Path, tokens: List<CrescentToken>): CrescentAST.Node.File {

		val imports = mutableListOf<CrescentAST.Node.Import>()

		val impls = mutableMapOf<String, CrescentAST.Node.Impl>()
		val enums = mutableMapOf<String, CrescentAST.Node.Enum>()
		val traits = mutableMapOf<String, CrescentAST.Node.Trait>()
		val staticImpls = mutableMapOf<String, CrescentAST.Node.Impl>()

		val sealeds = mutableMapOf<String, CrescentAST.Node.Sealed>()
		val structs = mutableMapOf<String, CrescentAST.Node.Struct>()
		val objects = mutableMapOf<String, CrescentAST.Node.Object>()

		val functions = mutableMapOf<String, CrescentAST.Node.Function>()
		val variables = mutableMapOf<String, CrescentAST.Node.Variable.Basic>()
		val constants = mutableMapOf<String, CrescentAST.Node.Variable.Constant>()
		val declaredTypes = mutableSetOf<String>()
		val declaredGlobals = mutableSetOf<String>()

		var mainFunction: CrescentAST.Node.Function? = null
		val tokenIterator = PeekingTokenIterator.excludingComments(tokens, filePath)

		var visibility = CrescentToken.Visibility.PUBLIC
		var hasExplicitVisibility = false
		val modifiers = mutableListOf<CrescentToken.Modifier>()
		var hasPendingPrefix = false

		while (tokenIterator.hasNext()) {

			val token = tokenIterator.next()
			val declarationStart = tokenIterator.position - 1

			// Should read top level tokens
			when (token) {

				is CrescentToken.Visibility -> {
					if (hasExplicitVisibility) {
						tokenIterator.fail("Multiple visibility modifiers before a declaration")
					}
					visibility = token
					hasExplicitVisibility = true
					hasPendingPrefix = true
					continue
				}

				is CrescentToken.Modifier -> {
					if (token in modifiers) tokenIterator.fail("Duplicate modifier '$token' before a declaration")
					modifiers += token
					hasPendingPrefix = true
					continue
				}

				CrescentToken.Type.STRUCT -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")
					readStruct(tokenIterator, visibility).let {
						registerType(it.name, "struct", declaredTypes, tokenIterator)
						structs.putUnique(it.name, it, "struct", tokenIterator)
					}
				}

				CrescentToken.Type.IMPL -> {
					rejectPrefix(hasPendingPrefix, token, tokenIterator)
					readImpl(tokenIterator).let {
						val typeName = (it.type as? CrescentAST.Node.Type.Basic)?.name ?: "${it.type}"
						if (CrescentToken.Modifier.STATIC in it.modifiers) {
							staticImpls.putUnique(typeName, it, "static impl", tokenIterator)
						} else {
							impls.putUnique(typeName, it, "impl", tokenIterator)
						}
					}
				}

				CrescentToken.Variable.CONST -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")
					readConstant(tokenIterator, visibility).let {
						registerGlobal(it.name, declaredGlobals, tokenIterator)
						constants.putUnique(it.name, it, "constant", tokenIterator)
					}
				}

				CrescentToken.Variable.VAL, CrescentToken.Variable.VAR -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")
					readVariables(tokenIterator, visibility, token == CrescentToken.Variable.VAL).forEach {
						registerGlobal(it.name, declaredGlobals, tokenIterator)
						variables.putUnique(it.name, it, "variable", tokenIterator)
					}
				}

				CrescentToken.Type.TRAIT -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")

					val name = tokenIterator.nextKey("trait name")
					val functionTraits = mutableListOf<CrescentAST.Node.FunctionTrait>()
					val functionTraitNames = mutableSetOf<String>()

					readNextUntilClosed(tokenIterator) { innerTokens ->

						when (innerTokens) {

							CrescentToken.Bracket.OPEN,
							CrescentToken.Parenthesis.OPEN,
							CrescentToken.Parenthesis.CLOSE -> {
							}

							CrescentToken.Statement.FUN -> {
								val functionTrait = readFunctionTrait(tokenIterator)
								if (!functionTraitNames.add(functionTrait.name)) {
									tokenIterator.fail("Duplicate function '${functionTrait.name}' in trait '$name'")
								}
								functionTraits += functionTrait
							}

							else -> tokenIterator.fail("Unexpected token $innerTokens in trait '$name'")
						}

					}

					registerType(name, "trait", declaredTypes, tokenIterator)
					traits.putUnique(
						name,
						tokenIterator.locate(declarationStart, CrescentAST.Node.Trait(name, functionTraits, visibility)),
						"trait",
						tokenIterator,
					)
				}

				CrescentToken.Type.OBJECT -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")
					readObject(tokenIterator, visibility).let {
						registerType(it.name, "object", declaredTypes, tokenIterator)
						objects.putUnique(it.name, it, "object", tokenIterator)
					}
				}

				CrescentToken.Type.ENUM -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")

					val name = tokenIterator.nextKey("enum name")
					val parameters = readParameters(tokenIterator)
					val entries = mutableListOf<CrescentAST.Node.EnumEntry>()
					val entryNames = mutableSetOf<String>()

					readNextUntilClosed(tokenIterator) { innerToken ->

						if (innerToken == CrescentToken.Bracket.OPEN) {
							return@readNextUntilClosed
						}

						val entryName = (innerToken as? CrescentToken.Key)?.string
							?: tokenIterator.fail("Expected enum entry name, got $innerToken")
						val entryStart = tokenIterator.position - 1
						val entryArgs = readArguments(tokenIterator)
						if (!entryNames.add(entryName)) tokenIterator.fail("Duplicate enum entry '$entryName' in enum '$name'")

						entries += tokenIterator.locate(entryStart, CrescentAST.Node.EnumEntry(entryName, entryArgs))
					}

					registerType(name, "enum", declaredTypes, tokenIterator)
					enums.putUnique(
						name,
						tokenIterator.locate(declarationStart, CrescentAST.Node.Enum(name, parameters, entries, visibility)),
						"enum",
						tokenIterator,
					)
				}

				CrescentToken.Type.SEALED -> {
					if (modifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on functions")

					val name = tokenIterator.nextKey("sealed type name")
					val innerStructs = mutableListOf<CrescentAST.Node.Struct>()
					val innerObjects = mutableListOf<CrescentAST.Node.Object>()
					val innerNames = mutableSetOf<String>()
					var innerVisibility = CrescentToken.Visibility.PUBLIC
					var hasInnerVisibility = false

					readNextUntilClosed(tokenIterator) { innerToken ->

						when (innerToken) {
							is CrescentToken.Visibility -> {
								if (hasInnerVisibility) tokenIterator.fail("Multiple visibility modifiers before sealed member")
								innerVisibility = innerToken
								hasInnerVisibility = true
							}
							CrescentToken.Type.STRUCT -> {
								val struct = readStruct(tokenIterator, innerVisibility)
								if (!innerNames.add(struct.name)) tokenIterator.fail("Duplicate sealed member '${struct.name}'")
								innerStructs += struct
								innerVisibility = CrescentToken.Visibility.PUBLIC
								hasInnerVisibility = false
							}
							CrescentToken.Type.OBJECT -> {
								val objectNode = readObject(tokenIterator, innerVisibility)
								if (!innerNames.add(objectNode.name)) tokenIterator.fail("Duplicate sealed member '${objectNode.name}'")
								innerObjects += objectNode
								innerVisibility = CrescentToken.Visibility.PUBLIC
								hasInnerVisibility = false
							}
							CrescentToken.Bracket.OPEN -> Unit
							else -> tokenIterator.fail("Unexpected token $innerToken inside sealed '$name'; expected struct or object")
						}
					}
					if (hasInnerVisibility) tokenIterator.fail("Visibility modifier is not followed by a sealed member")

					registerType(name, "sealed type", declaredTypes, tokenIterator)
					sealeds.putUnique(
						name,
						tokenIterator.locate(
							declarationStart,
							CrescentAST.Node.Sealed(name, innerStructs, innerObjects, visibility),
						),
						"sealed type",
						tokenIterator,
					)
				}

				CrescentToken.Statement.FUN -> {

					val function = readFunction(tokenIterator, visibility, modifiers)

					if (function.name == "main") {

						if (mainFunction != null) tokenIterator.fail("Duplicate main function; a file may declare only one main")

						mainFunction = function
					}

					functions.putUnique(function.name, function, "function", tokenIterator)
				}

				CrescentToken.Statement.IMPORT -> {
					rejectPrefix(hasPendingPrefix, token, tokenIterator)

					// A leading separator names the root package.
					if (tokenIterator.peekNext() == CrescentToken.Operator.IMPORT_SEPARATOR) {

						// Skip import separator
						tokenIterator.next()

						val typeName = when (val imported = tokenIterator.next()) {
							is CrescentToken.Key -> imported.string
							CrescentToken.Operator.MUL -> "*"
							else -> tokenIterator.fail("Expected imported type name or '*', got $imported")
						}

						val typeAlias = if (tokenIterator.peekNext() == CrescentToken.Operator.AS) {
							if (typeName == "*") tokenIterator.fail("Wildcard imports cannot have an alias")
							tokenIterator.expect(CrescentToken.Operator.AS, "import alias operator")
							tokenIterator.nextKey("import alias")
						} else {
							null
						}

						imports += tokenIterator.locate(declarationStart, CrescentAST.Node.Import(
							path = "",
							typeName,
							typeAlias
						))

						continue
					}

					val path = buildString {

						append(tokenIterator.nextKey("import path segment"))

						while (tokenIterator.peekNext() == CrescentToken.Operator.DOT) {

							// Skip the dot
							tokenIterator.next()

							append('.').append(tokenIterator.nextKey("import path segment"))
						}
					}

					tokenIterator.expect(CrescentToken.Operator.IMPORT_SEPARATOR, "import separator in `{path}::{type}`")

					val typeName = when (val next = tokenIterator.next()) {

						is CrescentToken.Key -> next.string
						CrescentToken.Operator.MUL -> "*"

						else -> tokenIterator.fail("Expected imported type name or '*', got $next")
					}

					val typeAlias = if (tokenIterator.peekNext() == CrescentToken.Operator.AS) {
						if (typeName == "*") tokenIterator.fail("Wildcard imports cannot have an alias")
						tokenIterator.expect(CrescentToken.Operator.AS, "import alias operator")
						tokenIterator.nextKey("import alias")
					} else {
						null
					}

					imports += tokenIterator.locate(declarationStart, CrescentAST.Node.Import(path, typeName, typeAlias))
				}

				else -> tokenIterator.fail("Unexpected top-level token $token")
			}

			// Reset visibility and modifiers
			visibility = CrescentToken.Visibility.PUBLIC
			hasExplicitVisibility = false
			modifiers.clear()
			hasPendingPrefix = false
		}
		if (hasPendingPrefix) tokenIterator.fail("Visibility/modifier is not followed by a declaration")

		return SourceLocations.attach(CrescentAST.Node.File(
			path = filePath,
			imports,
			structs,
			sealeds,
			impls,
			staticImpls,
			traits,
			objects,
			enums,
			variables,
			constants,
			functions,
			mainFunction,
		), tokenIterator.sourceText?.span(0, tokenIterator.sourceText!!.text.length))
	}


	private fun readObject(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility = CrescentToken.Visibility.PUBLIC,
	): CrescentAST.Node.Object {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("object name")
		if (tokenIterator.peekNext() != CrescentToken.Bracket.OPEN) {
			return tokenIterator.locate(sourceStart, CrescentAST.Node.Object(name, emptyMap(), emptyMap(), emptyMap(), visibility))
		}

		val objectVariables = mutableMapOf<String, CrescentAST.Node.Variable.Basic>()
		val objectFunctions = mutableMapOf<String, CrescentAST.Node.Function>()
		val objectConstants = mutableMapOf<String, CrescentAST.Node.Variable.Constant>()
		val objectMembers = mutableSetOf<String>()

		var innerVisibility = CrescentToken.Visibility.PUBLIC
		var hasExplicitVisibility = false
		val innerModifiers = mutableListOf<CrescentToken.Modifier>()
		var hasPendingPrefix = false

		readNextUntilClosed(tokenIterator) { innerToken ->

			when (innerToken) {

				CrescentToken.Bracket.OPEN, CrescentToken.Parenthesis.OPEN, CrescentToken.Parenthesis.CLOSE -> {
				}

				is CrescentToken.Modifier -> {
					if (innerToken in innerModifiers) tokenIterator.fail("Duplicate modifier '$innerToken' before object member")
					innerModifiers += innerToken
					hasPendingPrefix = true
					return@readNextUntilClosed
				}

				CrescentToken.Variable.VAL, CrescentToken.Variable.VAR -> {
					if (innerModifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on object functions")
					readVariable(tokenIterator, innerVisibility, isFinal = innerToken == CrescentToken.Variable.VAL).also {
						if (!objectMembers.add(it.name)) tokenIterator.fail("Duplicate member '${it.name}' in object '$name'")
						objectVariables.putUnique(it.name, it, "object variable", tokenIterator)
					}
				}

				CrescentToken.Variable.CONST -> {
					if (innerModifiers.isNotEmpty()) tokenIterator.fail("Modifiers are only valid on object functions")
					readConstant(tokenIterator, innerVisibility).also {
						if (!objectMembers.add(it.name)) tokenIterator.fail("Duplicate member '${it.name}' in object '$name'")
						objectConstants.putUnique(it.name, it, "object constant", tokenIterator)
					}
				}

				CrescentToken.Statement.FUN -> {
					readFunction(tokenIterator, innerVisibility, innerModifiers).also {
						if (!objectMembers.add(it.name)) tokenIterator.fail("Duplicate member '${it.name}' in object '$name'")
						objectFunctions.putUnique(it.name, it, "object function", tokenIterator)
					}
				}

				is CrescentToken.Visibility -> {
					if (hasExplicitVisibility) {
						tokenIterator.fail("Multiple visibility modifiers before object member")
					}
					innerVisibility = innerToken
					hasExplicitVisibility = true
					hasPendingPrefix = true
					return@readNextUntilClosed
				}

				else -> tokenIterator.fail("Unexpected token $innerToken in object '$name'")
			}

			innerVisibility = CrescentToken.Visibility.PUBLIC
			hasExplicitVisibility = false
			innerModifiers.clear()
			hasPendingPrefix = false
		}
		if (hasPendingPrefix) tokenIterator.fail("Visibility/modifier is not followed by an object member in '$name'")

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Object(name, objectVariables, objectConstants, objectFunctions, visibility))
	}

	private fun readStruct(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility = CrescentToken.Visibility.PUBLIC,
	): CrescentAST.Node.Struct {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("struct name")
		val variables = mutableListOf<CrescentAST.Node.Variable.Basic>()
		val variableNames = mutableSetOf<String>()

		// Skip open bracket
		tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "struct field-list opening parenthesis")

		var variableVisibility = CrescentToken.Visibility.PUBLIC
		var hasPendingVisibility = false
		var foundClosingParenthesis = false

		while (tokenIterator.hasNext()) {

			when (val nextToken = tokenIterator.next()) {

				CrescentToken.Parenthesis.CLOSE -> {
					foundClosingParenthesis = true
					break
				}

				CrescentToken.Operator.COMMA -> {
					// NOOP
				}

				is CrescentToken.Visibility -> {
					if (hasPendingVisibility) tokenIterator.fail("Multiple visibility modifiers before struct field")
					variableVisibility = nextToken
					hasPendingVisibility = true
					continue
				}

				CrescentToken.Variable.VAL, CrescentToken.Variable.VAR -> {
					val read = readVariables(
							tokenIterator,
							variableVisibility,
							nextToken == CrescentToken.Variable.VAL,
							requireInitializer = false,
						)
					read.forEach { variable ->
						if (!variableNames.add(variable.name)) tokenIterator.fail("Duplicate field '${variable.name}' in struct '$name'")
					}
					variables.addAll(read)
				}

				CrescentToken.Variable.CONST -> tokenIterator.fail("Constants are not supported as struct fields")

				else -> tokenIterator.fail("Unexpected token $nextToken in struct '$name'")
			}

			// Reset visibility and modifiers
			variableVisibility = CrescentToken.Visibility.PUBLIC
			hasPendingVisibility = false
		}
		if (hasPendingVisibility) tokenIterator.fail("Visibility modifier is not followed by a field in struct '$name'")
		if (!foundClosingParenthesis) tokenIterator.fail("Unterminated struct '$name'; expected closing parenthesis")

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Struct(name, variables, visibility))
	}

	private fun readImpl(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Impl {
		val sourceStart = tokenIterator.position
		var type: CrescentAST.Node.Type? = null

		val functions = mutableListOf<CrescentAST.Node.Function>()
		val functionNames = mutableSetOf<String>()
		val extendedTypes = mutableListOf<CrescentAST.Node.Type>()
		val readModifiers = mutableListOf<CrescentToken.Modifier>()
		val implModifiers = mutableListOf<CrescentToken.Modifier>()
		var readingExtendedTypes = false

		var readVisibility = CrescentToken.Visibility.PUBLIC
		var hasExplicitVisibility = false
		var hasPendingPrefix = false

		readNextUntilClosed(tokenIterator) { token ->

			when (token) {

				CrescentToken.Bracket.OPEN, CrescentToken.Parenthesis.OPEN, CrescentToken.Parenthesis.CLOSE -> {
					if (token == CrescentToken.Bracket.OPEN && type != null && hasPendingPrefix) {
						tokenIterator.fail("Visibility/modifier is not followed by a declaration in impl")
					}
					readingExtendedTypes = false
				}

				is CrescentToken.Key, CrescentToken.SquareBracket.OPEN -> {
					tokenIterator.back() // UnSkip type start
					if (type == null) {
						type = readType(tokenIterator)
						implModifiers.addAll(readModifiers)
					} else if (readingExtendedTypes) {
						extendedTypes += readType(tokenIterator)
					}
				}

				CrescentToken.Operator.TYPE_PREFIX -> readingExtendedTypes = true
				CrescentToken.Operator.COMMA -> Unit

				is CrescentToken.Modifier -> {
					if (type == null && token != CrescentToken.Modifier.STATIC) {
						tokenIterator.fail("Only the static modifier is supported before an impl target")
					}
					if (token in readModifiers) tokenIterator.fail("Duplicate modifier '$token' in impl prefix")
					readModifiers += token
					hasPendingPrefix = true
					return@readNextUntilClosed
				}

				is CrescentToken.Visibility -> {
					if (type == null) tokenIterator.fail("Visibility is not supported before an impl target")
					if (hasExplicitVisibility) tokenIterator.fail("Multiple visibility modifiers before impl member")
					readVisibility = token
					hasExplicitVisibility = true
					hasPendingPrefix = true
					return@readNextUntilClosed
				}

				CrescentToken.Statement.FUN -> {
					val function = readFunction(tokenIterator, readVisibility, readModifiers)
					if (!functionNames.add(function.name)) tokenIterator.fail("Duplicate function '${function.name}' in impl")
					functions += function
				}

				else -> tokenIterator.fail("Unexpected token $token in impl")
			}

			readModifiers.clear()
			readVisibility = CrescentToken.Visibility.PUBLIC
			hasExplicitVisibility = false
			hasPendingPrefix = false
		}
		if (hasPendingPrefix) tokenIterator.fail("Visibility/modifier is not followed by a declaration in impl")

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Impl(
			type ?: tokenIterator.fail("An impl declaration requires a target type"),
			implModifiers.toList(),
			extendedTypes,
			functions,
		))
	}


	private fun readFunction(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility,
		modifiers: List<CrescentToken.Modifier>
	): CrescentAST.Node.Function {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("function name")
		val parameters = readParameters(tokenIterator)

		val type =
			if (tokenIterator.peekNext() == CrescentToken.Operator.RETURN) {
				tokenIterator.next()
				readType(tokenIterator)
			} else {
				CrescentAST.Node.Type.unit
			}

		val expressions = readBlock(tokenIterator)

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Function(
			name,
			modifiers.toList(),
			visibility,
			parameters,
			type,
			expressions,
		))
	}

	private fun readBlock(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Statement.Block {
		val sourceStart = tokenIterator.position
		val expressionNodes = mutableListOf<CrescentAST.Node>()

		tokenIterator.expect(CrescentToken.Bracket.OPEN, "block opening brace")

		while (tokenIterator.hasNext() && tokenIterator.peekNext() != CrescentToken.Bracket.CLOSE) {

			val peekNext = tokenIterator.peekNext()

			if (peekNext == CrescentToken.Variable.VAL || peekNext == CrescentToken.Variable.VAR) {

				tokenIterator.next()

				readVariables(
					tokenIterator,
					CrescentToken.Visibility.PUBLIC,
					peekNext == CrescentToken.Variable.VAL
				).forEach {
					expressionNodes += it
				}
			}
			else {

				val expressionNode = when (tokenIterator.peekNext()) {

					CrescentToken.Statement.WHILE -> {
						readWhile(tokenIterator)
					}

					CrescentToken.Statement.FOR -> {
						readFor(tokenIterator)
					}

					else -> readExpression(tokenIterator)
				}


				expressionNodes += expressionNode
			}
		}

		tokenIterator.expect(CrescentToken.Bracket.CLOSE, "block closing brace")

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Statement.Block(expressionNodes))
	}

	private fun readFunctionTrait(tokenIterator: PeekingTokenIterator): CrescentAST.Node.FunctionTrait {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("function trait name")
		val parameters = readParameters(tokenIterator)

		val type =
			if (tokenIterator.peekNext() == CrescentToken.Operator.RETURN) {
				tokenIterator.next()
				readType(tokenIterator)
			} else {
				CrescentAST.Node.Type.unit
			}

		return tokenIterator.locate(sourceStart, CrescentAST.Node.FunctionTrait(name, parameters, type))
	}

	private fun readConstant(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility,
	): CrescentAST.Node.Variable.Constant {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("constant name")

		val type =
			if (tokenIterator.peekNext() == CrescentToken.Operator.TYPE_PREFIX) {
				tokenIterator.next() // Skip
				readType(tokenIterator)
			} else {
				CrescentAST.Node.Type.Implicit
			}

		if (tokenIterator.peekNext() != CrescentToken.Operator.ASSIGN) {
			tokenIterator.fail("Constant '$name' requires an initializer")
		}
		tokenIterator.expect(CrescentToken.Operator.ASSIGN, "constant assignment")
		val expression = readExpression(tokenIterator)


		return tokenIterator.locate(sourceStart, CrescentAST.Node.Variable.Constant(name, type, expression, visibility))
	}

	fun readVariable(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility,
		isFinal: Boolean,
	): CrescentAST.Node.Variable.Basic {
		val sourceStart = tokenIterator.position
		val name = tokenIterator.nextKey("variable name")

		val type =
			if (tokenIterator.peekNext() == CrescentToken.Operator.TYPE_PREFIX) {
				tokenIterator.next()
				readType(tokenIterator)
			} else {
				CrescentAST.Node.Type.Implicit
			}

		if (tokenIterator.peekNext() != CrescentToken.Operator.ASSIGN) {
			tokenIterator.fail("Variable '$name' requires an initializer")
		}
		tokenIterator.expect(CrescentToken.Operator.ASSIGN, "variable assignment")
		val expression = readExpression(tokenIterator)

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Variable.Basic(name, type, expression, isFinal, visibility))
	}

	fun readVariables(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility,
		isFinal: Boolean,
	): List<CrescentAST.Node.Variable.Basic> =
		readVariables(tokenIterator, visibility, isFinal, requireInitializer = true)

	private fun readVariables(
		tokenIterator: PeekingTokenIterator,
		visibility: CrescentToken.Visibility,
		isFinal: Boolean,
		requireInitializer: Boolean,
	): List<CrescentAST.Node.Variable.Basic> {

		val declarationStart = (tokenIterator.position - 1).coerceAtLeast(0)
		if (tokenIterator.peekNext() !is CrescentToken.Key) tokenIterator.fail("Variable declaration requires a name")
		val names = mutableListOf(tokenIterator.nextKey("variable name") to (tokenIterator.position - 1))
		while (true) {
			when (tokenIterator.peekNext()) {
				is CrescentToken.Key -> names += (tokenIterator.nextKey("variable name") to (tokenIterator.position - 1))
				CrescentToken.Operator.COMMA -> {
					tokenIterator.next()
					if (tokenIterator.peekNext() !is CrescentToken.Key) {
						tokenIterator.fail("Expected variable name after ','")
					}
					names += (tokenIterator.nextKey("variable name") to (tokenIterator.position - 1))
				}
				else -> break
			}
		}
		val uniqueNames = mutableSetOf<String>()
		names.forEach { (name, _) ->
			if (!uniqueNames.add(name)) tokenIterator.fail("Duplicate variable name '$name' in declaration")
		}

		val type =
			if (tokenIterator.peekNext() == CrescentToken.Operator.TYPE_PREFIX) {
				tokenIterator.next()
				readType(tokenIterator)
			} else {
				CrescentAST.Node.Type.Implicit
			}

		val expression = if (tokenIterator.peekNext() == CrescentToken.Operator.ASSIGN) {
			tokenIterator.expect(CrescentToken.Operator.ASSIGN, "variable assignment")
			readExpression(tokenIterator)
		} else {
			if (requireInitializer) tokenIterator.fail("Variable declaration requires an initializer")
			CrescentAST.Node.Expression(emptyList())
		}

		return names.map { (name, _) ->
			tokenIterator.locate(declarationStart, CrescentAST.Node.Variable.Basic(name, type, expression, isFinal, visibility))
		}
	}

	fun readWhile(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Statement.While {
		val sourceStart = tokenIterator.position
		tokenIterator.expect(CrescentToken.Statement.WHILE, "while keyword")
		tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "while predicate opening parenthesis")

		val predicate = readExpression(tokenIterator)
		tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "while predicate closing parenthesis")
		val block = readBlock(tokenIterator)

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Statement.While(predicate, block))
	}

	fun readFor(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Statement.For {
		val sourceStart = tokenIterator.position
		tokenIterator.expect(CrescentToken.Statement.FOR, "for keyword")

		val identifiers = mutableListOf<CrescentAST.Node.Identifier>()
		val identifierNames = mutableSetOf<String>()

		while (tokenIterator.hasNext()) {

			val identifier = readExpressionNode(tokenIterator) as? CrescentAST.Node.Identifier
				?: tokenIterator.fail("Expected for-loop identifier")
			if (!identifierNames.add(identifier.name)) {
				tokenIterator.fail("Duplicate for-loop identifier '${identifier.name}'")
			}
			identifiers += identifier

			if (tokenIterator.peekNext() != CrescentToken.Operator.COMMA) {
				break
			}

			tokenIterator.expect(CrescentToken.Operator.COMMA, "for-loop identifier separator")
		}

		tokenIterator.expect(CrescentToken.Operator.CONTAINS, "for-loop in operator")

		val ranges = mutableListOf<CrescentAST.Node.Statement.Range>()

		while (tokenIterator.hasNext()) {
			val rangeStart = tokenIterator.position
			val start = readExpressionUntil(tokenIterator, setOf(CrescentToken.Operator.RANGE_TO))
			tokenIterator.expect(CrescentToken.Operator.RANGE_TO, "for-loop range separator")
			val end = readExpressionUntil(
				tokenIterator,
				setOf(CrescentToken.Operator.COMMA, CrescentToken.Bracket.OPEN),
			)
			ranges += tokenIterator.locate(rangeStart, CrescentAST.Node.Statement.Range(start, end))

			if (tokenIterator.peekNext() != CrescentToken.Operator.COMMA) {
				break
			}

			tokenIterator.expect(CrescentToken.Operator.COMMA, "for-loop range separator")
		}

		val block = readBlock(tokenIterator)

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Statement.For(identifiers, ranges, block))
	}

	fun readParameters(tokenIterator: PeekingTokenIterator): List<CrescentAST.Node.Parameter> {

		if (tokenIterator.peekNext() != CrescentToken.Parenthesis.OPEN) {
			return emptyList()
		}

		tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "parameter-list opening parenthesis")
		val parameters = mutableListOf<CrescentAST.Node.Parameter>()
		val parameterNames = mutableSetOf<String>()

		var foundDefault = false
		while (tokenIterator.hasNext() && tokenIterator.peekNext() != CrescentToken.Parenthesis.CLOSE) {

			val namesStart = tokenIterator.position
			val names = tokenIterator.nextUntil { it !is CrescentToken.Key }.mapIndexed { index, token ->
				((token as? CrescentToken.Key)?.string ?: tokenIterator.fail("Expected parameter name, got $token")) to
					(namesStart + index)
			}
			if (names.isEmpty()) tokenIterator.fail("Expected parameter name")
			names.forEach { (name, _) ->
				if (!parameterNames.add(name)) tokenIterator.fail("Duplicate parameter '$name'")
			}

			tokenIterator.expect(CrescentToken.Operator.TYPE_PREFIX, "parameter type separator")
			val type = readType(tokenIterator)
			val defaultValue = if (tokenIterator.peekNext() == CrescentToken.Operator.ASSIGN) {
				tokenIterator.next()
				foundDefault = true
				when (val value = readExpression(tokenIterator)) {
					is CrescentAST.Node.Expression -> value
					else -> CrescentAST.Node.Expression(listOf(value))
				}
			} else {
				if (foundDefault) tokenIterator.fail("Required parameters cannot follow default parameters")
				null
			}

			names.forEach { (name, sourceStart) ->
				parameters += tokenIterator.locate(sourceStart, if (defaultValue == null) {
					CrescentAST.Node.Parameter.Basic(name, type)
				} else {
					CrescentAST.Node.Parameter.WithDefault(name, type, defaultValue)
				})
			}

			if (tokenIterator.peekNext() != CrescentToken.Parenthesis.CLOSE) {
				tokenIterator.expect(CrescentToken.Operator.COMMA, "parameter separator")
			}
		}

		tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "parameter-list closing parenthesis")

		return parameters
	}

	fun readArguments(tokenIterator: PeekingTokenIterator): List<CrescentAST.Node> {

		if (tokenIterator.peekNext() != CrescentToken.Parenthesis.OPEN) {
			return emptyList()
		}

		tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "argument-list opening parenthesis")
		val arguments = mutableListOf<CrescentAST.Node>()

		while (tokenIterator.hasNext() && tokenIterator.peekNext() != CrescentToken.Parenthesis.CLOSE) {

			arguments += readExpression(tokenIterator)

			if (tokenIterator.peekNext() != CrescentToken.Parenthesis.CLOSE) {
				tokenIterator.expect(CrescentToken.Operator.COMMA, "argument separator")
			}
		}

		tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "argument-list closing parenthesis")

		return arguments
	}

	fun readGetArguments(tokenIterator: PeekingTokenIterator): List<CrescentAST.Node> {

		if (tokenIterator.peekNext() != CrescentToken.SquareBracket.OPEN) {
			return emptyList()
		}

		tokenIterator.expect(CrescentToken.SquareBracket.OPEN, "index-list opening bracket")
		val arguments = mutableListOf<CrescentAST.Node>()

		while (tokenIterator.hasNext() && tokenIterator.peekNext() != CrescentToken.SquareBracket.CLOSE) {

			arguments += readExpression(tokenIterator)

			if (tokenIterator.peekNext() != CrescentToken.SquareBracket.CLOSE) {
				tokenIterator.expect(CrescentToken.Operator.COMMA, "index separator")
			}
		}

		tokenIterator.expect(CrescentToken.SquareBracket.CLOSE, "index-list closing bracket")

		return arguments
	}

	fun readWhen(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Statement.When {
		val sourceStart = tokenIterator.position
		val clauses = mutableListOf<CrescentAST.Node.Statement.When.Clause>()
		var hasElseClause = false

		if (tokenIterator.peekNext() != CrescentToken.Parenthesis.OPEN) {
			tokenIterator.fail("When expression requires a parenthesized subject")
		}
		tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "when argument opening parenthesis")
		if (tokenIterator.peekNext() == CrescentToken.Parenthesis.CLOSE) {
			tokenIterator.fail("When expression requires a nonempty subject")
		}
		val subjectName = if (tokenIterator.peekNext() is CrescentToken.Key && tokenIterator.peekNext(2) == CrescentToken.Operator.ASSIGN) {
			tokenIterator.nextKey("when subject name").also {
				tokenIterator.expect(CrescentToken.Operator.ASSIGN, "named when subject assignment")
			}
		} else null
		val argument = readExpression(tokenIterator)

		tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "when argument closing parenthesis")
		tokenIterator.expect(CrescentToken.Bracket.OPEN, "when body opening brace")

		readNextUntilClosed(tokenIterator, initialDepth = 1) {

			// Unskip the read token from readNextUntilClosed(tokenIterator)
			tokenIterator.back()
			if (hasElseClause) {
				if (tokenIterator.peekNext() == CrescentToken.Statement.ELSE) {
					tokenIterator.fail("Multiple else clauses in when expression")
				}
				tokenIterator.fail("When else clause must be last")
			}

			val ifExpressionNode = when (tokenIterator.peekNext()) {

				CrescentToken.Operator.DOT -> {

					tokenIterator.expect(CrescentToken.Operator.DOT, "enum shorthand prefix")
					val identifier = readExpressionNode(tokenIterator) as? CrescentAST.Node.Identifier
						?: tokenIterator.fail("Expected enum shorthand identifier after '.'")
					CrescentAST.Node.Statement.When.EnumShortHand(identifier.name)
				}

				CrescentToken.Statement.ELSE -> {

					tokenIterator.expect(CrescentToken.Statement.ELSE, "else clause")
					tokenIterator.expect(CrescentToken.Operator.RETURN, "when clause arrow")

					CrescentAST.Node.Statement.When.Else(readBlock(tokenIterator))
				}

				else -> {
					readExpressionUntil(tokenIterator, setOf(CrescentToken.Operator.RETURN))
				}

			}


			// If is else statement, no ifExpression
			if (ifExpressionNode is CrescentAST.Node.Statement.When.Else) {
				clauses += CrescentAST.Node.Statement.When.Clause(null, ifExpressionNode.thenBlock)
				hasElseClause = true
				return@readNextUntilClosed
			}

			tokenIterator.expect(CrescentToken.Operator.RETURN, "when clause arrow")

			val thenExpressions =
				if (tokenIterator.peekNext() == CrescentToken.Bracket.OPEN) {
					readBlock(tokenIterator)
				} else {
					CrescentAST.Node.Statement.Block(listOfNotNull(readExpression(tokenIterator)))
				}

			clauses += CrescentAST.Node.Statement.When.Clause(ifExpressionNode, thenExpressions)
		}

		return tokenIterator.locate(sourceStart, CrescentAST.Node.Statement.When(argument, clauses, subjectName))
	}

	fun readType(tokenIterator: PeekingTokenIterator): CrescentAST.Node.Type {
		val sourceStart = tokenIterator.position
		var type: CrescentAST.Node.Type

		when (val peekNext = tokenIterator.peekNext()) {

			CrescentToken.SquareBracket.OPEN -> {

				tokenIterator.expect(CrescentToken.SquareBracket.OPEN, "array type opening bracket")
				type = CrescentAST.Node.Type.Array(readType(tokenIterator))

				// Skip Array close
				tokenIterator.expect(CrescentToken.SquareBracket.CLOSE, "array type closing bracket")
			}

			is CrescentToken.Key -> {
				tokenIterator.next()
				type = CrescentAST.Node.Type.Basic(peekNext.string)
			}

			else -> tokenIterator.fail("Expected type, got $peekNext")

		}

		if (tokenIterator.peekNext() == CrescentToken.Operator.RESULT) {
			tokenIterator.next()
			type = CrescentAST.Node.Type.Result(type)
		}

		return tokenIterator.locate(sourceStart, type)
	}

	// Returns null if the node should be skipped in the expression
	fun readExpressionNode(tokenIterator: PeekingTokenIterator, isInDotChain: Boolean = false): CrescentAST.Node? {
		val sourceStart = tokenIterator.position
		return readExpressionNodeImpl(tokenIterator, isInDotChain)?.let { tokenIterator.locate(sourceStart, it) }
	}

	private fun readExpressionNodeImpl(tokenIterator: PeekingTokenIterator, isInDotChain: Boolean): CrescentAST.Node? {

		return when (val next = tokenIterator.next()) {

			is CrescentToken.Data.Comment -> {
				null
			}

			CrescentToken.SquareBracket.OPEN -> {

				val nodes = mutableListOf<CrescentAST.Node>()

				while (
					tokenIterator.hasNext() &&
					tokenIterator.peekNext() != CrescentToken.SquareBracket.CLOSE
				) {

					nodes += readExpressionUntil(
						tokenIterator,
						setOf(CrescentToken.Operator.COMMA, CrescentToken.SquareBracket.CLOSE),
					)

					if (tokenIterator.peekNext() != CrescentToken.SquareBracket.CLOSE) {
						tokenIterator.expect(CrescentToken.Operator.COMMA, "array element separator")
					}
				}

				tokenIterator.expect(CrescentToken.SquareBracket.CLOSE, "array literal closing bracket")
				readMemberChain(CrescentAST.Node.Array(nodes.toTypedArray()), tokenIterator, isInDotChain)
			}

			CrescentToken.Operator.RETURN -> {
				CrescentAST.Node.Return(readExpression(tokenIterator))
			}

			is CrescentToken.Operator -> {
				if (next == CrescentToken.Operator.DOT) {
					tokenIterator.fail("Member access '.' must follow a primary expression")
				}
				next
			}

			CrescentToken.Statement.WHEN -> {
				readWhen(tokenIterator)
			}

			CrescentToken.Parenthesis.OPEN -> {
				val grouped = readExpression(tokenIterator).also {
					tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "expression closing parenthesis")
				}
				readMemberChain(grouped, tokenIterator, isInDotChain)
			}

			CrescentToken.Statement.IF -> {

				tokenIterator.expect(CrescentToken.Parenthesis.OPEN, "if predicate opening parenthesis")

				val argument = readExpression(tokenIterator).also {
					tokenIterator.expect(CrescentToken.Parenthesis.CLOSE, "if predicate closing parenthesis")
				}

				val ifBlock = readBlock(tokenIterator)

				val elseBlock =
					if (tokenIterator.peekNext() == CrescentToken.Statement.ELSE) {
						tokenIterator.next()
						readBlock(tokenIterator)
					} else {
						null
					}

				CrescentAST.Node.Statement.If(argument, ifBlock, elseBlock)
			}

			is CrescentToken.Data -> {
				when (next) {

					is CrescentToken.Data.String -> {
						val escapedDollarOffsets = tokenIterator.escapedDollarOffsetsForLastToken()
						val stringSourceOffsets = tokenIterator.stringContentSourceOffsetsForLastToken()

						// If no string interpolation, keep it simple
						if ('$' !in next.kotlinString) {
							return readMemberChain(CrescentAST.Node.Primitive.String(next.kotlinString), tokenIterator, isInDotChain)
						}


						// String interpolation
						val nodes = mutableListOf<CrescentAST.Node>()
						val builder = StringBuilder()
						val iterator = PeekingCharIterator(next.kotlinString)

						while (iterator.hasNext()) {

							val nextChar = iterator.next()
							val charOffset = iterator.position - 1

							if (nextChar == '$' && charOffset in escapedDollarOffsets) {
								builder.append('$')
								continue
							}

							val interpolationStart = iterator.peekNextOrNull()
							if (nextChar != '$' || interpolationStart == null || (!interpolationStart.isLetter() && interpolationStart != '_' && interpolationStart != '{')) {
								builder.append(nextChar)
								continue
							}

							if (builder.isNotEmpty() || (builder.isEmpty() && nodes.isEmpty())) {
								nodes += CrescentAST.Node.Primitive.String(builder.toString())
								builder.clear()
								nodes += CrescentToken.Operator.ADD
							}

							if (iterator.peekNextOrNull() == '{') {
								iterator.next()
								val interpolationBodyStart = iterator.position
								val interpolationBody = try {
									iterator.readInterpolationBody()
								} catch (_: UnterminatedInterpolation) {
									val source = tokenIterator.sourceText
									val span = if (source != null && stringSourceOffsets != null) {
										source.span(stringSourceOffsets[charOffset], stringSourceOffsets[iterator.position])
									} else tokenIterator.lastSpan
									val absoluteOffset = span?.start?.offset ?: charOffset
									throw DiagnosticException(Diagnostic(
										DiagnosticSeverity.ERROR,
										"Unterminated string interpolation starting at offset $absoluteOffset",
										span,
									))
								}
								val interpolationBodyEnd = iterator.position - 1
								val interpolationLexed = if (tokenIterator.sourceText != null && stringSourceOffsets != null) {
									CrescentLexer.invoke(
										tokenIterator.sourceText!!,
										interpolationBody,
										stringSourceOffsets.copyOfRange(interpolationBodyStart, interpolationBodyEnd + 1),
									)
								} else CrescentLexer.invoke(interpolationBody)
								val interpolationTokens = PeekingTokenIterator(interpolationLexed)
								nodes += readExpression(interpolationTokens)
								if (interpolationTokens.hasNext()) {
									tokenIterator.fail("String interpolation must contain exactly one complete expression")
								}
							} else {
								val identifierStart = iterator.position
								val identifier = CrescentAST.Node.Identifier(iterator.nextUntil { it == ' ' || it == '$' || it != '_' && !it.isLetterOrDigit() })
								val source = tokenIterator.sourceText
								nodes += if (source != null && stringSourceOffsets != null) {
									SourceLocations.attach(identifier, source.span(stringSourceOffsets[identifierStart], stringSourceOffsets[iterator.position]))
								} else identifier
							}

							if (iterator.hasNext()) {
								nodes += CrescentToken.Operator.ADD
							}
						}

						if (builder.isNotEmpty()) {
							nodes += CrescentAST.Node.Primitive.String(builder.toString())
						}

						val interpolated = if (nodes.size == 1) {
							nodes[0]
						} else {
							CrescentAST.Node.Expression(ShuntingYard.invoke(nodes))
						}
						return readMemberChain(interpolated, tokenIterator, isInDotChain)
					}

					is CrescentToken.Data.Char -> {
						readMemberChain(CrescentAST.Node.Primitive.Char(next.kotlinChar), tokenIterator, isInDotChain)
					}

					is CrescentToken.Data.Boolean -> {
						readMemberChain(CrescentAST.Node.Primitive.Boolean(next.kotlinBoolean), tokenIterator, isInDotChain)
					}

					is CrescentToken.Data.Number -> {
						readMemberChain(CrescentAST.Node.Primitive.Number.from(next.number), tokenIterator, isInDotChain)
					}

					else -> tokenIterator.fail("Unknown data token $next")
				}
			}

			is CrescentToken.Key -> {

				val identifier = next.string

				val keyNode = when {

					!tokenIterator.isAtStatementBoundary && tokenIterator.peekNext() == CrescentToken.Parenthesis.OPEN -> {
						CrescentAST.Node.IdentifierCall(identifier, readArguments(tokenIterator))
					}

					!tokenIterator.isAtStatementBoundary && tokenIterator.peekNext() == CrescentToken.SquareBracket.OPEN -> {
						CrescentAST.Node.GetCall(identifier, readGetArguments(tokenIterator))
					}

					else -> {
						CrescentAST.Node.Identifier(identifier)
					}

				}

				return readMemberChain(keyNode, tokenIterator, isInDotChain)
			}

			else -> {
				tokenIterator.fail("Unexpected expression token $next")
			}

		}

	}

	private fun readMemberChain(
		primary: CrescentAST.Node,
		tokenIterator: PeekingTokenIterator,
		isInDotChain: Boolean,
	): CrescentAST.Node {
		if (isInDotChain || tokenIterator.isAtStatementBoundary || tokenIterator.peekNext() != CrescentToken.Operator.DOT) return primary
		val nodes = mutableListOf(primary)
		while (!tokenIterator.isAtStatementBoundary && tokenIterator.peekNext() == CrescentToken.Operator.DOT) {
			tokenIterator.next()
			nodes += readExpressionNode(tokenIterator, true) ?: tokenIterator.fail("Expected member after '.'")
		}
		return tokenIterator.locate((tokenIterator.position - nodes.size).coerceAtLeast(0), CrescentAST.Node.DotChain(nodes))
	}

	fun readExpression(tokenIterator: PeekingTokenIterator): CrescentAST.Node =
		readExpressionUntil(tokenIterator, emptySet(), stopAtStatementBoundary = true)

	private fun readExpressionUntil(
		tokenIterator: PeekingTokenIterator,
		terminators: Set<CrescentToken>,
		stopAtStatementBoundary: Boolean = true,
	): CrescentAST.Node {
		val sourceStart = tokenIterator.position
		val nodes = mutableListOf<CrescentAST.Node>()

		if (tokenIterator.peekNext() == CrescentToken.Bracket.OPEN) {
			tokenIterator.next()
		}

		while (tokenIterator.hasNext()) {
			if (tokenIterator.peekNext() in terminators) break
			if (
				stopAtStatementBoundary && tokenIterator.isAtStatementBoundary && nodes.isNotEmpty() &&
				nodes.lastOrNull() !is CrescentToken.Operator
			) break
			if (nodes.lastOrNull() in typeOperandOperators) {
				nodes += CrescentAST.Node.TypeLiteral(readType(tokenIterator))
				continue
			}
			if (
				nodes.size == 1 && isUnambiguousInfixReceiver(nodes.single()) &&
				tokenIterator.peekNext() is CrescentToken.Key && !tokenIterator.isAtStatementBoundary
			) {
				val receiver = collapseExpressionNodes(nodes, tokenIterator)
				val functionName = tokenIterator.nextKey("infix function name")
				val argument = readExpressionUntil(
					tokenIterator,
					terminators + infixTerminators,
					stopAtStatementBoundary,
				)
				nodes.clear()
				nodes += CrescentAST.Node.InfixCall(receiver, functionName, argument)
				continue
			}
			nodes += when (val peekNext = tokenIterator.peekNext()) {

				CrescentToken.Parenthesis.CLOSE, CrescentToken.Bracket.CLOSE, CrescentToken.SquareBracket.CLOSE -> {
					break
				}

				CrescentToken.Operator.COMMA -> {
					break
				}

				else -> {

					if (nodes.isNotEmpty()) {
						if (peekNext is CrescentToken.Operator) {
							if (peekNext == CrescentToken.Operator.RETURN) {
								break
							}
						} else if (nodes.lastOrNull() !is CrescentToken.Operator) {
							break
						}
					}

					readExpressionNode(tokenIterator) ?: continue
				}
			}
		}
		if (nodes.isEmpty()) tokenIterator.fail("Expected expression")

		return tokenIterator.locate(sourceStart, collapseExpressionNodes(nodes, tokenIterator))
	}

	private fun collapseExpressionNodes(
		nodes: List<CrescentAST.Node>,
		tokenIterator: PeekingTokenIterator,
	): CrescentAST.Node = if (nodes.size == 1) {
		nodes[0]
	} else if (nodes.any { it is CrescentToken.Operator }) {
			try {
				CrescentAST.Node.Expression(ShuntingYard.invoke(nodes))
			} catch (error: IllegalArgumentException) {
				tokenIterator.fail(error.message ?: "Malformed expression")
			}
		} else {
			CrescentAST.Node.Expression(nodes)
		}

	private val typeOperandOperators = setOf(
		CrescentToken.Operator.AS,
		CrescentToken.Operator.INSTANCE_OF,
		CrescentToken.Operator.NOT_INSTANCE_OF,
	)

	private val infixTerminators = setOf(
		CrescentToken.Operator.AND_COMPARE,
		CrescentToken.Operator.OR_COMPARE,
		CrescentToken.Operator.ASSIGN,
		CrescentToken.Operator.ADD_ASSIGN,
		CrescentToken.Operator.SUB_ASSIGN,
		CrescentToken.Operator.MUL_ASSIGN,
		CrescentToken.Operator.DIV_ASSIGN,
		CrescentToken.Operator.REM_ASSIGN,
		CrescentToken.Operator.POW_ASSIGN,
	)

	private fun isUnambiguousInfixReceiver(node: CrescentAST.Node): Boolean = when (node) {
		is CrescentAST.Node.Identifier,
		is CrescentAST.Node.IdentifierCall,
		is CrescentAST.Node.GetCall,
		is CrescentAST.Node.DotChain,
		is CrescentAST.Node.Expression,
		is CrescentAST.Node.Primitive,
		is CrescentAST.Node.Array,
		-> true
		else -> false
	}

	private fun PeekingTokenIterator.nextKey(context: String): String {
		val token = if (hasNext()) next() else fail("Expected $context, reached end of input")
		return (token as? CrescentToken.Key)?.string
			?: fail("Expected $context, got $token")
	}

	private fun rejectPrefix(
		hasPendingPrefix: Boolean,
		declaration: CrescentToken,
		tokenIterator: PeekingTokenIterator,
	) {
		if (hasPendingPrefix) tokenIterator.fail("Visibility/modifiers are not supported on $declaration declarations")
	}

	private fun registerType(
		name: String,
		kind: String,
		declaredTypes: MutableSet<String>,
		tokenIterator: PeekingTokenIterator,
	) {
		if (!declaredTypes.add(name)) tokenIterator.fail("Duplicate type '$name' while declaring $kind")
	}

	private fun registerGlobal(
		name: String,
		declaredGlobals: MutableSet<String>,
		tokenIterator: PeekingTokenIterator,
	) {
		if (!declaredGlobals.add(name)) tokenIterator.fail("Duplicate global '$name'")
	}

	private fun <T> MutableMap<String, T>.putUnique(
		name: String,
		value: T,
		kind: String,
		tokenIterator: PeekingTokenIterator,
	) {
		if (containsKey(name)) tokenIterator.fail("Duplicate $kind '$name'")
		this[name] = value
	}

	private fun PeekingTokenIterator.expect(expected: CrescentToken, context: String = expected.toString()): CrescentToken {
		val actual = if (hasNext()) next() else fail("Expected $context, reached end of input")
		if (actual != expected) fail("Expected $context ($expected), got $actual")
		return actual
	}

	private fun <T : CrescentAST.Node> PeekingTokenIterator.locate(startTokenIndex: Int, node: T): T =
		SourceLocations.attach(node, spanFrom(startTokenIndex, position))

	private fun PeekingTokenIterator.fail(message: String): Nothing {
		val detail = "Parse error at token index $position: $message"
		throw DiagnosticException(Diagnostic(DiagnosticSeverity.ERROR, detail, diagnosticSpan()))
	}

	private fun PeekingCharIterator.readInterpolationBody(): String {
		var depth = 1
		var quote: Char? = null
		var escaped = false
		return buildString {
			while (hasNext()) {
				val char = next()
				if (quote != null) {
					append(char)
					when {
						escaped -> escaped = false
						char == '\\' -> escaped = true
						char == quote -> quote = null
					}
					continue
				}
				when (char) {
					'\'', '"' -> {
						quote = char
						append(char)
					}
					'{' -> {
						depth++
						append(char)
					}
					'}' -> {
						depth--
						if (depth == 0) return@buildString
						append(char)
					}
					else -> append(char)
				}
			}
			throw UnterminatedInterpolation()
		}
	}

	private class UnterminatedInterpolation : RuntimeException()

	fun readNextUntilClosed(
		tokenIterator: PeekingTokenIterator,
		initialDepth: Int = 0,
		block: (token: CrescentToken) -> Unit,
	) {

		var count = initialDepth
		var foundOpeningBrace = initialDepth > 0

		while (tokenIterator.hasNext()) {

			val token = tokenIterator.next()

			when (token) {
				CrescentToken.Bracket.OPEN -> {
					foundOpeningBrace = true
					count++
				}
				CrescentToken.Bracket.CLOSE -> {

					count--

					if (count <= 0) {
						break
					}
				}
			}

			block(token)
		}

		if (!foundOpeningBrace) tokenIterator.fail("Expected opening brace")
		if (count > 0) tokenIterator.fail("Unterminated block; expected closing brace")
	}

}
