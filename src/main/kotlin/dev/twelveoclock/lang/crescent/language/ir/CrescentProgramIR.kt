package dev.twelveoclock.lang.crescent.language.ir

/**
 * Executable Crescent program after linking and lowering.
 *
 * Compiler-produced instances contain defensive collection snapshots. Source
 * paths are normalized strings and no parser token, AST node, or [java.nio.file.Path]
 * is retained after compilation. Public constructors remain ordinary Kotlin
 * data-class constructors and therefore expect callers not to mutate inputs.
 */
data class CrescentProgramIR(
	val sourceUnits: List<SourceUnitIR>,
	val main: FunctionRef,
) {
	init {
		require(sourceUnits.isNotEmpty()) { "A lowered program must contain at least one source unit" }
		require(sourceUnits == sourceUnits.sortedWith(compareBy({ it.source.packageId }, { it.source.sourcePath }))) {
			"Lowered source units must be ordered by package and source path"
		}
		require(sourceUnits.map(SourceUnitIR::source).distinct().size == sourceUnits.size) {
			"A lowered program cannot contain duplicate source identities"
		}
		require(sourceUnits.any { unit -> unit.functions.any { it.symbol == main.symbol } }) {
			"The lowered main function must be declared by a program source unit"
		}
	}
}

data class SourceId(
	val packageId: String,
	val sourcePath: String,
) {
	init {
		require(sourcePath.isNotBlank()) { "A source path cannot be blank" }
		require('\\' !in sourcePath) { "Source paths must use normalized '/' separators" }
	}
}

enum class SymbolKind {
	FUNCTION,
	GLOBAL,
	CONSTANT,
	STRUCT,
	SEALED,
	TRAIT,
	OBJECT,
	ENUM,
}

sealed interface DeclaredTypeRef

data class SymbolRef(
	val source: SourceId,
	val kind: SymbolKind,
	val name: String,
	/** Present only for a struct/object exported from a sealed declaration. */
	val enclosingTypeName: String? = null,
) : DeclaredTypeRef {
	init {
		require(name.isNotBlank()) { "A symbol name cannot be blank" }
		require(enclosingTypeName == null || kind == SymbolKind.STRUCT || kind == SymbolKind.OBJECT) {
			"Only a nested struct or object may have an enclosing type identity"
		}
	}
}

/** Exact identity for a declaration nested under a sealed parent. */
data class NestedSymbolRef(
	val parent: SymbolRef,
	val kind: SymbolKind,
	val name: String,
) : DeclaredTypeRef {
	init {
		require(parent.kind == SymbolKind.SEALED) { "A nested sealed symbol must identify its sealed parent" }
		require(kind == SymbolKind.STRUCT || kind == SymbolKind.OBJECT) {
			"A nested sealed symbol must identify a struct or object"
		}
		require(name.isNotBlank()) { "A nested sealed symbol name cannot be blank" }
	}
}

@JvmInline
value class FunctionRef(val symbol: SymbolRef) {
	init {
		require(symbol.kind == SymbolKind.FUNCTION) { "A function reference must identify a function" }
	}
}

enum class IRVisibility {
	PRIVATE,
	INTERNAL,
	PUBLIC,
}

enum class IRModifier {
	ASYNC,
	OVERRIDE,
	OPERATOR,
	INLINE,
	STATIC,
	INFIX,
}

private val IR_BUILTIN_TYPE_NAMES = setOf(
	"Any", "Unit", "Boolean", "String", "Char", "Future",
	"U8", "U16", "U32", "U64", "I8", "I16", "I32", "I64", "F32", "F64",
)

sealed interface IRType {
	data object Implicit : IRType
	data class Result(val value: IRType) : IRType
	data class Array(val element: IRType) : IRType
	data class Builtin(val name: String) : IRType {
		init {
			require(name in IR_BUILTIN_TYPE_NAMES) { "Unknown Crescent builtin type '$name'" }
		}
	}
	data class Declared(val symbol: DeclaredTypeRef) : IRType {
		init {
			val kind = when (symbol) {
				is SymbolRef -> symbol.kind
				is NestedSymbolRef -> symbol.kind
			}
			require(kind in setOf(SymbolKind.STRUCT, SymbolKind.SEALED, SymbolKind.TRAIT, SymbolKind.OBJECT, SymbolKind.ENUM)) {
				"A declared IR type must identify a type declaration"
			}
		}
	}
}

