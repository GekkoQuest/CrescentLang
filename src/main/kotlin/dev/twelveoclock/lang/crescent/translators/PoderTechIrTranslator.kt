package dev.twelveoclock.lang.crescent.translators

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/** A dependency-free structural PoderTechIR representation. */
data class PoderTechIrProgram(val modules: List<PoderTechIrModule>)

data class PoderTechIrSourceIdentity(val packageId: String, val sourcePath: Path?)

data class PoderTechIrModule(
	val name: String,
	val structs: List<PoderTechIrStruct>,
	val traits: List<PoderTechIrTrait>,
	val enums: List<PoderTechIrEnum>,
	val methods: List<PoderTechIrMethod>,
	val packageId: String = "",
	val sourcePath: Path? = null,
	val imports: List<PoderTechIrImport> = emptyList(),
	val importedSymbols: Map<String, PoderTechIrSymbol> = emptyMap(),
	val variables: List<PoderTechIrVariable> = emptyList(),
	val constants: List<PoderTechIrVariable> = emptyList(),
	val objects: List<PoderTechIrObject> = emptyList(),
	val sealedTypes: List<PoderTechIrSealed> = emptyList(),
	val implementations: List<PoderTechIrImplementation> = emptyList(),
)

val PoderTechIrModule.sourceIdentity: PoderTechIrSourceIdentity
	get() = PoderTechIrSourceIdentity(packageId, sourcePath?.toAbsolutePath()?.normalize())

enum class PoderTechIrVisibility { PRIVATE, INTERNAL, PUBLIC }
enum class PoderTechIrModifier { ASYNC, OVERRIDE, OPERATOR, INLINE, STATIC, INFIX }
enum class PoderTechIrMethodKind { TOP_LEVEL, OBJECT, INSTANCE_IMPL, STATIC_IMPL }
enum class PoderTechIrSymbolKind { FUNCTION, VARIABLE, CONSTANT, STRUCT, SEALED, TRAIT, OBJECT, ENUM }

sealed interface PoderTechIrType {
	data object Implicit : PoderTechIrType
	data class Named(val name: String) : PoderTechIrType
	data class Array(val elementType: PoderTechIrType) : PoderTechIrType
	data class Result(val valueType: PoderTechIrType) : PoderTechIrType
}

data class PoderTechIrImport(val packageId: String, val sourceName: String, val alias: String?)
data class PoderTechIrSymbol(
	val packageId: String,
	val declarationPath: Path,
	val sourceName: String,
	val kind: PoderTechIrSymbolKind,
	val visibility: PoderTechIrVisibility,
	val enclosingTypeName: String? = null,
)

data class PoderTechIrParameter(
	val name: String,
	val type: PoderTechIrType,
	val defaultValue: PoderTechIrNode.Expression? = null,
)

data class PoderTechIrVariable(
	val name: String,
	val type: PoderTechIrType,
	val initializer: PoderTechIrNode,
	val mutable: Boolean,
	val constant: Boolean,
	val visibility: PoderTechIrVisibility?,
)

data class PoderTechIrStruct(
	val name: String,
	val fields: List<String>,
	val visibility: PoderTechIrVisibility = PoderTechIrVisibility.PUBLIC,
	val fieldDefinitions: List<PoderTechIrVariable> = emptyList(),
	val enclosingTypeName: String? = null,
)

data class PoderTechIrTrait(
	val name: String,
	val methods: List<String>,
	val visibility: PoderTechIrVisibility = PoderTechIrVisibility.PUBLIC,
	val signatures: List<PoderTechIrSignature> = emptyList(),
)

data class PoderTechIrSignature(val name: String, val parameters: List<PoderTechIrParameter>, val returnType: PoderTechIrType)
data class PoderTechIrEnumEntry(val name: String, val arguments: List<PoderTechIrNode>)

