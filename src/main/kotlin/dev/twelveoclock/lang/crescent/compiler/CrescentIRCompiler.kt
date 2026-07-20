package dev.twelveoclock.lang.crescent.compiler

import dev.twelveoclock.lang.crescent.diagnostics.Diagnostic
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticException
import dev.twelveoclock.lang.crescent.diagnostics.DiagnosticSeverity
import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.File
import dev.twelveoclock.lang.crescent.language.ir.*
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import java.nio.file.Path

/** Lowers linked Crescent AST files into a deterministic, AST-free executable program. */
object CrescentIRCompiler {

	fun invoke(file: File): CrescentIR = invoke(listOf(file), file)

	fun invoke(files: List<File>, mainFile: File): CrescentIR {
		try {
			require(files.isNotEmpty()) { "At least one file is required to compile Crescent IR" }
			require(files.any { it === mainFile }) { "The main file must be included in the compiler input" }
			val lowerer = Lowerer(files.toList(), mainFile)
			return CrescentIR(listOf(CrescentIR.Command.Program(lowerer.lower())))
		} catch (exception: DiagnosticException) {
			throw exception
		} catch (exception: IllegalArgumentException) {
			val span = SourceLocations.spanOf(mainFile) ?: throw exception
			throw DiagnosticException(Diagnostic(DiagnosticSeverity.ERROR, exception.message ?: "Could not lower Crescent program", span), exception)
		}
	}

	private class Lowerer(private val files: List<File>, private val mainFile: File) {
		private data class ActualSymbol(
			val symbol: SymbolRef,
			val visibility: IRVisibility,
			val mainOnly: Boolean = false,
		)

		private val physicalSources = files.associateWith { physical(it.path) }
		private val sources = files.associateWith { SourceId(it.packageId, logical(it.path)) }
		private val actualSymbols = buildActualSymbols()
		private val packageNames = actualSymbols.values.groupBy { it.symbol.source.packageId to it.symbol.name }
		private val sourceNames = actualSymbols.values.groupBy { it.symbol.source to it.symbol.name }

		init {
			require(sources.size == files.size) { "The compiler input cannot contain the same File object twice" }
			physicalSources.entries.groupBy { it.value }.forEach { (path, entries) ->
				require(entries.map { it.key.packageId }.distinct().size == 1) {
					"Physical source '$path' is assigned to contradictory packages"
				}
			}
			require(sources.values.distinct().size == sources.size) { "The compiler input contains duplicate source paths" }
			packageNames.forEach { (identity, declarations) ->
				require(declarations.size == 1) {
					"Duplicate declaration '${identity.second}' in package '${identity.first}'"
				}
			}
			actualSymbols.values.forEach { require(it.symbol.name !in builtins) {
				"Declaration '${it.symbol.name}' uses a reserved builtin name"
			} }
			files.forEach { file -> file.importedSymbols.keys.forEach { alias -> require(alias !in builtins) {
				"Import alias '$alias' uses a reserved builtin name"
			} } }
			files.forEach(::validateImports)
		}

		fun lower(): CrescentProgramIR {
			val main = requireNotNull(mainFile.mainFunction) { "The main file does not declare a main function" }
			val mainRef = FunctionRef(SymbolRef(source(mainFile), SymbolKind.FUNCTION, main.name))
			val units = files.sortedWith(compareBy({ it.packageId }, { logical(it.path) })).map(::lowerFile)
			return CrescentProgramIR(units.toList(), mainRef)
		}

