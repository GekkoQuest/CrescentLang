package dev.twelveoclock.lang.crescent.ptir

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import dev.twelveoclock.lang.crescent.diagnostics.SourceSpan
import dev.twelveoclock.lang.crescent.translators.*
import dev.twelveoclock.lang.crescent.vm.RuntimeIO
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import kotlin.math.pow

class PoderTechIrExecutionException(
	val detail: String,
	val span: SourceSpan? = null,
	cause: Throwable? = null,
) : IllegalStateException(Diagnostic(DiagnosticSeverity.ERROR, detail, span).render(), cause) {
	val diagnostic = Diagnostic(DiagnosticSeverity.ERROR, detail, span)
	internal fun at(candidate: SourceSpan?): PoderTechIrExecutionException =
		if (span != null || candidate == null) this else PoderTechIrExecutionException(detail, candidate, cause)
}

data object PoderTechIrUnit {
	override fun toString() = "Basic(Unit)"
}

class PoderTechIrStructValue internal constructor(
	val typeName: String,
	val sourceIdentity: PoderTechIrSourceIdentity,
	private val storage: LinkedHashMap<String, Cell>,
) {
	val fields: Map<String, Any?> get() = storage.mapValues { it.value.value }
	internal fun cell(name: String): Cell? = storage[name]
	override fun toString(): String = "$typeName(${fields.entries.joinToString { "${it.key}=${display(it.value)}" }})"
}

data class PoderTechIrEnumValue(val typeName: String, val entryName: String, val arguments: List<Any?>, val sourceIdentity: PoderTechIrSourceIdentity) {
	override fun toString(): String = if (arguments.isEmpty()) entryName else "$entryName(${arguments.joinToString(transform = ::display)})"
}

data class PoderTechIrResult(val value: Any?, val failure: Boolean) {
	override fun toString(): String = if (failure) "Failure(${display(value)})" else "Success(${display(value)})"
}

internal data class Cell(var value: Any?, val mutable: Boolean)

/**
 * Independent interpreter for the authoritative PoderTechIR node graph.
 *
 * Construction deep-copies the complete program. Execution therefore never observes later mutation
 * of caller-owned lists or maps and never delegates to either Crescent VM.
 */