data class PoderTechIrEnum(
	val name: String,
	val entries: List<String>,
	val visibility: PoderTechIrVisibility = PoderTechIrVisibility.PUBLIC,
	val parameters: List<PoderTechIrParameter> = emptyList(),
	val entryDefinitions: List<PoderTechIrEnumEntry> = emptyList(),
)

data class PoderTechIrObject(
	val name: String,
	val visibility: PoderTechIrVisibility,
	val variables: List<PoderTechIrVariable>,
	val constants: List<PoderTechIrVariable>,
	val methods: List<PoderTechIrMethod>,
	val enclosingTypeName: String? = null,
)

data class PoderTechIrSealed(
	val name: String,
	val visibility: PoderTechIrVisibility,
	val structs: List<PoderTechIrStruct>,
	val objects: List<PoderTechIrObject>,
)

data class PoderTechIrImplementation(
	val target: PoderTechIrType,
	val static: Boolean,
	val modifiers: List<PoderTechIrModifier>,
	val extends: List<PoderTechIrType>,
	val methods: List<PoderTechIrMethod>,
)

data class PoderTechIrMethod(
	val name: String,
	val parameters: List<String>,
	val instructions: List<PoderTechIrInstruction>,
	val owner: String? = null,
	val kind: PoderTechIrMethodKind = PoderTechIrMethodKind.TOP_LEVEL,
	val visibility: PoderTechIrVisibility = PoderTechIrVisibility.PUBLIC,
	val modifiers: List<PoderTechIrModifier> = emptyList(),
	val parameterDefinitions: List<PoderTechIrParameter> = emptyList(),
	val returnType: PoderTechIrType = PoderTechIrType.Named("Unit"),
	val body: PoderTechIrNode.Block? = null,
)

sealed interface PoderTechIrNode {
	data class Literal(val kind: String, val value: Any) : PoderTechIrNode
	data class Identifier(val name: String) : PoderTechIrNode
	data class Call(val name: String, val arguments: List<PoderTechIrNode>) : PoderTechIrNode
	data class Index(val receiver: String, val arguments: List<PoderTechIrNode>) : PoderTechIrNode
	data class TypeLiteral(val type: PoderTechIrType) : PoderTechIrNode
	data class InfixCall(val receiver: PoderTechIrNode, val functionName: String, val argument: PoderTechIrNode) : PoderTechIrNode
	data class DotChain(val members: List<PoderTechIrNode>) : PoderTechIrNode
	data class ArrayLiteral(val values: List<PoderTechIrNode>) : PoderTechIrNode
	data class Operator(val literal: String) : PoderTechIrNode
	data class Expression(val nodes: List<PoderTechIrNode>) : PoderTechIrNode
	data class Variable(val definition: PoderTechIrVariable) : PoderTechIrNode
	data class Return(val expression: PoderTechIrNode) : PoderTechIrNode
	data class Block(val nodes: List<PoderTechIrNode>) : PoderTechIrNode
	data class If(val predicate: PoderTechIrNode, val thenBlock: Block, val elseBlock: Block?) : PoderTechIrNode
	data class While(val predicate: PoderTechIrNode, val body: Block) : PoderTechIrNode
	data class Range(val start: PoderTechIrNode, val end: PoderTechIrNode) : PoderTechIrNode
	data class For(val identifiers: List<String>, val ranges: List<Range>, val body: Block) : PoderTechIrNode
	data class When(val argument: PoderTechIrNode, val clauses: List<WhenClause>, val subjectName: String? = null) : PoderTechIrNode
	data class WhenClause(val predicate: PoderTechIrNode?, val body: Block)
	data class EnumShortHand(val name: String) : PoderTechIrNode
	data class Else(val body: Block) : PoderTechIrNode
	data object Break : PoderTechIrNode
	data object Continue : PoderTechIrNode
}

class PoderTechIrTranslationException(message: String) : IllegalArgumentException(message)