		private fun lowerFile(file: File): SourceUnitIR {
			val source = source(file)
			val imports = file.importedSymbols.toSortedMap().mapValues { (_, metadata) -> metadata.toSymbolRef() }.toMap()
			val globals = buildList {
				file.variables.toSortedMap().values.forEach { add(lowerGlobal(source, it, false, file)) }
				file.constants.toSortedMap().values.forEach { add(lowerGlobal(source, it, true, file)) }
			}
			val topFunctions = linkedMapOf<String, Node.Function>().apply {
				putAll(file.functions.toSortedMap())
				file.mainFunction?.let { put(it.name, it) }
			}.values.map { lowerFunction(source, it, null, file) }
			return SourceLocations.copy(file, SourceUnitIR(
				source = source,
				imports = imports,
				structs = file.structs.toSortedMap().values.map { struct ->
					val ref = symbol(source, SymbolKind.STRUCT, struct.name)
					StructIR(ref, struct.visibility.ir(), lowerFields(struct.variables, file, IRType.Declared(ref)))
				},
				sealeds = file.sealeds.toSortedMap().values.map { sealed -> lowerSealed(source, sealed, file) },
				traits = file.traits.toSortedMap().values.map { trait ->
					TraitIR(symbol(source, SymbolKind.TRAIT, trait.name), trait.visibility.ir(), trait.functionTraits.map {
						FunctionSignatureIR(it.name, it.params.map { parameter ->
							lowerType(when (parameter) {
								is Node.Parameter.Basic -> parameter.type
								is Node.Parameter.WithDefault -> parameter.type
							}, file)
						}, lowerType(it.returnType, file), it.params.map { parameter -> parameter is Node.Parameter.WithDefault })
					})
				},
				objects = file.objects.toSortedMap().values.map { lowerObject(source, it, file) },
				enums = file.enums.toSortedMap().values.map { enum -> lowerEnum(source, enum, file) },
				globals = globals,
				functions = topFunctions,
				implementations = (file.impls.toSortedMap().values + file.staticImpls.toSortedMap().values).map { lowerImpl(source, it, file) },
			))
		}

		private fun lowerGlobal(source: SourceId, variable: Node.Variable, constant: Boolean, file: File): GlobalIR =
			SourceLocations.copy(variable, GlobalIR(
				symbol(source, if (constant) SymbolKind.CONSTANT else SymbolKind.GLOBAL, variable.name),
				when (variable) {
					is Node.Variable.Basic -> variable.visibility.ir()
					is Node.Variable.Constant -> variable.visibility.ir()
					else -> IRVisibility.PRIVATE
				},
				lowerType(variable.type, file), !variable.isFinal, expression(variable.value, FunctionContext(file)),
			))

		private fun lowerFields(variables: List<Node.Variable>, file: File, owner: IRType): List<FieldIR> {
			val prior = linkedSetOf<String>()
			return variables.map { variable -> lowerField(variable, file, owner, prior.toSet()).also { prior += variable.name } }
		}

		private fun lowerField(variable: Node.Variable, file: File, owner: IRType? = null, ownerMembers: Set<String> = emptySet()): FieldIR {
			val value = variable.value
			val initializer = if (value is Node.Expression && value.nodes.isEmpty()) null
			else expression(value, FunctionContext(file, owner, ownerMembers))
			return SourceLocations.copy(variable, FieldIR(
				variable.name, lowerType(variable.type, file), !variable.isFinal,
				when (variable) {
					is Node.Variable.Basic -> variable.visibility.ir()
					is Node.Variable.Constant -> variable.visibility.ir()
					else -> IRVisibility.PRIVATE
				}, initializer,
			))
		}

		private fun lowerSealed(source: SourceId, sealed: Node.Sealed, file: File): SealedIR {
			val parent = symbol(source, SymbolKind.SEALED, sealed.name)
			return SealedIR(parent, sealed.visibility.ir(),
				sealed.structs.sortedBy(Node.Struct::name).map { nested ->
					val nestedRef = NestedSymbolRef(parent, SymbolKind.STRUCT, nested.name)
					SealedStructIR(nestedRef, strict(sealed.visibility.ir(), nested.visibility.ir()), lowerFields(nested.variables, file, IRType.Declared(nestedRef)))
				},
				sealed.objects.sortedBy(Node.Object::name).map { nested ->
					val owner = IRType.Declared(NestedSymbolRef(parent, SymbolKind.OBJECT, nested.name))
					val declarations = nested.constants.values + nested.variables.values
					val memberNames = (declarations.map(Node.Variable::name) + nested.functions.keys).toSet()
					SealedObjectIR(owner.symbol as NestedSymbolRef, strict(sealed.visibility.ir(), nested.visibility.ir()),
						lowerFields(declarations, file, owner),
						nested.functions.toSortedMap().values.map { lowerFunction(source, it, owner, file, memberNames) })
				})
		}

		private fun lowerObject(source: SourceId, node: Node.Object, file: File): ObjectIR {
			val ref = symbol(source, SymbolKind.OBJECT, node.name)
			val owner = IRType.Declared(ref)
			val declarations = node.constants.values + node.variables.values
			val memberNames = (declarations.map(Node.Variable::name) + node.functions.keys).toSet()
			return ObjectIR(ref, node.visibility.ir(), lowerFields(declarations, file, owner),
				node.functions.toSortedMap().values.map { lowerFunction(source, it, owner, file, memberNames) })
		}

