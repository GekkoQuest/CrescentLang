package dev.twelveoclock.lang.crescent.project

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import java.nio.file.Path
import java.util.Collections

class CrescentModuleResolutionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause) {
	@Volatile var sourceSpan: SourceSpan? = null
		private set
	val diagnostic: Diagnostic get() = Diagnostic(DiagnosticSeverity.ERROR, super.message.orEmpty(), sourceSpan)
	constructor(span: SourceSpan, message: String, cause: Throwable? = null) : this(message, cause) { sourceSpan = span }
	override val message: String get() = sourceSpan?.let { diagnostic.render() } ?: super.message.orEmpty()
}

object CrescentModuleResolver {

	const val STANDARD_LIBRARY_PREFIX = "crescent.std"

	fun link(
		projectRoot: Path,
		userFiles: List<Node.File>,
		standardLibraryFiles: List<Node.File> = emptyList(),
	): List<Node.File> {
		val root = projectRoot.toAbsolutePath().normalize()
		val linkedUserFiles = userFiles.map { file ->
			val path = file.path.toAbsolutePath().normalize()
			if (!path.startsWith(root)) {
				fail("Source $path is outside project root $root", file)
			}
			val relative = root.relativize(path)
			val packageId = packageId(relative.parent, path, file)
			if (isStandardLibraryPackage(packageId)) {
				fail("User source $path uses reserved package '$packageId'", file)
			}
			snapshot(SourceLocations.copy(file, file.copy(path = path, packageId = packageId, importedSymbols = emptyMap())))
		}
		val linkedStandardLibrary = standardLibraryFiles.map { file ->
			val path = file.path.normalize()
			if (!isStandardLibraryPackage(file.packageId)) {
				fail("Standard-library source $path must declare a '$STANDARD_LIBRARY_PREFIX' package, got '${file.packageId}'", file)
			}
			validatePackageId(file.packageId, path, file)
			snapshot(SourceLocations.copy(file, file.copy(path = path, importedSymbols = emptyMap())))
		}
		val files = (linkedUserFiles + linkedStandardLibrary).sortedBy { normalizedPathText(it.path) }
		val declarationsByPackage = buildPackageDeclarations(files)
		return immutableList(files.map { file ->
			snapshot(SourceLocations.copy(file, file.copy(importedSymbols = immutable(resolveImports(file, declarationsByPackage)))))
		})
	}

	fun isStandardLibraryPackage(packageId: String): Boolean =
		packageId == STANDARD_LIBRARY_PREFIX || packageId.startsWith("$STANDARD_LIBRARY_PREFIX.")

	private fun buildPackageDeclarations(files: List<Node.File>): Map<String, Map<String, Node.ModuleSymbol>> {
		val declarations = linkedMapOf<String, LinkedHashMap<String, Node.ModuleSymbol>>()
		for (file in files) {
			val packageDeclarations = declarations.getOrPut(file.packageId) { linkedMapOf() }
			for (symbol in declarations(file)) {
				val previous = packageDeclarations.putIfAbsent(symbol.sourceName, symbol)
				if (previous != null) {
					val locations = listOf(previous, symbol).sortedBy { normalizedPathText(it.declarationPath) }
					fail(
						"Duplicate symbol '${symbol.sourceName}' in package '${displayPackage(file.packageId)}': " +
							locations.joinToString { describeDeclaration(it) },
						symbol,
					)
				}
			}
		}
		return declarations.mapValues { (_, symbols) -> immutable(symbols) }
	}