/** Legacy stack projection retained for source compatibility. */
sealed interface PoderTechIrInstruction {
	data class Push(val value: Any) : PoderTechIrInstruction
	data class Load(val name: String) : PoderTechIrInstruction
	data class Store(val name: String, val mutable: Boolean) : PoderTechIrInstruction
	data class Invoke(val name: String, val argumentCount: Int) : PoderTechIrInstruction
	data class Operator(val literal: String) : PoderTechIrInstruction
	data class Member(val name: String) : PoderTechIrInstruction
	data class TypeLiteral(val type: PoderTechIrType) : PoderTechIrInstruction
	data class If(val condition: List<PoderTechIrInstruction>, val thenInstructions: List<PoderTechIrInstruction>, val elseInstructions: List<PoderTechIrInstruction>) : PoderTechIrInstruction
	data class While(val condition: List<PoderTechIrInstruction>, val body: List<PoderTechIrInstruction>) : PoderTechIrInstruction
	data class For(val identifiers: List<String>, val ranges: List<Pair<List<PoderTechIrInstruction>, List<PoderTechIrInstruction>>>, val body: List<PoderTechIrInstruction>) : PoderTechIrInstruction
	data class When(val argument: List<PoderTechIrInstruction>, val clauses: List<Pair<String, List<PoderTechIrInstruction>>>) : PoderTechIrInstruction
	data object Return : PoderTechIrInstruction
	data object Break : PoderTechIrInstruction
	data object Continue : PoderTechIrInstruction
}

object PoderTechIrTranslator {
	private data class CallableIdentity(val owner: String?, val kind: PoderTechIrMethodKind, val name: String, val parameterTypes: List<PoderTechIrType>)
	private data class MethodCandidate(val method: PoderTechIrMethod, val identity: CallableIdentity, val context: String)

	fun translate(file: Node.File): PoderTechIrProgram = translate(listOf(file))

	fun translate(files: List<Node.File>): PoderTechIrProgram {
		val duplicateSource = files.groupBy { it.packageId to it.path.toAbsolutePath().normalize() }.entries.firstOrNull { it.value.size > 1 }
		if (duplicateSource != null) throw PoderTechIrTranslationException(
			"Duplicate PoderTechIR source identity '${duplicateSource.key.first}:${duplicateSource.key.second}'",
		)
		return PoderTechIrProgram(files.sortedWith(compareBy<Node.File>({ it.packageId }, { it.path.toString() })).map(::translateModule))
	}