data class SourceUnitIR(
	val source: SourceId,
	val imports: Map<String, SymbolRef>,
	val structs: List<StructIR>,
	val sealeds: List<SealedIR>,
	val traits: List<TraitIR>,
	val objects: List<ObjectIR>,
	val enums: List<EnumIR>,
	val globals: List<GlobalIR>,
	val functions: List<FunctionIR>,
	val implementations: List<ImplementationIR>,
) {
	init {
		require(imports.keys.none(String::isBlank)) { "Imported names cannot be blank" }
		val symbols = buildList {
			addAll(structs.map(StructIR::symbol))
			addAll(sealeds.map(SealedIR::symbol))
			addAll(traits.map(TraitIR::symbol))
			addAll(objects.map(ObjectIR::symbol))
			addAll(enums.map(EnumIR::symbol))
			addAll(globals.map(GlobalIR::symbol))
			addAll(functions.map(FunctionIR::symbol))
		}
		require(symbols.all { it.source == source }) { "Every source-unit declaration must use its source identity" }
		require(symbols.distinct().size == symbols.size) { "A source unit cannot contain duplicate symbols" }
	}
}

data class ParameterIR(
	val slot: Int,
	val name: String,
	val type: IRType,
	val defaultValue: IRExpression?,
) {
	init {
		require(slot >= 0) { "Parameter slots cannot be negative" }
		require(name.isNotBlank()) { "Parameter names cannot be blank" }
	}
}

data class FunctionIR(
	val symbol: SymbolRef,
	/** Null for a source-level function; otherwise identifies its declaring holder type. */
	val owner: IRType?,
	val visibility: IRVisibility,
	val modifiers: Set<IRModifier>,
	val parameters: List<ParameterIR>,
	val returnType: IRType,
	val body: IRBlock,
) {
	/** Owner-qualified identity; method caches must never key on [symbol] alone. */
	val identity: FunctionIdentityIR get() = FunctionIdentityIR(symbol, owner)
	init {
		require(symbol.kind == SymbolKind.FUNCTION) { "A function declaration must use a function symbol" }
		require(parameters.map(ParameterIR::slot).distinct().size == parameters.size) {
			"Function parameter slots must be unique"
		}
	}
}

data class FunctionIdentityIR(val symbol: SymbolRef, val owner: IRType?)

data class GlobalIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val type: IRType,
	val mutable: Boolean,
	val initializer: IRExpression,
) {
	init {
		require(symbol.kind == SymbolKind.GLOBAL || symbol.kind == SymbolKind.CONSTANT) {
			"A global declaration must use a global or constant symbol"
		}
	}
}

data class FieldIR(
	val name: String,
	val type: IRType,
	val mutable: Boolean,
	val visibility: IRVisibility,
	val initializer: IRExpression?,
) {
	init {
		require(name.isNotBlank()) { "Field names cannot be blank" }
	}
}

data class StructIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val fields: List<FieldIR>,
) {
	init {
		require(symbol.kind == SymbolKind.STRUCT) { "A struct declaration must use a struct symbol" }
	}
}

data class SealedIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val structs: List<SealedStructIR>,
	val objects: List<SealedObjectIR>,
) {
	init {
		require(symbol.kind == SymbolKind.SEALED) { "A sealed declaration must use a sealed symbol" }
	}
}

data class SealedStructIR(
	val symbol: NestedSymbolRef,
	/** Effective visibility: the stricter of the outer sealed and inner declaration. */
	val visibility: IRVisibility,
	val fields: List<FieldIR>,
) {
	init {
		require(symbol.kind == SymbolKind.STRUCT) { "A sealed struct must use a nested struct identity" }
	}
}

data class SealedObjectIR(
	val symbol: NestedSymbolRef,
	/** Effective visibility: the stricter of the outer sealed and inner declaration. */
	val visibility: IRVisibility,
	val fields: List<FieldIR>,
	val functions: List<FunctionIR>,
) {
	init {
		require(symbol.kind == SymbolKind.OBJECT) { "A sealed object must use a nested object identity" }
	}
}