		private fun lowerEnum(source: SourceId, node: Node.Enum, file: File): EnumIR {
			val context = FunctionContext(file)
			val parameters = lowerParameters(node.parameters, context)
			return EnumIR(symbol(source, SymbolKind.ENUM, node.name), node.visibility.ir(), parameters,
				node.structs.map { EnumEntryIR(it.name, it.arguments.map { value -> expression(value, context) }) })
		}

		private fun lowerImpl(source: SourceId, node: Node.Impl, file: File): ImplementationIR {
			val target = lowerType(node.type, file)
			val modifiers = node.modifiers.mapTo(linkedSetOf()) { it.ir() }
			val members = (if (IRModifier.STATIC in modifiers) emptySet() else ownerMemberNames(target)) + node.functions.map(Node.Function::name)
			return ImplementationIR(target, modifiers, node.extends.map { lowerType(it, file) },
				node.functions.sortedBy(Node.Function::name).map { lowerFunction(source, it, target, file, members) })
		}

		private fun lowerFunction(source: SourceId, node: Node.Function, owner: IRType?, file: File, ownerMembers: Set<String> = emptySet()): FunctionIR {
			val context = FunctionContext(file, owner, ownerMembers)
			val parameters = lowerParameters(node.params, context)
			return SourceLocations.copy(node, FunctionIR(symbol(source, SymbolKind.FUNCTION, node.name), owner, node.visibility.ir(),
				node.modifiers.mapTo(linkedSetOf()) { it.ir() }, parameters, lowerType(node.returnType, file), block(node.innerCode, context))
			)
		}

		private fun lowerParameters(parameters: List<Node.Parameter>, context: FunctionContext): List<ParameterIR> = parameters.map { parameter ->
			val default = (parameter as? Node.Parameter.WithDefault)?.let { expression(it.defaultValue, context) }
			val slot = context.declare(parameter.name)
			when (parameter) {
				is Node.Parameter.Basic -> ParameterIR(slot, parameter.name, lowerType(parameter.type, context.file), null)
				is Node.Parameter.WithDefault -> ParameterIR(slot, parameter.name, lowerType(parameter.type, context.file), default)
			}
		}

		private inner class FunctionContext(val file: File, val owner: IRType? = null, val ownerMembers: Set<String> = emptySet()) {
			private var nextSlot = 0
			private val scopes = ArrayDeque<MutableMap<String, Int>>().apply { addLast(linkedMapOf()) }
			fun declare(name: String): Int {
				require(name !in scopes.last()) { "Duplicate local '$name'" }
				return nextSlot++.also { scopes.last()[name] = it }
			}
			fun resolve(name: String): Int? = scopes.reversed().firstNotNullOfOrNull { it[name] }
			fun <T> scope(action: () -> T): T {
				scopes.addLast(linkedMapOf())
				return try { action() } finally { scopes.removeLast() }
			}
		}

		private fun block(node: Node.Statement.Block, context: FunctionContext): IRBlock = context.scope {
			SourceLocations.copy(node, IRBlock(node.nodes.map { statement(it, context) }))
		}

		private fun statement(node: Node, context: FunctionContext): IRStatement {
			val lowered = when (node) {
			is Node.Variable.Local -> expression(node.value, context).let { initializer -> IRStatement.Declare(context.declare(node.name), node.name, lowerType(node.type, context.file), !node.isFinal, initializer) }
			is Node.Variable.Basic -> expression(node.value, context).let { initializer -> IRStatement.Declare(context.declare(node.name), node.name, lowerType(node.type, context.file), !node.isFinal, initializer) }
			is Node.Return -> IRStatement.Return(expression(node.expression, context))
			is Node.Statement.If -> IRStatement.If(expression(node.predicate, context), block(node.block, context), node.elseBlock?.let { block(it, context) })
			is Node.Statement.While -> IRStatement.While(expression(node.predicate, context), block(node.block, context))
			is Node.Statement.For -> context.scope {
				val ranges = node.ranges.map { RangeIR(expression(it.start, context), expression(it.end, context)) }
				val counters = node.identifiers.map { LoopCounterIR(context.declare(it.name), it.name) }
				IRStatement.For(counters, ranges, block(node.block, context))
			}
			is Node.Statement.When -> context.scope {
				val subject = expression(node.argument, context)
				val binding = node.subjectName?.let { LoopCounterIR(context.declare(it), it) }
				IRStatement.When(subject, binding, node.predicateToBlock.map { clause ->
					WhenClauseIR(when (val predicate = clause.ifExpressionNode) {
						null -> WhenPredicateIR.Else
						is Node.Statement.When.EnumShortHand -> WhenPredicateIR.EnumEntry(predicate.name)
						else -> WhenPredicateIR.Value(expression(predicate, context))
					}, block(clause.thenBlock, context))
				})
			}
			is Node.Statement.Block -> IRStatement.NestedBlock(block(node, context))
			CrescentToken.Keyword.BREAK -> IRStatement.Break
			CrescentToken.Keyword.CONTINUE -> IRStatement.Continue
			else -> IRStatement.Evaluate(expression(node, context))
			}
			return SourceLocations.copy(node, lowered)
		}