	private fun translateModule(file: Node.File): PoderTechIrModule {
		val candidates = buildList {
			addAll(file.functions.values.map { candidate(it, null, PoderTechIrMethodKind.TOP_LEVEL, "top-level function '${it.name}'") })
			addAll(file.objects.values.flatMap { owner -> owner.functions.values.map { candidate(it, owner.name, PoderTechIrMethodKind.OBJECT, "object '${owner.name}' function '${it.name}'") } })
			addAll(file.impls.values.flatMap { owner -> owner.functions.map { candidate(it, renderType(owner.type), PoderTechIrMethodKind.INSTANCE_IMPL, "impl '${renderType(owner.type)}' function '${it.name}'") } })
			addAll(file.staticImpls.values.flatMap { owner -> owner.functions.map { candidate(it, renderType(owner.type), PoderTechIrMethodKind.STATIC_IMPL, "static impl '${renderType(owner.type)}' function '${it.name}'") } })
		}
		val duplicateMethod = candidates.groupBy(MethodCandidate::identity).entries.firstOrNull { it.value.size > 1 }
		if (duplicateMethod != null) {
			val identity = duplicateMethod.key
			throw PoderTechIrTranslationException("Ambiguous PoderTechIR method identity '${identity.owner?.let { "$it." }.orEmpty()}${identity.name}(${identity.parameterTypes.joinToString(transform = ::renderType)})' [${identity.kind}] in ${file.path}: ${duplicateMethod.value.joinToString { it.context }}")
		}
		val methods = candidates.map(MethodCandidate::method).sortedWith(compareBy(PoderTechIrMethod::name, { it.parameters.joinToString("\u0000") }))
		val structs = file.structs.values.map(::translateStruct).sortedBy(PoderTechIrStruct::name)
		val traits = file.traits.values.map(::translateTrait).sortedBy(PoderTechIrTrait::name)
		val enums = file.enums.values.map(::translateEnum).sortedBy(PoderTechIrEnum::name)
		val objects = file.objects.values.map(::translateObject).sortedBy(PoderTechIrObject::name)
		val sealedTypes = file.sealeds.values.map(::translateSealed).sortedBy(PoderTechIrSealed::name)
		val implementations = buildList {
			addAll(file.impls.values.map { translateImplementation(it, false) })
			addAll(file.staticImpls.values.map { translateImplementation(it, true) })
		}.sortedWith(compareBy({ renderType(it.target) }, PoderTechIrImplementation::static))
		validateTypeIdentities(file, structs, traits, enums, objects, sealedTypes)
		return PoderTechIrModule(
			name = file.path.nameWithoutExtension.ifEmpty { "main" }, structs = structs, traits = traits, enums = enums, methods = methods,
			packageId = file.packageId, sourcePath = file.path.normalize(),
			imports = file.imports.map { PoderTechIrImport(it.path, it.typeName, it.typeAlias) },
			importedSymbols = file.importedSymbols.toSortedMap().mapValues { (_, symbol) -> PoderTechIrSymbol(symbol.packageId, symbol.declarationPath.normalize(), symbol.sourceName, symbolKind(symbol.kind), visibility(symbol.visibility), symbol.enclosingTypeName) },
			variables = file.variables.values.map(::translateVariable), constants = file.constants.values.map(::translateVariable),
			objects = objects, sealedTypes = sealedTypes, implementations = implementations,
		)
	}

	private fun candidate(function: Node.Function, owner: String?, kind: PoderTechIrMethodKind, context: String): MethodCandidate {
		val qualified = owner?.let { "$it.${function.name}" } ?: function.name
		val identity = CallableIdentity(owner, kind, function.name, function.params.map { parameter ->
			translateType(when (parameter) { is Node.Parameter.Basic -> parameter.type; is Node.Parameter.WithDefault -> parameter.type })
		})
		return MethodCandidate(translateMethod(function, qualified, owner, kind), identity, context)
	}

	private fun translateMethod(function: Node.Function, name: String = function.name, owner: String? = null, kind: PoderTechIrMethodKind = PoderTechIrMethodKind.TOP_LEVEL) = PoderTechIrMethod(
		name = name, parameters = function.params.map(Node.Parameter::name), instructions = lower(function.innerCode), owner = owner, kind = kind,
		visibility = visibility(function.visibility), modifiers = function.modifiers.map(::modifier),
		parameterDefinitions = function.params.map(::translateParameter), returnType = translateType(function.returnType),
		body = translateBlock(function.innerCode),
	)