data class FunctionSignatureIR(
	val name: String,
	val parameters: List<IRType>,
	val returnType: IRType,
	val parameterDefaults: List<Boolean> = List(parameters.size) { false },
) {
	init {
		require(name.isNotBlank()) { "Function signature names cannot be blank" }
		require(parameterDefaults.size == parameters.size) { "Trait parameter default metadata must match its parameter list" }
	}
}

data class TraitIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val functions: List<FunctionSignatureIR>,
) {
	init {
		require(symbol.kind == SymbolKind.TRAIT) { "A trait declaration must use a trait symbol" }
	}
}

data class ObjectIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val fields: List<FieldIR>,
	val functions: List<FunctionIR>,
) {
	init {
		require(symbol.kind == SymbolKind.OBJECT) { "An object declaration must use an object symbol" }
	}
}

data class EnumEntryIR(
	val name: String,
	val arguments: List<IRExpression>,
) {
	init {
		require(name.isNotBlank()) { "Enum entry names cannot be blank" }
	}
}

data class EnumIR(
	val symbol: SymbolRef,
	val visibility: IRVisibility,
	val parameters: List<ParameterIR>,
	val entries: List<EnumEntryIR>,
) {
	init {
		require(symbol.kind == SymbolKind.ENUM) { "An enum declaration must use an enum symbol" }
	}
}

data class ImplementationIR(
	val target: IRType,
	val modifiers: Set<IRModifier>,
	val extendedTypes: List<IRType>,
	val functions: List<FunctionIR>,
)

data class IRBlock(val statements: List<IRStatement>)

sealed interface IRStatement {
	data class Evaluate(val expression: IRExpression) : IRStatement
	data class Declare(
		val slot: Int,
		val name: String,
		val type: IRType,
		val mutable: Boolean,
		val initializer: IRExpression,
	) : IRStatement
	data class Return(val value: IRExpression?) : IRStatement
	data class If(val predicate: IRExpression, val thenBlock: IRBlock, val elseBlock: IRBlock?) : IRStatement
	data class While(val predicate: IRExpression, val body: IRBlock) : IRStatement
	data class For(
		val counters: List<LoopCounterIR>,
		val ranges: List<RangeIR>,
		val body: IRBlock,
	) : IRStatement
	data class When(
		val subject: IRExpression,
		val subjectBinding: LoopCounterIR?,
		val clauses: List<WhenClauseIR>,
	) : IRStatement
	data object Break : IRStatement
	data object Continue : IRStatement
	data class NestedBlock(val block: IRBlock) : IRStatement
}

data class LoopCounterIR(val slot: Int, val name: String)
data class RangeIR(val start: IRExpression, val endInclusive: IRExpression)

sealed interface WhenPredicateIR {
	data class Value(val expression: IRExpression) : WhenPredicateIR
	data class EnumEntry(val name: String) : WhenPredicateIR
	data object Else : WhenPredicateIR
}

data class WhenClauseIR(val predicate: WhenPredicateIR, val body: IRBlock)

sealed interface IRLiteral {
	data object Unit : IRLiteral
	data class Boolean(val value: kotlin.Boolean) : IRLiteral
	data class String(val value: kotlin.String) : IRLiteral
	data class Char(val value: kotlin.Char) : IRLiteral
	data class U8(val value: UByte) : IRLiteral
	data class U16(val value: UShort) : IRLiteral
	data class U32(val value: UInt) : IRLiteral
	data class U64(val value: ULong) : IRLiteral
	data class I8(val value: Byte) : IRLiteral
	data class I16(val value: Short) : IRLiteral
	data class I32(val value: Int) : IRLiteral
	data class I64(val value: Long) : IRLiteral
	data class F32(val value: Float) : IRLiteral
	data class F64(val value: Double) : IRLiteral
}

sealed interface VariableRefIR {
	data class Local(val slot: Int, val name: String) : VariableRefIR
	data class Global(val symbol: SymbolRef) : VariableRefIR
}

