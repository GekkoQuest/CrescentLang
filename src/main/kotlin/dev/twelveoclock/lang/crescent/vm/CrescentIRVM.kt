package dev.twelveoclock.lang.crescent.vm

import dev.twelveoclock.lang.crescent.language.ir.*
import dev.twelveoclock.lang.crescent.project.extensions.minimize
import java.util.LinkedList
import java.util.IdentityHashMap
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CrescentIRExecutionException(message: String, cause: Throwable? = null) :
	IllegalStateException(message, cause)

/** Waits without making Crescent `await` sensitive to the caller's interrupt timing. */
internal fun <T> awaitFutureUninterruptibly(future: Future<T>): T {
	var interrupted = false
	try {
		while (true) {
			try {
				return future.get()
			} catch (_: InterruptedException) {
				interrupted = true
			}
		}
	} catch (exception: java.util.concurrent.ExecutionException) {
		throw exception.cause ?: exception
	} finally {
		if (interrupted) Thread.currentThread().interrupt()
	}
}

/**
 * Executes both supported Crescent IR forms.
 *
 * A single [CrescentIR.Command.Program] is interpreted from its AST-free lowered
 * graph. All other commands belong to the serialized legacy stack IR. Legacy
 * integral arithmetic remains exact when its result is integral; fractional
 * division and mixed floating-point operations use JVM [Double] semantics.
 * Bitwise operators require integral operands, use 64-bit operations when a
 * [Long] participates, and otherwise retain their historical 32-bit width;
 * shifts use the width of their left operand and reject counts outside that
 * width (0..31 for 32-bit values and 0..63 for [Long] values).
 */
class CrescentIRVM @JvmOverloads constructor(
	crescentIR: CrescentIR,
	private val io: RuntimeIO = RuntimeIO.system(),
) : AutoCloseable {

	private val frozenIR = CrescentIR(crescentIR.commands.map { command ->
		when (command) {
			is CrescentIR.Command.Program -> command.copy(program = command.program.deepSnapshot())
			is CrescentIR.Command.Push -> command.also {
				check(it.value is String || it.value is Char || it.value is Boolean || it.value is Byte || it.value is Short ||
					it.value is Int || it.value is Long || it.value is Float && it.value.isFinite() || it.value is Double && it.value.isFinite()) {
					"Legacy Push accepts only immutable serializable literal values"
				}
			}
			else -> command
		}
	})
	private val sectionedCrescentIR = SectionedCrescentIR.from(frozenIR)
	private val program = frozenIR.commands.singleOrNull() as? CrescentIR.Command.Program
	private val lifecycleLock = Any()
	private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
	private val programExecution = program?.let { ProgramExecution(it.program, ::submit, io) }
	@Volatile private var closed = false

	fun invoke(args: List<String> = emptyList()) {
		synchronized(lifecycleLock) { check(!closed) { "CrescentIRVM is closed" } }
		programExecution?.let {
			it.invoke(args)
			return
		}

		runFunction("main", LinkedList(), mapOf("args" to args))
	}

	private fun <T> submit(action: () -> T): Future<T> = synchronized(lifecycleLock) {
		check(!closed) { "CrescentIRVM is closed" }
		executor.submit<T> { action() }
	}

	override fun close() {
		val shutdown = synchronized(lifecycleLock) {
			if (closed) false else { closed = true; true }
		}
		if (shutdown) executor.shutdown()
	}

	fun runFunction(
		name: String,
		stack: LinkedList<Any>,
		namedValuesInput: Map<String, Any> = emptyMap(),
	) {
		val functions = sectionedCrescentIR.sections[SectionedCrescentIR.Section.FUNCTION]
			?: throw CrescentIRExecutionException("Legacy IR does not define any functions")
		val functionCode = functions[name]
			?: throw CrescentIRExecutionException("Legacy IR does not define function '$name'")
		val namedValues = namedValuesInput.toMutableMap()

		fun failure(index: Int, command: CrescentIR.Command, detail: String, cause: Throwable? = null): Nothing {
			throw CrescentIRExecutionException(
				"Legacy IR function '$name', command ${index + 1} ('$command'): $detail",
				cause,
			)
		}

		fun pop(index: Int, command: CrescentIR.Command): Any {
			if (stack.isEmpty()) failure(index, command, "stack underflow")
			return stack.pop()
		}

		fun number(value: Any, index: Int, command: CrescentIR.Command): Number = value as? Number
			?: failure(index, command, "expected a number, found ${value::class.simpleName}")

		fun boolean(value: Any, index: Int, command: CrescentIR.Command): Boolean = value as? Boolean
			?: failure(index, command, "expected a boolean, found ${value::class.simpleName}")

		fun variableName(value: Any, index: Int, command: CrescentIR.Command): String {
			val variableName = value as? String
				?: failure(index, command, "expected a variable name, found ${value::class.simpleName}")
			if (variableName.isBlank()) failure(index, command, "variable name cannot be blank")
			return variableName
		}

		fun numericOperands(index: Int, command: CrescentIR.Command): Pair<Number, Number> {
			val right = number(pop(index, command), index, command)
			val left = number(pop(index, command), index, command)
			return left to right
		}

		fun Number.isIntegral(): Boolean = this is Byte || this is Short || this is Int || this is Long

		fun integralOperands(index: Int, command: CrescentIR.Command): Pair<Number, Number> {
			val (left, right) = numericOperands(index, command)
			if (!left.isIntegral() || !right.isIntegral()) {
				failure(index, command, "expected integral operands")
			}
			return left to right
		}

		fun shiftCount(left: Number, right: Number, index: Int, command: CrescentIR.Command): Int {
			val maximum = if (left is Long) 63 else 31
			val count = right.toLong()
			if (count !in 0L..maximum.toLong()) {
				failure(index, command, "shift count $count is outside the valid range 0..$maximum")
			}
			return count.toInt()
		}

		fun exactIntegral(
			left: Number,
			right: Number,
			index: Int,
			command: CrescentIR.Command,
			operation: (Long, Long) -> Long,
		): Number = try {
			operation(left.toLong(), right.toLong()).minimize()
		} catch (exception: ArithmeticException) {
			failure(index, command, "integer overflow", exception)
		}

		fun addNumbers(left: Number, right: Number, index: Int, command: CrescentIR.Command): Number =
			if (left.isIntegral() && right.isIntegral()) {
				exactIntegral(left, right, index, command, Math::addExact)
			} else {
				(left.toDouble() + right.toDouble()).minimize()
			}

		fun compareNumbers(left: Number, right: Number): Int =
			if (left.isIntegral() && right.isIntegral()) {
				left.toLong().compareTo(right.toLong())
			} else {
				left.toDouble().compareTo(right.toDouble())
			}

		fun booleanOperands(index: Int, command: CrescentIR.Command): Pair<Boolean, Boolean> {
			val right = boolean(pop(index, command), index, command)
			val left = boolean(pop(index, command), index, command)
			return left to right
		}

		fun jumpTarget(position: Int, index: Int, command: CrescentIR.Command): Int {
			if (position !in 1..functionCode.size + 1) {
				failure(
					index,
					command,
					"jump target $position is outside the section range 1..${functionCode.size + 1}",
				)
			}
			// Legacy positions include the section marker, which is removed by sectioning.
			return position - 1
		}

		var index = 0
		while (index < functionCode.size) {
			val command = functionCode[index]
			when (command) {
				is CrescentIR.Command.Push -> stack.push(command.value)

				is CrescentIR.Command.PushNamedValue -> {
					val value = namedValues[command.name]
						?: failure(index, command, "unknown named value '${command.name}'")
					stack.push(value)
				}

				CrescentIR.Command.Add -> {
					val right = pop(index, command)
					val left = pop(index, command)
					if (left is Number && right is Number) {
						stack.push(addNumbers(left, right, index, command))
					} else {
						stack.push(left.toString() + right.toString())
					}
				}

				CrescentIR.Command.Sub -> {
					val (left, right) = numericOperands(index, command)
					stack.push(if (left.isIntegral() && right.isIntegral()) {
						exactIntegral(left, right, index, command, Math::subtractExact)
					} else {
						(left.toDouble() - right.toDouble()).minimize()
					})
				}

				CrescentIR.Command.Mul -> {
					val (left, right) = numericOperands(index, command)
					stack.push(if (left.isIntegral() && right.isIntegral()) {
						exactIntegral(left, right, index, command, Math::multiplyExact)
					} else {
						(left.toDouble() * right.toDouble()).minimize()
					})
				}

				CrescentIR.Command.Div -> {
					val (left, right) = numericOperands(index, command)
					if (right.toDouble() == 0.0) failure(index, command, "division by zero")
					if (left.isIntegral() && right.isIntegral() &&
						left.toLong() == Long.MIN_VALUE && right.toLong() == -1L
					) {
						failure(index, command, "integer overflow")
					}
					val exactIntegralDivision = left.isIntegral() && right.isIntegral() &&
						left.toLong() % right.toLong() == 0L
					stack.push(if (exactIntegralDivision) {
						(left.toLong() / right.toLong()).minimize()
					} else {
						(left.toDouble() / right.toDouble()).minimize()
					})
				}

				CrescentIR.Command.Rem -> {
					val (left, right) = numericOperands(index, command)
					if (right.toDouble() == 0.0) failure(index, command, "remainder by zero")
					stack.push(if (left.isIntegral() && right.isIntegral()) {
						(left.toLong() % right.toLong()).minimize()
					} else {
						(left.toDouble() % right.toDouble()).minimize()
					})
				}

				CrescentIR.Command.Or -> {
					val (left, right) = integralOperands(index, command)
					stack.push(if (left is Long || right is Long) {
						(left.toLong() or right.toLong()).minimize()
					} else {
						left.toInt() or right.toInt()
					})
				}

				CrescentIR.Command.Xor -> {
					val (left, right) = integralOperands(index, command)
					stack.push(if (left is Long || right is Long) {
						(left.toLong() xor right.toLong()).minimize()
					} else {
						left.toInt() xor right.toInt()
					})
				}

				CrescentIR.Command.And -> {
					val (left, right) = integralOperands(index, command)
					stack.push(if (left is Long || right is Long) {
						(left.toLong() and right.toLong()).minimize()
					} else {
						left.toInt() and right.toInt()
					})
				}

				CrescentIR.Command.ShiftLeft -> {
					val (left, right) = integralOperands(index, command)
					val count = shiftCount(left, right, index, command)
					stack.push(if (left is Long) {
						(left shl count).minimize()
					} else {
						left.toInt() shl count
					})
				}

				CrescentIR.Command.ShiftRight -> {
					val (left, right) = integralOperands(index, command)
					val count = shiftCount(left, right, index, command)
					stack.push(if (left is Long) {
						(left shr count).minimize()
					} else {
						left.toInt() shr count
					})
				}

				CrescentIR.Command.UnsignedShiftRight -> {
					val (left, right) = integralOperands(index, command)
					val count = shiftCount(left, right, index, command)
					stack.push(if (left is Long) {
						(left ushr count).minimize()
					} else {
						left.toInt() ushr count
					})
				}

				CrescentIR.Command.IsLesser -> {
					val (left, right) = numericOperands(index, command)
					stack.push(compareNumbers(left, right) < 0)
				}

				CrescentIR.Command.IsGreater -> {
					val (left, right) = numericOperands(index, command)
					stack.push(compareNumbers(left, right) > 0)
				}

				CrescentIR.Command.IsLesserOrEqual -> {
					val (left, right) = numericOperands(index, command)
					stack.push(compareNumbers(left, right) <= 0)
				}

				CrescentIR.Command.IsGreaterOrEqual -> {
					val (left, right) = numericOperands(index, command)
					stack.push(compareNumbers(left, right) >= 0)
				}

				CrescentIR.Command.IsEqual -> {
					val right = pop(index, command)
					val left = pop(index, command)
					stack.push(if (left is Number && right is Number) {
						compareNumbers(left, right) == 0
					} else {
						left == right
					})
				}

				CrescentIR.Command.IsNotEqual -> {
					val right = pop(index, command)
					val left = pop(index, command)
					stack.push(if (left is Number && right is Number) {
						compareNumbers(left, right) != 0
					} else {
						left != right
					})
				}

				CrescentIR.Command.AndCompare -> {
					val (left, right) = booleanOperands(index, command)
					stack.push(left && right)
				}

				CrescentIR.Command.OrCompare -> {
					val (left, right) = booleanOperands(index, command)
					stack.push(left || right)
				}

				CrescentIR.Command.Assign -> {
					val target = variableName(pop(index, command), index, command)
					namedValues[target] = pop(index, command)
				}

				CrescentIR.Command.AddAssign -> {
					val target = variableName(pop(index, command), index, command)
					val current = namedValues[target]
						?: failure(index, command, "unknown named value '$target'")
					val right = pop(index, command)
					namedValues[target] = if (current is Number && right is Number) {
						addNumbers(current, right, index, command)
					} else {
						current.toString() + right.toString()
					}
				}

				is CrescentIR.Command.Jump -> {
					index = jumpTarget(command.position, index, command)
					continue
				}

				is CrescentIR.Command.JumpIf -> {
					if (boolean(pop(index, command), index, command)) {
						index = jumpTarget(command.position, index, command)
						continue
					}
				}

				is CrescentIR.Command.JumpIfFalse -> {
					if (!boolean(pop(index, command), index, command)) {
						index = jumpTarget(command.position, index, command)
						continue
					}
				}

				is CrescentIR.Command.Invoke -> when (command.name) {
					"print" -> io.print(pop(index, command).toString())
					"println" -> io.println(pop(index, command).toString())
					"readLine" -> stack.push(
						io.readLine() ?: failure(index, command, "readLine reached end of input"),
					)
					else -> runFunction(command.name, stack)
				}

				is CrescentIR.Command.LoadLibrary -> failure(
					index,
					command,
					"loadLibrary is retained for serialization compatibility but has no legacy runtime implementation",
				)

				is CrescentIR.Command.CreateInstance -> failure(
					index,
					command,
					"createInstance is retained for serialization compatibility but has no legacy runtime implementation",
				)

				is CrescentIR.Command.Fun,
				is CrescentIR.Command.Struct,
				is CrescentIR.Command.Program,
				-> failure(index, command, "section marker is not executable")
			}

			index++
		}
	}
}