	private fun translateStruct(node: Node.Struct, enclosingTypeName: String? = null) = PoderTechIrStruct(node.name, node.variables.map(Node.Variable::name), visibility(node.visibility), node.variables.map(::translateVariable), enclosingTypeName)
	private fun translateTrait(node: Node.Trait) = PoderTechIrTrait(node.name, node.functionTraits.map(Node.FunctionTrait::name), visibility(node.visibility), node.functionTraits.map { PoderTechIrSignature(it.name, it.params.map(::translateParameter), translateType(it.returnType)) })
	private fun translateEnum(node: Node.Enum) = PoderTechIrEnum(node.name, node.structs.map(Node.EnumEntry::name), visibility(node.visibility), node.parameters.map(::translateParameter), node.structs.map { PoderTechIrEnumEntry(it.name, it.arguments.map(::translateNode)) })
	private fun translateObject(node: Node.Object, enclosingTypeName: String? = null) = PoderTechIrObject(node.name, visibility(node.visibility), node.variables.values.map(::translateVariable), node.constants.values.map(::translateVariable), node.functions.values.map { translateMethod(it, "${node.name}.${it.name}", node.name, PoderTechIrMethodKind.OBJECT) }, enclosingTypeName)
	private fun translateSealed(node: Node.Sealed) = PoderTechIrSealed(node.name, visibility(node.visibility), node.structs.map { translateStruct(it, node.name) }, node.objects.map { translateObject(it, node.name) })
	private fun translateImplementation(node: Node.Impl, static: Boolean) = PoderTechIrImplementation(translateType(node.type), static, node.modifiers.map(::modifier), node.extends.map(::translateType), node.functions.map { translateMethod(it, "${renderType(node.type)}.${it.name}", renderType(node.type), if (static) PoderTechIrMethodKind.STATIC_IMPL else PoderTechIrMethodKind.INSTANCE_IMPL) })

	private fun translateParameter(node: Node.Parameter): PoderTechIrParameter = when (node) {
		is Node.Parameter.Basic -> PoderTechIrParameter(node.name, translateType(node.type))
		is Node.Parameter.WithDefault -> PoderTechIrParameter(node.name, translateType(node.type), translateExpression(node.defaultValue))
	}

	private fun translateVariable(node: Node.Variable) = PoderTechIrVariable(
		name = node.name, type = translateType(node.type), initializer = translateNode(node.value), mutable = !node.isFinal,
		constant = node is Node.Variable.Constant,
		visibility = when (node) {
			is Node.Variable.Basic -> visibility(node.visibility)
			is Node.Variable.Constant -> visibility(node.visibility)
			is Node.Variable.Local -> null
			else -> unsupported("variable", node)
		},
	)

	private fun translateType(type: Node.Type): PoderTechIrType = when (type) {
		Node.Type.Implicit -> PoderTechIrType.Implicit
		is Node.Type.Basic -> PoderTechIrType.Named(type.name)
		is Node.Type.Array -> PoderTechIrType.Array(translateType(type.type))
		is Node.Type.Result -> PoderTechIrType.Result(translateType(type.type))
		else -> unsupported("type", type)
	}

	private fun translateNode(node: Node): PoderTechIrNode = SourceLocations.copy(node, when (node) {
		is Node.Primitive.String -> PoderTechIrNode.Literal("String", node.data)
		is Node.Primitive.Char -> PoderTechIrNode.Literal("Char", node.data)
		is Node.Primitive.Boolean -> PoderTechIrNode.Literal("Boolean", node.data)
		is Node.Primitive.Number -> PoderTechIrNode.Literal(renderType(node.type), node.toKotlinNumber())
		is Node.Identifier -> PoderTechIrNode.Identifier(node.name)
		is Node.IdentifierCall -> PoderTechIrNode.Call(node.identifier, node.arguments.map(::translateNode))
		is Node.GetCall -> PoderTechIrNode.Index(node.identifier, node.arguments.map(::translateNode))
		is Node.TypeLiteral -> PoderTechIrNode.TypeLiteral(translateType(node.type))
		is Node.InfixCall -> PoderTechIrNode.InfixCall(translateNode(node.receiver), node.functionName, translateNode(node.argument))
		is Node.DotChain -> PoderTechIrNode.DotChain(node.nodes.map(::translateNode))
		is Node.Array -> PoderTechIrNode.ArrayLiteral(node.values.map(::translateNode))
		is CrescentToken.Operator -> PoderTechIrNode.Operator(node.literal)
		is Node.Expression -> translateExpression(node)
		is Node.Variable -> PoderTechIrNode.Variable(translateVariable(node))
		is Node.Return -> PoderTechIrNode.Return(translateNode(node.expression))
		is Node.Statement.Block -> translateBlock(node)
		is Node.Statement.If -> PoderTechIrNode.If(translateNode(node.predicate), translateBlock(node.block), node.elseBlock?.let(::translateBlock))
		is Node.Statement.While -> PoderTechIrNode.While(translateNode(node.predicate), translateBlock(node.block))
		is Node.Statement.Range -> translateRange(node)
		is Node.Statement.For -> PoderTechIrNode.For(node.identifiers.map(Node.Identifier::name), node.ranges.map(::translateRange), translateBlock(node.block))
		is Node.Statement.When -> PoderTechIrNode.When(translateNode(node.argument), node.predicateToBlock.map { PoderTechIrNode.WhenClause(it.ifExpressionNode?.let(::translateNode), translateBlock(it.thenBlock)) }, node.subjectName)
		is Node.Statement.When.EnumShortHand -> PoderTechIrNode.EnumShortHand(node.name)
		is Node.Statement.When.Else -> PoderTechIrNode.Else(translateBlock(node.thenBlock))
		CrescentToken.Keyword.BREAK -> PoderTechIrNode.Break
		CrescentToken.Keyword.CONTINUE -> PoderTechIrNode.Continue
		else -> unsupported("AST node", node)
	})