		private sealed interface RawExpression {
			data class Leaf(val node: Node) : RawExpression
			data class Unary(val operator: CrescentToken.Operator, val operand: RawExpression) : RawExpression
			data class Binary(val operator: CrescentToken.Operator, val left: RawExpression, val right: RawExpression) : RawExpression
		}

		private fun expression(node: Node, context: FunctionContext): IRExpression {
			val lowered = try { when (node) {
			is Node.Expression -> lowerRaw(buildRaw(node), context)
			is Node.Primitive.Boolean -> IRExpression.Literal(IRLiteral.Boolean(node.data))
			is Node.Primitive.String -> IRExpression.Literal(IRLiteral.String(node.data))
			is Node.Primitive.Char -> IRExpression.Literal(IRLiteral.Char(node.data))
			is Node.Primitive.Number.U8 -> IRExpression.Literal(IRLiteral.U8(node.data))
			is Node.Primitive.Number.U16 -> IRExpression.Literal(IRLiteral.U16(node.data))
			is Node.Primitive.Number.U32 -> IRExpression.Literal(IRLiteral.U32(node.data))
			is Node.Primitive.Number.U64 -> IRExpression.Literal(IRLiteral.U64(node.data))
			is Node.Primitive.Number.I8 -> IRExpression.Literal(IRLiteral.I8(node.data))
			is Node.Primitive.Number.I16 -> IRExpression.Literal(IRLiteral.I16(node.data))
			is Node.Primitive.Number.I32 -> IRExpression.Literal(IRLiteral.I32(node.data))
			is Node.Primitive.Number.I64 -> IRExpression.Literal(IRLiteral.I64(node.data))
			is Node.Primitive.Number.F32 -> IRExpression.Literal(IRLiteral.F32(node.data))
			is Node.Primitive.Number.F64 -> IRExpression.Literal(IRLiteral.F64(node.data))
			is Node.Array -> IRExpression.Array(node.values.map { expression(it, context) }.toList())
			is Node.Identifier -> identifier(node.name, context)
			is Node.IdentifierCall -> call(node.identifier, node.arguments, context)
			is Node.GetCall -> IRExpression.Index(identifier(node.identifier, context), node.arguments.map { expression(it, context) })
			is Node.DotChain -> dotChain(node, context)
			is Node.InfixCall -> IRExpression.Call(CallTargetIR.Member(expression(node.receiver, context), node.functionName), listOf(expression(node.argument, context)))
			is Node.TypeLiteral -> IRExpression.TypeValue(lowerType(node.type, context.file))
			is Node.Statement.If -> IRExpression.Conditional(statement(node, context) as IRStatement.If)
				else -> throw IllegalArgumentException("Unsupported expression node ${node::class.simpleName} in ${context.file.path}")
			} } catch (exception: DiagnosticException) {
				throw exception
			} catch (exception: IllegalArgumentException) {
				val span = SourceLocations.spanOf(node) ?: throw exception
				throw DiagnosticException(Diagnostic(DiagnosticSeverity.ERROR, exception.message ?: "Could not lower expression", span), exception)
			}
			return SourceLocations.copy(node, lowered)
		}

		private fun identifier(name: String, context: FunctionContext): IRExpression {
			context.resolve(name)?.let { return IRExpression.Variable(VariableRefIR.Local(it, name)) }
			if (name in context.ownerMembers) return IRExpression.Member(IRExpression.This, name)
			val own = sourceNames[source(context.file) to name]?.singleOrNull()?.symbol
			val imported = context.file.importedSymbols[name]?.toSymbolRef()
			val resolved = own ?: imported
			return when (resolved?.kind) {
				SymbolKind.GLOBAL, SymbolKind.CONSTANT -> IRExpression.Variable(VariableRefIR.Global(resolved))
				SymbolKind.STRUCT, SymbolKind.SEALED, SymbolKind.TRAIT, SymbolKind.OBJECT, SymbolKind.ENUM -> IRExpression.TypeValue(declaredType(resolved))
				else -> throw IllegalArgumentException("Unknown identifier '$name' in ${context.file.path}")
			}
		}