class PoderTechIrExecutor @JvmOverloads constructor(
	program: PoderTechIrProgram,
	private val io: RuntimeIO = RuntimeIO.system(),
) {
	private val program = program.snapshot()
	private val modulesByIdentity = this.program.modules.associateBy(::identity)
	private val states = IdentityHashMap<PoderTechIrModule, ModuleState>().apply {
		this@PoderTechIrExecutor.program.modules.forEach { module -> put(module, ModuleState(module)) }
	}

	fun invoke(
		module: PoderTechIrModule,
		functionName: String = "main",
		arguments: List<Any?> = emptyList(),
	): Any? {
		val frozen = modulesByIdentity[identity(module)]
			?: fail("PoderTechIR program does not contain source '${identity(module).render()}'")
		return invokeTopLevel(frozen, functionName, arguments)
	}

	fun invoke(functionName: String = "main", arguments: List<Any?> = emptyList()): Any? {
		val matches = program.modules.filter { module ->
			module.methods.any { it.kind == PoderTechIrMethodKind.TOP_LEVEL && it.name == functionName }
		}
		if (matches.isEmpty()) fail("PoderTechIR program does not define top-level function '$functionName'")
		if (matches.size != 1) fail("Ambiguous PoderTechIR top-level function '$functionName' in ${matches.joinToString { identity(it).render() }}")
		return invokeTopLevel(matches.single(), functionName, arguments)
	}

	fun invokeMain(programArguments: List<String> = emptyList()): Any? {
		val matches = program.modules.flatMap { module ->
			module.methods.filter { it.kind == PoderTechIrMethodKind.TOP_LEVEL && it.name == "main" }.map { module to it }
		}
		if (matches.isEmpty()) fail("PoderTechIR program does not define a top-level main")
		if (matches.size != 1) fail("Ambiguous PoderTechIR main in ${matches.joinToString { identity(it.first).render() }}")
		val (module, method) = matches.single()
		val arguments = when {
			method.parameterDefinitions.isEmpty() -> emptyList()
			method.parameterDefinitions.size == 1 && method.parameterDefinitions.single().type ==
				PoderTechIrType.Array(PoderTechIrType.Named("String")) -> listOf(programArguments.toMutableList())
			else -> fail("PoderTechIR main must accept zero parameters or one [String] parameter")
		}
		return invokeMethod(module, method, arguments, null)
	}

	private fun invokeTopLevel(module: PoderTechIrModule, name: String, arguments: List<Any?>): Any? {
		val candidates = module.methods.filter { it.kind == PoderTechIrMethodKind.TOP_LEVEL && it.name == name }
		val method = selectMethod(candidates, arguments, "top-level function '$name' in ${identity(module).render()}")
		return invokeMethod(module, method, arguments, null)
	}

	private fun invokeMethod(module: PoderTechIrModule, method: PoderTechIrMethod, arguments: List<Any?>, receiver: Any?): Any? {
		val required = method.parameterDefinitions.count { it.defaultValue == null }
		if (arguments.size !in required..method.parameterDefinitions.size) {
			fail("${method.name} expects $required..${method.parameterDefinitions.size} arguments, got ${arguments.size}")
		}
		val frame = Frame(module, null)
		if (receiver != null) frame.define("this", receiver, false)
		method.parameterDefinitions.forEachIndexed { index, parameter ->
			val value = arguments.getOrNull(index) ?: parameter.defaultValue?.let { eval(it, frame) }
				?: fail("Missing argument '${parameter.name}' for ${method.name}")
			frame.define(parameter.name, value, false)
		}
		val action = {
			try {
				method.body?.let { executeBlock(it, frame, child = false) }
					?: fail("PoderTechIR method '${method.name}' does not contain an executable body")
			} catch (signal: ReturnSignal) {
				signal.value
			} catch (signal: BreakSignal) {
				throw PoderTechIrExecutionException("break cannot escape PoderTechIR method '${method.name}'", signal.span)
			} catch (signal: ContinueSignal) {
				throw PoderTechIrExecutionException("continue cannot escape PoderTechIR method '${method.name}'", signal.span)
			}
		}
		return if (PoderTechIrModifier.ASYNC in method.modifiers) {
			try { CompletableFuture.completedFuture(action()) }
			catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
		} else action()
	}

	private fun executeBlock(block: PoderTechIrNode.Block, parent: Frame, child: Boolean = true): Any? {
		val frame = if (child) Frame(parent.module, parent) else parent
		var result: Any? = PoderTechIrUnit
		for (node in block.nodes) result = execute(node, frame)
		return result
	}

	private fun execute(node: PoderTechIrNode, frame: Frame): Any? = try {
		executeUnwrapped(node, frame)
	} catch (failure: PoderTechIrExecutionException) {
		throw failure.at(SourceLocations.spanOf(node))
	}

	private fun executeUnwrapped(node: PoderTechIrNode, frame: Frame): Any? = when (node) {
		is PoderTechIrNode.Variable -> {
			val value = eval(node.definition.initializer, frame)
			frame.define(node.definition.name, value, node.definition.mutable)
			PoderTechIrUnit
		}
		is PoderTechIrNode.Return -> throw ReturnSignal(eval(node.expression, frame))
		is PoderTechIrNode.Block -> executeBlock(node, frame)
		is PoderTechIrNode.If -> if (boolean(eval(node.predicate, frame), "if predicate")) executeBlock(node.thenBlock, frame)
			else node.elseBlock?.let { executeBlock(it, frame) } ?: PoderTechIrUnit
		is PoderTechIrNode.While -> {
			while (boolean(eval(node.predicate, frame), "while predicate")) {
				try { executeBlock(node.body, frame) } catch (_: ContinueSignal) { continue } catch (_: BreakSignal) { break }
			}
			PoderTechIrUnit
		}
		is PoderTechIrNode.For -> executeFor(node, frame)
		is PoderTechIrNode.When -> executeWhen(node, frame)
		is PoderTechIrNode.Else -> executeBlock(node.body, frame)
		PoderTechIrNode.Break -> throw BreakSignal(SourceLocations.spanOf(node))
		PoderTechIrNode.Continue -> throw ContinueSignal(SourceLocations.spanOf(node))
		else -> eval(node, frame)
	}

	private fun executeFor(node: PoderTechIrNode.For, frame: Frame): Any? {
		if (node.identifiers.size != node.ranges.size) fail("PoderTechIR for loop requires one range per identifier")
		fun visit(index: Int, loopFrame: Frame) {
			if (index == node.identifiers.size) {
				try { executeBlock(node.body, loopFrame) } catch (_: ContinueSignal) { return }
				return
			}
			val range = node.ranges[index]
			val start = integral(eval(range.start, loopFrame), "range start")
			val end = integral(eval(range.end, loopFrame), "range end")
			for (value in start..end) {
				val iteration = Frame(frame.module, loopFrame)
				iteration.define(node.identifiers[index], narrowIntegral(value), false)
				visit(index + 1, iteration)
			}
		}
		try { visit(0, Frame(frame.module, frame)) } catch (_: BreakSignal) { }
		return PoderTechIrUnit
	}

	private fun executeWhen(node: PoderTechIrNode.When, frame: Frame): Any? {
		val argument = eval(node.argument, frame)
		val whenFrame = Frame(frame.module, frame)
		node.subjectName?.let { whenFrame.define(it, argument, false) }
		for (clause in node.clauses) {
			val matches = clause.predicate == null || when (val predicate = clause.predicate) {
				is PoderTechIrNode.EnumShortHand -> argument is PoderTechIrEnumValue && argument.entryName == predicate.name
				else -> equal(argument, eval(predicate, whenFrame))
			}
			if (matches) return executeBlock(clause.body, whenFrame)
		}
		return PoderTechIrUnit
	}

	private fun eval(node: PoderTechIrNode, frame: Frame): Any? = try {
		when (node) {
			is PoderTechIrNode.Literal -> node.value
			is PoderTechIrNode.Identifier -> resolveIdentifier(node.name, frame)
			is PoderTechIrNode.Call -> invokeCall(frame.module, node.name, node.arguments.map { eval(it, frame) }, frame)
			is PoderTechIrNode.Index -> index(resolveIdentifier(node.receiver, frame), node.arguments.map { eval(it, frame) })
			is PoderTechIrNode.TypeLiteral -> TypeValue(node.type, frame.module.sourceIdentity)
			is PoderTechIrNode.InfixCall -> invokeMember(eval(node.receiver, frame), node.functionName, listOf(eval(node.argument, frame)), frame)
			is PoderTechIrNode.DotChain -> evalDotChain(node, frame)
			is PoderTechIrNode.ArrayLiteral -> node.values.mapTo(mutableListOf()) { eval(it, frame) }
			is PoderTechIrNode.Expression -> evalExpression(node, frame)
			is PoderTechIrNode.Range -> integral(eval(node.start, frame), "range start")..integral(eval(node.end, frame), "range end")
			is PoderTechIrNode.EnumShortHand -> EnumShortHandValue(node.name)
			is PoderTechIrNode.Operator -> fail("Standalone operator '${node.literal}' is not executable")
			is PoderTechIrNode.Variable, is PoderTechIrNode.Return, is PoderTechIrNode.Block, is PoderTechIrNode.If,
			is PoderTechIrNode.While, is PoderTechIrNode.For, is PoderTechIrNode.When, is PoderTechIrNode.Else,
			PoderTechIrNode.Break, PoderTechIrNode.Continue -> execute(node, frame)
		}
	} catch (failure: PoderTechIrExecutionException) { throw failure.at(SourceLocations.spanOf(node)) }
	catch (failure: Throwable) { fail("PoderTechIR execution failed: ${failure.message ?: failure::class.simpleName}", failure) }

	private fun evalDotChain(chain: PoderTechIrNode.DotChain, frame: Frame): Any? {
		if (chain.members.isEmpty()) fail("A PoderTechIR dot chain must contain a receiver")
		var receiver = eval(chain.members.first(), frame)
		for (member in chain.members.drop(1)) receiver = when (member) {
			is PoderTechIrNode.Identifier -> member(receiver, member.name, frame)
			is PoderTechIrNode.Call -> invokeMember(receiver, member.name, member.arguments.map { eval(it, frame) }, frame)
			is PoderTechIrNode.Index -> index(member(receiver, member.receiver, frame), member.arguments.map { eval(it, frame) })
			else -> fail("Unsupported PoderTechIR dot-chain member ${member::class.simpleName}")
		}
		return receiver
	}

	private fun evalExpression(expression: PoderTechIrNode.Expression, frame: Frame): Any? {
		if (expression.nodes.isEmpty()) return PoderTechIrUnit
		val parser = ExpressionParser(expression.nodes)
		val tree = parser.parse()
		return evalTree(tree, frame)
	}

	private fun evalTree(tree: Expr, frame: Frame): Any? = when (tree) {
		is Expr.Value -> eval(tree.node, frame)
		is Expr.Unary -> when (tree.operator) {
			"!" -> evalTree(tree.operand, frame).let { value -> if (value is Boolean) !value else invokeMember(value, "not", emptyList(), frame) }
			"-" -> evalTree(tree.operand, frame).let { value -> if (isNumeric(value)) negate(value) else invokeMember(value, "unaryMinus", emptyList(), frame) }
			else -> fail("Unsupported unary PoderTechIR operator '${tree.operator}'")
		}
		is Expr.Postfix -> when (tree.operator) {
			"?" -> (evalTree(tree.operand, frame) as? PoderTechIrResult)?.let { if (it.failure) fail("Unwrapped failed Result: ${display(it.value)}") else it.value }
				?: fail("Operator ? expects Result")
			else -> fail("Unsupported postfix PoderTechIR operator '${tree.operator}'")
		}
		is Expr.Binary -> when (tree.operator) {
			"&&" -> boolean(evalTree(tree.left, frame), "left operand of &&") && boolean(evalTree(tree.right, frame), "right operand of &&")
			"||" -> boolean(evalTree(tree.left, frame), "left operand of ||") || boolean(evalTree(tree.right, frame), "right operand of ||")
			"=", "+=", "-=", "*=", "/=", "%=", "^=" -> assign(tree, frame)
			"as" -> cast(evalTree(tree.left, frame), typeValue(evalTree(tree.right, frame), "right operand of as"))
			"is" -> isType(evalTree(tree.left, frame), typeValue(evalTree(tree.right, frame), "right operand of is"))
			"!is" -> !isType(evalTree(tree.left, frame), typeValue(evalTree(tree.right, frame), "right operand of !is"))
			else -> binary(tree.operator, evalTree(tree.left, frame), evalTree(tree.right, frame), frame)
		}
	}

	private fun assign(tree: Expr.Binary, frame: Frame): Any? {
		val target = reference(tree.left, frame)
		val right = evalTree(tree.right, frame)
		val value = if (tree.operator == "=") right else binary(tree.operator.dropLast(1), target.get(), right, frame)
		target.set(value)
		return PoderTechIrUnit
	}

	private fun reference(expr: Expr, frame: Frame): Reference = when (expr) {
		is Expr.Value -> when (val node = expr.node) {
			is PoderTechIrNode.Identifier -> frame.cell(node.name)?.let(::CellReference)
				?: states.getValue(frame.module).globalCell(node.name, frame)?.let(::CellReference)
				?: fail("Unknown assignment target '${node.name}'")
			is PoderTechIrNode.Index -> IndexReference(resolveIdentifier(node.receiver, frame), node.arguments.map { eval(it, frame) })
			is PoderTechIrNode.DotChain -> dotReference(node, frame)
			else -> fail("PoderTechIR assignment requires an identifier, member, or index target")
		}
		else -> fail("PoderTechIR assignment requires a simple target")
	}

	private fun dotReference(chain: PoderTechIrNode.DotChain, frame: Frame): Reference {
		if (chain.members.size < 2) fail("PoderTechIR member assignment requires a receiver")
		var receiver = eval(chain.members.first(), frame)
		for (member in chain.members.drop(1).dropLast(1)) receiver = when (member) {
			is PoderTechIrNode.Identifier -> member(receiver, member.name, frame)
			is PoderTechIrNode.Call -> invokeMember(receiver, member.name, member.arguments.map { eval(it, frame) }, frame)
			is PoderTechIrNode.Index -> index(member(receiver, member.receiver, frame), member.arguments.map { eval(it, frame) })
			else -> fail("Unsupported assignment chain member")
		}
		return when (val last = chain.members.last()) {
			is PoderTechIrNode.Identifier -> CellReference(memberCell(receiver, last.name, frame))
			is PoderTechIrNode.Index -> IndexReference(member(receiver, last.receiver, frame), last.arguments.map { eval(it, frame) })
			else -> fail("PoderTechIR assignment chain must end in a member or index")
		}
	}

	private fun resolveIdentifier(name: String, frame: Frame): Any? {
		frame.cell(name)?.let { return it.value }
		frame.cell("this")?.value?.let { receiver -> receiverMemberOrNull(receiver, name, frame)?.let { return it } }
		states.getValue(frame.module).globalCell(name, frame)?.let { return it.value }
		frame.module.objects.firstOrNull { it.name == name }?.let { return states.getValue(frame.module).objectValue(it, frame) }
		frame.module.sealedTypes.firstNotNullOfOrNull { sealed -> sealed.objects.firstOrNull { it.name == name } }
			?.let { return states.getValue(frame.module).objectValue(it, frame) }
		(frame.module.structs.map { it.name } + frame.module.enums.map { it.name } + frame.module.sealedTypes.map { it.name } + frame.module.traits.map { it.name })
			.firstOrNull { it == name }?.let { return TypeValue(PoderTechIrType.Named(it), frame.module.sourceIdentity) }
		frame.module.enums.firstNotNullOfOrNull { enumeration ->
			enumeration.entryDefinitions.firstOrNull { it.name == name }?.let { entry -> PoderTechIrEnumValue(enumeration.name, entry.name, entry.arguments.map { eval(it, frame) }, frame.module.sourceIdentity) }
		}?.let { return it }
		frame.module.importedSymbols[name]?.let { symbol ->
			val target = moduleFor(symbol.packageId, symbol.declarationPath)
			return when (symbol.kind) {
				PoderTechIrSymbolKind.VARIABLE, PoderTechIrSymbolKind.CONSTANT -> states.getValue(target).globalCell(symbol.sourceName, Frame(target, null))?.value
					?: fail("Imported global '$name' is missing")
				PoderTechIrSymbolKind.OBJECT -> target.objects.firstOrNull { it.name == symbol.sourceName }?.let { states.getValue(target).objectValue(it, Frame(target, null)) }
					?: fail("Imported object '$name' is missing")
				PoderTechIrSymbolKind.ENUM, PoderTechIrSymbolKind.STRUCT, PoderTechIrSymbolKind.SEALED, PoderTechIrSymbolKind.TRAIT -> TypeValue(PoderTechIrType.Named(symbol.sourceName), target.sourceIdentity)
				PoderTechIrSymbolKind.FUNCTION -> fail("Imported function '$name' must be called")
			}
		}
		builtinIdentifier(name)?.let { return it }
		fail("Unknown PoderTechIR identifier '$name' in ${identity(frame.module).render()}")
	}

	private fun invokeCall(module: PoderTechIrModule, name: String, arguments: List<Any?>, frame: Frame): Any? {
		builtin(name, arguments, frame)?.let { return it }
		(module.structs + module.sealedTypes.flatMap { it.structs }).firstOrNull { it.name == name }?.let { return constructStruct(module, it, arguments) }
		module.enums.firstOrNull { enumeration -> enumeration.entryDefinitions.any { it.name == name } }?.let { enumeration ->
			val entry = enumeration.entryDefinitions.single { it.name == name }
			return PoderTechIrEnumValue(enumeration.name, entry.name, arguments, module.sourceIdentity)
		}
		val local = module.methods.filter { it.kind == PoderTechIrMethodKind.TOP_LEVEL && it.name == name }
		if (local.isNotEmpty()) return invokeMethod(module, selectMethod(local, arguments, "function '$name'"), arguments, null)
		module.importedSymbols[name]?.let { symbol ->
			val target = moduleFor(symbol.packageId, symbol.declarationPath)
			return when (symbol.kind) {
				PoderTechIrSymbolKind.FUNCTION -> invokeTopLevel(target, symbol.sourceName, arguments)
				PoderTechIrSymbolKind.STRUCT -> target.structs.firstOrNull { it.name == symbol.sourceName }?.let { constructStruct(target, it, arguments) }
					?: fail("Imported struct '$name' is missing")
				PoderTechIrSymbolKind.ENUM -> target.enums.firstNotNullOfOrNull { enumeration -> enumeration.entryDefinitions.firstOrNull { it.name == symbol.sourceName }?.let { PoderTechIrEnumValue(enumeration.name, it.name, arguments, target.sourceIdentity) } }
					?: fail("Imported enum entry '$name' is missing")
				else -> fail("Imported symbol '$name' is not callable")
			}
		}
		fail("Unknown PoderTechIR function '$name' in ${identity(module).render()}")
	}

	private fun selectMethod(methods: List<PoderTechIrMethod>, arguments: List<Any?>, label: String): PoderTechIrMethod {
		val byArity = methods.filter { method ->
			arguments.size in method.parameterDefinitions.count { it.defaultValue == null }..method.parameterDefinitions.size
		}
		val compatible = byArity.filter { method -> arguments.indices.all { isType(arguments[it], method.parameterDefinitions[it].type) } }
		val candidates = compatible.ifEmpty { byArity }
		if (candidates.isEmpty()) fail("No matching PoderTechIR $label for ${arguments.size} arguments")
		if (candidates.size != 1) fail("Ambiguous PoderTechIR $label for ${arguments.size} arguments")
		return candidates.single()
	}

	private fun member(receiver: Any?, name: String, frame: Frame): Any? = when (receiver) {
		is PoderTechIrStructValue -> receiver.cell(name)?.value ?: fail("${receiver.typeName} has no member '$name'")
		is PoderTechIrEnumValue -> receiverMemberOrNull(receiver, name, frame) ?: fail("${receiver.typeName} has no member '$name'")
		is ObjectValue -> receiver.cell(name, frame).value
		is List<*> -> when (name) { "size", "length" -> receiver.size; else -> fail("Array has no member '$name'") }
		is String -> when (name) { "size", "length" -> receiver.length; else -> fail("String has no member '$name'") }
		is TypeValue -> staticValue(receiver, name, frame)
		else -> fail("${runtimeType(receiver)} has no member '$name'")
	}

	private fun receiverMemberOrNull(receiver: Any?, name: String, frame: Frame): Any? = when (receiver) {
		is PoderTechIrStructValue -> receiver.cell(name)?.value
		is ObjectValue -> if ((receiver.definition.variables + receiver.definition.constants).any { it.name == name }) receiver.cell(name, frame).value else null
		is PoderTechIrEnumValue -> program.modules.firstOrNull { it.sourceIdentity == receiver.sourceIdentity }?.enums?.firstOrNull { it.name == receiver.typeName }?.parameters?.indexOfFirst { it.name == name }?.takeIf { it >= 0 }?.let { receiver.arguments[it] }
		else -> null
	}

	private fun memberCell(receiver: Any?, name: String, frame: Frame): Cell = when (receiver) {
		is PoderTechIrStructValue -> receiver.cell(name) ?: fail("${receiver.typeName} has no field '$name'")
		is ObjectValue -> receiver.cell(name, frame)
		else -> fail("${runtimeType(receiver)} member '$name' is not assignable")
	}

	private fun invokeMember(receiver: Any?, name: String, arguments: List<Any?>, frame: Frame): Any? {
		when (receiver) {
			is List<*> -> when (name) { "get" -> return index(receiver, arguments); "set" -> { IndexReference(receiver, arguments.take(1)).set(arguments.getOrNull(1)); return PoderTechIrUnit } }
			is String -> if (name == "get") return index(receiver, arguments)
			is Future<*> -> if (name == "await") return receiver.get()
			is TypeValue -> {
				val typeName = renderType(receiver.type)
				val typeModule = moduleFor(receiver, frame)
				typeModule.enums.firstOrNull { it.name == typeName }?.entryDefinitions?.firstOrNull { it.name == name }?.let {
					return constructEnum(typeModule, typeModule.enums.single { it.name == typeName }, it, arguments)
				}
				typeModule.sealedTypes.firstOrNull { it.name == typeName }?.structs?.firstOrNull { it.name == name }?.let {
					return constructStruct(typeModule, it, arguments)
				}
				val candidates = typeModule.methods.filter { it.kind == PoderTechIrMethodKind.STATIC_IMPL && it.owner == typeName && it.name.substringAfterLast('.') == name }
				if (candidates.isNotEmpty()) return invokeMethod(typeModule, selectMethod(candidates, arguments, "static method '$typeName.$name'"), arguments, receiver)
			}
		}
		val typeName = runtimeType(receiver)
		val implementationModule = when (receiver) {
			is PoderTechIrStructValue -> program.modules.singleOrNull { it.sourceIdentity == receiver.sourceIdentity } ?: frame.module
			is PoderTechIrEnumValue -> program.modules.singleOrNull { it.sourceIdentity == receiver.sourceIdentity } ?: frame.module
			else -> frame.module
		}
		val candidates = implementationModule.methods.filter { it.kind == PoderTechIrMethodKind.INSTANCE_IMPL && it.owner == typeName && it.name.substringAfterLast('.') == name }
		if (candidates.isNotEmpty()) return invokeMethod(implementationModule, selectMethod(candidates, arguments, "method '$typeName.$name'"), arguments, receiver)
		if (receiver is ObjectValue) {
			val methods = receiver.definition.methods.filter { it.name.substringAfterLast('.') == name }
			if (methods.isNotEmpty()) return invokeMethod(receiver.module, selectMethod(methods, arguments, "object method '${receiver.definition.name}.$name'"), arguments, receiver)
		}
		fail("${runtimeType(receiver)} has no callable member '$name'")
	}

	private fun binary(operator: String, left: Any?, right: Any?, frame: Frame): Any? {
		val userOperator = operatorMethods[operator]
		if (!isBuiltinOperand(left) && userOperator != null && (operator !in setOf("==", "!=") || hasUserMember(left, userOperator, frame))) {
			val result = invokeMember(left, userOperator, listOf(right), frame)
			return when (operator) {
				"!=" -> !boolean(result, "operator equals result")
				"<", "<=", ">", ">=" -> (result as? Number)?.toInt()?.let { comparison -> when (operator) { "<" -> comparison < 0; "<=" -> comparison <= 0; ">" -> comparison > 0; else -> comparison >= 0 } } ?: fail("operator compareTo must return I32")
				else -> result
			}
		}
		if (operator == "==") return equal(left, right)
		if (operator == "!=") return !equal(left, right)
		if (operator == "===") return left === right
		if (operator == "!==") return left !== right
		if (operator == "in" || operator == "!in") {
			val contains = when (right) { is List<*> -> left in right; is String -> left is String && left in right; is LongRange -> left is Number && left.toLong() in right; else -> invokeMember(right, "contains", listOf(left), frame) as? Boolean ?: false }
			return if (operator == "in") contains else !contains
		}
		if (operator == "..") return integral(left, "range start")..integral(right, "range end")
		if (operator in setOf("<", "<=", ">", ">=")) {
			val comparison = compare(left, right)
			return when (operator) { "<" -> comparison < 0; "<=" -> comparison <= 0; ">" -> comparison > 0; else -> comparison >= 0 }
		}
		if (operator == "+" && (left is String || right is String)) return display(left) + display(right)
		return numeric(operator, left, right)
	}

	private fun builtin(name: String, args: List<Any?>, frame: Frame): Any? = when (name) {
		"print" -> PoderTechIrUnit.also { arity(name, args, 1); io.print(display(args.single())) }
		"println" -> PoderTechIrUnit.also { if (args.size !in 0..1) fail("println expects 0..1 arguments"); io.println(args.singleOrNull()?.let(::display).orEmpty()) }
		"readLine" -> { arity(name, args, 1); io.println(display(args.single())); io.readLine() ?: fail("readLine reached end of input") }
		"readBoolean" -> { arity(name, args, 1); io.println(display(args.single())); (io.readLine() ?: fail("readBoolean reached end of input")).toBooleanStrict() }
		"readInt" -> { arity(name, args, 1); io.println(display(args.single())); (io.readLine() ?: fail("readInt reached end of input")).toInt() }
		"readDouble" -> { arity(name, args, 1); io.println(display(args.single())); (io.readLine() ?: fail("readDouble reached end of input")).toDouble() }
		"success" -> { arity(name, args, 1); PoderTechIrResult(args.single(), false) }
		"failure" -> { arity(name, args, 1); PoderTechIrResult(args.single(), true) }
		"arrayOf" -> args.toMutableList()
		"await" -> { arity(name, args, 1); (args.single() as? Future<*>)?.get() ?: fail("await expects an async result") }
		"typeOf" -> { arity(name, args, 1); TypeValue(PoderTechIrType.Named(runtimeType(args.single()))) }
		"sqrt" -> { arity(name, args, 1); kotlin.math.sqrt(double(args.single())) }
		"sin" -> { arity(name, args, 1); kotlin.math.sin(double(args.single())) }
		"round" -> { arity(name, args, 1); kotlin.math.round(double(args.single())) }
		else -> null
	}

	private fun builtinIdentifier(name: String): Any? = when (name) {
		"true" -> true; "false" -> false; "Unit" -> PoderTechIrUnit
		else -> null
	}

	private fun constructStruct(module: PoderTechIrModule, struct: PoderTechIrStruct, args: List<Any?>): PoderTechIrStructValue {
		if (args.size != struct.fieldDefinitions.size) fail("${struct.name} expects ${struct.fieldDefinitions.size} arguments, got ${args.size}")
		val fields = linkedMapOf<String, Cell>()
		struct.fieldDefinitions.forEachIndexed { index, field -> fields[field.name] = Cell(args[index], field.mutable) }
		return PoderTechIrStructValue(struct.name, module.sourceIdentity, fields)
	}

	private fun constructEnum(module: PoderTechIrModule, enumeration: PoderTechIrEnum, entry: PoderTechIrEnumEntry, args: List<Any?>): PoderTechIrEnumValue {
		val declarationFrame = Frame(module, null)
		val supplied = if (args.isNotEmpty()) args else entry.arguments.map { eval(it, declarationFrame) }
		if (supplied.size > enumeration.parameters.size) fail("${enumeration.name}.${entry.name} expects at most ${enumeration.parameters.size} arguments, got ${supplied.size}")
		val values = supplied.toMutableList()
		enumeration.parameters.take(values.size).forEachIndexed { index, parameter -> declarationFrame.define(parameter.name, values[index], false) }
		enumeration.parameters.drop(values.size).forEach { parameter ->
			val value = parameter.defaultValue?.let { eval(it, declarationFrame) }
				?: fail("${enumeration.name}.${entry.name} is missing argument '${parameter.name}'")
			declarationFrame.define(parameter.name, value, false)
			values += value
		}
		return PoderTechIrEnumValue(enumeration.name, entry.name, values, module.sourceIdentity)
	}

	private fun index(receiver: Any?, arguments: List<Any?>): Any? {
		arity("get", arguments, 1)
		val index = integral(arguments.single(), "index").toInt()
		return when (receiver) {
			is List<*> -> receiver.getOrNull(index) ?: fail("Array index $index is out of bounds")
			is String -> receiver.getOrNull(index) ?: fail("String index $index is out of bounds")
			else -> fail("Indexed access expects an Array or String, found ${runtimeType(receiver)}")
		}
	}

	private fun staticValue(type: TypeValue, name: String, frame: Frame): Any? {
		val rendered = renderType(type.type)
		if (name == "class") return rendered
		val module = moduleFor(type, frame)
		module.enums.firstOrNull { it.name == rendered }?.let { enumeration ->
			enumeration.entryDefinitions.firstOrNull { it.name == name }?.let { return constructEnum(module, enumeration, it, emptyList()) }
		}
		module.sealedTypes.firstOrNull { it.name == rendered }?.objects?.firstOrNull { it.name == name }?.let { return states.getValue(module).objectValue(it, frame) }
		module.objects.firstOrNull { it.name == rendered }?.let { return states.getValue(module).objectValue(it, frame) }
		fail("Type $rendered has no static member '$name'")
	}

	private fun moduleFor(type: TypeValue, frame: Frame): PoderTechIrModule = type.sourceIdentity?.let { source ->
		program.modules.singleOrNull { it.sourceIdentity == source }
	} ?: frame.module

	private fun hasUserMember(receiver: Any?, name: String, frame: Frame): Boolean {
		val typeName = runtimeType(receiver)
		val module = when (receiver) {
			is PoderTechIrStructValue -> program.modules.singleOrNull { it.sourceIdentity == receiver.sourceIdentity }
			is PoderTechIrEnumValue -> program.modules.singleOrNull { it.sourceIdentity == receiver.sourceIdentity }
			else -> null
		} ?: frame.module
		return module.methods.any { it.kind == PoderTechIrMethodKind.INSTANCE_IMPL && it.owner == typeName && it.name.substringAfterLast('.') == name } ||
			receiver is ObjectValue && receiver.definition.methods.any { it.name.substringAfterLast('.') == name }
	}

	private fun cast(value: Any?, type: PoderTechIrType): Any? {
		if (isType(value, type)) return value
		return when ((type as? PoderTechIrType.Named)?.name) {
			"String" -> display(value)
			"I8" -> integral(value, "cast").toByte(); "I16" -> integral(value, "cast").toShort(); "I32" -> integral(value, "cast").toInt(); "I64" -> integral(value, "cast")
			"U8" -> integral(value, "cast").toUByte(); "U16" -> integral(value, "cast").toUShort(); "U32" -> integral(value, "cast").toUInt(); "U64" -> integral(value, "cast").toULong()
			"F32" -> double(value).toFloat(); "F64" -> double(value)
			else -> fail("Cannot cast ${runtimeType(value)} to ${renderType(type)}")
		}
	}

	private fun isType(value: Any?, type: PoderTechIrType): Boolean = when (type) {
		PoderTechIrType.Implicit -> true
		is PoderTechIrType.Array -> value is List<*> && value.all { isType(it, type.elementType) }
		is PoderTechIrType.Result -> value is PoderTechIrResult && isType(value.value, type.valueType)
		is PoderTechIrType.Named -> when (type.name) {
			"Any" -> true; "Unit" -> value === PoderTechIrUnit; "String" -> value is String; "Char" -> value is Char; "Boolean" -> value is Boolean; "Future" -> value is Future<*>
			"I8" -> value is Byte; "I16" -> value is Short; "I32" -> value is Int; "I64" -> value is Long
			"U8" -> value is UByte; "U16" -> value is UShort; "U32" -> value is UInt; "U64" -> value is ULong
			"F32" -> value is Float; "F64" -> value is Double
			else -> runtimeType(value) == type.name || when (value) {
				is PoderTechIrStructValue -> program.modules.singleOrNull { it.sourceIdentity == value.sourceIdentity }?.implementations?.any { implementation ->
					renderType(implementation.target) == value.typeName && implementation.extends.any { renderType(it) == type.name }
				} == true
				is PoderTechIrEnumValue -> program.modules.singleOrNull { it.sourceIdentity == value.sourceIdentity }?.implementations?.any { implementation ->
					renderType(implementation.target) == value.typeName && implementation.extends.any { renderType(it) == type.name }
				} == true
				else -> false
			}
		}
	}

	private inner class ModuleState(private val module: PoderTechIrModule) {
		private val globals = linkedMapOf<String, Cell>()
		private val initializing = mutableSetOf<String>()
		private val objects = linkedMapOf<String, ObjectValue>()

		fun globalCell(name: String, frame: Frame): Cell? {
			globals[name]?.let { return it }
			val definition = (module.variables + module.constants).firstOrNull { it.name == name } ?: return null
			if (!initializing.add(name)) fail("Cyclic PoderTechIR initializer for '$name'")
			try { globals[name] = Cell(eval(definition.initializer, Frame(module, null)), definition.mutable) }
			finally { initializing.remove(name) }
			return globals.getValue(name)
		}

		fun objectValue(definition: PoderTechIrObject, frame: Frame): ObjectValue = objects.getOrPut(definition.name) {
			ObjectValue(module, definition)
		}
	}

	private inner class ObjectValue(val module: PoderTechIrModule, val definition: PoderTechIrObject) {
		private val cells = linkedMapOf<String, Cell>()
		private val initializing = mutableSetOf<String>()
		fun cell(name: String, frame: Frame): Cell {
			cells[name]?.let { return it }
			val field = (definition.variables + definition.constants).firstOrNull { it.name == name }
				?: fail("Object ${definition.name} has no member '$name'")
			if (!initializing.add(name)) fail("Cyclic PoderTechIR object initializer '${definition.name}.$name'")
			try { cells[name] = Cell(eval(field.initializer, Frame(module, frame).also { it.define("this", this, false) }), field.mutable) }
			finally { initializing.remove(name) }
			return cells.getValue(name)
		}
		override fun toString() = definition.name
	}

	private class Frame(val module: PoderTechIrModule, private val parent: Frame?) {
		private val cells = linkedMapOf<String, Cell>()
		fun define(name: String, value: Any?, mutable: Boolean) {
			if (name in cells) fail("Duplicate local '$name'")
			cells[name] = Cell(value, mutable)
		}
		fun cell(name: String): Cell? = cells[name] ?: parent?.cell(name)
	}

	private interface Reference { fun get(): Any?; fun set(value: Any?) }
	private class CellReference(private val cell: Cell) : Reference {
		override fun get() = cell.value
		override fun set(value: Any?) { if (!cell.mutable) fail("Cannot assign to immutable value"); cell.value = value }
	}
	private class IndexReference(private val receiver: Any?, private val arguments: List<Any?>) : Reference {
		override fun get(): Any? { arity("get", arguments, 1); val i = integral(arguments.single(), "index").toInt(); return when (receiver) { is List<*> -> receiver.getOrNull(i) ?: fail("Array index $i is out of bounds"); else -> fail("Indexed target is not an Array") } }
		@Suppress("UNCHECKED_CAST")
		override fun set(value: Any?) { arity("set", arguments, 1); val list = receiver as? MutableList<Any?> ?: fail("Indexed target is not a mutable Array"); val i = integral(arguments.single(), "index").toInt(); if (i !in list.indices) fail("Array index $i is out of bounds"); list[i] = value }
	}

	private sealed interface Expr {
		data class Value(val node: PoderTechIrNode) : Expr
		data class Unary(val operator: String, val operand: Expr) : Expr
		data class Postfix(val operator: String, val operand: Expr) : Expr
		data class Binary(val operator: String, val left: Expr, val right: Expr) : Expr
	}

	private class ExpressionParser(private val nodes: List<PoderTechIrNode>) {
		fun parse(): Expr {
			if (nodes.isEmpty()) fail("PoderTechIR expression is missing an operand")
			val stack = ArrayDeque<Expr>()
			for (node in nodes) when (node) {
				is PoderTechIrNode.Operator -> when {
					node.literal == "?" -> stack.addLast(Expr.Postfix(node.literal, stack.removeLastOrNull()
						?: fail("Operator '${node.literal}' is missing an operand")))
					node.literal == "!" || node.literal == "-" && stack.size == 1 -> stack.addLast(Expr.Unary(node.literal, stack.removeLast()))
					else -> {
						val right = stack.removeLastOrNull() ?: fail("Operator '${node.literal}' is missing a right operand")
						val left = stack.removeLastOrNull() ?: fail("Operator '${node.literal}' is missing a left operand")
						stack.addLast(Expr.Binary(node.literal, left, right))
					}
				}
				else -> stack.addLast(Expr.Value(node))
			}
			if (stack.size != 1) fail("Malformed postfix PoderTechIR expression: expected one result, found ${stack.size}")
			return stack.single()
		}
	}

	internal data class TypeValue(val type: PoderTechIrType, val sourceIdentity: PoderTechIrSourceIdentity? = null) {
		override fun toString(): String = when (type) {
			is PoderTechIrType.Named -> "Basic(${type.name})"
			else -> renderType(type)
		}
	}
	private data class EnumShortHandValue(val name: String)
	private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
	private class BreakSignal(val span: SourceSpan?) : RuntimeException(null, null, false, false)
	private class ContinueSignal(val span: SourceSpan?) : RuntimeException(null, null, false, false)

	private fun moduleFor(packageId: String, path: Path): PoderTechIrModule = program.modules.singleOrNull {
		it.packageId == packageId && it.sourcePath?.toAbsolutePath()?.normalize() == path.toAbsolutePath().normalize()
	} ?: fail("Imported PoderTechIR source '$packageId:${path.normalize()}' is missing or ambiguous")

	private data class ModuleIdentity(val packageId: String, val sourcePath: Path?) { fun render() = "$packageId:${sourcePath ?: "<synthetic>"}" }
	private fun identity(module: PoderTechIrModule) = ModuleIdentity(module.packageId, module.sourcePath?.toAbsolutePath()?.normalize())

	private companion object {
		val operatorMethods = mapOf("+" to "plus", "-" to "minus", "*" to "times", "/" to "div", "%" to "rem", "^" to "pow", "==" to "equals", "!=" to "equals", "<" to "compareTo", "<=" to "compareTo", ">" to "compareTo", ">=" to "compareTo", "and" to "bitAnd", "or" to "bitOr", "xor" to "bitXor", "shl" to "bitShiftLeft", "shr" to "bitShiftRight", "ushr" to "unsignedBitShiftRight")
		fun isBuiltinOperand(value: Any?) = value == null || value is String || value is Char || value is Boolean || isNumeric(value) || value is List<*> || value is LongRange
	}
}