	private fun translateExpression(node: Node.Expression) = SourceLocations.copy(node, PoderTechIrNode.Expression(node.nodes.map(::translateNode)))
	private fun translateBlock(node: Node.Statement.Block) = SourceLocations.copy(node, PoderTechIrNode.Block(node.nodes.map(::translateNode)))
	private fun translateRange(node: Node.Statement.Range) = SourceLocations.copy(node, PoderTechIrNode.Range(translateNode(node.start), translateNode(node.end)))

	private fun lower(node: Node): List<PoderTechIrInstruction> = when (node) {
		is Node.Primitive.String -> listOf(PoderTechIrInstruction.Push(node.data)); is Node.Primitive.Char -> listOf(PoderTechIrInstruction.Push(node.data)); is Node.Primitive.Boolean -> listOf(PoderTechIrInstruction.Push(node.data)); is Node.Primitive.Number -> listOf(PoderTechIrInstruction.Push(node.toKotlinNumber()))
		is Node.Identifier -> listOf(PoderTechIrInstruction.Load(node.name)); is Node.IdentifierCall -> node.arguments.flatMap(::lower) + PoderTechIrInstruction.Invoke(node.identifier, node.arguments.size); is Node.GetCall -> listOf(PoderTechIrInstruction.Load(node.identifier)) + node.arguments.flatMap(::lower) + PoderTechIrInstruction.Invoke("get", node.arguments.size)
		is Node.TypeLiteral -> listOf(PoderTechIrInstruction.TypeLiteral(translateType(node.type))); is Node.InfixCall -> lower(node.receiver) + lower(node.argument) + PoderTechIrInstruction.Invoke(node.functionName, 1)
		is Node.DotChain -> lowerDotChain(node); is Node.Expression -> node.nodes.flatMap(::lower); is Node.Variable -> lower(node.value) + PoderTechIrInstruction.Store(node.name, !node.isFinal); is Node.Return -> lower(node.expression) + PoderTechIrInstruction.Return; is Node.Statement.Block -> node.nodes.flatMap(::lower)
		is Node.Statement.If -> listOf(PoderTechIrInstruction.If(lower(node.predicate), lower(node.block), node.elseBlock?.let(::lower).orEmpty())); is Node.Statement.While -> listOf(PoderTechIrInstruction.While(lower(node.predicate), lower(node.block))); is Node.Statement.For -> listOf(PoderTechIrInstruction.For(node.identifiers.map(Node.Identifier::name), node.ranges.map { lower(it.start) to lower(it.end) }, lower(node.block)))
		is Node.Statement.When -> listOf(PoderTechIrInstruction.When(lower(node.argument), node.predicateToBlock.map { (it.ifExpressionNode?.let(::renderNode) ?: "else") to lower(it.thenBlock) })); is CrescentToken.Operator -> listOf(PoderTechIrInstruction.Operator(node.literal)); is Node.Array -> node.values.flatMap(::lower) + PoderTechIrInstruction.Invoke("arrayOf", node.values.size)
		is Node.Statement.When.EnumShortHand -> listOf(PoderTechIrInstruction.Load(node.name)); is Node.Statement.When.Else -> lower(node.thenBlock)
		CrescentToken.Keyword.BREAK -> listOf(PoderTechIrInstruction.Break); CrescentToken.Keyword.CONTINUE -> listOf(PoderTechIrInstruction.Continue)
		else -> unsupported("AST node", node)
	}