		private fun call(name: String, arguments: List<Node>, context: FunctionContext): IRExpression {
			val lowered = arguments.map { expression(it, context) }
			if (name in builtins) return IRExpression.Call(CallTargetIR.Builtin(name), lowered)
			if (name in context.ownerMembers) return IRExpression.Call(CallTargetIR.Member(IRExpression.This, name), lowered)
			val resolved = sourceNames[source(context.file) to name]?.singleOrNull()?.symbol ?: context.file.importedSymbols[name]?.toSymbolRef()
			val target = when (resolved?.kind) {
				SymbolKind.FUNCTION -> CallTargetIR.Function(FunctionRef(resolved))
				SymbolKind.STRUCT, SymbolKind.ENUM -> CallTargetIR.Constructor(resolved)
				else -> throw IllegalArgumentException("Unknown function or constructor '$name' in ${context.file.path}")
			}
			return IRExpression.Call(target, lowered)
		}

		private fun dotChain(node: Node.DotChain, context: FunctionContext): IRExpression {
			require(node.nodes.isNotEmpty()) { "Dot chain cannot be empty" }
			var current = expression(node.nodes.first(), context)
			node.nodes.drop(1).forEach { member -> current = when (member) {
				is Node.Identifier -> IRExpression.Member(current, member.name)
				is Node.IdentifierCall -> IRExpression.Call(CallTargetIR.Member(current, member.identifier), member.arguments.map { expression(it, context) })
				is Node.GetCall -> IRExpression.Index(IRExpression.Member(current, member.identifier), member.arguments.map { expression(it, context) })
				else -> throw IllegalArgumentException("Unsupported dot-chain member ${member::class.simpleName}")
			} }
			return current
		}

		private fun buildRaw(node: Node.Expression): RawExpression {
			val stack = ArrayDeque<RawExpression>()
			node.nodes.forEach { part ->
				if (part !is CrescentToken.Operator) stack.addLast(RawExpression.Leaf(part))
				else when (part) {
					CrescentToken.Operator.NOT, CrescentToken.Operator.RESULT -> stack.addLast(RawExpression.Unary(part, pop(stack, part)))
					else -> { val right = pop(stack, part); val left = pop(stack, part); stack.addLast(RawExpression.Binary(part, left, right)) }
				}
			}
			require(stack.size == 1) { "Expression left ${stack.size} unevaluated values" }
			return stack.removeLast()
		}

		private fun pop(stack: ArrayDeque<RawExpression>, operator: CrescentToken.Operator): RawExpression =
			stack.removeLastOrNull() ?: throw IllegalArgumentException("Operator '$operator' is missing an operand")

		private fun lowerRaw(raw: RawExpression, context: FunctionContext): IRExpression = when (raw) {
			is RawExpression.Leaf -> expression(raw.node, context)
			is RawExpression.Unary -> when (raw.operator) {
				CrescentToken.Operator.NOT -> IRExpression.Unary(IRUnaryOperation.NOT, lowerRaw(raw.operand, context))
				CrescentToken.Operator.RESULT -> IRExpression.PropagateResult(lowerRaw(raw.operand, context))
				else -> throw IllegalArgumentException("Unsupported unary operator ${raw.operator}")
			}
			is RawExpression.Binary -> lowerBinary(raw, context)
		}

