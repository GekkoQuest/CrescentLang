package dev.twelveoclock.lang.crescent.translators

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension

/** A dependency-free, Java-21-safe representation of PoderTechIR modules. */
data class PoderTechIrProgram(val modules: List<PoderTechIrModule>)

data class PoderTechIrModule(
	val name: String,
	val structs: List<PoderTechIrStruct>,
	val traits: List<PoderTechIrTrait>,
	val enums: List<PoderTechIrEnum>,
	val methods: List<PoderTechIrMethod>,
)

data class PoderTechIrStruct(val name: String, val fields: List<String>)
data class PoderTechIrTrait(val name: String, val methods: List<String>)
data class PoderTechIrEnum(val name: String, val entries: List<String>)
data class PoderTechIrMethod(val name: String, val parameters: List<String>, val instructions: List<PoderTechIrInstruction>)

sealed interface PoderTechIrInstruction {
	data class Push(val value: Any) : PoderTechIrInstruction
	data class Load(val name: String) : PoderTechIrInstruction
	data class Store(val name: String, val mutable: Boolean) : PoderTechIrInstruction
	data class Invoke(val name: String, val argumentCount: Int) : PoderTechIrInstruction
	data class Operator(val literal: String) : PoderTechIrInstruction
	data class Member(val name: String) : PoderTechIrInstruction
	data class If(
		val condition: List<PoderTechIrInstruction>,
		val thenInstructions: List<PoderTechIrInstruction>,
		val elseInstructions: List<PoderTechIrInstruction>,
	) : PoderTechIrInstruction
	data class While(val condition: List<PoderTechIrInstruction>, val body: List<PoderTechIrInstruction>) : PoderTechIrInstruction
	data class For(val identifiers: List<String>, val ranges: List<Pair<List<PoderTechIrInstruction>, List<PoderTechIrInstruction>>>, val body: List<PoderTechIrInstruction>) : PoderTechIrInstruction
	data class When(val argument: List<PoderTechIrInstruction>, val clauses: List<Pair<String, List<PoderTechIrInstruction>>>) : PoderTechIrInstruction
	data object Return : PoderTechIrInstruction
}

object PoderTechIrTranslator {

	fun translate(file: Node.File): PoderTechIrProgram = translate(listOf(file))

	fun translate(files: List<Node.File>): PoderTechIrProgram = PoderTechIrProgram(files.map(::translateModule))

	private fun translateModule(file: Node.File): PoderTechIrModule {
		val methods = buildList {
			addAll(file.functions.values.map(::translateMethod))
			addAll(file.objects.values.flatMap { objectNode -> objectNode.functions.values.map { translateMethod(it, "${objectNode.name}.") } })
			addAll(file.impls.values.flatMap { implementation -> implementation.functions.map { translateMethod(it, "${implementation.type}.") } })
			addAll(file.staticImpls.values.flatMap { implementation -> implementation.functions.map { translateMethod(it, "${implementation.type}.") } })
		}
		return PoderTechIrModule(
			name = file.path.nameWithoutExtension.ifEmpty { "main" },
			structs = file.structs.values.map { PoderTechIrStruct(it.name, it.variables.map(Node.Variable::name)) } +
				file.sealeds.values.flatMap { it.structs }.map { PoderTechIrStruct(it.name, it.variables.map(Node.Variable::name)) },
			traits = file.traits.values.map { PoderTechIrTrait(it.name, it.functionTraits.map(Node.FunctionTrait::name)) },
			enums = file.enums.values.map { PoderTechIrEnum(it.name, it.structs.map(Node.EnumEntry::name)) },
			methods = methods,
		)
	}

	private fun translateMethod(function: Node.Function, prefix: String = "") = PoderTechIrMethod(
		name = prefix + function.name,
		parameters = function.params.map(Node.Parameter::name),
		instructions = lower(function.innerCode),
	)

	private fun lower(node: Node): List<PoderTechIrInstruction> = when (node) {
		is Node.Primitive.String -> listOf(PoderTechIrInstruction.Push(node.data))
		is Node.Primitive.Char -> listOf(PoderTechIrInstruction.Push(node.data))
		is Node.Primitive.Boolean -> listOf(PoderTechIrInstruction.Push(node.data))
		is Node.Primitive.Number -> listOf(PoderTechIrInstruction.Push(node.toKotlinNumber()))
		is Node.Identifier -> listOf(PoderTechIrInstruction.Load(node.name))
		is Node.IdentifierCall -> node.arguments.flatMap(::lower) + PoderTechIrInstruction.Invoke(node.identifier, node.arguments.size)
		is Node.GetCall -> listOf(PoderTechIrInstruction.Load(node.identifier)) + node.arguments.flatMap(::lower) + PoderTechIrInstruction.Member("get")
		is Node.DotChain -> node.nodes.flatMapIndexed { index, member ->
			if (index == 0) lower(member) else lower(member) + PoderTechIrInstruction.Member(member.toString())
		}
		is Node.Expression -> node.nodes.flatMap(::lower)
		is Node.Variable -> lower(node.value) + PoderTechIrInstruction.Store(node.name, !node.isFinal)
		is Node.Return -> lower(node.expression) + PoderTechIrInstruction.Return
		is Node.Statement.Block -> node.nodes.flatMap(::lower)
		is Node.Statement.If -> listOf(PoderTechIrInstruction.If(lower(node.predicate), lower(node.block), node.elseBlock?.let(::lower).orEmpty()))
		is Node.Statement.While -> listOf(PoderTechIrInstruction.While(lower(node.predicate), lower(node.block)))
		is Node.Statement.For -> listOf(PoderTechIrInstruction.For(node.identifiers.map(Node.Identifier::name), node.ranges.map { lower(it.start) to lower(it.end) }, lower(node.block)))
		is Node.Statement.When -> listOf(PoderTechIrInstruction.When(lower(node.argument), node.predicateToBlock.map { (predicate, block) -> (predicate?.toString() ?: "else") to lower(block) }))
		is dev.twelveoclock.lang.crescent.language.token.CrescentToken.Operator -> listOf(PoderTechIrInstruction.Operator(node.literal))
		is Node.Array -> node.values.flatMap(::lower) + PoderTechIrInstruction.Invoke("arrayOf", node.values.size)
		else -> emptyList()
	}
}

/** Backwards-compatible entry point for the translator name used by the original project. */
class CrescentToPTIR {
	fun translate(projectDir: Path, vararg crescent: Node.File): List<PoderTechIrModule> {
		require(crescent.all { it.path.startsWith(projectDir) || !it.path.isAbsolute }) {
			"All translated files must belong to the project directory"
		}
		return PoderTechIrTranslator.translate(crescent.toList()).modules
	}
}

object PoderTranslator {
	fun translate(input: Node.File): PoderTechIrProgram = PoderTechIrTranslator.translate(input)
}