	private fun lowerDotChain(chain: Node.DotChain): List<PoderTechIrInstruction> = buildList {
		if (chain.nodes.isEmpty()) throw PoderTechIrTranslationException("A dot chain must contain a receiver")
		addAll(lower(chain.nodes.first()))
		for (member in chain.nodes.drop(1)) when (member) {
			is Node.Identifier -> add(PoderTechIrInstruction.Member(member.name))
			is Node.IdentifierCall -> { add(PoderTechIrInstruction.Member(member.identifier)); addAll(member.arguments.flatMap(::lower)); add(PoderTechIrInstruction.Invoke(member.identifier, member.arguments.size)) }
			is Node.GetCall -> { add(PoderTechIrInstruction.Member(member.identifier)); addAll(member.arguments.flatMap(::lower)); add(PoderTechIrInstruction.Invoke("get", member.arguments.size)) }
			else -> unsupported("dot-chain member", member)
		}
	}

	private fun validateTypeIdentities(file: Node.File, structs: List<PoderTechIrStruct>, traits: List<PoderTechIrTrait>, enums: List<PoderTechIrEnum>, objects: List<PoderTechIrObject>, sealedTypes: List<PoderTechIrSealed>) {
		val identities = structs.map { it.name to "struct" } + traits.map { it.name to "trait" } + enums.map { it.name to "enum" } + objects.map { it.name to "object" } + sealedTypes.map { it.name to "sealed" }
		val duplicate = identities.groupBy { it.first }.entries.firstOrNull { it.value.size > 1 }
		if (duplicate != null) throw PoderTechIrTranslationException("Ambiguous PoderTechIR type identity '${duplicate.key}' in package '${file.packageId}' from ${file.path}: ${duplicate.value.joinToString { it.second }}")
	}