		private fun lowerBinary(raw: RawExpression.Binary, context: FunctionContext): IRExpression {
			val operator = raw.operator
			if (operator in assignmentOperators) {
				val target = assignmentTarget(raw.left, context)
				val value = lowerRaw(raw.right, context)
				return if (operator == CrescentToken.Operator.ASSIGN) IRExpression.Assign(target, value)
				else IRExpression.CompoundAssign(target, compoundOperations.getValue(operator), value)
			}
			if (operator == CrescentToken.Operator.AS || operator == CrescentToken.Operator.INSTANCE_OF || operator == CrescentToken.Operator.NOT_INSTANCE_OF) {
				val literal = (raw.right as? RawExpression.Leaf)?.node as? Node.TypeLiteral
					requireNotNull(literal) { "$operator requires a type literal on the right" }
				val value = lowerRaw(raw.left, context)
				val type = lowerType(literal.type, context.file)
				return if (operator == CrescentToken.Operator.AS) IRExpression.Cast(value, type)
				else IRExpression.TypeTest(value, type, operator == CrescentToken.Operator.NOT_INSTANCE_OF)
			}
			val left = lowerRaw(raw.left, context)
			val right = lowerRaw(raw.right, context)
			return when (operator) {
				CrescentToken.Operator.AND_COMPARE -> IRExpression.LogicalAnd(left, right)
				CrescentToken.Operator.OR_COMPARE -> IRExpression.LogicalOr(left, right)
				else -> IRExpression.Binary(binaryOperations[operator] ?: error("Unsupported binary operator $operator"), left, right)
			}
		}

		private fun assignmentTarget(raw: RawExpression, context: FunctionContext): AssignmentTargetIR {
			val value = lowerRaw(raw, context)
			return when (value) {
				is IRExpression.Variable -> AssignmentTargetIR.Variable(value.reference)
				is IRExpression.Index -> {
					require(value.arguments.size == 1) { "Array assignment expects exactly one index" }
					AssignmentTargetIR.Index(value.receiver, value.arguments.single())
				}
				is IRExpression.Member -> AssignmentTargetIR.Member(value.receiver, value.name)
				else -> throw IllegalArgumentException("Assignment requires a variable, member, or array index")
			}
		}

		private fun lowerType(type: Node.Type, file: File): IRType {
			val lowered = try { when (type) {
			Node.Type.Implicit -> IRType.Implicit
			is Node.Type.Array -> IRType.Array(lowerType(type.type, file))
			is Node.Type.Result -> IRType.Result(lowerType(type.type, file))
			is Node.Type.Basic -> {
				if (type.name in builtinTypes) IRType.Builtin(type.name)
				else {
					val symbol = sourceNames[source(file) to type.name]?.singleOrNull()?.symbol ?: file.importedSymbols[type.name]?.toSymbolRef()
						?: throw IllegalArgumentException("Unknown type '${type.name}' in ${file.path}")
					declaredType(symbol)
				}
			}
				else -> throw IllegalArgumentException("Unsupported type node ${type::class.simpleName}")
			} } catch (exception: DiagnosticException) {
				throw exception
			} catch (exception: IllegalArgumentException) {
				val span = SourceLocations.spanOf(type) ?: throw exception
				throw DiagnosticException(Diagnostic(DiagnosticSeverity.ERROR, exception.message ?: "Could not lower type", span), exception)
			}
			return SourceLocations.copy(type, lowered)
		}

		private fun declaredType(symbol: SymbolRef): IRType.Declared = if (symbol.enclosingTypeName == null) IRType.Declared(symbol)
		else {
			val parent = actualSymbols.values.singleOrNull { it.symbol.source == symbol.source && it.symbol.kind == SymbolKind.SEALED && it.symbol.name == symbol.enclosingTypeName }?.symbol
				?: throw IllegalArgumentException("Missing enclosing sealed type '${symbol.enclosingTypeName}' for ${symbol.name}")
			IRType.Declared(NestedSymbolRef(parent, symbol.kind, symbol.name))
		}

		private fun ownerMemberNames(type: IRType): Set<String> {
			val declared = type as? IRType.Declared ?: return emptySet()
			val sourceId = when (val reference = declared.symbol) {
				is SymbolRef -> reference.source
				is NestedSymbolRef -> reference.parent.source
			}
			val file = files.firstOrNull { source(it) == sourceId } ?: return emptySet()
			return when (val reference = declared.symbol) {
				is SymbolRef -> when (reference.kind) {
					SymbolKind.STRUCT -> file.structs[reference.name]?.variables?.map(Node.Variable::name).orEmpty().toSet()
					SymbolKind.OBJECT -> file.objects[reference.name]?.let { (it.constants.keys + it.variables.keys + it.functions.keys).toSet() }.orEmpty()
					else -> emptySet()
				}
				is NestedSymbolRef -> file.sealeds[reference.parent.name]?.let { sealed ->
					sealed.structs.firstOrNull { it.name == reference.name }?.variables?.map(Node.Variable::name)?.toSet()
						?: sealed.objects.firstOrNull { it.name == reference.name }?.let { (it.constants.keys + it.variables.keys + it.functions.keys).toSet() }
				}.orEmpty()
			}
		}