private fun PoderTechIrProgram.snapshot(): PoderTechIrProgram {
	val payloads = LiteralPayloadSnapshotter()
	return PoderTechIrProgram(modules.map { it.snapshot(payloads) })
}
private fun PoderTechIrModule.snapshot(payloads: LiteralPayloadSnapshotter) = copy(
	structs = structs.map { it.copy(fields = it.fields.toList(), fieldDefinitions = it.fieldDefinitions.map { variable -> variable.snapshot(payloads) }) },
	traits = traits.map { it.copy(methods = it.methods.toList(), signatures = it.signatures.map { signature -> signature.copy(parameters = signature.parameters.map { parameter -> parameter.snapshot(payloads) }) }) },
	enums = enums.map { it.copy(entries = it.entries.toList(), parameters = it.parameters.map { parameter -> parameter.snapshot(payloads) }, entryDefinitions = it.entryDefinitions.map { entry -> entry.copy(arguments = entry.arguments.map { node -> node.snapshot(payloads) }) }) },
	methods = methods.map { it.snapshot(payloads) }, imports = imports.toList(), importedSymbols = importedSymbols.toMap(),
	variables = variables.map { it.snapshot(payloads) }, constants = constants.map { it.snapshot(payloads) },
	objects = objects.map { it.copy(variables = it.variables.map { variable -> variable.snapshot(payloads) }, constants = it.constants.map { variable -> variable.snapshot(payloads) }, methods = it.methods.map { method -> method.snapshot(payloads) }) },
	sealedTypes = sealedTypes.map { sealed -> sealed.copy(structs = sealed.structs.map { it.snapshot(payloads) }, objects = sealed.objects.map { it.snapshot(payloads) }) },
	implementations = implementations.map { implementation -> implementation.copy(modifiers = implementation.modifiers.toList(), extends = implementation.extends.toList(), methods = implementation.methods.map { method -> method.snapshot(payloads) }) },
	sourcePath = sourcePath?.normalize(),
)
private fun PoderTechIrStruct.snapshot(payloads: LiteralPayloadSnapshotter) = copy(fields = fields.toList(), fieldDefinitions = fieldDefinitions.map { it.snapshot(payloads) })
private fun PoderTechIrObject.snapshot(payloads: LiteralPayloadSnapshotter) = copy(variables = variables.map { it.snapshot(payloads) }, constants = constants.map { it.snapshot(payloads) }, methods = methods.map { it.snapshot(payloads) })
private fun PoderTechIrMethod.snapshot(payloads: LiteralPayloadSnapshotter) = copy(parameters = parameters.toList(), instructions = instructions.map { it.snapshot(payloads) }, modifiers = modifiers.toList(), parameterDefinitions = parameterDefinitions.map { it.snapshot(payloads) }, body = body?.snapshot(payloads) as? PoderTechIrNode.Block)
private fun PoderTechIrParameter.snapshot(payloads: LiteralPayloadSnapshotter) = copy(defaultValue = defaultValue?.snapshot(payloads) as? PoderTechIrNode.Expression)
private fun PoderTechIrVariable.snapshot(payloads: LiteralPayloadSnapshotter) = copy(initializer = initializer.snapshot(payloads))
private fun PoderTechIrNode.snapshot(payloads: LiteralPayloadSnapshotter): PoderTechIrNode = SourceLocations.copy(this, when (this) {
	is PoderTechIrNode.Literal -> copy(value = payloads.snapshot(value) ?: fail("PoderTechIR literal payload cannot be null")); is PoderTechIrNode.Identifier -> copy(); is PoderTechIrNode.Call -> copy(arguments = arguments.map { it.snapshot(payloads) }); is PoderTechIrNode.Index -> copy(arguments = arguments.map { it.snapshot(payloads) }); is PoderTechIrNode.TypeLiteral -> copy(); is PoderTechIrNode.InfixCall -> copy(receiver = receiver.snapshot(payloads), argument = argument.snapshot(payloads)); is PoderTechIrNode.DotChain -> copy(members = members.map { it.snapshot(payloads) }); is PoderTechIrNode.ArrayLiteral -> copy(values = values.map { it.snapshot(payloads) }); is PoderTechIrNode.Operator -> copy(); is PoderTechIrNode.Expression -> copy(nodes = nodes.map { it.snapshot(payloads) }); is PoderTechIrNode.Variable -> copy(definition = definition.snapshot(payloads)); is PoderTechIrNode.Return -> copy(expression = expression.snapshot(payloads)); is PoderTechIrNode.Block -> copy(nodes = nodes.map { it.snapshot(payloads) }); is PoderTechIrNode.If -> copy(predicate = predicate.snapshot(payloads), thenBlock = thenBlock.snapshot(payloads) as PoderTechIrNode.Block, elseBlock = elseBlock?.snapshot(payloads) as? PoderTechIrNode.Block); is PoderTechIrNode.While -> copy(predicate = predicate.snapshot(payloads), body = body.snapshot(payloads) as PoderTechIrNode.Block); is PoderTechIrNode.Range -> copy(start = start.snapshot(payloads), end = end.snapshot(payloads)); is PoderTechIrNode.For -> copy(identifiers = identifiers.toList(), ranges = ranges.map { it.snapshot(payloads) as PoderTechIrNode.Range }, body = body.snapshot(payloads) as PoderTechIrNode.Block); is PoderTechIrNode.When -> copy(argument = argument.snapshot(payloads), clauses = clauses.map { it.copy(predicate = it.predicate?.snapshot(payloads), body = it.body.snapshot(payloads) as PoderTechIrNode.Block) }); is PoderTechIrNode.EnumShortHand -> copy(); is PoderTechIrNode.Else -> copy(body = body.snapshot(payloads) as PoderTechIrNode.Block); PoderTechIrNode.Break -> this; PoderTechIrNode.Continue -> this
})
private fun PoderTechIrInstruction.snapshot(payloads: LiteralPayloadSnapshotter): PoderTechIrInstruction = when (this) {
	is PoderTechIrInstruction.Push -> copy(value = payloads.snapshot(value) ?: fail("PoderTechIR push payload cannot be null"))
	is PoderTechIrInstruction.Load -> copy(); is PoderTechIrInstruction.Store -> copy(); is PoderTechIrInstruction.Invoke -> copy(); is PoderTechIrInstruction.Operator -> copy(); is PoderTechIrInstruction.Member -> copy(); is PoderTechIrInstruction.TypeLiteral -> copy()
	is PoderTechIrInstruction.If -> copy(condition = condition.map { it.snapshot(payloads) }, thenInstructions = thenInstructions.map { it.snapshot(payloads) }, elseInstructions = elseInstructions.map { it.snapshot(payloads) })
	is PoderTechIrInstruction.While -> copy(condition = condition.map { it.snapshot(payloads) }, body = body.map { it.snapshot(payloads) })
	is PoderTechIrInstruction.For -> copy(identifiers = identifiers.toList(), ranges = ranges.map { (start, end) -> start.map { it.snapshot(payloads) } to end.map { it.snapshot(payloads) } }, body = body.map { it.snapshot(payloads) })
	is PoderTechIrInstruction.When -> copy(argument = argument.map { it.snapshot(payloads) }, clauses = clauses.map { (predicate, body) -> predicate to body.map { it.snapshot(payloads) } })
	PoderTechIrInstruction.Return, PoderTechIrInstruction.Break, PoderTechIrInstruction.Continue -> this
}