private class ProgramExecution(
	private val program: CrescentProgramIR,
	private val submit: (() -> Any?) -> Future<Any?>,
	private val io: RuntimeIO,
) {
	private object UnitValue
	private data class TypeValue(val type: IRType)
	private data class ResultValue(val successful: Boolean, val value: Any?, val declaredType: IRType.Result)
	private data class FutureValue(val future: Future<Any?>)
	private inner class ArrayValue(values: List<Any?>, private var elementType: IRType? = null) {
		private val lock = Any()
		private val values = values.toMutableList()
		fun get(index: Int): Any? = synchronized(lock) { values.getOrElse(index) { error("Array index $index is out of bounds for size ${values.size}") } }
		fun set(index: Int, value: Any?) = synchronized(lock) { values.set(index, normalizeElement(value)) }
		fun update(index: Int, operation: (Any?) -> Any?): Any? = synchronized(lock) {
			val next = normalizeElement(operation(values.getOrElse(index) { error("Array index $index is out of bounds for size ${values.size}") }))
			values[index] = next
			next
		}
		fun size(): Int = synchronized(lock) { values.size }
		fun snapshot(): List<Any?> = synchronized(lock) { values.toList() }
		fun constrain(type: IRType): ArrayValue = synchronized(lock) {
			elementType = elementType ?: type
			for (index in values.indices) values[index] = normalizeElement(values[index])
			this
		}
		private fun normalizeElement(value: Any?): Any? = elementType?.let { coerce(it, value, "Array element") } ?: value
	}
	private open inner class Cell(
		initial: Any?, val mutable: Boolean = true, private val normalize: (Any?) -> Any? = { it },
		private val declarationSource: SourceId? = null, private val visibility: IRVisibility = IRVisibility.PRIVATE,
	) {
		open val supportsCompound: Boolean = true
		private val lock = Any()
		private var value = normalize(initial)
		open fun get(): Any? = synchronized(lock) { value }
		open fun set(newValue: Any?) = synchronized(lock) { check(mutable) { "Cannot assign to an immutable binding" }; value = normalize(newValue) }
		open fun update(operation: (Any?) -> Any?): Any? = synchronized(lock) {
			check(mutable) { "Cannot assign to an immutable binding" }
			normalize(operation(value)).also { value = it }
		}
		fun requireAccess(caller: SourceId) {
			val declared = declarationSource ?: return
			check(visible(visibility, declared, caller)) { "${visibility.name.lowercase()} member is not accessible from ${caller.sourcePath}" }
		}
	}
	private data class StructValue(val type: IRType.Declared, val fields: Map<String, Cell>)
	private data class RuntimeStructDecl(val visibility: IRVisibility, val fields: List<FieldIR>)
	private data class RuntimeObjectDecl(val symbol: DeclaredTypeRef, val visibility: IRVisibility, val fields: List<FieldIR>, val functions: List<FunctionIR>)
	private data class ObjectValue(val type: IRType.Declared, val declaration: RuntimeObjectDecl, val fields: Map<String, Cell>)
	private data class EnumValue(val type: IRType.Declared, val entry: String, val fields: Map<String, Cell>)
	private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
	private object BreakSignal : RuntimeException(null, null, false, false)
	private object ContinueSignal : RuntimeException(null, null, false, false)
	private class PropagateSignal(val result: ResultValue) : RuntimeException(null, null, false, false)
	private inner class InitSlot<T>(private val description: String) {
		private val lock = ReentrantLock()
		private val ready = lock.newCondition()
		private var owner: Thread? = null
		private var completed: Result<T>? = null
		fun get(initializer: () -> T): T {
			lock.withLock {
				while (true) {
					completed?.let { return it.getOrThrow() }
					if (owner == null) { owner = Thread.currentThread(); break }
					val current = Thread.currentThread()
					check(owner !== current) { "Cyclic initialization of $description" }
					synchronized(initializationLock) {
						var cursor = owner
						while (cursor != null) { check(cursor !== current) { "Cross-thread initialization cycle involving $description" }; cursor = waitEdges[cursor] }
						waitEdges[current] = checkNotNull(owner)
					}
					try { ready.await() } finally { synchronized(initializationLock) { waitEdges.remove(current) } }
				}
			}
			val result = runCatching {
				activeInitialization.set(activeInitialization.get() + 1)
				try { initializer() } finally { activeInitialization.set(activeInitialization.get() - 1) }
			}
			lock.withLock { completed = result; owner = null; ready.signalAll() }
			return result.getOrThrow()
		}
	}

	private data class Frame(
		val parent: Frame?,
		val source: SourceId = parent?.source ?: error("A root frame requires a source identity"),
		val holder: Any? = parent?.holder,
		val cells: MutableMap<Int, Cell> = linkedMapOf(),
	) {
		fun cell(slot: Int): Cell = cells[slot] ?: parent?.cell(slot) ?: error("Unknown local slot $slot")
	}

	private val units = program.sourceUnits.associateBy(SourceUnitIR::source)
	private val functions = program.sourceUnits.flatMap(SourceUnitIR::functions).associateBy(FunctionIR::symbol)
	private val globals = program.sourceUnits.flatMap(SourceUnitIR::globals).associateBy(GlobalIR::symbol)
	private val structs: Map<SymbolRef, RuntimeStructDecl> = buildMap {
		program.sourceUnits.flatMap(SourceUnitIR::structs).forEach { put(it.symbol, RuntimeStructDecl(it.visibility, it.fields)) }
		program.sourceUnits.flatMap(SourceUnitIR::sealeds).forEach { sealed -> sealed.structs.forEach {
			put(SymbolRef(sealed.symbol.source, SymbolKind.STRUCT, it.symbol.name, sealed.symbol.name), RuntimeStructDecl(it.visibility, it.fields))
		} }
	}
	private val objects: Map<SymbolRef, RuntimeObjectDecl> = buildMap {
		program.sourceUnits.flatMap(SourceUnitIR::objects).forEach { put(it.symbol, RuntimeObjectDecl(it.symbol, it.visibility, it.fields, it.functions)) }
		program.sourceUnits.flatMap(SourceUnitIR::sealeds).forEach { sealed -> sealed.objects.forEach {
			val ref = SymbolRef(sealed.symbol.source, SymbolKind.OBJECT, it.symbol.name, sealed.symbol.name)
			put(ref, RuntimeObjectDecl(it.symbol, it.visibility, it.fields, it.functions))
		} }
	}
	private val enums = program.sourceUnits.flatMap(SourceUnitIR::enums).associateBy(EnumIR::symbol)
	private val traits = program.sourceUnits.flatMap(SourceUnitIR::traits).associateBy(TraitIR::symbol)
	private val globalSlots = linkedMapOf<SymbolRef, InitSlot<Cell>>()
	private val objectSlots = linkedMapOf<SymbolRef, InitSlot<ObjectValue>>()
	private val initializationLock = Any()
	private val waitEdges = linkedMapOf<Thread, Thread>()
	private val activeInitialization = ThreadLocal.withInitial { 0 }

	init {
		program.sourceUnits.forEach { unit ->
			fun validate(function: FunctionIR, expectedOwner: IRType?, inImplementation: Boolean = false) {
				check(function.symbol.source == unit.source) { "Function '${function.symbol.name}' forges its source identity" }
				check(function.owner == expectedOwner) { "Function '${function.symbol.name}' has an invalid owner identity" }
				val modifiers = function.modifiers
				check(!(IRModifier.ASYNC in modifiers && modifiers.any { it in setOf(IRModifier.INLINE, IRModifier.OPERATOR, IRModifier.OVERRIDE) })) {
					"async ${function.symbol.name} cannot be inline, operator, or override"
				}
				check(IRModifier.STATIC !in modifiers) { "static is only valid on an impl target" }
				check(inImplementation || modifiers.none { it in setOf(IRModifier.OPERATOR, IRModifier.OVERRIDE, IRModifier.INFIX) }) {
					"operator, override, and infix modifiers are only valid in an impl"
				}
				if (IRModifier.INFIX in modifiers) check(function.parameters.size == 1 && function.parameters.single().defaultValue == null) {
					"infix ${function.symbol.name} requires one non-defaulted parameter"
				}
			}
			unit.functions.forEach { validate(it, null) }
			unit.objects.forEach { declaration ->
				val owner = IRType.Declared(declaration.symbol)
				declaration.functions.forEach { validate(it, owner) }
			}
			unit.sealeds.forEach { sealed ->
				sealed.structs.forEach { declaration ->
					check(declaration.symbol.parent == sealed.symbol) { "Nested struct '${declaration.symbol.name}' forges its parent identity" }
				}
				sealed.objects.forEach { declaration ->
					check(declaration.symbol.parent == sealed.symbol) { "Nested object '${declaration.symbol.name}' forges its parent identity" }
					val owner = IRType.Declared(declaration.symbol)
					declaration.functions.forEach { validate(it, owner) }
				}
			}
			unit.implementations.forEach { implementation ->
				listOf(implementation.target).plus(implementation.extendedTypes).forEach { type ->
					check(type is IRType.Builtin || type is IRType.Declared) { "Implementation target and supertypes must be named types, got $type" }
					if (type is IRType.Declared) {
						val symbol = declaredSymbol(type)
						check(declarationVisibility(symbol) != null) { "Declared implementation type '${symbol.name}' does not resolve to a program declaration" }
					}
				}
				val static = IRModifier.STATIC in implementation.modifiers
				if (static) check(implementation.modifiers == setOf(IRModifier.STATIC)) { "Static implementation must have exactly the static modifier" }
				implementation.functions.forEach { function ->
					validate(function, implementation.target, inImplementation = true)
					if (static) check(function.modifiers.none { it in setOf(IRModifier.OPERATOR, IRModifier.OVERRIDE, IRModifier.INFIX) }) {
						"Static implementation method ${function.symbol.name} cannot be operator, override, or infix"
					}
					if (IRModifier.OPERATOR in function.modifiers) validateOperatorSignature(function, implementation.target, static)
				}
				if (!static) validateTraitImplementation(implementation)
			}
		}
		val main = functions[program.main.symbol] ?: error("Lowered main function is missing")
		check(main.visibility == IRVisibility.PUBLIC) { "main must be public" }
		check(main.modifiers.isEmpty()) { "main cannot have modifiers" }
	}

	private data class TraitRequirement(val trait: TraitIR, val signature: FunctionSignatureIR)

	private fun validateTraitImplementation(implementation: ImplementationIR) {
		val requirements = implementation.extendedTypes.flatMap { collectTraitRequirements(it, linkedSetOf()) }
		for (requirement in requirements) {
			val function = implementation.functions.firstOrNull { signatureMatches(it, requirement.signature) }
				?: error("${implementation.target} must implement ${requirement.trait.symbol.name}.${requirement.signature.name}")
			check(IRModifier.OVERRIDE in function.modifiers) {
				"${implementation.target}.${function.symbol.name} must be declared override"
			}
			check(visibilityRank(function.visibility) <= visibilityRank(requirement.trait.visibility)) {
				"Override ${function.symbol.name} narrows visibility of trait ${requirement.trait.symbol.name}"
			}
		}
		implementation.functions.forEach { function ->
			val matching = requirements.any { signatureMatches(function, it.signature) }
			check(matching == (IRModifier.OVERRIDE in function.modifiers)) {
				"${implementation.target}.${function.symbol.name} ${if (matching) "must" else "must not"} be declared override"
			}
		}
	}

	private fun collectTraitRequirements(type: IRType, active: MutableSet<IRType>): List<TraitRequirement> {
		check(active.add(type)) { "Cyclic trait inheritance involving $type" }
		return try {
			val own = (type as? IRType.Declared)?.let { declared -> traits[declaredSymbol(declared)] }
			val requirements = own?.functions.orEmpty().map { TraitRequirement(checkNotNull(own), it) }.toMutableList()
			program.sourceUnits.asSequence().flatMap { it.implementations.asSequence() }
				.filter { IRModifier.STATIC !in it.modifiers && it.target == type }
				.flatMap { it.extendedTypes.asSequence() }
				.forEach { requirements += collectTraitRequirements(it, active) }
			requirements.distinctBy { it.trait.symbol to it.signature }
		} finally {
			active.remove(type)
		}
	}

	private fun signatureMatches(function: FunctionIR, signature: FunctionSignatureIR): Boolean =
		function.symbol.name == signature.name && function.returnType == signature.returnType &&
			function.parameters.map(ParameterIR::type) == signature.parameters &&
			function.parameters.map { it.defaultValue != null } == signature.parameterDefaults

	private fun visibilityRank(visibility: IRVisibility): Int = when (visibility) {
		IRVisibility.PUBLIC -> 0
		IRVisibility.INTERNAL -> 1
		IRVisibility.PRIVATE -> 2
	}

	private fun validateOperatorSignature(function: FunctionIR, receiver: IRType, static: Boolean) {
		check(!static) { "operator ${function.symbol.name} requires a non-static impl" }
		val arity = when (function.symbol.name) {
			"not", "unaryMinus" -> 0
			"set" -> 2
			"plus", "minus", "times", "div", "rem", "pow", "equals", "compareTo", "contains", "get",
			"bitShiftLeft", "bitShiftRight", "unsignedBitShiftRight", "bitAnd", "bitOr", "bitXor" -> 1
			else -> error("Unsupported operator function ${function.symbol.name}")
		}
		check(function.parameters.size == arity && function.parameters.all { it.defaultValue == null }) {
			"operator ${function.symbol.name} requires $arity non-defaulted parameter(s)"
		}
		when (function.symbol.name) {
			"not", "equals", "contains" -> check(function.returnType == IRType.Builtin("Boolean")) { "operator ${function.symbol.name} must return Boolean" }
			"compareTo" -> check(function.returnType == IRType.Builtin("I32")) { "operator compareTo must return I32" }
			"unaryMinus", "bitShiftLeft", "bitShiftRight", "unsignedBitShiftRight", "bitAnd", "bitOr", "bitXor" ->
				check(sameType(receiver, function.returnType)) { "operator ${function.symbol.name} must return its receiver type" }
			"set" -> check(function.returnType == IRType.Builtin("Unit")) { "operator set must return Unit" }
			else -> check(function.returnType != IRType.Builtin("Unit")) { "operator ${function.symbol.name} must return a value" }
		}
	}

	fun invoke(args: List<String>) {
		val function = functions[program.main.symbol] ?: error("Lowered main function is missing")
		val values = if (function.parameters.isEmpty()) emptyList() else listOf(ArrayValue(args.toList()))
		callFunction(function, values, null)
	}

	private fun callFunction(function: FunctionIR, arguments: List<Any?>, holder: Any?): Any? {
		val action = {
			val result = try {
				check(arguments.size <= function.parameters.size) { "Function ${function.symbol.name} expects at most ${function.parameters.size} arguments, got ${arguments.size}" }
				val frame = Frame(null, function.symbol.source, holder)
				function.parameters.forEachIndexed { index, parameter ->
					val value = arguments.getOrNull(index) ?: parameter.defaultValue?.let { evaluate(it, frame) }
						?: error("Function ${function.symbol.name} is missing argument '${parameter.name}'")
					frame.cells[parameter.slot] = typedCell(value, false, parameter.type, "Parameter ${parameter.name}")
				}
				val blockResult = executeBlock(function.body, frame)
				if (function.returnType == IRType.Builtin("Unit")) UnitValue else blockResult
			} catch (signal: ReturnSignal) {
				signal.value
			} catch (signal: PropagateSignal) {
				check(function.returnType is IRType.Result) { "Result propagation is only valid in a Result-returning function" }
				coerce(function.returnType, signal.result, "Function ${function.symbol.name} return")
			}
			coerce(function.returnType, result, "Function ${function.symbol.name} return")
		}
		return if (IRModifier.ASYNC in function.modifiers) {
			check(activeInitialization.get() == 0) { "Async launch is not allowed during initialization" }
			FutureValue(submit(action))
		} else action()
	}

	private fun executeBlock(block: IRBlock, parent: Frame): Any? {
		val frame = Frame(parent)
		var last: Any? = UnitValue
		block.statements.forEach { last = execute(it, frame) }
		return last
	}

	private fun execute(statement: IRStatement, frame: Frame): Any? = when (statement) {
		is IRStatement.Evaluate -> evaluate(statement.expression, frame)
		is IRStatement.Declare -> {
			val value = evaluate(statement.initializer, frame)
			frame.cells[statement.slot] = typedCell(value, statement.mutable, statement.type, "Variable ${statement.name}")
			UnitValue
		}
		is IRStatement.Return -> throw ReturnSignal(statement.value?.let { evaluate(it, frame) } ?: UnitValue)
		is IRStatement.If -> if (boolean(evaluate(statement.predicate, frame))) executeBlock(statement.thenBlock, frame)
			else statement.elseBlock?.let { executeBlock(it, frame) } ?: UnitValue
		is IRStatement.While -> {
			while (boolean(evaluate(statement.predicate, frame))) {
				try { executeBlock(statement.body, frame) } catch (_: ContinueSignal) { continue } catch (_: BreakSignal) { break }
			}
			UnitValue
		}
		is IRStatement.For -> executeFor(statement, frame)
		is IRStatement.When -> {
			val subject = evaluate(statement.subject, frame)
			val whenFrame = Frame(frame)
			statement.subjectBinding?.let { whenFrame.cells[it.slot] = Cell(subject, false) }
			val clause = statement.clauses.firstOrNull { clause -> when (val predicate = clause.predicate) {
				WhenPredicateIR.Else -> true
				is WhenPredicateIR.EnumEntry -> subject is EnumValue && subject.entry == predicate.name
				is WhenPredicateIR.Value -> equal(subject, evaluate(predicate.expression, whenFrame))
			} }
			clause?.let { executeBlock(it.body, whenFrame) } ?: UnitValue
		}
		IRStatement.Break -> throw BreakSignal
		IRStatement.Continue -> throw ContinueSignal
		is IRStatement.NestedBlock -> executeBlock(statement.block, frame)
	}

	private fun executeFor(statement: IRStatement.For, parent: Frame): Any? {
		check(statement.counters.isNotEmpty()) { "A for loop needs at least one counter" }
		check(statement.ranges.size == 1 || statement.ranges.size == statement.counters.size) { "For ranges must be shared or match counters" }
		val loopFrame = Frame(parent)
		val evaluated = statement.ranges.map { integer(evaluate(it.start, parent))..integer(evaluate(it.endInclusive, parent)) }
		val ranges = if (evaluated.size == 1) List(statement.counters.size) { evaluated.single() } else evaluated
		var broken = false
		fun iterate(index: Int) {
			if (broken) return
			if (index == statement.counters.size) {
				@Suppress("UNUSED_VARIABLE")
				val ignored = try { executeBlock(statement.body, loopFrame) } catch (_: ContinueSignal) { Unit } catch (_: BreakSignal) { broken = true }
				return
			}
			for (value in ranges[index]) {
				val counter = statement.counters[index]
				loopFrame.cells[counter.slot] = typedCell(value, false, IRType.Builtin("I32"), "Loop counter ${counter.name}")
				iterate(index + 1)
				if (broken) return
			}
		}
		iterate(0)
		return UnitValue
	}

	private fun evaluate(expression: IRExpression, frame: Frame): Any? = when (expression) {
		IRExpression.This -> frame.holder ?: error("'this' is unavailable outside a member function")
		is IRExpression.Literal -> literal(expression.value)
		is IRExpression.Array -> ArrayValue(expression.elements.map { evaluate(it, frame) })
		is IRExpression.Variable -> variableCell(expression.reference, frame).get()
		is IRExpression.TypeValue -> materializeType(expression.type, frame)
		is IRExpression.Call -> call(expression, frame)
		is IRExpression.Index -> {
			val receiver = evaluate(expression.receiver, frame)
			val arguments = expression.arguments.map { evaluate(it, frame) }
			if (receiver is ArrayValue) {
				check(arguments.size == 1) { "Array access expects one index" }
				receiver.get(integer(arguments.single()))
			} else userNamedOperator(receiver, "get", arguments, frame)
				?: error("Indexed access expects an Array or operator get receiver")
		}
		is IRExpression.Member -> evaluate(expression.receiver, frame).let { receiver ->
			memberCell(receiver, expression.name, frame.source)?.get() ?: memberValue(receiver, expression.name)
		}
		is IRExpression.Unary -> evaluate(expression.operand, frame).let { value -> when (expression.operation) {
			IRUnaryOperation.NOT -> userNamedOperator(value, "not", emptyList(), frame) ?: !boolean(value)
			IRUnaryOperation.NEGATE -> userNamedOperator(value, "unaryMinus", emptyList(), frame) ?: numericUnary(value)
		} }
		is IRExpression.Binary -> binary(expression.operation, evaluate(expression.left, frame), evaluate(expression.right, frame), frame)
		is IRExpression.LogicalAnd -> boolean(evaluate(expression.left, frame)) && boolean(evaluate(expression.right, frame))
		is IRExpression.LogicalOr -> boolean(evaluate(expression.left, frame)) || boolean(evaluate(expression.right, frame))
		is IRExpression.Assign -> {
			val target = targetCell(expression.target, frame)
			target.set(evaluate(expression.value, frame))
			UnitValue
		}
		is IRExpression.CompoundAssign -> {
			val target = targetCell(expression.target, frame)
			check(target.supportsCompound) { "Compound assignment does not dispatch user operators" }
			val right = evaluate(expression.value, frame)
			target.update { left -> compound(expression.operation, left, right) }
			UnitValue
		}
		is IRExpression.Cast -> cast(evaluate(expression.value, frame), expression.targetType)
		is IRExpression.TypeTest -> assignable(expression.targetType, evaluate(expression.value, frame)).let { if (expression.negated) !it else it }
		is IRExpression.PropagateResult -> when (val result = evaluate(expression.value, frame)) {
			is ResultValue -> if (result.successful) result.value else throw PropagateSignal(result)
			else -> error("Result propagation expects Success or Failure")
		}
		is IRExpression.Conditional -> execute(expression.statement, frame)
	}

	private fun call(expression: IRExpression.Call, frame: Frame): Any? {
		if (expression.target is CallTargetIR.Member) {
			val receiver = evaluate(expression.target.receiver, frame)
			val arguments = expression.arguments.map { evaluate(it, frame) }
			return memberCall(receiver, expression.target.name, arguments, frame.source)
		}
		if (expression.target is CallTargetIR.Builtin && expression.target.name == "await") {
			check(activeInitialization.get() == 0) { "await is not allowed during initialization" }
		}
		val arguments = expression.arguments.map { evaluate(it, frame) }
		return when (val target = expression.target) {
			is CallTargetIR.Builtin -> builtin(target.name, arguments)
			is CallTargetIR.Function -> {
				val function = functions[target.function.symbol] ?: error("Unknown function ${target.function.symbol}")
				check(visible(function.visibility, function.symbol.source, frame.source)) { "${function.visibility.name.lowercase()} function '${function.symbol.name}' is not accessible" }
				callFunction(function, arguments, null)
			}
			is CallTargetIR.Member -> error("Member calls are evaluated before argument lowering")
			is CallTargetIR.Constructor -> construct(target.type, arguments, frame.source)
		}
	}

	private fun memberCall(receiverInput: Any?, name: String, arguments: List<Any?>, caller: SourceId): Any? {
		val receiver = receiverInput
		if (receiver is TypeValue) {
			val enum = enumDeclaration(receiver.type)
			if (enum != null) return when (name) {
				"values" -> { requireArity(name, arguments, 0); ArrayValue(enum.entries.map { constructEnum(enum, it, emptyList()) }) }
				"random" -> { requireArity(name, arguments, 0); constructEnum(enum, enum.entries.randomOrNull() ?: error("Cannot select from empty enum"), emptyList()) }
				else -> constructEnum(enum, enum.entries.firstOrNull { it.name == name } ?: error("Unknown enum entry '$name'"), arguments)
			}
			val staticMatches = program.sourceUnits.asSequence().flatMap { it.implementations.asSequence() }
				.filter { IRModifier.STATIC in it.modifiers && sameType(it.target, receiver.type) }
				.flatMap { it.functions.asSequence() }.filter { it.symbol.name == name }.toList()
			check(staticMatches.size <= 1) { "Ambiguous static member function '$name'" }
			staticMatches.singleOrNull()?.let { function ->
				check(visible(function.visibility, function.symbol.source, caller)) { "Static member function '$name' is not accessible" }
				return callFunction(function, arguments, receiver)
			}
		}
		when (name) {
			"toString" -> return display(receiver)
			"toBoolean" -> return when (receiver) { is Boolean -> receiver; is String -> receiver.toBooleanStrict(); else -> error("Cannot convert to Boolean") }
			"toInt", "toI32" -> return when (receiver) { is Number -> receiver.toInt(); is String -> receiver.toInt(); else -> error("Cannot convert to I32") }
			"toDouble", "toF64" -> return when (receiver) { is Number -> receiver.toDouble(); is String -> receiver.toDouble(); else -> error("Cannot convert to F64") }
		}
		val type = runtimeType(receiver)
		val direct = when (receiver) {
			is ObjectValue -> receiver.declaration.functions.firstOrNull { it.symbol.name == name }
			else -> null
		}
		val implementations = program.sourceUnits.asSequence().flatMap { it.implementations.asSequence() }
			.filter { IRModifier.STATIC !in it.modifiers && sameType(it.target, type) }.flatMap { it.functions.asSequence() }
			.filter { it.symbol.name == name }.toList()
		check(direct != null || implementations.size <= 1) { "Ambiguous member function '$name'" }
		val function = direct ?: implementations.singleOrNull() ?: error("Unknown member function '$name'")
		check(visible(function.visibility, function.symbol.source, caller)) { "${function.visibility.name.lowercase()} member function '$name' is not accessible" }
		return callFunction(function, arguments, receiver)
	}

	private fun builtin(name: String, arguments: List<Any?>): Any? = when (name) {
		"print" -> UnitValue.also { requireArity(name, arguments, 1); io.print(display(arguments.single())) }
		"println" -> UnitValue.also { check(arguments.size in 0..1) { "println expects 0..1 arguments, got ${arguments.size}" }; io.println(if (arguments.isEmpty()) "" else display(arguments.single())) }
		"readLine" -> {
			requireArity(name, arguments, 1); io.println(display(arguments.single()))
			io.readLine() ?: error("readLine reached end of input")
		}
		"readBoolean" -> {
			requireArity(name, arguments, 1); io.println(display(arguments.single()))
			(io.readLine() ?: error("readBoolean reached end of input")).toBooleanStrict()
		}
		"readDouble" -> { requireArity(name, arguments, 1); io.println(display(arguments.single())); (io.readLine() ?: error("readDouble reached end of input")).toDouble() }
		"readInt" -> { requireArity(name, arguments, 1); io.println(display(arguments.single())); (io.readLine() ?: error("readInt reached end of input")).toInt() }
		"success" -> singleArg(name, arguments).let { ResultValue(true, it, IRType.Result(runtimeType(it))) }
		"failure" -> ResultValue(false, singleArg(name, arguments), IRType.Result(IRType.Builtin("Any")))
		"sqrt" -> Math.sqrt(numericDouble(singleArg(name, arguments)))
		"sin" -> Math.sin(numericDouble(singleArg(name, arguments)))
		"round" -> kotlin.math.round(numericDouble(singleArg(name, arguments)))
		"await" -> {
			check(activeInitialization.get() == 0) { "await is not allowed during initialization" }
			await(singleArg(name, arguments))
		}
		"typeOf" -> TypeValue(runtimeType(singleArg(name, arguments)))
		else -> error("Unknown builtin '$name'")
	}

	private fun requireArity(name: String, arguments: List<Any?>, count: Int) {
		check(arguments.size == count) { "$name expects $count arguments, got ${arguments.size}" }
	}
	private fun singleArg(name: String, arguments: List<Any?>): Any? {
		requireArity(name, arguments, 1)
		return arguments.single()
	}

	private fun construct(symbol: SymbolRef, arguments: List<Any?>, caller: SourceId): Any? {
		structs[symbol]?.let { declaration ->
			check(visible(declaration.visibility, symbol.source, caller)) { "${declaration.visibility.name.lowercase()} constructor '${symbol.name}' is not accessible" }
			val declarationFields = declaration.fields
			val minimum = declarationFields.indexOfLast { it.initializer == null } + 1
			check(arguments.size in minimum..declarationFields.size) { "Struct ${symbol.name} expects $minimum..${declarationFields.size} arguments, got ${arguments.size}" }
			val fields = linkedMapOf<String, Cell>()
			val instance = StructValue(declaredType(symbol), fields)
			declarationFields.forEachIndexed { index, field ->
				val value = arguments.getOrNull(index) ?: field.initializer?.let { evaluate(it, Frame(null, symbol.source, instance)) }
					?: error("Struct ${symbol.name} is missing field '${field.name}'")
				fields[field.name] = typedCell(value, field.mutable, field.type, "Field ${field.name}", symbol.source, field.visibility)
			}
			return instance
		}
		enums[symbol]?.let { error("Enum construction requires an entry member") }
		if (symbol in objects) return materializeType(declaredType(symbol), Frame(null, caller))
		error("Declared symbol '${symbol.name}' is not a constructible program declaration")
	}

	private fun materializeType(type: IRType, frame: Frame): Any? {
		val declared = type as? IRType.Declared ?: return TypeValue(type)
		val symbol = when (val reference = declared.symbol) {
			is SymbolRef -> reference
			is NestedSymbolRef -> SymbolRef(reference.parent.source, reference.kind, reference.name, reference.parent.name)
		}
		val visibility = declarationVisibility(symbol)
			?: error("Declared symbol '${symbol.name}' does not resolve to a program declaration")
		check(visible(visibility, symbol.source, frame.source)) {
			"${visibility.name.lowercase()} declaration '${symbol.name}' is not accessible"
		}
		objects[symbol]?.let { declaration ->
			val slot = synchronized(initializationLock) { objectSlots.getOrPut(symbol) { InitSlot("object ${symbol.name}") } }
			return slot.get {
				val fields = linkedMapOf<String, Cell>()
				val value = ObjectValue(declared, declaration, fields)
				declaration.fields.forEach { field ->
					val initial = field.initializer?.let { evaluate(it, Frame(null, symbol.source, value)) }
						?: error("Object field '${field.name}' requires an initializer")
					fields[field.name] = typedCell(initial, field.mutable, field.type, "Field ${field.name}", symbol.source, field.visibility)
				}
				value
			}
		}
		return TypeValue(type)
	}

	private fun declarationVisibility(symbol: SymbolRef): IRVisibility? =
		structs[symbol]?.visibility ?: objects[symbol]?.visibility ?: enums[symbol]?.visibility
			?: program.sourceUnits.asSequence().flatMap { it.sealeds.asSequence() }.firstOrNull { it.symbol == symbol }?.visibility
			?: program.sourceUnits.asSequence().flatMap { it.traits.asSequence() }.firstOrNull { it.symbol == symbol }?.visibility

	private fun globalCell(symbol: SymbolRef, caller: SourceId): Cell {
		val declaration = globals[symbol] ?: error("Declared global '${symbol.name}' does not resolve to a program declaration")
		check(visible(declaration.visibility, symbol.source, caller)) {
			"${declaration.visibility.name.lowercase()} global '${symbol.name}' is not accessible"
		}
		val slot = synchronized(initializationLock) { globalSlots.getOrPut(symbol) { InitSlot("global ${symbol.name}") } }
		return slot.get {
			typedCell(evaluate(declaration.initializer, Frame(null, symbol.source)), declaration.mutable, declaration.type, "Global ${symbol.name}", symbol.source, declaration.visibility)
		}
	}

	private fun variableCell(reference: VariableRefIR, frame: Frame): Cell = when (reference) {
		is VariableRefIR.Local -> frame.cell(reference.slot)
		is VariableRefIR.Global -> globalCell(reference.symbol, frame.source)
	}

	private fun typedCell(
		value: Any?, mutable: Boolean, type: IRType, context: String,
		declarationSource: SourceId? = null, visibility: IRVisibility = IRVisibility.PRIVATE,
	): Cell {
		val effectiveType = if (type is IRType.Implicit) runtimeType(value) else type
		return Cell(value, mutable, { coerce(effectiveType, it, context) }, declarationSource, visibility)
	}

	private fun coerce(type: IRType, value: Any?, context: String): Any? {
		if (type is IRType.Result && value is ResultValue) {
			val payload = if (value.successful) coerce(type.value, value.value, "$context success payload") else value.value
			return ResultValue(value.successful, payload, type)
		}
		if (type is IRType.Array && value is ArrayValue) {
			value.constrain(type.element)
			return value
		}
		if (assignable(type, value)) return value
		val builtin = type as? IRType.Builtin
		if (builtin != null && builtin.name in numericTypeNames && isNumeric(value)) {
			return try { cast(value, type) } catch (failure: IllegalStateException) {
				throw IllegalStateException("$context expects $type, got ${runtimeType(value)}", failure)
			}
		}
		checkValue(type, value, context)
		return value
	}

	private fun checkValue(type: IRType, value: Any?, context: String) {
		check(assignable(type, value)) { "$context expects $type, got ${runtimeType(value)}" }
	}

	private fun targetCell(target: AssignmentTargetIR, frame: Frame): Cell = when (target) {
		is AssignmentTargetIR.Variable -> variableCell(target.reference, frame)
		is AssignmentTargetIR.Member -> memberCell(evaluate(target.receiver, frame), target.name, frame.source) ?: error("Unknown member '${target.name}'")
		is AssignmentTargetIR.Index -> {
			val receiver = evaluate(target.receiver, frame)
			val indexValue = evaluate(target.index, frame)
			val array = receiver as? ArrayValue
			val index = array?.let { integer(indexValue) }
			object : Cell(UnitValue) {
				override val supportsCompound: Boolean = array != null
				override fun get(): Any? = array?.get(checkNotNull(index))
					?: userNamedOperator(receiver, "get", listOf(indexValue), frame)
					?: error("Indexed access expects an Array or operator get receiver")
				override fun set(newValue: Any?) {
					if (array != null) array.set(checkNotNull(index), newValue)
					else check(userNamedOperator(receiver, "set", listOf(indexValue, newValue), frame) === UnitValue) { "operator set must return Unit" }
				}
				override fun update(operation: (Any?) -> Any?): Any? = array?.update(checkNotNull(index), operation)
					?: error("Compound assignment does not dispatch user operators")
			}
		}
	}

	private fun memberCell(receiverInput: Any?, name: String, caller: SourceId): Cell? = when (val receiver = receiverInput) {
		is StructValue -> receiver.fields[name]
		is ObjectValue -> receiver.fields[name]
		is EnumValue -> receiver.fields[name]
		else -> null
	}?.also { it.requireAccess(caller) }

	private fun memberValue(receiverInput: Any?, name: String): Any? = when (val receiver = receiverInput) {
		is ArrayValue -> if (name == "size" || name == "length") receiver.size() else error("Unknown array member '$name'")
		is TypeValue -> {
			val enum = enumDeclaration(receiver.type) ?: error("Unknown type member '$name'")
			constructEnum(enum, enum.entries.firstOrNull { it.name == name } ?: error("Unknown enum entry '$name'"), emptyList())
		}
		else -> error("Unknown member '$name'")
	}

	private fun enumDeclaration(type: IRType): EnumIR? {
		val declared = type as? IRType.Declared ?: return null
		val symbol = declared.symbol as? SymbolRef ?: return null
		return enums[symbol]
	}

	private fun constructEnum(enum: EnumIR, entry: EnumEntryIR, explicitArguments: List<Any?>): EnumValue {
		val frame = Frame(null, enum.symbol.source)
		val arguments = if (explicitArguments.isNotEmpty()) explicitArguments else entry.arguments.map { evaluate(it, frame) }
		val minimum = enum.parameters.indexOfLast { it.defaultValue == null } + 1
		check(arguments.size in minimum..enum.parameters.size) { "Enum ${enum.symbol.name}.${entry.name} expects $minimum..${enum.parameters.size} arguments, got ${arguments.size}" }
		val fields = linkedMapOf<String, Cell>()
		enum.parameters.forEachIndexed { index, parameter ->
			val value = arguments.getOrNull(index) ?: parameter.defaultValue?.let { evaluate(it, frame) }
				?: error("Missing enum argument '${parameter.name}'")
			val cell = typedCell(value, false, parameter.type, "Enum field ${parameter.name}", enum.symbol.source, IRVisibility.PUBLIC)
			frame.cells[parameter.slot] = cell
			fields[parameter.name] = cell
		}
		return EnumValue(IRType.Declared(enum.symbol), entry.name, fields)
	}

	private fun literal(value: IRLiteral): Any? = when (value) {
		IRLiteral.Unit -> UnitValue
		is IRLiteral.Boolean -> value.value
		is IRLiteral.String -> value.value
		is IRLiteral.Char -> value.value
		is IRLiteral.U8 -> value.value
		is IRLiteral.U16 -> value.value
		is IRLiteral.U32 -> value.value
		is IRLiteral.U64 -> value.value
		is IRLiteral.I8 -> value.value
		is IRLiteral.I16 -> value.value
		is IRLiteral.I32 -> value.value
		is IRLiteral.I64 -> value.value
		is IRLiteral.F32 -> value.value
		is IRLiteral.F64 -> value.value
	}

	private fun binary(operation: IRBinaryOperation, leftInput: Any?, rightInput: Any?, frame: Frame? = null): Any? {
		val left = leftInput; val right = rightInput
		if (frame != null) {
			if (operation == IRBinaryOperation.SUBTRACT && isNumeric(left) && numericDouble(left) == 0.0 &&
				(right is StructValue || right is ObjectValue || right is EnumValue)) {
				userNamedOperator(right, "unaryMinus", emptyList(), frame)?.let { return it }
			}
			val receiver = if (operation == IRBinaryOperation.CONTAINS || operation == IRBinaryOperation.NOT_CONTAINS) right else left
			val argument = if (receiver === right) left else right
			userOperator(operation, receiver, argument, frame)?.let { return it }
		}
		return when (operation) {
			IRBinaryOperation.ADD -> if (left is String || right is String) display(left) + display(right) else numericBinary("+", left, right)
			IRBinaryOperation.SUBTRACT -> numericBinary("-", left, right)
			IRBinaryOperation.MULTIPLY -> numericBinary("*", left, right)
			IRBinaryOperation.DIVIDE -> numericBinary("/", left, right)
			IRBinaryOperation.POWER -> Math.pow(numericDouble(left), numericDouble(right))
			IRBinaryOperation.REMAINDER -> numericBinary("%", left, right)
			IRBinaryOperation.EQUAL -> equal(left, right)
			IRBinaryOperation.NOT_EQUAL -> !equal(left, right)
			IRBinaryOperation.REFERENCE_EQUAL -> left === right
			IRBinaryOperation.REFERENCE_NOT_EQUAL -> left !== right
			IRBinaryOperation.LESS -> compare(left, right) < 0
			IRBinaryOperation.LESS_OR_EQUAL -> compare(left, right) <= 0
			IRBinaryOperation.GREATER -> compare(left, right) > 0
			IRBinaryOperation.GREATER_OR_EQUAL -> compare(left, right) >= 0
			IRBinaryOperation.CONTAINS -> contains(right, left)
			IRBinaryOperation.NOT_CONTAINS -> !contains(right, left)
			IRBinaryOperation.RANGE_TO -> ArrayValue((integer(left)..integer(right)).toList())
			IRBinaryOperation.SHIFT_LEFT -> shift("shl", left, right)
			IRBinaryOperation.SHIFT_RIGHT -> shift("shr", left, right)
			IRBinaryOperation.UNSIGNED_SHIFT_RIGHT -> shift("ushr", left, right)
			IRBinaryOperation.BIT_OR -> integralBinary("or", left, right)
			IRBinaryOperation.BIT_AND -> integralBinary("and", left, right)
			IRBinaryOperation.BIT_XOR -> integralBinary("xor", left, right)
		}
	}

	private fun compound(operation: IRCompoundOperation, left: Any?, right: Any?): Any? = binary(when (operation) {
		IRCompoundOperation.ADD -> IRBinaryOperation.ADD
		IRCompoundOperation.SUBTRACT -> IRBinaryOperation.SUBTRACT
		IRCompoundOperation.MULTIPLY -> IRBinaryOperation.MULTIPLY
		IRCompoundOperation.DIVIDE -> IRBinaryOperation.DIVIDE
		IRCompoundOperation.POWER -> IRBinaryOperation.POWER
		IRCompoundOperation.REMAINDER -> IRBinaryOperation.REMAINDER
	}, left, right)

	private fun userOperator(operation: IRBinaryOperation, receiver: Any?, argument: Any?, frame: Frame): Any? {
		val name = operatorNames[operation] ?: return null
		val value = userNamedOperator(receiver, name, listOf(argument), frame) ?: return null
		return when (operation) {
			IRBinaryOperation.NOT_EQUAL, IRBinaryOperation.NOT_CONTAINS -> !boolean(value)
			IRBinaryOperation.LESS -> integer(value) < 0
			IRBinaryOperation.LESS_OR_EQUAL -> integer(value) <= 0
			IRBinaryOperation.GREATER -> integer(value) > 0
			IRBinaryOperation.GREATER_OR_EQUAL -> integer(value) >= 0
			else -> value
		}
	}

	private fun userNamedOperator(receiver: Any?, name: String, arguments: List<Any?>, frame: Frame): Any? {
		if (receiver !is StructValue && receiver !is ObjectValue && receiver !is EnumValue) return null
		val actual = runtimeType(receiver)
		val matches = program.sourceUnits.asSequence().flatMap { it.implementations.asSequence() }
			.filter { IRModifier.STATIC !in it.modifiers && it.target == actual }.flatMap { it.functions.asSequence() }
			.filter { it.symbol.name == name && IRModifier.OPERATOR in it.modifiers }.toList()
		if (matches.isEmpty()) return null
		check(matches.size == 1) { "Ambiguous operator $name for $actual" }
		val function = matches.single()
		check(visible(function.visibility, function.symbol.source, frame.source)) { "Operator $name is not accessible" }
		check(function.parameters.size == arguments.size) { "operator $name expects ${function.parameters.size} arguments" }
		return callFunction(function, arguments, receiver)
	}

	private fun numeric(left: Any?, right: Any?, action: (Double, Double) -> Double): Any {
		val a = number(left); val b = number(right); val result = action(a.toDouble(), b.toDouble())
		return when {
			a is Float || a is Double || b is Float || b is Double || !result.isFinite() || result % 1.0 != 0.0 -> result.minimize()
			a is Long || b is Long -> result.toLong()
			else -> result.toInt()
		}
	}

	private fun numericBinary(operation: String, left: Any?, right: Any?): Any {
		check(isNumeric(left) && isNumeric(right)) { "Expected numeric operands" }
		val leftUnsigned = isUnsigned(left); val rightUnsigned = isUnsigned(right)
		check(leftUnsigned == rightUnsigned) { "Cannot combine signed and unsigned numbers" }
		if (leftUnsigned) {
			val a = unsigned(left); val b = unsigned(right)
			val value = when (operation) { "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> a / b; "%" -> a % b; else -> error(operation) }
			return if (left is ULong || right is ULong) value else value.toUInt()
		}
		val a = number(left); val b = number(right)
		if (operation == "/" || operation == "%") check(b.toDouble() != 0.0) { "Division by zero" }
		if (a is Double || b is Double) return when (operation) { "+" -> a.toDouble()+b.toDouble(); "-" -> a.toDouble()-b.toDouble(); "*" -> a.toDouble()*b.toDouble(); "/" -> a.toDouble()/b.toDouble(); "%" -> a.toDouble()%b.toDouble(); else -> error(operation) }
		if (a is Float || b is Float) return when (operation) { "+" -> a.toFloat()+b.toFloat(); "-" -> a.toFloat()-b.toFloat(); "*" -> a.toFloat()*b.toFloat(); "/" -> a.toFloat()/b.toFloat(); "%" -> a.toFloat()%b.toFloat(); else -> error(operation) }
		if (operation == "/" && b.toLong() == -1L &&
			((a is Long && a == Long.MIN_VALUE) || (a !is Long && a.toInt() == Int.MIN_VALUE))) error("Integer overflow")
		val value = when (operation) { "+" -> a.toLong()+b.toLong(); "-" -> a.toLong()-b.toLong(); "*" -> a.toLong()*b.toLong(); "/" -> a.toLong()/b.toLong(); "%" -> a.toLong()%b.toLong(); else -> error(operation) }
		return if (a is Long || b is Long) value else value.toInt()
	}

	private fun integralBinary(operation: String, left: Any?, right: Any?): Any {
		check(isIntegral(left) && isIntegral(right)) { "Expected integral operands" }
		check(isUnsigned(left) == isUnsigned(right)) { "Cannot combine signed and unsigned numbers" }
		return if (isUnsigned(left)) {
			val a=unsigned(left); val b=unsigned(right); val value=when(operation){"or"->a or b;"and"->a and b;"xor"->a xor b;else->error(operation)}
			if(left is ULong||right is ULong)value else value.toUInt()
		} else {
			val a=number(left).toLong(); val b=number(right).toLong(); val value=when(operation){"or"->a or b;"and"->a and b;"xor"->a xor b;else->error(operation)}
			if(left is Long||right is Long)value else value.toInt()
		}
	}

	private fun shift(operation: String, left: Any?, right: Any?): Any {
		check(isIntegral(left) && isIntegral(right)) { "Shift expects integral operands" }
		val count = unsignedOrSignedLong(right)
		val width = if (left is Long || left is ULong) 64 else 32
		check(count in 0 until width.toLong()) { "Shift count $count is outside 0..${width-1}" }
		return when (left) {
			is ULong -> when(operation){"shl"->left shl count.toInt();"shr","ushr"->left shr count.toInt();else->error(operation)}
			is UByte, is UShort, is UInt -> unsigned(left).toUInt().let { if(operation=="shl") it shl count.toInt() else it shr count.toInt() }
			is Long -> when(operation){"shl"->left shl count.toInt();"shr"->left shr count.toInt();"ushr"->left ushr count.toInt();else->error(operation)}
			else -> number(left).toInt().let { when(operation){"shl"->it shl count.toInt();"shr"->it shr count.toInt();"ushr"->it ushr count.toInt();else->error(operation)} }
		}
	}

	private fun isUnsigned(value: Any?) = value is UByte || value is UShort || value is UInt || value is ULong
	private fun isIntegral(value: Any?) = isUnsigned(value) || value is Byte || value is Short || value is Int || value is Long
	private fun unsigned(value: Any?): ULong = when(value){is UByte->value.toULong();is UShort->value.toULong();is UInt->value.toULong();is ULong->value;else->error("Expected unsigned number")}
	private fun unsignedOrSignedLong(value: Any?): Long = if(isUnsigned(value)) unsigned(value).toLong() else number(value).toLong()

	private fun numericUnary(value: Any?): Any = when (val number = number(value)) {
		is Double -> -number; is Float -> -number; is Long -> -number; else -> -number.toInt()
	}

	private fun number(value: Any?): Number = value as? Number ?: when (value) {
		is UByte -> value.toInt(); is UShort -> value.toInt(); is UInt -> value.toLong(); is ULong -> value.toLong(); else -> error("Expected a number, got ${value?.let { it::class.simpleName }}")
	}
	private fun numericDouble(value: Any?): Double = if (isUnsigned(value)) unsigned(value).toDouble() else number(value).toDouble()
	private fun isNumeric(value: Any?) = value is Number || value is UByte || value is UShort || value is UInt || value is ULong
	private fun integer(value: Any?): Int {
		check(isIntegral(value)) { "Expected an exact whole-number Int value" }
		val converted = if (isUnsigned(value)) {
			val unsigned = unsigned(value); check(unsigned <= Int.MAX_VALUE.toULong()) { "Integer is outside Int range" }; unsigned.toInt()
		} else {
			val signed = number(value).toLong(); check(signed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Integer is outside Int range" }; signed.toInt()
		}
		return converted
	}
	private fun boolean(value: Any?): Boolean = value as? Boolean ?: error("Expected a Boolean")
	private fun compare(left: Any?, right: Any?): Int = if (isNumeric(left) && isNumeric(right)) {
		check(isUnsigned(left) == isUnsigned(right)) { "Cannot compare signed and unsigned numbers" }
		if(isUnsigned(left)) unsigned(left).compareTo(unsigned(right)) else {
			val a=number(left);val b=number(right);if(a is Float||a is Double||b is Float||b is Double)a.toDouble().compareTo(b.toDouble()) else a.toLong().compareTo(b.toLong())
		}
	} else when { left is String && right is String -> left.compareTo(right); left is Char && right is Char -> left.compareTo(right); else -> error("Values are not order-comparable") }
	private fun equal(left: Any?, right: Any?): Boolean = equal(left, right, mutableSetOf())
	private fun equal(left: Any?, right: Any?, visited: MutableSet<IdentityPair>): Boolean {
		if (left === right) return true
		return when {
			isNumeric(left) && isNumeric(right) -> runCatching { compare(left,right)==0 }.getOrDefault(false)
			left is ArrayValue && right is ArrayValue -> compareComposite(left, right, visited) {
				val a = left.snapshot(); val b = right.snapshot()
				a.size == b.size && a.indices.all { equal(a[it], b[it], visited) }
			}
			left is StructValue && right is StructValue -> compareComposite(left, right, visited) {
				sameType(left.type, right.type) && equalFields(left.fields, right.fields, visited)
			}
			left is EnumValue && right is EnumValue -> compareComposite(left, right, visited) {
				left.entry == right.entry && sameType(left.type, right.type) && equalFields(left.fields, right.fields, visited)
			}
			left is ResultValue && right is ResultValue -> compareComposite(left, right, visited) {
				left.successful == right.successful && sameType(left.declaredType, right.declaredType) && equal(left.value, right.value, visited)
			}
			left is ObjectValue && right is ObjectValue -> false
			else -> left == right
		}
	}
	private inline fun compareComposite(left: Any, right: Any, visited: MutableSet<IdentityPair>, block: () -> Boolean): Boolean =
		if (!visited.add(IdentityPair(left, right))) true else block()
	private fun equalFields(left: Map<String, Cell>, right: Map<String, Cell>, visited: MutableSet<IdentityPair>): Boolean =
		left.keys == right.keys && left.all { (name, cell) -> equal(cell.get(), right.getValue(name).get(), visited) }
	private class IdentityPair(private val left: Any, private val right: Any) {
		override fun equals(other: Any?): Boolean = other is IdentityPair &&
			(left === other.left && right === other.right || left === other.right && right === other.left)
		override fun hashCode(): Int = System.identityHashCode(left) xor System.identityHashCode(right)
	}
	private fun contains(container: Any?, value: Any?): Boolean = when (container) { is String -> (value as? String)?.let { it in container } ?: error("String containment expects a String"); is ArrayValue -> container.snapshot().any { equal(it, value) }; else -> error("Value does not support containment") }

	private fun cast(value: Any?, type: IRType): Any? {
		if (assignable(type, value)) return value
		val name = (type as? IRType.Builtin)?.name ?: error("Invalid cast to $type")
		return when (name) {
			"U8" -> checkedUnsigned(value, UByte.MAX_VALUE.toULong()).toUByte()
			"U16" -> checkedUnsigned(value, UShort.MAX_VALUE.toULong()).toUShort()
			"U32" -> checkedUnsigned(value, UInt.MAX_VALUE.toULong()).toUInt()
			"U64" -> checkedUnsigned(value, ULong.MAX_VALUE)
			"I8" -> checkedInteger(value, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte()
			"I16" -> checkedInteger(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
			"I32" -> checkedInteger(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
			"I64" -> checkedInteger(value, Long.MIN_VALUE, Long.MAX_VALUE)
			"F32" -> numericDouble(value).toFloat().also { check(it.isFinite()) { "Numeric cast overflow" } }
			"F64" -> numericDouble(value).also { check(it.isFinite()) { "Numeric cast overflow" } }
			else -> error("Invalid cast to $type")
		}
	}

	private fun checkedUnsigned(value: Any?, maximum: ULong): ULong {
		val integer = exactInteger(value)
		check(integer.signum() >= 0 && integer <= BigInteger(maximum.toString())) { "Numeric cast overflow" }
		return integer.toString().toULong()
	}

	private fun checkedInteger(value: Any?, minimum: Long, maximum: Long): Long {
		val integer = exactInteger(value)
		check(integer >= BigInteger.valueOf(minimum) && integer <= BigInteger.valueOf(maximum)) { "Numeric cast overflow" }
		return integer.toLong()
	}

	private fun exactInteger(value: Any?): BigInteger {
		val decimal = when (value) {
			is UByte, is UShort, is UInt, is ULong, is Byte, is Short, is Int, is Long -> BigDecimal(value.toString())
			is Float -> BigDecimal.valueOf(value.toDouble())
			is Double -> { check(value.isFinite()) { "Numeric cast overflow" }; BigDecimal.valueOf(value) }
			else -> error("Invalid numeric cast")
		}
		return try { decimal.toBigIntegerExact() } catch (_: ArithmeticException) { error("Numeric cast requires a whole value") }
	}

	private fun assignable(type: IRType, valueInput: Any?): Boolean {
		val value = valueInput
		return when (type) {
			IRType.Implicit -> true
			is IRType.Result -> value is ResultValue && sameType(type, value.declaredType)
			is IRType.Array -> value is ArrayValue && value.snapshot().all { assignable(type.element, it) }
			is IRType.Declared -> {
				val symbol = declaredSymbol(type)
				declarationVisibility(symbol)
					?: error("Declared symbol '${symbol.name}' does not resolve to a program declaration")
				sameType(type, runtimeType(value))
			}
			is IRType.Builtin -> when (type.name) {
				"Any" -> true; "Unit" -> value === UnitValue; "Boolean" -> value is Boolean; "String" -> value is String; "Char" -> value is Char; "Future" -> value is FutureValue
				"U8" -> value is UByte; "U16" -> value is UShort; "U32" -> value is UInt; "U64" -> value is ULong
				"I8" -> value is Byte; "I16" -> value is Short; "I32" -> value is Int; "I64" -> value is Long; "F32" -> value is Float; "F64" -> value is Double
				else -> false
			}
		}
	}

	private fun runtimeType(value: Any?): IRType = when (value) {
		UnitValue -> IRType.Builtin("Unit"); is Boolean -> IRType.Builtin("Boolean"); is String -> IRType.Builtin("String"); is Char -> IRType.Builtin("Char")
		is UByte -> IRType.Builtin("U8"); is UShort -> IRType.Builtin("U16"); is UInt -> IRType.Builtin("U32"); is ULong -> IRType.Builtin("U64")
		is Byte -> IRType.Builtin("I8"); is Short -> IRType.Builtin("I16"); is Int -> IRType.Builtin("I32"); is Long -> IRType.Builtin("I64"); is Float -> IRType.Builtin("F32"); is Double -> IRType.Builtin("F64")
		is StructValue -> value.type; is ObjectValue -> value.type; is EnumValue -> value.type
		is ResultValue -> value.declaredType; is ArrayValue -> IRType.Array(value.snapshot().firstOrNull()?.let(::runtimeType) ?: IRType.Builtin("Any")); is FutureValue -> IRType.Builtin("Future")
		else -> IRType.Builtin("Any")
	}

	private fun sameType(target: IRType, actual: IRType, seen: MutableSet<IRType> = linkedSetOf()): Boolean {
		if (target == actual || target == IRType.Builtin("Any")) return true
		if (!seen.add(actual)) return false
		val targetDeclared = (target as? IRType.Declared)?.symbol
		val actualNested = (actual as? IRType.Declared)?.symbol as? NestedSymbolRef
		if (targetDeclared is SymbolRef && targetDeclared.kind == SymbolKind.SEALED && actualNested?.parent == targetDeclared) return true
		return program.sourceUnits.asSequence().flatMap { it.implementations.asSequence() }
			.filter { it.target == actual }.flatMap { it.extendedTypes.asSequence() }
			.any { ancestor -> sameType(target, ancestor, seen) }
	}
	private fun visible(visibility: IRVisibility, declaration: SourceId, caller: SourceId): Boolean = when (visibility) {
		IRVisibility.PUBLIC -> true
		IRVisibility.INTERNAL -> declaration.packageId == caller.packageId
		IRVisibility.PRIVATE -> declaration == caller
	}
	private fun declaredType(symbol: SymbolRef): IRType.Declared = if (symbol.enclosingTypeName == null) IRType.Declared(symbol) else {
		val parent = SymbolRef(symbol.source, SymbolKind.SEALED, symbol.enclosingTypeName)
		IRType.Declared(NestedSymbolRef(parent, symbol.kind, symbol.name))
	}
	private fun declaredSymbol(type: IRType.Declared): SymbolRef = when (val reference = type.symbol) {
		is SymbolRef -> reference
		is NestedSymbolRef -> SymbolRef(reference.parent.source, reference.kind, reference.name, reference.parent.name)
	}
	private fun await(value: Any?): Any? = if (value is FutureValue) awaitFutureUninterruptibly(value.future) else value
	private fun display(valueInput: Any?): String = display(valueInput, IdentityHashMap())
	private fun display(value: Any?, active: IdentityHashMap<Any, Boolean>): String = when (value) {
		null -> "null"
		UnitValue -> "Basic(Unit)"
		is TypeValue -> renderType(value.type)
		is ArrayValue -> renderComposite(value, active) { value.snapshot().joinToString(prefix = "[", postfix = "]") { display(it, active) } }
		is StructValue -> renderComposite(value, active) {
			"${declaredSymbol(value.type).name}(${value.fields.entries.joinToString { (name, cell) -> "$name=${display(cell.get(), active)}" }})"
		}
		is ObjectValue -> declaredSymbol(value.type).name
		is EnumValue -> value.entry
		is ResultValue -> renderComposite(value, active) { "${if (value.successful) "Success" else "Failure"}(${display(value.value, active)})" }
		is FutureValue -> "Future(pending)"
		else -> value.toString()
	}
	private fun renderType(type: IRType): String = when (type) {
		IRType.Implicit -> "Implicit"
		is IRType.Builtin -> "Basic(${type.name})"
		is IRType.Array -> "Array<${renderType(type.element)}>"
		is IRType.Result -> "Result<${renderType(type.value)}>"
		is IRType.Declared -> declaredSymbol(type).name
	}
	private inline fun renderComposite(value: Any, active: IdentityHashMap<Any, Boolean>, block: () -> String): String {
		if (active.put(value, true) != null) return "<cycle>"
		return try { block() } finally { active.remove(value) }
	}

	private val numericTypeNames = setOf("U8", "U16", "U32", "U64", "I8", "I16", "I32", "I64", "F32", "F64")
	private val operatorNames = mapOf(
		IRBinaryOperation.ADD to "plus", IRBinaryOperation.SUBTRACT to "minus", IRBinaryOperation.MULTIPLY to "times",
		IRBinaryOperation.DIVIDE to "div", IRBinaryOperation.REMAINDER to "rem", IRBinaryOperation.POWER to "pow",
		IRBinaryOperation.EQUAL to "equals", IRBinaryOperation.NOT_EQUAL to "equals",
		IRBinaryOperation.LESS to "compareTo", IRBinaryOperation.LESS_OR_EQUAL to "compareTo",
		IRBinaryOperation.GREATER to "compareTo", IRBinaryOperation.GREATER_OR_EQUAL to "compareTo",
		IRBinaryOperation.CONTAINS to "contains", IRBinaryOperation.NOT_CONTAINS to "contains",
		IRBinaryOperation.SHIFT_LEFT to "bitShiftLeft", IRBinaryOperation.SHIFT_RIGHT to "bitShiftRight",
		IRBinaryOperation.UNSIGNED_SHIFT_RIGHT to "unsignedBitShiftRight", IRBinaryOperation.BIT_AND to "bitAnd",
		IRBinaryOperation.BIT_OR to "bitOr", IRBinaryOperation.BIT_XOR to "bitXor",
	)
}