		private fun buildActualSymbols(): Map<List<Any?>, ActualSymbol> {
			val result = linkedMapOf<List<Any?>, ActualSymbol>()
			fun add(file: File, kind: SymbolKind, name: String, visibility: IRVisibility, enclosing: String? = null, mainOnly: Boolean = false) {
				val ref = SymbolRef(source(file), kind, name, enclosing)
				val key = listOf(ref.source.packageId, ref.source.sourcePath, ref.name, ref.kind, ref.enclosingTypeName)
				require(result.put(key, ActualSymbol(ref, visibility, mainOnly)) == null) { "Duplicate declaration metadata for $ref" }
			}
			files.forEach { file ->
				file.structs.values.forEach { add(file, SymbolKind.STRUCT, it.name, it.visibility.ir()) }
				file.sealeds.values.forEach { sealed ->
					add(file, SymbolKind.SEALED, sealed.name, sealed.visibility.ir())
					sealed.structs.forEach { add(file, SymbolKind.STRUCT, it.name, strict(sealed.visibility.ir(), it.visibility.ir()), sealed.name) }
					sealed.objects.forEach { add(file, SymbolKind.OBJECT, it.name, strict(sealed.visibility.ir(), it.visibility.ir()), sealed.name) }
				}
				file.traits.values.forEach { add(file, SymbolKind.TRAIT, it.name, it.visibility.ir()) }
				file.objects.values.forEach { add(file, SymbolKind.OBJECT, it.name, it.visibility.ir()) }
				file.enums.values.forEach { add(file, SymbolKind.ENUM, it.name, it.visibility.ir()) }
				file.variables.values.forEach { add(file, SymbolKind.GLOBAL, it.name, it.visibility.ir()) }
				file.constants.values.forEach { add(file, SymbolKind.CONSTANT, it.name, it.visibility.ir()) }
				file.functions.values.forEach { add(file, SymbolKind.FUNCTION, it.name, it.visibility.ir(), mainOnly = it === file.mainFunction) }
				file.mainFunction?.takeIf { it.name !in file.functions }?.let { add(file, SymbolKind.FUNCTION, it.name, it.visibility.ir(), mainOnly = true) }
			}
			return result
		}

		private fun validateImports(file: File) {
			val importer = source(file)
			file.importedSymbols.forEach { (alias, metadata) ->
				require(alias.isNotBlank()) { "Imported aliases cannot be blank" }
				val ref = metadata.toSymbolRef()
				val key = listOf(ref.source.packageId, ref.source.sourcePath, ref.name, ref.kind, ref.enclosingTypeName)
				val actual = actualSymbols[key] ?: throw IllegalArgumentException("Import '$alias' refers to a declaration that is not in the linked program")
				require(!actual.mainOnly) { "Import '$alias' cannot refer to a main-only function" }
				require(actual.visibility == metadata.visibility.ir()) { "Import '$alias' has forged visibility metadata" }
				require(when (actual.visibility) {
					IRVisibility.PUBLIC -> true
					IRVisibility.INTERNAL -> importer.packageId == actual.symbol.source.packageId
					IRVisibility.PRIVATE -> importer == actual.symbol.source
				}) { "Import '$alias' cannot access ${actual.visibility.name.lowercase()} declaration '${actual.symbol.name}'" }
			}
		}

		private fun Node.ModuleSymbol.toSymbolRef(): SymbolRef {
			val linked = files.singleOrNull { it.packageId == packageId && physical(it.path) == physical(declarationPath) }
			return SymbolRef(linked?.let(::source) ?: SourceId(packageId, logical(declarationPath)), kind.ir(), sourceName, enclosingTypeName)
		}

		private fun source(file: File): SourceId = sources.getValue(file)
		private fun symbol(source: SourceId, kind: SymbolKind, name: String) = SymbolRef(source, kind, name)
	}

	private fun physical(path: Path): String = path.toAbsolutePath().normalize().toString().replace('\\', '/')
	private fun logical(path: Path): String = path.normalize().fileName.toString().replace('\\', '/')