private class LiteralPayloadSnapshotter {
	private val copies = IdentityHashMap<Any, Any>()

	fun snapshot(value: Any?, location: String = "literal"): Any? {
		if (value == null || immutable(value)) return value
		copies[value]?.let { return it }
		return when (value) {
			is PoderTechIrStructValue -> {
				val fields = linkedMapOf<String, Cell>()
				val frozen = PoderTechIrStructValue(value.typeName, value.sourceIdentity, fields)
				copies[value] = frozen
				value.fields.forEach { (name, field) -> fields[name] = Cell(snapshot(field, "$location.$name"), false) }
				frozen
			}
			is List<*> -> {
				val backing = ArrayList<Any?>(value.size)
				val frozen = Collections.unmodifiableList(backing)
				copies[value] = frozen
				value.forEachIndexed { index, element -> backing += snapshot(element, "$location[$index]") }
				frozen
			}
			is Set<*> -> {
				val backing = LinkedHashSet<Any?>(value.size)
				val frozen = Collections.unmodifiableSet(backing)
				copies[value] = frozen
				value.forEachIndexed { index, element ->
					if (element != null && !immutable(element)) fail("Unsupported PoderTechIR literal set element at $location[$index]: ${element::class.qualifiedName}; set elements must be immutable scalars")
					backing += element
				}
				frozen
			}
			is Map<*, *> -> {
				val backing = LinkedHashMap<Any?, Any?>(value.size)
				val frozen = Collections.unmodifiableMap(backing)
				copies[value] = frozen
				value.entries.forEachIndexed { index, entry ->
					val key = entry.key
					if (key != null && !immutable(key)) fail("Unsupported PoderTechIR literal map key at $location[$index]: ${key::class.qualifiedName}; map keys must be immutable scalars")
					backing[key] = snapshot(entry.value, "$location[${display(key)}]")
				}
				frozen
			}
			else -> if (value.javaClass.isArray) snapshotArray(value, location)
			else fail("Unsupported PoderTechIR literal payload at $location: ${value::class.qualifiedName}; expected a scalar, list, set, map, or array")
		}
	}