	private fun renderType(type: Node.Type): String = when (val translated = translateType(type)) { PoderTechIrType.Implicit -> "Implicit"; is PoderTechIrType.Named -> translated.name; is PoderTechIrType.Array -> "[${renderType(translated.elementType)}]"; is PoderTechIrType.Result -> "${renderType(translated.valueType)}?" }
	private fun renderType(type: PoderTechIrType): String = when (type) { PoderTechIrType.Implicit -> "Implicit"; is PoderTechIrType.Named -> type.name; is PoderTechIrType.Array -> "[${renderType(type.elementType)}]"; is PoderTechIrType.Result -> "${renderType(type.valueType)}?" }
	private fun visibility(value: CrescentToken.Visibility): PoderTechIrVisibility = when (value) {
		CrescentToken.Visibility.PRIVATE -> PoderTechIrVisibility.PRIVATE
		CrescentToken.Visibility.INTERNAL -> PoderTechIrVisibility.INTERNAL
		CrescentToken.Visibility.PUBLIC -> PoderTechIrVisibility.PUBLIC
	}
	private fun modifier(value: CrescentToken.Modifier): PoderTechIrModifier = when (value) {
		CrescentToken.Modifier.ASYNC -> PoderTechIrModifier.ASYNC
		CrescentToken.Modifier.OVERRIDE -> PoderTechIrModifier.OVERRIDE
		CrescentToken.Modifier.OPERATOR -> PoderTechIrModifier.OPERATOR
		CrescentToken.Modifier.INLINE -> PoderTechIrModifier.INLINE
		CrescentToken.Modifier.STATIC -> PoderTechIrModifier.STATIC
		CrescentToken.Modifier.INFIX -> PoderTechIrModifier.INFIX
	}
	private fun symbolKind(kind: Node.ModuleSymbolKind): PoderTechIrSymbolKind = when (kind) {
		Node.ModuleSymbolKind.FUNCTION -> PoderTechIrSymbolKind.FUNCTION
		Node.ModuleSymbolKind.VARIABLE -> PoderTechIrSymbolKind.VARIABLE
		Node.ModuleSymbolKind.CONSTANT -> PoderTechIrSymbolKind.CONSTANT
		Node.ModuleSymbolKind.STRUCT -> PoderTechIrSymbolKind.STRUCT
		Node.ModuleSymbolKind.SEALED -> PoderTechIrSymbolKind.SEALED
		Node.ModuleSymbolKind.TRAIT -> PoderTechIrSymbolKind.TRAIT
		Node.ModuleSymbolKind.OBJECT -> PoderTechIrSymbolKind.OBJECT
		Node.ModuleSymbolKind.ENUM -> PoderTechIrSymbolKind.ENUM
	}
	private fun unsupported(kind: String, node: Any): Nothing = throw PoderTechIrTranslationException("Unsupported PoderTechIR $kind: ${node::class.qualifiedName}")
	private fun renderNode(node: Node): String = when (node) { is Node.Primitive.String -> quoteString(node.data); is Node.Primitive.Char -> quoteChar(node.data); is Node.Primitive.Boolean -> node.data.toString(); is Node.Primitive.Number -> node.toKotlinNumber().toString(); is Node.Identifier -> node.name; is Node.IdentifierCall -> "${node.identifier}(${node.arguments.joinToString(", ", transform = ::renderNode)})"; is Node.GetCall -> "${node.identifier}[${node.arguments.joinToString(", ", transform = ::renderNode)}]"; is Node.TypeLiteral -> renderType(node.type); is Node.InfixCall -> "${renderNode(node.receiver)} ${node.functionName} ${renderNode(node.argument)}"; is Node.DotChain -> node.nodes.joinToString(".", transform = ::renderNode); is Node.Expression -> node.nodes.joinToString(" ", transform = ::renderNode); is Node.Statement.When.EnumShortHand -> ".${node.name}"; is CrescentToken.Operator -> node.literal; is Node.Array -> node.values.joinToString(prefix = "[", postfix = "]", transform = ::renderNode); else -> unsupported("when predicate", node) }
	private fun quoteString(value: String): String = buildString { append('\"'); value.forEach { append(escapeCharacter(it, '\"')) }; append('\"') }
	private fun quoteChar(value: Char): String = "'${escapeCharacter(value, '\'')}'"
	private fun escapeCharacter(value: Char, quote: Char): String = when (value) { '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '\u0000' -> "\\0"; quote -> "\\$quote"; else -> value.toString() }
}

/** Backwards-compatible entry point for the translator name used by the original project. */
class CrescentToPTIR {
	fun translate(projectDir: Path, vararg crescent: Node.File): List<PoderTechIrModule> {
		val root = projectDir.toAbsolutePath().normalize()
		require(crescent.all { (if (it.path.isAbsolute) it.path else root.resolve(it.path)).normalize().startsWith(root) }) { "All translated files must belong to the project directory" }
		return PoderTechIrTranslator.translate(crescent.toList()).modules
	}
}

object PoderTranslator { fun translate(input: Node.File): PoderTechIrProgram = PoderTechIrTranslator.translate(input) }