	private fun CrescentToken.Visibility.ir() = IRVisibility.valueOf(name)
	private fun CrescentToken.Modifier.ir() = IRModifier.valueOf(name)
	private fun Node.ModuleSymbolKind.ir() = when (this) {
		Node.ModuleSymbolKind.FUNCTION -> SymbolKind.FUNCTION
		Node.ModuleSymbolKind.VARIABLE -> SymbolKind.GLOBAL
		Node.ModuleSymbolKind.CONSTANT -> SymbolKind.CONSTANT
		Node.ModuleSymbolKind.STRUCT -> SymbolKind.STRUCT
		Node.ModuleSymbolKind.SEALED -> SymbolKind.SEALED
		Node.ModuleSymbolKind.TRAIT -> SymbolKind.TRAIT
		Node.ModuleSymbolKind.OBJECT -> SymbolKind.OBJECT
		Node.ModuleSymbolKind.ENUM -> SymbolKind.ENUM
	}
	private fun strict(left: IRVisibility, right: IRVisibility) = if (left.ordinal < right.ordinal) left else right

	private val builtins = setOf("print", "println", "readLine", "readBoolean", "readDouble", "readInt", "success", "failure", "sqrt", "sin", "round", "await", "typeOf")
	private val builtinTypes = setOf("Any", "Unit", "Boolean", "String", "Char", "U8", "U16", "U32", "U64", "I8", "I16", "I32", "I64", "F32", "F64", "Future")
	private val assignmentOperators = setOf(CrescentToken.Operator.ASSIGN, CrescentToken.Operator.ADD_ASSIGN, CrescentToken.Operator.SUB_ASSIGN, CrescentToken.Operator.MUL_ASSIGN, CrescentToken.Operator.DIV_ASSIGN, CrescentToken.Operator.REM_ASSIGN, CrescentToken.Operator.POW_ASSIGN)
	private val compoundOperations = mapOf(
		CrescentToken.Operator.ADD_ASSIGN to IRCompoundOperation.ADD, CrescentToken.Operator.SUB_ASSIGN to IRCompoundOperation.SUBTRACT,
		CrescentToken.Operator.MUL_ASSIGN to IRCompoundOperation.MULTIPLY, CrescentToken.Operator.DIV_ASSIGN to IRCompoundOperation.DIVIDE,
		CrescentToken.Operator.REM_ASSIGN to IRCompoundOperation.REMAINDER, CrescentToken.Operator.POW_ASSIGN to IRCompoundOperation.POWER,
	)
	private val binaryOperations = mapOf(
		CrescentToken.Operator.ADD to IRBinaryOperation.ADD, CrescentToken.Operator.SUB to IRBinaryOperation.SUBTRACT,
		CrescentToken.Operator.MUL to IRBinaryOperation.MULTIPLY, CrescentToken.Operator.DIV to IRBinaryOperation.DIVIDE,
		CrescentToken.Operator.POW to IRBinaryOperation.POWER, CrescentToken.Operator.REM to IRBinaryOperation.REMAINDER,
		CrescentToken.Operator.EQUALS_COMPARE to IRBinaryOperation.EQUAL, CrescentToken.Operator.NOT_EQUALS_COMPARE to IRBinaryOperation.NOT_EQUAL,
		CrescentToken.Operator.EQUALS_REFERENCE_COMPARE to IRBinaryOperation.REFERENCE_EQUAL, CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE to IRBinaryOperation.REFERENCE_NOT_EQUAL,
		CrescentToken.Operator.LESSER_COMPARE to IRBinaryOperation.LESS, CrescentToken.Operator.LESSER_EQUALS_COMPARE to IRBinaryOperation.LESS_OR_EQUAL,
		CrescentToken.Operator.GREATER_COMPARE to IRBinaryOperation.GREATER, CrescentToken.Operator.GREATER_EQUALS_COMPARE to IRBinaryOperation.GREATER_OR_EQUAL,
		CrescentToken.Operator.CONTAINS to IRBinaryOperation.CONTAINS, CrescentToken.Operator.NOT_CONTAINS to IRBinaryOperation.NOT_CONTAINS,
		CrescentToken.Operator.RANGE_TO to IRBinaryOperation.RANGE_TO, CrescentToken.Operator.BIT_SHIFT_LEFT to IRBinaryOperation.SHIFT_LEFT,
		CrescentToken.Operator.BIT_SHIFT_RIGHT to IRBinaryOperation.SHIFT_RIGHT, CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT to IRBinaryOperation.UNSIGNED_SHIFT_RIGHT,
		CrescentToken.Operator.BIT_OR to IRBinaryOperation.BIT_OR, CrescentToken.Operator.BIT_AND to IRBinaryOperation.BIT_AND, CrescentToken.Operator.BIT_XOR to IRBinaryOperation.BIT_XOR,
	)
}