	private fun snapshotArray(value: Any, location: String): Any {
		val length = java.lang.reflect.Array.getLength(value)
		val copy = java.lang.reflect.Array.newInstance(value.javaClass.componentType, length)
		copies[value] = copy
		for (index in 0 until length) java.lang.reflect.Array.set(copy, index, snapshot(java.lang.reflect.Array.get(value, index), "$location[$index]"))
		return copy
	}

	private fun immutable(value: Any) = value is String || value is Char || value is Boolean || value is Byte || value is Short ||
		value is Int || value is Long || value is Float || value is Double || value is UByte || value is UShort || value is UInt || value is ULong ||
		value === PoderTechIrUnit || value is PoderTechIrType || value is PoderTechIrSourceIdentity
}

private fun numeric(operator: String, left: Any?, right: Any?): Any {
	if (!isNumeric(left) || !isNumeric(right)) fail("Operator $operator expects numeric operands, found ${runtimeType(left)} and ${runtimeType(right)}")
	val unsigned = isUnsigned(left) || isUnsigned(right)
	if (isUnsigned(left) != isUnsigned(right)) fail("Operator $operator cannot mix signed and unsigned numbers")
	if (left is Double || right is Double || left is Float || right is Float) {
		val a = double(left); val b = double(right); val value = when (operator) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b; "^" -> a.pow(b); else -> fail("Operator $operator does not support floating-point operands") }
		return if (left is Float && right is Float) value.toFloat() else value
	}
	if (unsigned) {
		val a = unsignedLong(left); val b = unsignedLong(right); return when (operator) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b; "and" -> a and b; "or" -> a or b; "xor" -> a xor b; "shl" -> a shl b.toInt(); "shr", "ushr" -> a shr b.toInt(); "^" -> a.toDouble().pow(b.toDouble()).toULong(); else -> fail("Unsupported numeric operator '$operator'") }.let { if (left is ULong || right is ULong) it else it.toUInt() }
	}
	val a = integral(left, "left operand"); val b = integral(right, "right operand")
	val value = when (operator) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b; "and" -> a and b; "or" -> a or b; "xor" -> a xor b; "shl" -> a shl b.toInt(); "shr" -> a shr b.toInt(); "ushr" -> a ushr b.toInt(); "^" -> a.toDouble().pow(b.toDouble()).toLong(); else -> fail("Unsupported numeric operator '$operator'") }
	return if (left is Long || right is Long) value else value.toInt()
}
private fun negate(value: Any?): Any = when (value) { is Byte -> (-value).toByte(); is Short -> (-value).toShort(); is Int -> -value; is Long -> -value; is Float -> -value; is Double -> -value; else -> fail("Unary - expects a signed number") }
private fun compare(left: Any?, right: Any?): Int = when {
	isNumeric(left) && isNumeric(right) -> when {
		isUnsigned(left) != isUnsigned(right) -> fail("Cannot compare signed and unsigned numbers")
		left is Float || left is Double || right is Float || right is Double -> double(left).compareTo(double(right))
		isUnsigned(left) -> unsignedLong(left).compareTo(unsignedLong(right))
		else -> integral(left, "left comparison operand").compareTo(integral(right, "right comparison operand"))
	}
	left is String && right is String -> left.compareTo(right)
	left is Char && right is Char -> left.compareTo(right)
	else -> fail("Values ${runtimeType(left)} and ${runtimeType(right)} are not comparable")
}
private fun equal(left: Any?, right: Any?): Boolean = structurallyEqual(left, right, mutableSetOf())
private fun structurallyEqual(left: Any?, right: Any?, seen: MutableSet<IdentityPair>): Boolean {
	if (left === right) return true
	if (isNumeric(left) && isNumeric(right)) return runCatching { compare(left, right) == 0 }.getOrDefault(false)
	if (left == null || right == null) return false
	val pair = IdentityPair(left, right)
	if (!seen.add(pair)) return true
	return when {
		left is PoderTechIrStructValue && right is PoderTechIrStructValue ->
			left.typeName == right.typeName && left.sourceIdentity == right.sourceIdentity &&
				left.fields.keys == right.fields.keys && left.fields.keys.all { structurallyEqual(left.fields[it], right.fields[it], seen) }
		left is PoderTechIrEnumValue && right is PoderTechIrEnumValue ->
			left.typeName == right.typeName && left.entryName == right.entryName && left.sourceIdentity == right.sourceIdentity &&
				left.arguments.size == right.arguments.size && left.arguments.indices.all { structurallyEqual(left.arguments[it], right.arguments[it], seen) }
		left is List<*> && right is List<*> -> left.size == right.size && left.indices.all { structurallyEqual(left[it], right[it], seen) }
		left is PoderTechIrResult && right is PoderTechIrResult -> left.failure == right.failure && structurallyEqual(left.value, right.value, seen)
		else -> left == right
	}
}
private class IdentityPair(private val left: Any, private val right: Any) {
	override fun equals(other: Any?) = other is IdentityPair &&
		(left === other.left && right === other.right || left === other.right && right === other.left)
	override fun hashCode() = System.identityHashCode(left) xor System.identityHashCode(right)
}
private fun boolean(value: Any?, context: String) = value as? Boolean ?: fail("$context expects Boolean, found ${runtimeType(value)}")
private fun isNumeric(value: Any?) = value is Byte || value is Short || value is Int || value is Long || value is Float || value is Double || value is UByte || value is UShort || value is UInt || value is ULong
private fun isUnsigned(value: Any?) = value is UByte || value is UShort || value is UInt || value is ULong
private fun unsignedLong(value: Any?) = when (value) { is UByte -> value.toULong(); is UShort -> value.toULong(); is UInt -> value.toULong(); is ULong -> value; else -> fail("Expected unsigned integer") }
private fun integral(value: Any?, context: String): Long = when (value) { is Byte -> value.toLong(); is Short -> value.toLong(); is Int -> value.toLong(); is Long -> value; is UByte -> value.toLong(); is UShort -> value.toLong(); is UInt -> value.toLong(); is ULong -> if (value <= Long.MAX_VALUE.toULong()) value.toLong() else fail("$context exceeds signed range"); else -> fail("$context expects an integer, found ${runtimeType(value)}") }
private fun narrowIntegral(value: Long): Any = if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
private fun double(value: Any?): Double = when (value) { is Number -> value.toDouble(); is UByte -> value.toDouble(); is UShort -> value.toDouble(); is UInt -> value.toDouble(); is ULong -> value.toDouble(); else -> fail("Expected a number, found ${runtimeType(value)}") }
private fun typeValue(value: Any?, context: String) = (value as? PoderTechIrExecutor.TypeValue)?.type ?: fail("$context expects a type literal")
private fun arity(name: String, arguments: List<*>, expected: Int) { if (arguments.size != expected) fail("$name expects $expected arguments, got ${arguments.size}") }
private fun runtimeType(value: Any?): String = when (value) { null -> "Null"; PoderTechIrUnit -> "Unit"; is PoderTechIrStructValue -> value.typeName; is PoderTechIrEnumValue -> value.typeName; is Byte -> "I8"; is Short -> "I16"; is Int -> "I32"; is Long -> "I64"; is UByte -> "U8"; is UShort -> "U16"; is UInt -> "U32"; is ULong -> "U64"; is Float -> "F32"; is Double -> "F64"; is List<*> -> "Array"; else -> value::class.simpleName ?: "value" }
private fun renderType(type: PoderTechIrType): String = when (type) { PoderTechIrType.Implicit -> "Implicit"; is PoderTechIrType.Named -> type.name; is PoderTechIrType.Array -> "[${renderType(type.elementType)}]"; is PoderTechIrType.Result -> "${renderType(type.valueType)}?" }
private fun display(value: Any?): String = when (value) { null -> "null"; is Future<*> -> "Future(pending)"; is List<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::display); else -> value.toString() }
private fun fail(message: String, cause: Throwable? = null): Nothing = throw PoderTechIrExecutionException(message, cause = cause)