	private fun declarations(file: Node.File): List<Node.ModuleSymbol> = buildList {
		fun addSymbol(
			name: String,
			kind: Node.ModuleSymbolKind,
			visibility: CrescentToken.Visibility,
			node: Node,
			enclosingTypeName: String? = null,
		) {
			add(SourceLocations.copyOrAttach(
				node,
				Node.ModuleSymbol(file.packageId, file.path.normalize(), name, kind, visibility, enclosingTypeName),
				SourceLocations.spanOf(file),
			))
		}
		fun <T : Node> addSymbols(
			kind: Node.ModuleSymbolKind,
			entries: Map<String, T>,
			visibility: (T) -> CrescentToken.Visibility,
		) {
			entries.toSortedMap().forEach { (name, node) ->
				addSymbol(name, kind, visibility(node), node)
			}
		}
		addSymbols(Node.ModuleSymbolKind.FUNCTION, file.functions) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.VARIABLE, file.variables) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.CONSTANT, file.constants) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.STRUCT, file.structs) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.SEALED, file.sealeds) { it.visibility }
		file.sealeds.toSortedMap().values.forEach { sealed ->
			sealed.structs.sortedBy(Node.Struct::name).forEach { nested ->
				addSymbol(
					nested.name,
					Node.ModuleSymbolKind.STRUCT,
					effectiveVisibility(sealed.visibility, nested.visibility),
					nested,
					sealed.name,
				)
			}
			sealed.objects.sortedBy(Node.Object::name).forEach { nested ->
				addSymbol(
					nested.name,
					Node.ModuleSymbolKind.OBJECT,
					effectiveVisibility(sealed.visibility, nested.visibility),
					nested,
					sealed.name,
				)
			}
		}
		addSymbols(Node.ModuleSymbolKind.TRAIT, file.traits) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.OBJECT, file.objects) { it.visibility }
		addSymbols(Node.ModuleSymbolKind.ENUM, file.enums) { it.visibility }
	}

	private fun resolveImports(
		file: Node.File,
		declarationsByPackage: Map<String, Map<String, Node.ModuleSymbol>>,
	): Map<String, Node.ModuleSymbol> {
		val ownDeclarations = declarations(file).associateBy(Node.ModuleSymbol::sourceName)
		val currentPackage = declarationsByPackage.getValue(file.packageId)
		val implicitPackageBindings = linkedMapOf<String, Node.ModuleSymbol>()
		currentPackage.toSortedMap().forEach { (name, symbol) ->
			if (symbol.declarationPath != file.path && symbol.visibility != CrescentToken.Visibility.PRIVATE) {
				implicitPackageBindings[name] = symbol
			}
		}

		val exactBindings = linkedMapOf<String, Node.ModuleSymbol>()
		val wildcardCandidates = linkedMapOf<String, MutableList<Node.ModuleSymbol>>()
		for (importNode in file.imports) {
			val targetPackageId = importNode.path
			val targetPackage = declarationsByPackage[targetPackageId]
				?: fail(
					"Import in ${file.path} refers to missing package '${displayPackage(targetPackageId)}'",
					importNode,
				)
			if (importNode.typeName == "*") {
				if (importNode.typeAlias != null) fail("Wildcard import in ${file.path} cannot have an alias", importNode)
				targetPackage.toSortedMap().forEach { (name, symbol) ->
					if (isAccessible(file, symbol)) wildcardCandidates.getOrPut(name) { mutableListOf() }.add(symbol)
				}
				continue
			}

			val symbol = targetPackage[importNode.typeName]
				?: fail(
					"Import in ${file.path} refers to missing symbol '${importNode.typeName}' " +
						"in package '${displayPackage(targetPackageId)}'",
					importNode,
				)
			if (!isAccessible(file, symbol)) {
				fail(
					"Import in ${file.path} cannot access ${symbol.visibility.name.lowercase()} symbol " +
						"'${symbol.sourceName}' from ${symbol.declarationPath} in package '${displayPackage(targetPackageId)}'",
					importNode,
				)
			}
			val localName = importNode.typeAlias ?: importNode.typeName
			val local = ownDeclarations[localName]
			if (local != null && local != symbol) {
				fail("Import alias '$localName' in ${file.path} conflicts with a declaration in the same file", importNode)
			}
			val implicit = implicitPackageBindings[localName]
			if (implicit != null && implicit != symbol) {
				fail("Import alias '$localName' in ${file.path} conflicts with package symbol at ${implicit.declarationPath}", importNode)
			}
			val previous = exactBindings.putIfAbsent(localName, symbol)
			if (previous != null && previous != symbol) {
				fail(
					"Conflicting exact imports for '$localName' in ${file.path}: " +
						listOf(previous, symbol).sortedBy { normalizedPathText(it.declarationPath) }
							.joinToString { "${displayPackage(it.packageId)}::${it.sourceName} at ${it.declarationPath}" },
					importNode,
				)
			}
		}

		val resolved = linkedMapOf<String, Node.ModuleSymbol>()
		resolved.putAll(implicitPackageBindings)
		resolved.putAll(exactBindings)
		for ((name, candidates) in wildcardCandidates.toSortedMap()) {
			if (name in ownDeclarations || name in resolved) continue
			val distinct = candidates.distinct().sortedWith(
				compareBy<Node.ModuleSymbol>({ it.packageId }, { normalizedPathText(it.declarationPath) }, { it.sourceName }),
			)
			if (distinct.size > 1) {
				fail(
					"Ambiguous wildcard import '$name' in ${file.path}: " +
						distinct.joinToString { "${displayPackage(it.packageId)}::${it.sourceName} at ${it.declarationPath}" },
					file,
				)
			}
			if (distinct.isNotEmpty()) resolved[name] = distinct.single()
		}
		return resolved.toSortedMap()
	}

	private fun isAccessible(importer: Node.File, symbol: Node.ModuleSymbol): Boolean = when {
		symbol.declarationPath == importer.path -> true
		symbol.packageId == importer.packageId -> symbol.visibility != CrescentToken.Visibility.PRIVATE
		else -> symbol.visibility == CrescentToken.Visibility.PUBLIC
	}

	private fun effectiveVisibility(
		outer: CrescentToken.Visibility,
		inner: CrescentToken.Visibility,
	): CrescentToken.Visibility = if (outer.ordinal < inner.ordinal) outer else inner

	private fun validatePackageId(packageId: String, path: Path, file: Node.File) {
		if (packageId.isEmpty()) return
		val invalid = packageId.split('.').firstOrNull { !isIdentifier(it) }
		if (invalid != null) fail("Invalid package segment '$invalid' derived for source $path", file)
	}

	private fun packageId(relativeParent: Path?, source: Path, file: Node.File): String {
		if (relativeParent == null) return ""
		val segments = relativeParent.map { it.toString() }
		val invalid = segments.firstOrNull { !isIdentifier(it) }
		if (invalid != null) fail("Invalid package directory '$invalid' for source $source", file)
		return segments.joinToString(".")
	}

	private fun isIdentifier(value: String): Boolean =
		value.isNotEmpty() && isIdentifierStart(value.first()) && value.drop(1).all(::isIdentifierPart)

	private fun isIdentifierStart(char: Char): Boolean = char == '_' || char.isLetter()

	private fun isIdentifierPart(char: Char): Boolean = char == '_' || char.isLetterOrDigit()

	private fun snapshot(file: Node.File): Node.File {
		val functions = immutable(file.functions.mapValues { snapshotFunction(it.value) })
		return SourceLocations.copy(file, file.copy(
			imports = immutableList(file.imports.map { SourceLocations.copy(it, it.copy()) }),
			structs = immutable(file.structs.mapValues { snapshotStruct(it.value) }),
			sealeds = immutable(file.sealeds.mapValues { snapshotSealed(it.value) }),
			impls = immutable(file.impls.mapValues { snapshotImpl(it.value) }),
			staticImpls = immutable(file.staticImpls.mapValues { snapshotImpl(it.value) }),
			traits = immutable(file.traits.mapValues { snapshotTrait(it.value) }),
			objects = immutable(file.objects.mapValues { snapshotObject(it.value) }),
			enums = immutable(file.enums.mapValues { snapshotEnum(it.value) }),
			variables = immutable(file.variables.mapValues { snapshotBasicVariable(it.value) }),
			constants = immutable(file.constants.mapValues { snapshotConstant(it.value) }),
			functions = functions,
			mainFunction = file.mainFunction?.let { functions[it.name] ?: snapshotFunction(it) },
			importedSymbols = immutable(file.importedSymbols.mapValues { it.value.copy() }),
		))
	}

	private fun snapshotStruct(struct: Node.Struct): Node.Struct = SourceLocations.copy(struct, struct.copy(
		variables = immutableList(struct.variables.map(::snapshotBasicVariable)),
	))

	private fun snapshotSealed(sealed: Node.Sealed): Node.Sealed = SourceLocations.copy(sealed, sealed.copy(
		structs = immutableList(sealed.structs.map(::snapshotStruct)),
		objects = immutableList(sealed.objects.map(::snapshotObject)),
	))

	private fun snapshotTrait(trait: Node.Trait): Node.Trait = SourceLocations.copy(trait, trait.copy(
		functionTraits = immutableList(trait.functionTraits.map(::snapshotFunctionTrait)),
	))

	private fun snapshotObject(objectNode: Node.Object): Node.Object = SourceLocations.copy(objectNode, objectNode.copy(
		variables = immutable(objectNode.variables.mapValues { snapshotBasicVariable(it.value) }),
		constants = immutable(objectNode.constants.mapValues { snapshotConstant(it.value) }),
		functions = immutable(objectNode.functions.mapValues { snapshotFunction(it.value) }),
	))

	private fun snapshotImpl(impl: Node.Impl): Node.Impl = SourceLocations.copy(impl, impl.copy(
		type = snapshotType(impl.type),
		modifiers = immutableList(impl.modifiers),
		extends = immutableList(impl.extends.map(::snapshotType)),
		functions = immutableList(impl.functions.map(::snapshotFunction)),
	))

	private fun snapshotEnum(enumNode: Node.Enum): Node.Enum = SourceLocations.copy(enumNode, enumNode.copy(
		parameters = immutableList(enumNode.parameters.map(::snapshotParameter)),
		structs = immutableList(enumNode.structs.map(::snapshotEnumEntry)),
	))

	private fun snapshotEnumEntry(entry: Node.EnumEntry): Node.EnumEntry = SourceLocations.copy(entry, entry.copy(
		arguments = immutableList(entry.arguments.map(::snapshotNode)),
	))

	private fun snapshotFunctionTrait(functionTrait: Node.FunctionTrait): Node.FunctionTrait = SourceLocations.copy(functionTrait, functionTrait.copy(
		params = immutableList(functionTrait.params.map(::snapshotParameter)),
		returnType = snapshotType(functionTrait.returnType),
	))

	private fun snapshotBasicVariable(variable: Node.Variable.Basic): Node.Variable.Basic = SourceLocations.copy(variable, variable.copy(
		type = snapshotType(variable.type),
		value = snapshotNode(variable.value),
	))

	private fun snapshotConstant(variable: Node.Variable.Constant): Node.Variable.Constant = SourceLocations.copy(variable, variable.copy(
		type = snapshotType(variable.type),
		value = snapshotNode(variable.value),
	))

	private fun snapshotLocal(variable: Node.Variable.Local): Node.Variable.Local = SourceLocations.copy(variable, variable.copy(
		type = snapshotType(variable.type),
		value = snapshotNode(variable.value),
	))

	private fun snapshotFunction(function: Node.Function): Node.Function = SourceLocations.copy(function, function.copy(
		modifiers = immutableList(function.modifiers),
		params = immutableList(function.params.map(::snapshotParameter)),
		returnType = snapshotType(function.returnType),
		innerCode = snapshotBlock(function.innerCode),
	))

	private fun snapshotParameter(parameter: Node.Parameter): Node.Parameter = SourceLocations.copy(parameter, when (parameter) {
		is Node.Parameter.Basic -> parameter.copy(type = snapshotType(parameter.type))
		is Node.Parameter.WithDefault -> parameter.copy(
			type = snapshotType(parameter.type),
			defaultValue = snapshotExpression(parameter.defaultValue),
		)
	})

	private fun snapshotType(type: Node.Type): Node.Type = SourceLocations.copy(type, when (type) {
		Node.Type.Implicit -> type
		is Node.Type.Basic -> Node.Type.Basic(type.name)
		is Node.Type.Array -> Node.Type.Array(snapshotType(type.type))
		is Node.Type.Result -> Node.Type.Result(snapshotType(type.type))
		else -> type
	})

	private fun snapshotExpression(expression: Node.Expression): Node.Expression =
		SourceLocations.copy(expression, Node.Expression(immutableList(expression.nodes.map(::snapshotNode))))

	private fun snapshotBlock(block: Node.Statement.Block): Node.Statement.Block =
		SourceLocations.copy(block, Node.Statement.Block(immutableList(block.nodes.map(::snapshotNode))))

	private fun snapshotNode(node: Node): Node {
		val snapshot = when (node) {
		is Node.GetCall -> node.copy(arguments = immutableList(node.arguments.map(::snapshotNode)))
		is Node.IdentifierCall -> node.copy(arguments = immutableList(node.arguments.map(::snapshotNode)))
		is Node.TypeLiteral -> Node.TypeLiteral(snapshotType(node.type))
		is Node.InfixCall -> node.copy(receiver = snapshotNode(node.receiver), argument = snapshotNode(node.argument))
		is Node.Expression -> snapshotExpression(node)
		is Node.DotChain -> Node.DotChain(immutableList(node.nodes.map(::snapshotNode)))
		is Node.Return -> Node.Return(snapshotNode(node.expression))
		is Node.Import -> node.copy()
		is Node.Struct -> snapshotStruct(node)
		is Node.Sealed -> snapshotSealed(node)
		is Node.Trait -> snapshotTrait(node)
		is Node.Object -> snapshotObject(node)
		is Node.Impl -> snapshotImpl(node)
		is Node.Enum -> snapshotEnum(node)
		is Node.EnumEntry -> snapshotEnumEntry(node)
		is Node.FunctionTrait -> snapshotFunctionTrait(node)
		is Node.Identifier -> node
		is Node.Variable.Basic -> snapshotBasicVariable(node)
		is Node.Variable.Constant -> snapshotConstant(node)
		is Node.Variable.Local -> snapshotLocal(node)
		is Node.Function -> snapshotFunction(node)
		is Node.File -> snapshot(node)
		is Node.Parameter -> snapshotParameter(node)
		is Node.Type -> snapshotType(node)
		is Node.Statement.When -> node.copy(
			argument = snapshotNode(node.argument),
			predicateToBlock = immutableList(node.predicateToBlock.map { clause ->
				clause.copy(
					ifExpressionNode = clause.ifExpressionNode?.let(::snapshotNode),
					thenBlock = snapshotBlock(clause.thenBlock),
				)
			}),
		)
		is Node.Statement.When.Clause -> node.copy(
			ifExpressionNode = node.ifExpressionNode?.let(::snapshotNode),
			thenBlock = snapshotBlock(node.thenBlock),
		)
		is Node.Statement.When.EnumShortHand -> node
		is Node.Statement.When.Else -> Node.Statement.When.Else(snapshotBlock(node.thenBlock))
		is Node.Statement.If -> node.copy(
			predicate = snapshotNode(node.predicate),
			block = snapshotBlock(node.block),
			elseBlock = node.elseBlock?.let(::snapshotBlock),
		)
		is Node.Statement.While -> node.copy(
			predicate = snapshotNode(node.predicate),
			block = snapshotBlock(node.block),
		)
		is Node.Statement.For -> node.copy(
			identifiers = immutableList(node.identifiers.map { Node.Identifier(it.name) }),
			ranges = immutableList(node.ranges.map { range ->
				range.copy(start = snapshotNode(range.start), end = snapshotNode(range.end))
			}),
			block = snapshotBlock(node.block),
		)
		is Node.Statement.Block -> snapshotBlock(node)
		is Node.Statement.Range -> node.copy(start = snapshotNode(node.start), end = snapshotNode(node.end))
		is Node.Array -> Node.Array(kotlin.Array(node.values.size) { index -> snapshotNode(node.values[index]) })
			else -> node
		}
		return if (snapshot === node) node else SourceLocations.copy(node, snapshot)
	}

	private fun normalizedPathText(path: Path): String = path.normalize().toString().replace('\\', '/')

	private fun displayPackage(packageId: String): String = packageId.ifEmpty { "<root>" }

	private fun describeDeclaration(symbol: Node.ModuleSymbol): String = buildString {
		append(symbol.kind)
		symbol.enclosingTypeName?.let { append(" nested in sealed '").append(it).append("'") }
		append(" at ").append(symbol.declarationPath)
	}

	private fun fail(message: String, source: Any? = null): Nothing {
		val span = SourceLocations.spanOf(source)
		throw if (span == null) CrescentModuleResolutionException(message) else CrescentModuleResolutionException(span, message)
	}

	private fun <K, V> immutable(values: Map<K, V>): Map<K, V> =
		Collections.unmodifiableMap(LinkedHashMap(values))

	private fun <T> immutableList(values: Collection<T>): List<T> =
		Collections.unmodifiableList(ArrayList(values))
}