sealed interface AssignmentTargetIR {
	data class Variable(val reference: VariableRefIR) : AssignmentTargetIR
	data class Index(val receiver: IRExpression, val index: IRExpression) : AssignmentTargetIR
	data class Member(val receiver: IRExpression, val name: String) : AssignmentTargetIR
}

enum class IRUnaryOperation {
	NEGATE,
	NOT,
}

enum class IRBinaryOperation {
	ADD,
	SUBTRACT,
	MULTIPLY,
	DIVIDE,
	POWER,
	REMAINDER,
	EQUAL,
	NOT_EQUAL,
	REFERENCE_EQUAL,
	REFERENCE_NOT_EQUAL,
	LESS,
	LESS_OR_EQUAL,
	GREATER,
	GREATER_OR_EQUAL,
	CONTAINS,
	NOT_CONTAINS,
	RANGE_TO,
	SHIFT_LEFT,
	SHIFT_RIGHT,
	UNSIGNED_SHIFT_RIGHT,
	BIT_OR,
	BIT_AND,
	BIT_XOR,
}

enum class IRCompoundOperation {
	ADD,
	SUBTRACT,
	MULTIPLY,
	DIVIDE,
	POWER,
	REMAINDER,
}

sealed interface CallTargetIR {
	data class Builtin(val name: String) : CallTargetIR
	data class Function(val function: FunctionRef) : CallTargetIR
	data class Member(val receiver: IRExpression, val name: String) : CallTargetIR
	data class Constructor(val type: SymbolRef) : CallTargetIR
}

sealed interface IRExpression {
	data object This : IRExpression
	data class Literal(val value: IRLiteral) : IRExpression
	data class Array(val elements: List<IRExpression>) : IRExpression
	data class Variable(val reference: VariableRefIR) : IRExpression
	data class TypeValue(val type: IRType) : IRExpression
	data class Call(val target: CallTargetIR, val arguments: List<IRExpression>) : IRExpression
	data class Index(val receiver: IRExpression, val arguments: List<IRExpression>) : IRExpression
	data class Member(val receiver: IRExpression, val name: String) : IRExpression
	data class Unary(val operation: IRUnaryOperation, val operand: IRExpression) : IRExpression
	data class Binary(
		val operation: IRBinaryOperation,
		val left: IRExpression,
		val right: IRExpression,
	) : IRExpression
	data class LogicalAnd(val left: IRExpression, val right: IRExpression) : IRExpression
	data class LogicalOr(val left: IRExpression, val right: IRExpression) : IRExpression
	data class Assign(val target: AssignmentTargetIR, val value: IRExpression) : IRExpression
	data class CompoundAssign(
		val target: AssignmentTargetIR,
		val operation: IRCompoundOperation,
		val value: IRExpression,
	) : IRExpression
	data class Cast(val value: IRExpression, val targetType: IRType) : IRExpression
	data class TypeTest(val value: IRExpression, val targetType: IRType, val negated: Boolean) : IRExpression
	data class PropagateResult(val value: IRExpression) : IRExpression
	data class Conditional(val statement: IRStatement.If) : IRExpression
}

/** Defensive graph snapshot for public, manually assembled IR values. */
fun CrescentProgramIR.deepSnapshot(): CrescentProgramIR = copy(sourceUnits = sourceUnits.map(SourceUnitIR::snapshot))

private fun SourceUnitIR.snapshot() = copy(
	imports = imports.toMap(), structs = structs.map { it.copy(fields = it.fields.map(FieldIR::snapshot)) },
	sealeds = sealeds.map { it.copy(
		structs = it.structs.map { nested -> nested.copy(fields = nested.fields.map(FieldIR::snapshot)) },
		objects = it.objects.map { nested -> nested.copy(fields = nested.fields.map(FieldIR::snapshot), functions = nested.functions.map(FunctionIR::snapshot)) },
	) },
	traits = traits.map { it.copy(functions = it.functions.map { signature -> signature.copy(parameters = signature.parameters.toList(), parameterDefaults = signature.parameterDefaults.toList()) }) },
	objects = objects.map { it.copy(fields = it.fields.map(FieldIR::snapshot), functions = it.functions.map(FunctionIR::snapshot)) },
	enums = enums.map { it.copy(parameters = it.parameters.map(ParameterIR::snapshot), entries = it.entries.map { entry -> entry.copy(arguments = entry.arguments.map(IRExpression::snapshot)) }) },
	globals = globals.map { it.copy(initializer = it.initializer.snapshot()) },
	functions = functions.map(FunctionIR::snapshot),
	implementations = implementations.map { it.copy(modifiers = it.modifiers.toSet(), extendedTypes = it.extendedTypes.toList(), functions = it.functions.map(FunctionIR::snapshot)) },
)

private fun ParameterIR.snapshot() = copy(defaultValue = defaultValue?.snapshot())
private fun FieldIR.snapshot() = copy(initializer = initializer?.snapshot())
private fun FunctionIR.snapshot() = copy(modifiers = modifiers.toSet(), parameters = parameters.map(ParameterIR::snapshot), body = body.snapshot())
private fun IRBlock.snapshot() = copy(statements = statements.map(IRStatement::snapshot))
private fun IRStatement.snapshot(): IRStatement = when (this) {
	is IRStatement.Evaluate -> copy(expression = expression.snapshot())
	is IRStatement.Declare -> copy(initializer = initializer.snapshot())
	is IRStatement.Return -> copy(value = value?.snapshot())
	is IRStatement.If -> copy(predicate = predicate.snapshot(), thenBlock = thenBlock.snapshot(), elseBlock = elseBlock?.snapshot())
	is IRStatement.While -> copy(predicate = predicate.snapshot(), body = body.snapshot())
	is IRStatement.For -> copy(counters = counters.toList(), ranges = ranges.map { it.copy(start = it.start.snapshot(), endInclusive = it.endInclusive.snapshot()) }, body = body.snapshot())
	is IRStatement.When -> copy(subject = subject.snapshot(), clauses = clauses.map { clause -> clause.copy(
		predicate = when (val predicate = clause.predicate) { is WhenPredicateIR.Value -> predicate.copy(expression = predicate.expression.snapshot()); else -> predicate },
		body = clause.body.snapshot(),
	) })
	IRStatement.Break, IRStatement.Continue -> this
	is IRStatement.NestedBlock -> copy(block = block.snapshot())
}
private fun AssignmentTargetIR.snapshot(): AssignmentTargetIR = when (this) {
	is AssignmentTargetIR.Variable -> this
	is AssignmentTargetIR.Index -> copy(receiver = receiver.snapshot(), index = index.snapshot())
	is AssignmentTargetIR.Member -> copy(receiver = receiver.snapshot())
}
private fun CallTargetIR.snapshot(): CallTargetIR = when (this) {
	is CallTargetIR.Builtin, is CallTargetIR.Function, is CallTargetIR.Constructor -> this
	is CallTargetIR.Member -> copy(receiver = receiver.snapshot())
}
private fun IRExpression.snapshot(): IRExpression = when (this) {
	IRExpression.This, is IRExpression.Literal, is IRExpression.Variable, is IRExpression.TypeValue -> this
	is IRExpression.Array -> copy(elements = elements.map(IRExpression::snapshot))
	is IRExpression.Call -> copy(target = target.snapshot(), arguments = arguments.map(IRExpression::snapshot))
	is IRExpression.Index -> copy(receiver = receiver.snapshot(), arguments = arguments.map(IRExpression::snapshot))
	is IRExpression.Member -> copy(receiver = receiver.snapshot())
	is IRExpression.Unary -> copy(operand = operand.snapshot())
	is IRExpression.Binary -> copy(left = left.snapshot(), right = right.snapshot())
	is IRExpression.LogicalAnd -> copy(left = left.snapshot(), right = right.snapshot())
	is IRExpression.LogicalOr -> copy(left = left.snapshot(), right = right.snapshot())
	is IRExpression.Assign -> copy(target = target.snapshot(), value = value.snapshot())
	is IRExpression.CompoundAssign -> copy(target = target.snapshot(), value = value.snapshot())
	is IRExpression.Cast -> copy(value = value.snapshot())
	is IRExpression.TypeTest -> copy(value = value.snapshot())
	is IRExpression.PropagateResult -> copy(value = value.snapshot())
	is IRExpression.Conditional -> copy(statement = statement.snapshot() as IRStatement.If)
}
