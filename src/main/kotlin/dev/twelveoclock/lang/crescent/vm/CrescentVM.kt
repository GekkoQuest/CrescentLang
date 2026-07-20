package dev.twelveoclock.lang.crescent.vm

import dev.twelveoclock.lang.crescent.diagnostics.SourceLocations
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Type
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

// External host-function registration and operator overloading are not part of the direct VM API.
class CrescentVM @JvmOverloads constructor(
	files: List<Node.File>,
	val mainFile: Node.File,
	private val io: RuntimeIO = RuntimeIO.system(),
) : AutoCloseable {
	private companion object {
		val BUILT_IN_TYPE_NAMES = setOf(
			"Any", "Unit", "Boolean", "String", "Char", "Future",
			"I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64",
		)
	}

	val files: List<Node.File> = Collections.unmodifiableList(ArrayList(files))
	private val objectCache = mutableMapOf<DeclarationId, Instance.Object>()
	private val initializationLock = Any()
	private val lifecycleLock = Any()
	private val fileVariables = IdentityHashMap<Node.File, MutableMap<String, Variable>>()
	private val fileVariableInitializations = mutableMapOf<FileVariableKey, Initialization<Variable>>()
	private val objectInitializations = mutableMapOf<ObjectKey, Initialization<Instance.Object>>()
	private val initializationOwners = mutableMapOf<InitializationKey, Thread>()
	private val threadWaits = IdentityHashMap<Thread, InitializationKey>()
	private val activeInitializations = ThreadLocal.withInitial { mutableSetOf<InitializationKey>() }
	private val coroutineExecutor = Executors.newVirtualThreadPerTaskExecutor()
	@Volatile private var closed = false
	val objects: Map<String, Instance.Object>
		get() = synchronized(initializationLock) {
			val values = objectCache.values.toList()
			require(values.map { it.name }.distinct().size == values.size) {
				"Object names are ambiguous across packages; use declaration-qualified lookup"
			}
			Collections.unmodifiableMap(values.associateBy { it.name })
		}
	init {
		require(files.any { it === mainFile }) { "The main file must be part of the VM file set by identity" }
		validateLinkedDeclarations()
		validateImplementations()
		validateRuntimeSemantics()
	}


	fun invoke(args: List<String> = emptyList()) {
		ensureOpen()
		invokeMain(args)
	}

	fun invokeAsync(args: List<String> = emptyList()): CompletableFuture<Node> {
		val capturedArgs = args.toList()
		return submitAsync { invokeMain(capturedArgs) }
	}

	private fun invokeMain(args: List<String>): Node {

		val mainFunction = mainFile.mainFunction
			?: throw CrescentRuntimeException(mainFile.path, "No main function was declared")

		val functionArgs = if (mainFunction.params.isEmpty()) {
			emptyList()
		}
		else {
			listOf(Instance.Array(Array(args.size) { Primitive.String(args[it]) }, Type.Basic("String"), tentative = false))
		}

		return runFunction(
			mainFunction,
			functionArgs,
			BlockContext(mainFile, mainFile, mutableMapOf(), mutableMapOf())
		)
	}

	override fun close() {
		synchronized(lifecycleLock) {
			if (!closed) {
				closed = true
				coroutineExecutor.shutdown()
			}
		}
	}

	private fun ensureOpen() {
		check(!closed) { "The Crescent VM has been closed" }
	}

	private fun submitAsync(sourcePath: java.nio.file.Path = mainFile.path, block: () -> Node): CompletableFuture<Node> {
		if (activeInitializations.get().isNotEmpty()) {
			throw CrescentRuntimeException(sourcePath, "Async work cannot be launched during file or object initialization")
		}
		return synchronized(lifecycleLock) {
			ensureOpen()
			CompletableFuture.supplyAsync(block, coroutineExecutor)
		}
	}

	fun runFunction(function: Node.Function, args: List<Node>, context: BlockContext): Node {

		val requiredParameters = function.params.count { it is Node.Parameter.Basic }
		runtimeCheck(context, args.size in requiredParameters..function.params.size) {
			"Function ${function.name} expects $requiredParameters..${function.params.size} arguments, got ${args.size}"
		}

		val functionContext = BlockContext(
			context.file,
			context.holder,
			mutableMapOf(),
			mutableMapOf(),
			returnType = function.returnType,
		)
		fun typedPropagation(failure: Instance.Result.Failure): Node = coerceBoundary(
			function.returnType,
			failure,
			functionContext,
		) { "Function ${function.name} returns ${function.returnType}, got ${findType(failure)}" }

		function.params.forEachIndexed { index, parameter ->

			val arg = args.getOrNull(index) ?: when (parameter) {
				is Node.Parameter.WithDefault -> try {
					runNode(parameter.defaultValue, functionContext)
				} catch (propagation: PropagateResult) {
					runtimeCheck(functionContext, function.returnType is Type.Result) {
						"Result propagation is only valid in a Result-returning function"
					}
					return typedPropagation(propagation.failure)
				}
				is Node.Parameter.Basic -> runtimeError(functionContext, "Missing required argument '${parameter.name}'")
			}

			val parameterType = parameterType(parameter)
			val coercedArg = coerceBoundary(parameterType, arg, functionContext) {
				"Parameter ${parameter.name} expects $parameterType, got ${findType(arg)}"
			}
			checkIsSameType(parameter, coercedArg, functionContext) { expected ->
				"Parameter ${parameter.name} expects $expected, got ${findType(coercedArg)}"
			}

			functionContext.parameters[parameter.name] = Variable(parameter.name, boundaryInstance(parameterType, coercedArg), true, parameterType, context.file)
		}

		val blockResult = try {
			runBlock(function.innerCode, functionContext)
		} catch (propagation: PropagateResult) {
			runtimeCheck(functionContext, function.returnType is Type.Result) {
				"Result propagation is only valid in a Result-returning function"
			}
			return typedPropagation(propagation.failure)
		}
		runtimeCheck(functionContext, blockResult != CrescentToken.Keyword.BREAK && blockResult != CrescentToken.Keyword.CONTINUE) {
			"Loop control cannot escape function ${function.name}"
		}
		val result = when {
			blockResult is Node.Return -> blockResult.expression
			function.returnType == Type.unit -> Type.unit
			else -> blockResult
		}
		val coercedResult = coerceBoundary(function.returnType, result, functionContext) {
			"Function ${function.name} returns ${function.returnType}, got ${findType(result)}"
		}
		checkIsSameType(function.returnType, coercedResult, functionContext) {
			"Function ${function.name} returns ${function.returnType}, got ${findType(coercedResult)}"
		}
		return coercedResult
	}

	fun runBlock(block: Node.Statement.Block, context: BlockContext): Node {
		val blockContext = context.childScope()
		block.nodes.forEachIndexed { index, node ->
			val result = runNode(node, blockContext)
			val scopedResult = if (result is Node.Return) {
				Node.Return(runNode(result.expression, blockContext))
			} else result

			if (index + 1 == block.nodes.size || scopedResult is Node.Return || scopedResult == CrescentToken.Keyword.BREAK || scopedResult == CrescentToken.Keyword.CONTINUE) {
				return scopedResult
			}
		}

		return Type.unit
	}

	fun runNode(node: Node, context: BlockContext): Node = try {
		runNodeImpl(node, context)
	} catch (exception: CrescentRuntimeException) {
		throw exception.withSourceSpan(SourceLocations.spanOf(node) ?: SourceLocations.spanOf(context.holder) ?: SourceLocations.spanOf(context.file))
	}

	private fun runNodeImpl(node: Node, context: BlockContext): Node {
		when (node) {

			is Primitive.String,
			is Primitive.Number,
			is Primitive.Char,
			is Primitive.Boolean,
			is Instance,
			CrescentToken.Keyword.BREAK,
			CrescentToken.Keyword.CONTINUE,
			-> {
				return node
			}

			is Node.Array -> {
				val values = node.values.map { runNode(it, context) }.toTypedArray()
				val inferred = values.firstOrNull()?.let(::findType) ?: Type.any
				val array = Instance.Array(values, inferred, tentative = true)
				constrainArray(array, inferred, finalize = false, context) { "Array literal elements must have type $inferred" }
				return array
			}

			is Node.Identifier -> {
				val resolved = resolveIdentifier(node.name, context)
				if (context.explicitHolder) {
					return resolved ?: runtimeError(context, "Unknown member: ${node.name}")
				}
				return resolved
					?: findObjectDeclaration(node.name, context)?.second?.let { runObject(it, context) }
					?: findEnumDeclaration(node.name, context)?.second?.let { Type.Basic(node.name) }
					?: findStructDeclaration(node.name, context)?.second?.let { Type.Basic(node.name) }
					?: runtimeError(context, "Unknown identifier: ${node.name}")
			}

			is Node.IdentifierCall -> {
				return runFunctionCall(node, context)
			}

			is Node.Return -> {
				return node
			}

			is Node.GetCall -> {
				runtimeCheck(context, node.arguments.size == 1) {
					"Array access expects one index, got ${node.arguments.size}"
				}
				val receiver = runNode(Node.Identifier(node.identifier), context)
				val indexValue = runNode(node.arguments.single(), context)
				if (receiver is Instance.Array) {
					val index = requireIndex(indexValue, context)
					return arrayElement(receiver, index, context)
				}
				return invokeUserOperator(receiver, "get", listOf(indexValue), context)
					?: runtimeError(context, "Indexed access expects an Array or operator get receiver")
			}

			is Node.DotChain -> {
				runtimeCheck(context, node.nodes.isNotEmpty()) { "Dot chain cannot be empty" }
				var lastNode: Node? = null

				node.nodes.forEach {

					if (lastNode == null) {
						lastNode = runNode(it, context)
						return@forEach
					}

					lastNode = runNode(it, context.copy(holder = checkNotNull(lastNode), explicitHolder = true))
				}

				return lastNode ?: runtimeError(context, "Dot chain cannot be empty")
			}

			is Node.Expression -> {
				return runExpression(node, context)
			}

			is Node.Statement.If -> {

				val result =
					if (requireBoolean(runNode(node.predicate, context), context, "if predicate")) {
						runBlock(node.block, context)
					}
					else {
						node.elseBlock?.let {
							runBlock(it, context)
						}
					} ?: Type.unit

				return result
			}

			is Node.Statement.While -> {
				while (requireBoolean(runNode(node.predicate, context), context, "while predicate")) {
					when (val result = runBlock(node.block, context)) {
						is Node.Return -> return result
						CrescentToken.Keyword.BREAK -> break
						CrescentToken.Keyword.CONTINUE -> continue
						else -> Unit
					}
				}
			}

			is Node.Statement.When -> {
				val whenContext = context.childScope()
				val argument = runNode(node.argument, whenContext)
				node.subjectName?.let { name ->
					whenContext.variables[name] = Variable(name, instanceOf(argument), true)
				}
				for (clause in node.predicateToBlock) {
					val matches = when (val predicate = clause.ifExpressionNode) {
						null -> true
						is Node.Statement.When.EnumShortHand -> argument is Instance.Enum && argument.entryName == predicate.name
						else -> valuesEqual(argument, runNode(predicate, whenContext))
					}
					if (matches) return runBlock(clause.thenBlock, whenContext)
				}
				return Type.unit
			}

			is Node.Statement.For -> {
				runtimeCheck(context, node.identifiers.isNotEmpty()) { "A for loop needs at least one counter" }
				runtimeCheck(context, node.ranges.size == 1 || node.ranges.size == node.identifiers.size) {
					"A for loop needs one shared range or one range per counter"
				}

				val evaluatedRanges = node.ranges.map { range ->
					val start = requireIndex(runNode(range.start, context), context, "range start")
					val end = requireIndex(runNode(range.end, context), context, "range end")
					start..end
				}
				val ranges = if (evaluatedRanges.size == 1) {
					List(node.identifiers.size) { evaluatedRanges.single() }
				} else evaluatedRanges
				var returnSignal: Node.Return? = null
				var breakRequested = false
				fun iterate(counterIndex: Int, values: List<Int>) {
					if (breakRequested || returnSignal != null) return
					if (counterIndex == node.identifiers.size) {
						val iterationContext = context.childScope()
						node.identifiers.zip(values).forEach { (identifier, value) ->
							val counter = Instance.Node(Primitive.Number.I32.type, Primitive.Number.I32(value))
							iterationContext.variables[identifier.name] = Variable(identifier.name, counter, true)
						}
						when (val result = runBlock(node.block, iterationContext)) {
							is Node.Return -> returnSignal = result
							CrescentToken.Keyword.BREAK -> breakRequested = true
							CrescentToken.Keyword.CONTINUE -> Unit
						}
						return
					}
					for (value in ranges[counterIndex]) {
						iterate(counterIndex + 1, values + value)
						if (breakRequested || returnSignal != null) return
					}
				}
				iterate(0, emptyList())
				returnSignal?.let { return it }
			}

			is Node.TypeLiteral -> runtimeError(context, "A type literal is only valid after as/is/!is")

			is Node.InfixCall -> {
				val receiver = runNode(node.receiver, context)
				val argument = runNode(node.argument, context)
				val callContext = context.copy(holder = receiver, explicitHolder = true)
				val (functionFile, function) = findFunction(node.functionName, callContext)
					?: runtimeError(context, "Unknown infix function ${node.functionName}")
				runtimeCheck(context, CrescentToken.Modifier.INFIX in function.modifiers) {
					"Function ${node.functionName} is not declared infix"
				}
				val functionContext = BlockContext(functionFile, receiver, mutableMapOf(), mutableMapOf())
				return if (CrescentToken.Modifier.ASYNC in function.modifiers) {
					Instance.Future(submitAsync(context.file.path) { runFunction(function, listOf(argument), functionContext) })
				} else runFunction(function, listOf(argument), functionContext)
			}

			is Node.Variable.Basic,
			is Node.Variable.Local -> {
				val variable = runVariable(node, context)
				runtimeCheck(context, node.name !in context.variables) { "Variable ${node.name} is already declared in this scope" }
				context.variables[variable.name] = variable
			}

			else -> runtimeError(context, "Unexpected runtime node: $node")
		}

		return Type.unit
	}

	fun runVariable(node: Node.Variable, context: BlockContext): Variable {

		val value = when (node) {

			is Node.Variable.Basic -> runNode(node.value, context)

			is Node.Variable.Local -> runNode(node.value, context)

			is Node.Variable.Constant -> {
				if (context.holder is Node.File || context.holder is Node.Object) {
					runNode(node.value, context)
				}
				else runtimeError(context, "Constant ${node.name} must be declared in an object or file")
			}

			else -> runtimeError(context, "Unsupported variable declaration: $node")
		}
		val type = if (node.type is Type.Implicit) findType(value) else node.type
		if (node.type is Type.Implicit) freezeArrayConstraints(value)
		val coercedValue = if (node.type is Type.Implicit) value else coerceBoundary(type, value, context) {
			"Variable ${node.name} expects $type, got ${findType(value)}"
		}
		if (node.type !is Type.Implicit) {
			checkIsSameType(type, coercedValue, context) {
				"Variable ${node.name} expects $type, got ${findType(coercedValue)}"
			}
		}

		val visibility = when (node) {
			is Node.Variable.Basic -> node.visibility
			is Node.Variable.Constant -> node.visibility
			else -> CrescentToken.Visibility.PUBLIC
		}
		return Variable(node.name, boundaryInstance(type, coercedValue), node.isFinal, type, context.file, visibility)
	}

	fun runObject(objectNode: Node.Object, context: BlockContext): Instance.Object {
		val key = ObjectKey(objectNode)
		val declarationFile = files.firstOrNull { file -> file.objects.values.any { it === objectNode } || file.sealeds.values.any { sealed -> sealed.objects.any { it === objectNode } } }
			?: runtimeError(context, "Object ${objectNode.name} is not part of the linked program")
		val declarationId = declarationIdFor(declarationFile, objectNode.name, Node.ModuleSymbolKind.OBJECT)
		val active = activeInitializations.get()
		if (key in active) runtimeError(context, "Cyclic initialization of object ${objectNode.name}")

		var initializes = false
		val initialization = synchronized(initializationLock) {
			objectCache[declarationId]?.let { return it }
			objectInitializations[key] ?: createInitialization<Instance.Object>(key).also {
				objectInitializations[key] = it
				initializes = true
			}
		}
		if (!initializes) return awaitInitialization(key, initialization, context)

		active += key
		return try {
			val initializedMembers = mutableMapOf<String, Variable>()
			val objectContext = BlockContext(declarationFile, objectNode, mutableMapOf(), initializedMembers)
			val constants = linkedMapOf<String, Variable>()
			val variables = linkedMapOf<String, Variable>()
			for ((name, declaration) in objectNode.constants) {
				val value = runVariable(declaration, objectContext)
				constants[name] = value
				initializedMembers[name] = value
			}
			for ((name, declaration) in objectNode.variables) {
				val value = runVariable(declaration, objectContext)
				variables[name] = value
				initializedMembers[name] = value
			}

			val instance = Instance.Object(
				objectNode.name,
				objectNode.type,
				constants,
				variables,
				objectNode.functions,
				declarationId,
			)
			synchronized(initializationLock) {
				objectCache[declarationId] = instance
				initializationOwners.remove(key)
			}
			initialization.future.complete(instance)
			instance
		} catch (throwable: Throwable) {
			synchronized(initializationLock) {
				initializationOwners.remove(key)
			}
			initialization.future.completeExceptionally(throwable)
			throw throwable
		} finally {
			finishInitialization(key, active)
		}
	}

	private sealed interface EvalNode {
		data class Leaf(val node: Node) : EvalNode
		data class Unary(val operator: CrescentToken.Operator, val operand: EvalNode) : EvalNode
		data class Binary(val operator: CrescentToken.Operator, val left: EvalNode, val right: EvalNode) : EvalNode
	}

	private interface AssignmentTarget {
		val supportsCompound: Boolean get() = true
		fun read(): Node
		fun write(value: Node)
		fun update(transform: (Node) -> Node)
	}

	fun runExpression(expression: Node.Expression, context: BlockContext): Node =
		evaluateExpressionTree(buildExpressionTree(expression, context), context)

	private fun buildExpressionTree(expression: Node.Expression, context: BlockContext): EvalNode {
		val stack = ArrayDeque<EvalNode>()
		for (node in expression.nodes) {
			if (node !is CrescentToken.Operator) {
				stack.addLast(EvalNode.Leaf(node))
				continue
			}
			when (operatorArity[node]) {
				1 -> {
					val operand = popEval(stack, context, "Operator '$node' is missing an operand")
					stack.addLast(EvalNode.Unary(node, operand))
				}
				2 -> {
					val right = popEval(stack, context, "Operator '$node' is missing a right operand")
					val left = popEval(stack, context, "Operator '$node' is missing a left operand")
					stack.addLast(EvalNode.Binary(node, left, right))
				}
				else -> runtimeError(context, "Token '$node' is not valid in a runtime expression")
			}
		}
		runtimeCheck(context, stack.size == 1) { "Expression left ${stack.size} unevaluated values" }
		return stack.removeLast()
	}

	private fun popEval(stack: ArrayDeque<EvalNode>, context: BlockContext, error: String): EvalNode {
		if (stack.isEmpty()) runtimeError(context, error)
		return stack.removeLast()
	}

	private fun evaluateExpressionTree(tree: EvalNode, context: BlockContext): Node = when (tree) {
		is EvalNode.Leaf -> runNode(tree.node, context)
		is EvalNode.Unary -> {
			val value = evaluateExpressionTree(tree.operand, context)
			when (tree.operator) {
				CrescentToken.Operator.RESULT -> when (value) {
					is Instance.Result.Success -> value.value
					is Instance.Result.Failure -> {
						runtimeCheck(context, context.enclosingReturnType() is Type.Result) {
							"Result propagation is only valid in a Result-returning function"
						}
						throw PropagateResult(value)
					}
					else -> runtimeError(context, "Result propagation expects Success or Failure, got ${findType(value)}")
				}
				CrescentToken.Operator.NOT -> invokeUserOperator(value, "not", emptyList(), context)
					?: runEagerExpression(Node.Expression(listOf(value, tree.operator)), context)
				else -> runEagerExpression(Node.Expression(listOf(value, tree.operator)), context)
			}
		}
		is EvalNode.Binary -> when (tree.operator) {
			CrescentToken.Operator.AND_COMPARE -> {
				val left = requireBoolean(evaluateExpressionTree(tree.left, context), context, "left operand of &&")
				if (!left) Primitive.Boolean(false)
				else Primitive.Boolean(requireBoolean(evaluateExpressionTree(tree.right, context), context, "right operand of &&"))
			}
			CrescentToken.Operator.OR_COMPARE -> {
				val left = requireBoolean(evaluateExpressionTree(tree.left, context), context, "left operand of ||")
				if (left) Primitive.Boolean(true)
				else Primitive.Boolean(requireBoolean(evaluateExpressionTree(tree.right, context), context, "right operand of ||"))
			}
			CrescentToken.Operator.AS,
			CrescentToken.Operator.INSTANCE_OF,
			CrescentToken.Operator.NOT_INSTANCE_OF -> {
				val value = evaluateExpressionTree(tree.left, context)
				val literal = (tree.right as? EvalNode.Leaf)?.node as? Node.TypeLiteral
					?: runtimeError(context, "${tree.operator} requires a type literal on the right")
				validateTypeReference(literal.type, context)
				when (tree.operator) {
					CrescentToken.Operator.AS -> castValue(value, literal.type, context)
					CrescentToken.Operator.INSTANCE_OF -> Primitive.Boolean(isAssignable(literal.type, value, context))
					else -> Primitive.Boolean(!isAssignable(literal.type, value, context))
				}
			}
			CrescentToken.Operator.ASSIGN,
			CrescentToken.Operator.ADD_ASSIGN,
			CrescentToken.Operator.SUB_ASSIGN,
			CrescentToken.Operator.MUL_ASSIGN,
			CrescentToken.Operator.DIV_ASSIGN,
			CrescentToken.Operator.REM_ASSIGN,
			CrescentToken.Operator.POW_ASSIGN -> {
				val target = (tree.left as? EvalNode.Leaf)?.node
					?: runtimeError(context, "${tree.operator} requires an assignment target")
				val location = resolveAssignmentTarget(target, context)
				val right = evaluateExpressionTree(tree.right, context)
				if (tree.operator == CrescentToken.Operator.ASSIGN) {
					location.write(right)
					Type.unit
				} else {
					runtimeCheck(context, location.supportsCompound) {
						"Compound assignment does not dispatch user operators"
					}
					val rightString = if (tree.operator == CrescentToken.Operator.ADD_ASSIGN) right.asString() else null
					location.update { left -> compoundAssignmentValue(tree.operator, left, right, rightString, context) }
					Type.unit
				}
			}
			else -> {
				val left = evaluateExpressionTree(tree.left, context)
				val right = evaluateExpressionTree(tree.right, context)
				dispatchBinaryOperator(tree.operator, left, right, context)
					?: runEagerExpression(Node.Expression(listOf(left, right, tree.operator)), context)
			}
		}
	}

	private fun resolveAssignmentTarget(node: Node, context: BlockContext): AssignmentTarget = when (node) {
		is Node.Identifier -> variableAssignmentTarget(
			resolveVariable(node.name, context) ?: runtimeError(context, "Variable ${node.name} was not found for reassignment"),
			context,
		)
		is Node.GetCall -> arrayAssignmentTarget(node, context)
		is Node.DotChain -> {
			runtimeCheck(context, node.nodes.size >= 2) { "A dotted assignment target needs a receiver and member" }
			var holder = runNode(node.nodes.first(), context)
			for (member in node.nodes.subList(1, node.nodes.lastIndex)) {
				holder = runNode(member, context.copy(holder = holder, explicitHolder = true))
			}
			val memberContext = context.copy(holder = holder, explicitHolder = true)
			when (val member = node.nodes.last()) {
				is Node.Identifier -> variableAssignmentTarget(
					resolveHolderVariable(member.name, memberContext)
						?: runtimeError(context, "Member ${member.name} was not found for reassignment"),
					memberContext,
				)
				is Node.GetCall -> arrayAssignmentTarget(member, memberContext)
				else -> runtimeError(context, "Invalid dotted assignment target: $member")
			}
		}
		else -> runtimeError(context, "Invalid assignment target: $node")
	}

	private fun variableAssignmentTarget(variable: Variable, context: BlockContext): AssignmentTarget = object : AssignmentTarget {
		override fun read(): Node = variable.instance.asNode()
		override fun write(value: Node) = assignVariable(variable, value, context)
		override fun update(transform: (Node) -> Node) = updateVariable(variable, context, transform)
	}

	private fun arrayAssignmentTarget(call: Node.GetCall, context: BlockContext): AssignmentTarget {
		runtimeCheck(context, call.arguments.size == 1) { "Array assignment expects one index, got ${call.arguments.size}" }
		val variable = resolveVariable(call.identifier, context)
			?: runtimeError(context, "Variable ${call.identifier} was not found for array assignment")
		val receiver = variable.instance.asNode()
		val indexValue = runNode(call.arguments.single(), context)
		val array = receiver as? Instance.Array
		val index = if (array != null) requireIndex(indexValue, context) else null
		if (array != null) checkArrayIndex(array, checkNotNull(index), context)
		return object : AssignmentTarget {
			override val supportsCompound: Boolean = array != null
			override fun read(): Node = if (array != null) arrayElement(array, checkNotNull(index), context) else
				invokeUserOperator(receiver, "get", listOf(indexValue), context)
					?: runtimeError(context, "Indexed access expects an Array or operator get receiver")
			override fun write(value: Node) {
				if (array == null) {
					val result = invokeUserOperator(receiver, "set", listOf(indexValue, value), context)
						?: runtimeError(context, "Indexed assignment expects an Array or operator set receiver")
					runtimeCheck(context, result == Type.unit) { "operator set must return Unit" }
					return
				}
				val declarationContext = variable.declarationFile?.let { BlockContext(it, it, mutableMapOf()) } ?: context
				(variable.declaredType as? Type.Array)?.let { elementType ->
					runtimeCheck(declarationContext, isAssignable(elementType.type, value, declarationContext)) {
						"Array ${call.identifier} expects ${elementType.type}, got ${findType(value)}"
					}
				}
				setArrayElement(array, checkNotNull(index), value, context)
			}
			override fun update(transform: (Node) -> Node) {
				if (array == null) runtimeError(context, "Compound assignment does not dispatch user operators")
				val declarationContext = variable.declarationFile?.let { BlockContext(it, it, mutableMapOf()) } ?: context
				val resolvedIndex = checkNotNull(index)
				checkArrayIndex(array, resolvedIndex, context)
				array.update(resolvedIndex) { current ->
					val transformed = transform(current)
					val value = coerceArrayElement(array, transformed, declarationContext) {
						"Array ${call.identifier} expects ${arrayConstraint(array).type}, got ${findType(transformed)}"
					}
					(variable.declaredType as? Type.Array)?.let { elementType ->
						runtimeCheck(declarationContext, isAssignable(elementType.type, value, declarationContext)) {
							"Array ${call.identifier} expects ${elementType.type}, got ${findType(value)}"
						}
					}
					value
				}
			}
		}
	}

	private fun compoundAssignmentValue(
		operator: CrescentToken.Operator,
		left: Node,
		right: Node,
		rightString: String?,
		context: BlockContext,
	): Node {
		runtimeCheck(context, left !is Instance.Struct && left !is Instance.Enum && left !is Instance.Object) {
			"Compound assignment does not dispatch user operators"
		}
		return when (operator) {
			CrescentToken.Operator.ADD_ASSIGN -> if (left is Primitive.String) {
				Primitive.String(left.data + checkNotNull(rightString))
			} else numericOperation(context, "+=") { requireNumber(left, context, "left operand of +=") + requireNumber(right, context, "right operand of +=") }
			CrescentToken.Operator.SUB_ASSIGN -> numericOperation(context, "-=") { requireNumber(left, context, "left operand of -=") - requireNumber(right, context, "right operand of -=") }
			CrescentToken.Operator.MUL_ASSIGN -> numericOperation(context, "*=") { requireNumber(left, context, "left operand of *=").multiply(requireNumber(right, context, "right operand of *=")) }
			CrescentToken.Operator.DIV_ASSIGN -> divideNumbers(requireNumber(left, context, "left operand of /="), requireNumber(right, context, "right operand of /="), context)
			CrescentToken.Operator.REM_ASSIGN -> numericOperation(context, "%=") { requireNumber(left, context, "left operand of %=") % requireNumber(right, context, "right operand of %=") }
			CrescentToken.Operator.POW_ASSIGN -> numericOperation(context, "^=") { requireNumber(left, context, "left operand of ^=").pow(requireNumber(right, context, "right operand of ^=")) }
			else -> error("Not a compound assignment operator: $operator")
		}
	}

	private fun dispatchBinaryOperator(
		operator: CrescentToken.Operator,
		left: Node,
		right: Node,
		context: BlockContext,
	): Node? {
		val methodName = binaryOperatorMethods[operator] ?: return null
		val receiver = if (operator in setOf(CrescentToken.Operator.CONTAINS, CrescentToken.Operator.NOT_CONTAINS)) right else left
		val argument = if (receiver === right) left else right
		if (operator == CrescentToken.Operator.SUB && left is Primitive.Number && BigDecimal(left.asString()).signum() == 0 &&
			right is Instance && right !is Instance.Node && right !is Instance.Result) {
			return invokeUserOperator(right, "unaryMinus", emptyList(), context)
		}
		val result = invokeUserOperator(receiver, methodName, listOf(argument), context) ?: run {
			if (receiver is Instance.Struct || receiver is Instance.Enum || receiver is Instance.Object) {
				if (methodName == "equals") return null
				runtimeError(context, "No operator $methodName is declared for ${findType(receiver)}")
			}
			return null
		}
		return when (operator) {
			CrescentToken.Operator.NOT_EQUALS_COMPARE -> Primitive.Boolean(!requireBoolean(result, context, "operator equals result"))
			CrescentToken.Operator.NOT_CONTAINS -> Primitive.Boolean(!requireBoolean(result, context, "operator contains result"))
			CrescentToken.Operator.LESSER_COMPARE,
			CrescentToken.Operator.LESSER_EQUALS_COMPARE,
			CrescentToken.Operator.GREATER_COMPARE,
			CrescentToken.Operator.GREATER_EQUALS_COMPARE -> {
				val comparison = (result as? Primitive.Number.I32)?.data
					?: runtimeError(context, "operator compareTo must return I32")
				Primitive.Boolean(when (operator) {
					CrescentToken.Operator.LESSER_COMPARE -> comparison < 0
					CrescentToken.Operator.LESSER_EQUALS_COMPARE -> comparison <= 0
					CrescentToken.Operator.GREATER_COMPARE -> comparison > 0
					else -> comparison >= 0
				})
			}
			else -> result
		}
	}

	private fun invokeUserOperator(receiver: Node, name: String, arguments: List<Node>, context: BlockContext): Node? {
		val declarationId = when (receiver) {
			is Instance.Struct -> receiver.declarationId
			is Instance.Enum -> receiver.declarationId
			is Instance.Object -> receiver.declarationId
			else -> return null
		} ?: return null
		val found = findImplementationFunction(findType(receiver), declarationId, name, static = false, context) ?: return null
		val (file, function) = found
		runtimeCheck(context, isAccessible(function.visibility, file, context.file)) {
			"Operator $name is not accessible from ${context.file.path}"
		}
		runtimeCheck(context, CrescentToken.Modifier.OPERATOR in function.modifiers) { "${function.name} is not declared operator" }
		return runFunction(function, arguments, BlockContext(file, receiver, mutableMapOf()))
	}

	private val binaryOperatorMethods = mapOf(
		CrescentToken.Operator.ADD to "plus", CrescentToken.Operator.SUB to "minus",
		CrescentToken.Operator.MUL to "times", CrescentToken.Operator.DIV to "div",
		CrescentToken.Operator.REM to "rem", CrescentToken.Operator.POW to "pow",
		CrescentToken.Operator.EQUALS_COMPARE to "equals", CrescentToken.Operator.NOT_EQUALS_COMPARE to "equals",
		CrescentToken.Operator.LESSER_COMPARE to "compareTo", CrescentToken.Operator.LESSER_EQUALS_COMPARE to "compareTo",
		CrescentToken.Operator.GREATER_COMPARE to "compareTo", CrescentToken.Operator.GREATER_EQUALS_COMPARE to "compareTo",
		CrescentToken.Operator.CONTAINS to "contains", CrescentToken.Operator.NOT_CONTAINS to "contains",
		CrescentToken.Operator.BIT_SHIFT_LEFT to "bitShiftLeft", CrescentToken.Operator.BIT_SHIFT_RIGHT to "bitShiftRight",
		CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT to "unsignedBitShiftRight",
		CrescentToken.Operator.BIT_AND to "bitAnd", CrescentToken.Operator.BIT_OR to "bitOr", CrescentToken.Operator.BIT_XOR to "bitXor",
	)

	private val operatorArity = mapOf(
		CrescentToken.Operator.NOT to 1,
		CrescentToken.Operator.RESULT to 1,
	) + setOf(
		CrescentToken.Operator.ADD, CrescentToken.Operator.SUB, CrescentToken.Operator.MUL,
		CrescentToken.Operator.DIV, CrescentToken.Operator.POW, CrescentToken.Operator.REM,
		CrescentToken.Operator.ASSIGN, CrescentToken.Operator.ADD_ASSIGN, CrescentToken.Operator.SUB_ASSIGN,
		CrescentToken.Operator.MUL_ASSIGN, CrescentToken.Operator.DIV_ASSIGN, CrescentToken.Operator.REM_ASSIGN,
		CrescentToken.Operator.POW_ASSIGN, CrescentToken.Operator.OR_COMPARE, CrescentToken.Operator.AND_COMPARE,
		CrescentToken.Operator.EQUALS_COMPARE, CrescentToken.Operator.LESSER_COMPARE,
		CrescentToken.Operator.GREATER_COMPARE, CrescentToken.Operator.LESSER_EQUALS_COMPARE,
		CrescentToken.Operator.GREATER_EQUALS_COMPARE, CrescentToken.Operator.BIT_SHIFT_RIGHT,
		CrescentToken.Operator.BIT_SHIFT_LEFT, CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT,
		CrescentToken.Operator.BIT_OR, CrescentToken.Operator.BIT_XOR, CrescentToken.Operator.BIT_AND,
		CrescentToken.Operator.EQUALS_REFERENCE_COMPARE, CrescentToken.Operator.NOT_EQUALS_COMPARE,
		CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE, CrescentToken.Operator.CONTAINS,
		CrescentToken.Operator.NOT_CONTAINS, CrescentToken.Operator.RANGE_TO, CrescentToken.Operator.AS,
		CrescentToken.Operator.INSTANCE_OF, CrescentToken.Operator.NOT_INSTANCE_OF,
	).associateWith { 2 }

	private fun runEagerExpression(expression: Node.Expression, context: BlockContext): Node {

		val stack = LinkedList<Node>()

		for (node in expression.nodes) {
			when (node) {

				is CrescentToken.Operator -> {
					when (node) {

						CrescentToken.Operator.NOT -> {
							val value = popBoolean(stack, context, node)
							stack.push(Primitive.Boolean(!value.data))
						}

						CrescentToken.Operator.ADD -> {
							val pop1 = popValue(stack, context, node)
							val pop2 = popValue(stack, context, node)

							stack.push(
								if (pop2 is Primitive.String || pop1 is Primitive.String) {
									Primitive.String(pop2.asString() + pop1.asString())
								} else {
									numericOperation(context, "+") { requireNumber(pop2, context, "left operand of +") + requireNumber(pop1, context, "right operand of +") }
								}
							)
						}
						CrescentToken.Operator.SUB -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = stack.poll()?.let { requireNumber(runNode(it, context), context, "left operand of -") }

							if (pop2 == null) {
								stack.push(numericOperation(context, "unary -") { pop1.multiply(Primitive.Number.I8(-1)) })
							}
							else {
								stack.push(numericOperation(context, "-") { pop2 - pop1 })
							}
						}
						CrescentToken.Operator.MUL -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(numericOperation(context, "*") { pop2.multiply(pop1) })
						}
						CrescentToken.Operator.DIV -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(divideNumbers(pop2, pop1, context))
						}
						CrescentToken.Operator.POW -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(numericOperation(context, "^") { pop2.pow(pop1) })
						}
						CrescentToken.Operator.REM -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(numericOperation(context, "%") { pop2 % pop1 })
						}

						CrescentToken.Operator.ASSIGN -> {

							val value = popValue(stack, context, node)

							when (val pop2 = popNode(stack, context, node)) {

								is Node.GetCall -> {
									runtimeCheck(context, pop2.arguments.size == 1) {
										"Array assignment expects one index, got ${pop2.arguments.size}"
									}
									val variable = resolveVariable(pop2.identifier, context)
										?: runtimeError(context, "Variable ${pop2.identifier} was not found for array assignment")
									val array = requireArray(variable.instance.asNode(), context, pop2.identifier)
									val index = requireIndex(runNode(pop2.arguments.single(), context), context)
								checkArrayIndex(array, index, context)
					(variable.declaredType as? Type.Array)?.let { elementType ->
										runtimeCheck(context, isAssignable(elementType.type, value, context)) {
											"Array ${pop2.identifier} expects ${elementType.type}, got ${findType(value)}"
										}
									}
								setArrayElement(array, index, value, context)
								}

								is Node.Identifier -> assignValue(pop2, value, context)
								else -> runtimeError(context, "Invalid assignment target: $pop2")
							}

							return Type.unit
						}

						CrescentToken.Operator.ADD_ASSIGN,
						CrescentToken.Operator.SUB_ASSIGN,
						CrescentToken.Operator.MUL_ASSIGN,
						CrescentToken.Operator.DIV_ASSIGN,
						CrescentToken.Operator.REM_ASSIGN,
						CrescentToken.Operator.POW_ASSIGN -> {
							val right = popValue(stack, context, node)
							val target = popNode(stack, context, node) as? Node.Identifier
								?: runtimeError(context, "$node requires an identifier assignment target")
							val variable = resolveVariable(target.name, context)
								?: runtimeError(context, "Variable ${target.name} was not found for reassignment")
							val rightString = if (node == CrescentToken.Operator.ADD_ASSIGN) right.asString() else null
							updateVariable(variable, context) { left -> compoundAssignmentValue(node, left, right, rightString, context) }
							return Type.unit
						}

						CrescentToken.Operator.OR_COMPARE -> {
							val pop1 = popBoolean(stack, context, node)
							val pop2 = popBoolean(stack, context, node)
							stack.push(Primitive.Boolean(pop2.data || pop1.data))
						}

						CrescentToken.Operator.AND_COMPARE -> {
							val pop1 = popBoolean(stack, context, node)
							val pop2 = popBoolean(stack, context, node)
							stack.push(Primitive.Boolean(pop2.data && pop1.data))
						}

						CrescentToken.Operator.EQUALS_COMPARE -> {
							val pop1 = popValue(stack, context, node)
							val pop2 = popValue(stack, context, node)
							stack.push(Primitive.Boolean(valuesEqual(pop2, pop1)))
						}
						CrescentToken.Operator.LESSER_EQUALS_COMPARE -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(Primitive.Boolean(compareNumbers(pop2, pop1)?.let { it <= 0 } == true))
						}
						CrescentToken.Operator.GREATER_EQUALS_COMPARE -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(Primitive.Boolean(compareNumbers(pop2, pop1)?.let { it >= 0 } == true))
						}

						CrescentToken.Operator.LESSER_COMPARE -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(Primitive.Boolean(compareNumbers(pop2, pop1)?.let { it < 0 } == true))
						}
						CrescentToken.Operator.GREATER_COMPARE -> {
							val pop1 = popNumber(stack, context, node)
							val pop2 = popNumber(stack, context, node)
							stack.push(Primitive.Boolean(compareNumbers(pop2, pop1)?.let { it > 0 } == true))
						}

						CrescentToken.Operator.EQUALS_REFERENCE_COMPARE -> {
							val pop1 = popValue(stack, context, node)
							val pop2 = popValue(stack, context, node)
							stack.push(Primitive.Boolean(pop2 === pop1))
						}

						CrescentToken.Operator.NOT_EQUALS_COMPARE -> {

							val pop1 = popValue(stack, context, node)
							val pop2 = popValue(stack, context, node)
							stack.push(Primitive.Boolean(!valuesEqual(pop2, pop1)))
						}

						CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE -> {
							val pop1 = popValue(stack, context, node)
							val pop2 = popValue(stack, context, node)
							stack.push(Primitive.Boolean(pop2 !== pop1))
						}
						CrescentToken.Operator.CONTAINS,
						CrescentToken.Operator.NOT_CONTAINS -> {
							val needle = popValue(stack, context, node)
							val haystack = popValue(stack, context, node)
							val contains = when (haystack) {
								is Primitive.String -> haystack.data.contains(needle.asString())
								is Instance.Array -> arraySnapshot(haystack).any { valuesEqual(it, needle) }
								else -> runtimeError(context, "$node expects a String or Array on the left, got ${findType(haystack)}")
							}
							stack.push(Primitive.Boolean(if (node == CrescentToken.Operator.CONTAINS) contains else !contains))
						}
						CrescentToken.Operator.RANGE_TO -> {
							val end = requireIndex(popValue(stack, context, node), context, "range end")
							val start = requireIndex(popValue(stack, context, node), context, "range start")
							stack.push(Instance.Array((start..end).map { Primitive.Number.I32(it) }.toTypedArray(), Type.Basic("I32"), tentative = false))
						}
						CrescentToken.Operator.TYPE_PREFIX -> runtimeError(context, "Type declarations are not runtime expressions")
						CrescentToken.Operator.RETURN -> {
							return stack.poll() ?: Type.unit
						}
						CrescentToken.Operator.RESULT -> runtimeError(context, "Internal error: Result propagation bypassed the Eval tree")
						CrescentToken.Operator.COMMA -> runtimeError(context, "Comma is only valid between arguments")
						CrescentToken.Operator.DOT -> runtimeError(context, "Dot access must be represented as a dot chain")
						CrescentToken.Operator.AS -> runtimeError(context, "Internal error: typed cast bypassed the Eval tree")
						CrescentToken.Operator.IMPORT_SEPARATOR -> runtimeError(context, "Import separators are not runtime expressions")

						CrescentToken.Operator.INSTANCE_OF -> runtimeError(context, "Internal error: type test bypassed the Eval tree")

						CrescentToken.Operator.BIT_SHIFT_RIGHT -> {
							val count = popValue(stack, context, node)
							val value = popNumber(stack, context, node)
							stack.push(shiftNumber(value, count, context, unsigned = false, left = false))
						}

						CrescentToken.Operator.BIT_SHIFT_LEFT -> {

							val count = popValue(stack, context, node)
							val value = popNumber(stack, context, node)
							stack.push(shiftNumber(value, count, context, unsigned = false, left = true))
						}

						CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT -> {

							val count = popValue(stack, context, node)
							val value = popNumber(stack, context, node)
							stack.push(shiftNumber(value, count, context, unsigned = true, left = false))
						}

						CrescentToken.Operator.BIT_OR -> {

							val right = popNumber(stack, context, node)
							val left = popNumber(stack, context, node)
							stack.push(bitwiseNumbers(left, right, context, "or", Long::or, ULong::or))
						}

						CrescentToken.Operator.BIT_AND -> {

							val right = popNumber(stack, context, node)
							val left = popNumber(stack, context, node)
							stack.push(bitwiseNumbers(left, right, context, "and", Long::and, ULong::and))
						}
						CrescentToken.Operator.BIT_XOR -> {

							val right = popNumber(stack, context, node)
							val left = popNumber(stack, context, node)
							stack.push(bitwiseNumbers(left, right, context, "xor", Long::xor, ULong::xor))
						}

						CrescentToken.Operator.NOT_INSTANCE_OF -> runtimeError(context, "Internal error: negated type test bypassed the Eval tree")
					}
				}

				is Primitive.String,
				is Primitive.Number,
				is Primitive.Char,
				is Primitive.Boolean,
				is Node.Array,
				is Node.Identifier,
				is Node.GetCall,
				is Node.Statement.If,
				is Node.IdentifierCall,
				is Node.DotChain,
				is Instance,
				is Type,
				-> {
					stack.push(node)
				}

				else -> runtimeError(context, "Unexpected expression node: $node")
			}
		}

		runtimeCheck(context, stack.size == 1) { "Expression left ${stack.size} values on the stack" }
		return runNode(popNode(stack, context, "expression result"), context)
	}

	fun runFunctionCall(node: Node.IdentifierCall, context: BlockContext): Node {

		when (node.identifier) {
			"success" -> {
				requireArgumentCount(node, context, 1)
				return Instance.Result.Success(runNode(node.arguments.single(), context))
			}

			"failure" -> {
				requireArgumentCount(node, context, 1)
				return Instance.Result.Failure(runNode(node.arguments.single(), context))
			}

			"sqrt" -> {
				requireArgumentCount(node, context, 1)
				return Primitive.Number.F64(sqrt(requireNumber(runNode(node.arguments[0], context), context, "sqrt argument").toF64().data))
			}

			"sin" -> {
				requireArgumentCount(node, context, 1)
				return Primitive.Number.F64(sin(requireNumber(runNode(node.arguments[0], context), context, "sin argument").toF64().data))
			}

			"round" -> {
				requireArgumentCount(node, context, 1)
				return Primitive.Number.F64(round(requireNumber(runNode(node.arguments[0], context), context, "round argument").toF64().data))
			}

			"print" -> {
				requireArgumentCount(node, context, 1)
				io.print(runNode(node.arguments[0], context).asString())
			}

			"println" -> {

				requireArgumentCount(node, context, 0..1)

				if (node.arguments.isEmpty()) {
					io.println("")
				}
				else {
					io.println(runNode(node.arguments[0], context).asString())
				}
			}

			"readLine" -> {
				requireArgumentCount(node, context, 1)
				io.println(runNode(node.arguments[0], context).asString())
				return readInputValue(context, "readLine") { Primitive.String(it) }
			}

			"readBoolean" -> {
				requireArgumentCount(node, context, 1)
				io.println(runNode(node.arguments[0], context).asString())
				return readInputValue(context, "readBoolean") { Primitive.Boolean(it.toBooleanStrict()) }
			}

			"readDouble" -> {
				requireArgumentCount(node, context, 1)
				io.println(runNode(node.arguments[0], context).asString())
				return readInputValue(context, "readDouble") { Primitive.Number.F64(it.toDouble()) }
			}

			"readInt" -> {
				requireArgumentCount(node, context, 1)
				io.println(runNode(node.arguments[0], context).asString())
				return readInputValue(context, "readInt") { Primitive.Number.I32(it.toInt()) }
			}

			"await" -> {
				requireArgumentCount(node, context, 1)
				runtimeCheck(context, activeInitializations.get().isEmpty()) {
					"Async results cannot be awaited during file or object initialization"
				}
				val future = runNode(node.arguments.single(), context) as? Instance.Future
					?: runtimeError(context, "await expects an async function result")
				return try {
					future.value.join()
				} catch (exception: CompletionException) {
					throw (exception.cause ?: exception)
				}
			}

			"typeOf" -> {
				requireArgumentCount(node, context, 1)
				return findType(runNode(node.arguments[0], context))
			}

			else -> {
				val holderType = (context.holder as? Type.Basic)?.name
				val enum = holderType?.let { findEnumDeclaration(it, context)?.second }

				if (enum != null) {
					return when (node.identifier) {
						"random" -> {
							requireArgumentCount(node, context, 0)
							val entry = enum.structs.randomOrNull()
								?: runtimeError(context, "Cannot select a random entry from empty enum ${enum.name}")
							instantiateEnum(enum, entry.name, context)
						}
						"values" -> {
							requireArgumentCount(node, context, 0)
							Instance.Array(
								enum.structs.map { instantiateEnum(enum, it.name, context) }.toTypedArray(),
								Type.Basic(enum.name),
								tentative = false,
							)
						}
						else -> instantiateEnum(enum, node.identifier, context, node.arguments.map { runNode(it, context) })
					}
				}

				val argumentValues = node.arguments.map { runNode(it, context) }
				val structDeclaration = if (context.explicitHolder && context.holder is Type.Basic) {
					null
				} else {
					findStructDeclaration(node.identifier, context)
				}

				if (structDeclaration != null) {
					val (declarationFile, struct) = structDeclaration
					val minimumArguments = struct.variables.indexOfLast { !it.hasDefaultValue() } + 1
					runtimeCheck(context, argumentValues.size in minimumArguments..struct.variables.size) {
						"Struct ${struct.name} expects $minimumArguments..${struct.variables.size} arguments, got ${argumentValues.size}"
					}
					val parameters = mutableMapOf<String, Variable>()
					val declarationContext = BlockContext(declarationFile, declarationFile, parameters, mutableMapOf())

					struct.variables.forEachIndexed { index, variable ->
						val isProvided = index < argumentValues.size
						val argument = if (isProvided) argumentValues[index] else runNode(variable.value, declarationContext)
						val valueContext = if (isProvided) context else declarationContext

						checkIsSameType(variable.type, argument, valueContext) {
							"Variable ${variable.name} had an argument of type ${findType(argument)}, expected ${variable.type}"
						}
						val coercedArgument = coerceBoundary(variable.type, argument, valueContext) {
							"Variable ${variable.name} had an argument of type ${findType(argument)}, expected ${variable.type}"
						}

						parameters[variable.name] = Variable(variable.name, boundaryInstance(variable.type, coercedArgument), variable.isFinal, variable.type, declarationFile, variable.visibility)
					}

					return Instance.Struct(struct.name, parameters, declarationIdFor(declarationFile, struct.name, Node.ModuleSymbolKind.STRUCT))
				}

				val (functionFile, function) = findFunction(node.identifier, context)
					?: throw CrescentRuntimeException(context.file.path, "Unknown function: ${node.identifier}(${argumentValues.joinToString { it.asString() }})")
				val functionHolder = if (isFunctionOnHolder(function, context.holder, context)) context.holder else functionFile
				val functionContext = BlockContext(functionFile, functionHolder, mutableMapOf(), mutableMapOf())
				return if (CrescentToken.Modifier.ASYNC in function.modifiers) {
					Instance.Future(submitAsync(context.file.path) { runFunction(function, argumentValues, functionContext) })
				} else {
					runFunction(function, argumentValues, functionContext)
				}
			}

		}

		return Type.unit
	}

	fun Node.asString(): String = renderValue(this, IdentityHashMap())

	private fun renderValue(value: Node, active: IdentityHashMap<Node, Boolean>): String = when (value) {
		is Primitive.String -> value.data
		is Type -> "$value"
		is Primitive.Char -> "${value.data}"
		is Primitive.Number -> "$value"
		is Primitive.Boolean -> "${value.data}"
		is Node.Array -> value.values.joinToString(prefix = "[", postfix = "]") { renderValue(it, active) }
		is Instance.Array -> renderComposite(value, active) {
			arraySnapshot(value).joinToString(prefix = "[", postfix = "]") { renderValue(it, active) }
		}
		is Node.Identifier -> value.name
		is Instance.Struct -> renderComposite(value, active) {
			"${value.name}(${value.variables.values.joinToString { "${it.name}=${renderValue(it.instance.asNode(), active)}" }})"
		}
		is Instance.Object -> value.name
		is Instance.Enum -> value.entryName
		is Instance.Future -> "Future(pending)"
		is Instance.Result.Success -> renderComposite(value, active) { "Success(${renderValue(value.value, active)})" }
		is Instance.Result.Failure -> renderComposite(value, active) { "Failure(${renderValue(value.error, active)})" }
		is Instance.Node -> renderComposite(value, active) { renderValue(value.value, active) }
		else -> error("Unknown node ${value::class}")
	}

	private inline fun renderComposite(value: Node, active: IdentityHashMap<Node, Boolean>, render: () -> String): String {
		if (active.put(value, true) != null) return "<cycle>"
		return try {
			render()
		} finally {
			active.remove(value)
		}
	}


	fun findType(value: Node): Type = when (value) {

		is Instance -> value.type
		is Node.Typed -> value.type
		is Type.Basic -> value

		is Node.IdentifierCall -> {
			if (value.identifier in this.mainFile.structs) {
				Type.Basic(value.identifier)
			}
			else Type.any
		}

		else -> error("Unexpected value: ${value::class}")
	}

	fun checkIsSameType(
		parameter: Node.Parameter,
		value: Node,
		errorBlock: (parameterType: Type) -> String,
	) = checkIsSameType(parameter, value, null, errorBlock)

	fun checkIsSameType(
		parameter: Node.Parameter,
		value: Node,
		context: BlockContext?,
		errorBlock: (parameterType: Type) -> String,
	) =
		when (parameter) {

			is Node.Parameter.Basic -> {
				checkIsSameType(parameter.type, value, context) {
					errorBlock(parameter.type)
				}
			}

			is Node.Parameter.WithDefault -> {
				checkIsSameType(parameter.type, value, context) {
					errorBlock(parameter.type)
				}
			}
		}

	fun checkIsSameType(type: Type, value: Node, errorBlock: () -> String) =
		checkIsSameType(type, value, null, errorBlock)

	fun checkIsSameType(type: Type, value: Node, context: BlockContext?, errorBlock: () -> String): Unit {
		when (type) {

		is Type.Array -> {
			if (value !is Instance.Array || !arraySnapshot(value).all { isAssignable(type.type, it, context) }) {
				throw CrescentRuntimeException(context?.file?.path ?: mainFile.path, errorBlock())
			}
		}

		is Type.Basic -> {
			if (type != Type.any && !isAssignable(type, value, context)) {
				throw CrescentRuntimeException(context?.file?.path ?: mainFile.path, errorBlock())
			}
		}

		is Type.Implicit -> Unit
		is Type.Result -> when (value) {
			is Instance.Result.Success -> checkIsSameType(type.type, value.value, context, errorBlock)
			is Instance.Result.Failure -> Unit
			else -> throw CrescentRuntimeException(context?.file?.path ?: mainFile.path, errorBlock())
		}
		else -> {
			throw CrescentRuntimeException(context?.file?.path ?: mainFile.path, "Expected $type, got ${value::class.qualifiedName}")
		}
		}
	}

	fun isAssignable(expected: Type, value: Node, context: BlockContext? = null): Boolean {
		if (expected == Type.any || expected is Type.Implicit) return true
		if (expected is Type.Result) return when (value) {
			is Instance.Result.Success -> isAssignable(expected.type, value.value, context)
			is Instance.Result.Failure -> true
			else -> false
		}
		if (expected is Type.Basic && expected.name in numericTypeNames && value is Primitive.Number) return true
		val actual = findType(value)
		if (expected is Type.Array && value is Instance.Array) return arraySnapshot(value).all { isAssignable(expected.type, it, context) }
		if (expected !is Type.Basic || actual !is Type.Basic) return false
		val dynamicId = when (value) {
			is Instance.Struct -> value.declarationId
			is Instance.Object -> value.declarationId
			is Instance.Enum -> value.declarationId
			else -> null
		}
		if (dynamicId != null) {
			val expectedId = resolveTypeId(expected, context)
			if (expectedId != null) {
				if (expectedId == dynamicId) return true
				if (expectedId.kind == Node.ModuleSymbolKind.SEALED &&
					dynamicId.packageId == expectedId.packageId && dynamicId.declarationPath == expectedId.declarationPath &&
					dynamicId.enclosingTypeName == expectedId.sourceName) return true
				if (expectedId.kind == Node.ModuleSymbolKind.TRAIT && implementsTrait(dynamicId, expectedId)) return true
				return false
			}
			return false
		}
		if (expected.name !in BUILT_IN_TYPE_NAMES) return false
		if (expected == actual) return true

		return false
	}

	private val numericTypeNames = setOf("I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64")

	private fun validateTypeReference(type: Type, context: BlockContext) {
		when (type) {
			is Type.Array -> validateTypeReference(type.type, context)
			is Type.Result -> validateTypeReference(type.type, context)
			is Type.Implicit -> runtimeError(context, "Implicit is not a runtime type")
			is Type.Basic -> {
				if (type.name in BUILT_IN_TYPE_NAMES) return
				val own = type.name in context.file.structs || type.name in context.file.sealeds ||
					type.name in context.file.traits || type.name in context.file.objects || type.name in context.file.enums ||
					context.file.sealeds.values.any { sealed -> sealed.structs.any { it.name == type.name } || sealed.objects.any { it.name == type.name } }
				val imported = context.file.importedSymbols[type.name]?.kind in setOf(
					Node.ModuleSymbolKind.STRUCT, Node.ModuleSymbolKind.SEALED, Node.ModuleSymbolKind.TRAIT,
					Node.ModuleSymbolKind.OBJECT, Node.ModuleSymbolKind.ENUM,
				)
				runtimeCheck(context, own || imported) { "Unknown or inaccessible type ${type.name}" }
			}
			else -> runtimeError(context, "Unsupported runtime type $type")
		}
	}

	private fun coerceBoundary(
		type: Type,
		value: Node,
		context: BlockContext,
		finalizeArrayBoundary: Boolean = true,
		errorBlock: () -> String,
	): Node = when (type) {
		is Type.Implicit -> value
		is Type.Array -> {
			val array = value as? Instance.Array ?: throw CrescentRuntimeException(context.file.path, errorBlock())
			constrainArray(array, type.type, finalize = finalizeArrayBoundary, context, errorBlock)
			array
		}
		is Type.Result -> when (value) {
			is Instance.Result.Failure -> if (value.type == type) value else Instance.Result.Failure(value.error, type)
			is Instance.Result.Success -> {
				val coerced = coerceBoundary(type.type, value.value, context, finalizeArrayBoundary, errorBlock)
				if (coerced === value.value && value.type == type) value else Instance.Result.Success(coerced, type)
			}
			else -> throw CrescentRuntimeException(context.file.path, errorBlock())
		}
		is Type.Basic -> if (type.name in numericTypeNames) {
			val number = value as? Primitive.Number ?: throw CrescentRuntimeException(context.file.path, errorBlock())
			checkedNumericCast(number, type.name, context)
		} else {
			checkIsSameType(type, value, context, errorBlock)
			value
		}
		else -> {
			checkIsSameType(type, value, context, errorBlock)
			value
		}
	}

	private fun constrainArray(
		array: Instance.Array,
		requestedType: Type,
		finalize: Boolean,
		context: BlockContext,
		errorBlock: () -> String,
	) {
		val constraint = array.constrain(requestedType, finalize)
			?: runtimeError(context, errorBlock())
		array.transformAll { element ->
			coerceBoundary(
					constraint.type,
					element,
					context,
					finalizeArrayBoundary = !constraint.tentative,
					errorBlock = errorBlock,
				)
		}
	}

	private fun arrayConstraint(array: Instance.Array): Instance.Array.Constraint = array.constraint()

	private fun freezeArrayConstraints(value: Node) = freezeArrayConstraints(value, IdentityHashMap())

	private fun freezeArrayConstraints(value: Node, visited: IdentityHashMap<Node, Boolean>) {
		if (visited.put(value, true) != null) return
		when (value) {
			is Instance.Array -> {
				value.freeze()
				arraySnapshot(value).forEach { freezeArrayConstraints(it, visited) }
			}
			is Instance.Struct -> value.variables.values.forEach { freezeArrayConstraints(it.instance.asNode(), visited) }
			is Instance.Enum -> value.properties.values.forEach { freezeArrayConstraints(it.instance.asNode(), visited) }
			is Instance.Result.Success -> freezeArrayConstraints(value.value, visited)
			is Instance.Result.Failure -> freezeArrayConstraints(value.error, visited)
			is Instance.Node -> freezeArrayConstraints(value.value, visited)
			else -> Unit
		}
	}

	private fun coerceArrayElement(array: Instance.Array, value: Node, context: BlockContext, errorBlock: () -> String): Node =
		arrayConstraint(array).let {
			coerceBoundary(it.type, value, context, finalizeArrayBoundary = !it.tentative, errorBlock = errorBlock)
		}

	private fun castValue(value: Node, target: Type, context: BlockContext): Node {
		if (target is Type.Basic && target.name in numericTypeNames) {
			val number = value as? Primitive.Number
				?: runtimeError(context, "Cannot cast ${findType(value)} to $target")
			return checkedNumericCast(number, target.name, context)
		}
		runtimeCheck(context, isAssignable(target, value, context)) { "Cannot cast ${findType(value)} to $target" }
		return value
	}

	private fun checkedNumericCast(value: Primitive.Number, target: String, context: BlockContext): Primitive.Number {
		if (target == "F32") {
			val converted = value.toF32()
			runtimeCheck(context, !value.toF64().data.isFinite() || converted.data.isFinite()) { "Numeric cast to F32 overflows" }
			return converted
		}
		if (target == "F64") return value.toF64()
		val decimal = when (value) {
			is Primitive.Number.F32 -> {
				runtimeCheck(context, value.data.isFinite() && value.data % 1f == 0f) { "Integral cast requires a finite whole number" }
				BigDecimal.valueOf(value.data.toDouble())
			}
			is Primitive.Number.F64 -> {
				runtimeCheck(context, value.data.isFinite() && value.data % 1.0 == 0.0) { "Integral cast requires a finite whole number" }
				BigDecimal.valueOf(value.data)
			}
			else -> BigDecimal(value.toString())
		}
		val integer = try { decimal.toBigIntegerExact() } catch (_: ArithmeticException) {
			runtimeError(context, "Integral cast requires a whole number")
		}
		val bounds = integralBounds.getValue(target)
		runtimeCheck(context, integer >= bounds.first && integer <= bounds.second) { "Numeric cast to $target is out of range" }
		return when (target) {
			"I8" -> Primitive.Number.I8(integer.toByte())
			"I16" -> Primitive.Number.I16(integer.toShort())
			"I32" -> Primitive.Number.I32(integer.toInt())
			"I64" -> Primitive.Number.I64(integer.toLong())
			"U8" -> Primitive.Number.U8(integer.toString().toUByte())
			"U16" -> Primitive.Number.U16(integer.toString().toUShort())
			"U32" -> Primitive.Number.U32(integer.toString().toUInt())
			"U64" -> Primitive.Number.U64(integer.toString().toULong())
			else -> runtimeError(context, "Unknown numeric target $target")
		}
	}

	private val integralBounds = mapOf(
		"I8" to (BigInteger.valueOf(Byte.MIN_VALUE.toLong()) to BigInteger.valueOf(Byte.MAX_VALUE.toLong())),
		"I16" to (BigInteger.valueOf(Short.MIN_VALUE.toLong()) to BigInteger.valueOf(Short.MAX_VALUE.toLong())),
		"I32" to (BigInteger.valueOf(Int.MIN_VALUE.toLong()) to BigInteger.valueOf(Int.MAX_VALUE.toLong())),
		"I64" to (BigInteger.valueOf(Long.MIN_VALUE) to BigInteger.valueOf(Long.MAX_VALUE)),
		"U8" to (BigInteger.ZERO to BigInteger.valueOf(UByte.MAX_VALUE.toLong())),
		"U16" to (BigInteger.ZERO to BigInteger.valueOf(UShort.MAX_VALUE.toLong())),
		"U32" to (BigInteger.ZERO to BigInteger(UInt.MAX_VALUE.toString())),
		"U64" to (BigInteger.ZERO to BigInteger(ULong.MAX_VALUE.toString())),
	)

	private fun valuesEqual(left: Node, right: Node): Boolean = valuesEqual(left, right, mutableSetOf())

	private fun valuesEqual(left: Node, right: Node, visited: MutableSet<IdentityPair>): Boolean {
		if (left === right) return true
		return when {
			left is Primitive.Number && right is Primitive.Number -> compareNumbers(left, right) == 0
			left is Instance.Array && right is Instance.Array -> compareComposite(left, right, visited) {
				val leftValues = arraySnapshot(left)
				val rightValues = arraySnapshot(right)
				leftValues.size == rightValues.size && leftValues.indices.all { valuesEqual(leftValues[it], rightValues[it], visited) }
			}
			left is Instance.Struct && right is Instance.Struct -> compareComposite(left, right, visited) {
				left.declarationId == right.declarationId && equalVariableMaps(left.variables, right.variables, visited)
			}
			left is Instance.Enum && right is Instance.Enum -> compareComposite(left, right, visited) {
				left.declarationId == right.declarationId && left.entryName == right.entryName &&
					equalVariableMaps(left.properties, right.properties, visited)
			}
			left is Instance.Object && right is Instance.Object -> false
			left is Instance.Result.Success && right is Instance.Result.Success -> compareComposite(left, right, visited) {
				valuesEqual(left.value, right.value, visited)
			}
			left is Instance.Result.Failure && right is Instance.Result.Failure -> compareComposite(left, right, visited) {
				valuesEqual(left.error, right.error, visited)
			}
			left is Instance.Node && right is Instance.Node -> compareComposite(left, right, visited) {
				valuesEqual(left.value, right.value, visited)
			}
			left is Instance.Node -> compareComposite(left, right, visited) { valuesEqual(left.value, right, visited) }
			right is Instance.Node -> compareComposite(left, right, visited) { valuesEqual(left, right.value, visited) }
			else -> left == right
		}
	}

	private inline fun compareComposite(left: Node, right: Node, visited: MutableSet<IdentityPair>, compare: () -> Boolean): Boolean {
		if (!visited.add(IdentityPair(left, right))) return true
		return compare()
	}

	private fun equalVariableMaps(
		left: Map<String, Variable>,
		right: Map<String, Variable>,
		visited: MutableSet<IdentityPair>,
	): Boolean = left.keys == right.keys && left.keys.all { name ->
		valuesEqual(checkNotNull(left[name]).instance.asNode(), checkNotNull(right[name]).instance.asNode(), visited)
	}

	private class IdentityPair(private val left: Node, private val right: Node) {
		override fun equals(other: Any?): Boolean = other is IdentityPair &&
			(left === other.left && right === other.right || left === other.right && right === other.left)

		override fun hashCode(): Int = System.identityHashCode(left) xor System.identityHashCode(right)
	}

	private fun compareNumbers(left: Primitive.Number, right: Primitive.Number): Int? {
		if (left is Primitive.Number.F32 || left is Primitive.Number.F64 || right is Primitive.Number.F32 || right is Primitive.Number.F64) {
			val leftDouble = left.toF64().data
			val rightDouble = right.toF64().data
			if (leftDouble.isNaN() || rightDouble.isNaN()) return null
			return leftDouble.compareTo(rightDouble)
		}

		val leftUnsigned = left.unsignedValueOrNull()
		val rightUnsigned = right.unsignedValueOrNull()
		if (leftUnsigned != null && rightUnsigned != null) return leftUnsigned.compareTo(rightUnsigned)
		val leftSigned = left.signedValueOrNull()
		val rightSigned = right.signedValueOrNull()
		if (leftSigned != null && rightSigned != null) return leftSigned.compareTo(rightSigned)
		if (leftSigned != null && rightUnsigned != null) {
			return if (leftSigned < 0) -1 else leftSigned.toULong().compareTo(rightUnsigned)
		}
		if (leftUnsigned != null && rightSigned != null) {
			return if (rightSigned < 0) 1 else leftUnsigned.compareTo(rightSigned.toULong())
		}
		return null
	}

	private fun Primitive.Number.signedValueOrNull(): Long? = when (this) {
		is Primitive.Number.I8 -> data.toLong()
		is Primitive.Number.I16 -> data.toLong()
		is Primitive.Number.I32 -> data.toLong()
		is Primitive.Number.I64 -> data
		else -> null
	}

	private fun Primitive.Number.unsignedValueOrNull(): ULong? = when (this) {
		is Primitive.Number.U8 -> data.toULong()
		is Primitive.Number.U16 -> data.toULong()
		is Primitive.Number.U32 -> data.toULong()
		is Primitive.Number.U64 -> data
		else -> null
	}

	private fun popNode(stack: LinkedList<Node>, context: BlockContext, operator: Any): Node =
		stack.poll() ?: runtimeError(context, "$operator is missing an operand")

	private fun popValue(stack: LinkedList<Node>, context: BlockContext, operator: Any): Node =
		runNode(popNode(stack, context, operator), context)

	private fun popNumber(stack: LinkedList<Node>, context: BlockContext, operator: Any): Primitive.Number =
		requireNumber(popValue(stack, context, operator), context, "operand of $operator")

	private fun popBoolean(stack: LinkedList<Node>, context: BlockContext, operator: Any): Primitive.Boolean =
		popValue(stack, context, operator) as? Primitive.Boolean
			?: runtimeError(context, "$operator expects Boolean operands")

	private fun requireNumber(value: Node, context: BlockContext, description: String): Primitive.Number =
		value as? Primitive.Number ?: runtimeError(context, "$description must be numeric, got ${findType(value)}")

	private inline fun numericOperation(
		context: BlockContext,
		operator: String,
		operation: () -> Primitive.Number,
	): Primitive.Number = try {
		operation()
	} catch (exception: ArithmeticException) {
		runtimeError(context, "Invalid arithmetic for $operator: ${exception.message ?: "arithmetic domain error"}", exception)
	} catch (exception: IllegalStateException) {
		runtimeError(context, "Invalid arithmetic for $operator: ${exception.message ?: "incompatible numeric types"}", exception)
	}

	private fun divideNumbers(
		left: Primitive.Number,
		right: Primitive.Number,
		context: BlockContext,
	): Primitive.Number {
		val leftSigned = left.signedValueOrNull()
		val rightSigned = right.signedValueOrNull()
		if (rightSigned == -1L && leftSigned != null) {
			val overflow = if (left is Primitive.Number.I64) leftSigned == Long.MIN_VALUE else leftSigned == Int.MIN_VALUE.toLong()
			runtimeCheck(context, !overflow) { "Integer division overflow" }
		}
		return numericOperation(context, "/") { left / right }
	}

	private fun bitwiseNumbers(
		left: Primitive.Number,
		right: Primitive.Number,
		context: BlockContext,
		operator: String,
		signed: (Long, Long) -> Long,
		unsigned: (ULong, ULong) -> ULong,
	): Primitive.Number {
		val leftUnsigned = left.unsignedValueOrNull()
		val rightUnsigned = right.unsignedValueOrNull()
		val leftSigned = left.signedValueOrNull()
		val rightSigned = right.signedValueOrNull()
		if (leftUnsigned != null && rightUnsigned != null) {
			val result = unsigned(leftUnsigned, rightUnsigned)
			return if (left is Primitive.Number.U64 || right is Primitive.Number.U64) Primitive.Number.U64(result)
			else Primitive.Number.U32(result.toUInt())
		}
		if (leftSigned != null && rightSigned != null) {
			val result = signed(leftSigned, rightSigned)
			return if (left is Primitive.Number.I64 || right is Primitive.Number.I64) Primitive.Number.I64(result)
			else Primitive.Number.I32(result.toInt())
		}
		runtimeError(context, "$operator requires integral operands with matching signedness")
	}

	private fun shiftNumber(
		value: Primitive.Number,
		countValue: Node,
		context: BlockContext,
		unsigned: Boolean,
		left: Boolean,
	): Primitive.Number {
		val countNumber = countValue as? Primitive.Number
			?: runtimeError(context, "Shift count must be an integer")
		val count = countNumber.signedValueOrNull()?.takeIf { it >= 0 }?.toULong()
			?: countNumber.unsignedValueOrNull()
			?: runtimeError(context, "Shift count must be a non-negative integer")
		val isWide = value is Primitive.Number.I64 || value is Primitive.Number.U64
		val maximum = if (isWide) 63uL else 31uL
		runtimeCheck(context, count <= maximum) { "Shift count $count is outside 0..$maximum" }
		val amount = count.toInt()
		value.unsignedValueOrNull()?.let {
			val result = if (left) it shl amount else it shr amount
			return if (isWide) Primitive.Number.U64(result) else Primitive.Number.U32(result.toUInt())
		}
		val signedValue = value.signedValueOrNull()
			?: runtimeError(context, "Shift operands must be integral")
		val result = when {
			left -> signedValue shl amount
			unsigned -> signedValue ushr amount
			else -> signedValue shr amount
		}
		return if (isWide) Primitive.Number.I64(result) else Primitive.Number.I32(result.toInt())
	}

	private fun instanceOf(value: Node): Instance = value as? Instance ?: Instance.Node(findType(value), value)

	private fun boundaryInstance(type: Type, value: Node): Instance = value as? Instance ?: Instance.Node(type, value)

	private fun Instance.asNode(): Node = when (this) {
		is Instance.Node -> value
		else -> this
	}

	private fun resolveIdentifier(name: String, context: BlockContext): Node? {
		if (context.explicitHolder) return resolveHolderNode(name, context)
		val local = context.findLexicalVariable(name)?.instance?.asNode()
			?: resolveHolderVariable(name, context)?.instance?.asNode()
		if (local != null) return local
		(context.holder as? Node.Object)?.let { objectNode ->
			if (name in objectNode.constants || name in objectNode.variables) {
				runtimeError(context, "Object member $name was referenced before it was initialized")
			}
		}
		return resolveHolderNode(name, context)
			?: resolveFileVariable(context.file, name)?.instance?.asNode()
			?: resolveLinkedFileVariable(name, context)?.instance?.asNode()
	}

	private fun resolveVariable(name: String, context: BlockContext): Variable? {
		if (context.explicitHolder) return resolveHolderVariable(name, context)
		val local = context.findLexicalVariable(name)
			?: resolveHolderVariable(name, context)
		if (local != null) return local
		(context.holder as? Node.Object)?.let { objectNode ->
			if (name in objectNode.constants || name in objectNode.variables) {
				runtimeError(context, "Object member $name was referenced before it was initialized")
			}
		}
		return resolveFileVariable(context.file, name) ?: resolveLinkedFileVariable(name, context)
	}

	private fun resolveHolderNode(name: String, context: BlockContext): Node? =
		resolveHolderVariable(name, context)?.instance?.asNode() ?: when (val holder = context.holder) {
		is Type.Basic -> findEnumDeclaration(holder.name, context)?.second?.let { instantiateEnum(it, name, context) }
			is Node.Object -> holder.constants[name]?.value ?: holder.variables[name]?.value
			else -> null
		}

	private fun resolveHolderVariable(name: String, context: BlockContext): Variable? {
		val variable = when (val holder = context.holder) {
			is Instance.Struct -> holder.variables[name]
			is Instance.Object -> holder.constants[name] ?: holder.variables[name]
			is Instance.Enum -> holder.properties[name]
			is Node.File -> resolveFileVariable(holder, name)
			else -> null
		}
		if (variable != null && !isAccessible(variable.visibility, variable.declarationFile, context.file)) {
			runtimeError(context, "Member $name is not accessible from ${context.file.path}")
		}
		return variable
	}

	private fun resolveLinkedFileVariable(name: String, context: BlockContext): Variable? {
		val symbol = context.file.importedSymbols[name] ?: return null
		if (symbol.kind !in setOf(Node.ModuleSymbolKind.VARIABLE, Node.ModuleSymbolKind.CONSTANT)) return null
		val file = files.singleOrNull { it.path.normalize() == symbol.declarationPath.normalize() }
			?: runtimeError(context, "Imported variable $name refers to a missing file")
		validateImportedSymbol(name, symbol, file, context)
		return resolveFileVariable(file, symbol.sourceName)
	}

	private fun resolveFileVariable(file: Node.File, name: String): Variable? {
		val declaration = file.constants[name] ?: file.variables[name] ?: return null
		val key = FileVariableKey(file, name)
		val active = activeInitializations.get()
		if (key in active) {
			throw CrescentRuntimeException(file.path, "Cyclic initialization of file variable $name")
		}

		var initializes = false
		val initialization = synchronized(initializationLock) {
			fileVariables[file]?.get(name)?.let { return it }
			fileVariableInitializations[key] ?: createInitialization<Variable>(key).also {
				fileVariableInitializations[key] = it
				initializes = true
			}
		}
		if (!initializes) return awaitInitialization(key, initialization, file.path)

		active += key
		return try {
			val variable = runVariable(declaration, BlockContext(file, file, mutableMapOf(), mutableMapOf()))
			synchronized(initializationLock) {
				fileVariables.getOrPut(file) { mutableMapOf() }[name] = variable
				initializationOwners.remove(key)
			}
			initialization.future.complete(variable)
			variable
		} catch (throwable: Throwable) {
			synchronized(initializationLock) {
				initializationOwners.remove(key)
			}
			initialization.future.completeExceptionally(throwable)
			throw throwable
		} finally {
			finishInitialization(key, active)
		}
	}

	private fun <T> createInitialization(key: InitializationKey): Initialization<T> =
		Initialization(Thread.currentThread(), CompletableFuture<T>()).also { initializationOwners[key] = it.owner }

	private fun <T> awaitInitialization(
		key: InitializationKey,
		initialization: Initialization<T>,
		context: BlockContext,
	): T = awaitInitialization(key, initialization, context.file.path)

	private fun <T> awaitInitialization(
		key: InitializationKey,
		initialization: Initialization<T>,
		sourcePath: java.nio.file.Path,
	): T {
		if (initialization.future.isDone) return joinInitialization(initialization)
		val waiter = Thread.currentThread()
		synchronized(initializationLock) {
			if (wouldCreateInitializationCycle(waiter, initialization.owner)) {
				throw CrescentRuntimeException(sourcePath, "Cyclic initialization involving ${key.description}")
			}
			threadWaits[waiter] = key
		}
		return try {
			joinInitialization(initialization)
		} finally {
			synchronized(initializationLock) { threadWaits.remove(waiter) }
		}
	}

	private fun <T> joinInitialization(initialization: Initialization<T>): T = try {
		initialization.future.join()
	} catch (exception: CompletionException) {
		throw (exception.cause ?: exception)
	}

	private fun wouldCreateInitializationCycle(waiter: Thread, initialOwner: Thread): Boolean {
		var owner = initialOwner
		val visited = Collections.newSetFromMap(IdentityHashMap<Thread, Boolean>())
		while (visited.add(owner)) {
			if (owner === waiter) return true
			val waitingFor = threadWaits[owner] ?: return false
			owner = initializationOwners[waitingFor] ?: return false
		}
		return false
	}

	private fun finishInitialization(key: InitializationKey, active: MutableSet<InitializationKey>) {
		active -= key
		if (active.isEmpty()) activeInitializations.remove()
	}

	private interface InitializationKey {
		val description: String
	}

	private class FileVariableKey(val file: Node.File, val name: String) : InitializationKey {
		override val description = "file variable $name"

		override fun equals(other: Any?): Boolean =
			other is FileVariableKey && file === other.file && name == other.name

		override fun hashCode(): Int = 31 * System.identityHashCode(file) + name.hashCode()
	}

	private class ObjectKey(val declaration: Node.Object) : InitializationKey {
		override val description = "object ${declaration.name}"

		override fun equals(other: Any?): Boolean = other is ObjectKey && declaration === other.declaration
		override fun hashCode(): Int = System.identityHashCode(declaration)
	}

	private data class Initialization<T>(val owner: Thread, val future: CompletableFuture<T>)

	private fun requireBoolean(value: Node, context: BlockContext, description: String): Boolean =
		(value as? Primitive.Boolean)?.data
			?: runtimeError(context, "$description must be Boolean, got ${findType(value)}")

	private fun requireArray(value: Node, context: BlockContext, name: String): Instance.Array =
		value as? Instance.Array ?: runtimeError(context, "$name is not an array")

	private fun arraySnapshot(array: Instance.Array): Array<Node> = array.snapshot()

	private fun arrayElement(array: Instance.Array, index: Int, context: BlockContext): Node {
		checkArrayIndex(array, index, context)
		return array[index]
	}

	private fun setArrayElement(array: Instance.Array, index: Int, value: Node, context: BlockContext) {
		checkArrayIndex(array, index, context)
		array[index] = coerceArrayElement(array, value, context) {
			"Array expects ${arrayConstraint(array).type}, got ${findType(value)}"
		}
	}

	private fun checkArrayIndex(array: Instance.Array, index: Int, context: BlockContext) {
		runtimeCheck(context, index in 0 until array.size) {
			"Array index $index is out of bounds for size ${array.size}"
		}
	}

	private fun requireIndex(value: Node, context: BlockContext, description: String = "array index"): Int {
		val number = value as? Primitive.Number
			?: runtimeError(context, "$description must be an integer, got ${findType(value)}")
		val integer = when (number) {
			is Primitive.Number.I8 -> number.data.toLong()
			is Primitive.Number.I16 -> number.data.toLong()
			is Primitive.Number.I32 -> number.data.toLong()
			is Primitive.Number.I64 -> number.data
			is Primitive.Number.U8 -> number.data.toLong()
			is Primitive.Number.U16 -> number.data.toLong()
			is Primitive.Number.U32 -> number.data.toLong()
			is Primitive.Number.U64 -> if (number.data <= Long.MAX_VALUE.toULong()) number.data.toLong() else null
			is Primitive.Number.F32 -> number.data.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
			is Primitive.Number.F64 -> number.data.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
			else -> null
		} ?: runtimeError(context, "$description must be an integer, got $number")
		runtimeCheck(context, integer in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
			"$description $integer is outside the supported integer range"
		}
		return integer.toInt()
	}

	private fun requireArgumentCount(call: Node.IdentifierCall, context: BlockContext, expected: Int) =
		requireArgumentCount(call, context, expected..expected)

	private fun requireArgumentCount(call: Node.IdentifierCall, context: BlockContext, expected: IntRange) {
		runtimeCheck(context, call.arguments.size in expected) {
			val description = if (expected.first == expected.last) "${expected.first}" else "${expected.first}..${expected.last}"
			"${call.identifier} expects $description arguments, got ${call.arguments.size}"
		}
	}

	private fun readInputValue(context: BlockContext, operation: String, convert: (String) -> Node): Node {
		val input = try {
			io.readLine()
		} catch (exception: Exception) {
			runtimeError(context, "$operation could not read input", exception)
		} ?: runtimeError(context, "$operation reached end of input")
		return try {
			convert(input)
		} catch (exception: IllegalArgumentException) {
			runtimeError(context, "$operation received invalid input: $input", exception)
		}
	}

	private inline fun runtimeCheck(context: BlockContext, condition: Boolean, message: () -> String) {
		if (!condition) runtimeError(context, message())
	}

	private fun runtimeError(context: BlockContext, message: String, cause: Throwable? = null): Nothing =
		throw CrescentRuntimeException(context.file.path, message, cause)

	private fun assignValue(identifier: Node.Identifier, value: Node, context: BlockContext) {
		val variable = resolveVariable(identifier.name, context)
			?: throw CrescentRuntimeException(context.file.path, "Variable ${identifier.name} was not found for reassignment")
		assignVariable(variable, value, context)
	}

	private fun assignVariable(variable: Variable, value: Node, context: BlockContext) {
		runtimeCheck(context, !variable.isFinal) { "Variable ${variable.name} is not mutable" }
		val declarationContext = variable.declarationFile?.let { BlockContext(it, it, mutableMapOf()) } ?: context
		val coercedValue = coerceBoundary(variable.declaredType, value, declarationContext) {
			"Variable ${variable.name}: ${variable.declaredType} cannot be assigned to ${findType(value)}"
		}
		checkIsSameType(variable.declaredType, coercedValue, declarationContext) {
			"Variable ${variable.name}: ${variable.declaredType} cannot be assigned to ${findType(coercedValue)}"
		}
		variable.instance = when (val storage = variable.instance) {
			is Instance.Node -> Instance.Node(storage.type, coercedValue)
			else -> boundaryInstance(variable.declaredType, coercedValue)
		}
	}

	private fun updateVariable(variable: Variable, context: BlockContext, transform: (Node) -> Node) {
		runtimeCheck(context, !variable.isFinal) { "Variable ${variable.name} is not mutable" }
		val declarationContext = variable.declarationFile?.let { BlockContext(it, it, mutableMapOf()) } ?: context
		variable.updateInstance { storage ->
			val value = transform(storage.asNode())
			val coercedValue = coerceBoundary(variable.declaredType, value, declarationContext) {
				"Variable ${variable.name}: ${variable.declaredType} cannot be assigned to ${findType(value)}"
			}
			checkIsSameType(variable.declaredType, coercedValue, declarationContext) {
				"Variable ${variable.name}: ${variable.declaredType} cannot be assigned to ${findType(coercedValue)}"
			}
			when (storage) {
				is Instance.Node -> Instance.Node(storage.type, coercedValue)
				else -> boundaryInstance(variable.declaredType, coercedValue)
			}
		}
	}

	private fun isFunctionOnHolder(function: Node.Function, holder: Node, context: BlockContext): Boolean = when (holder) {
		is Instance.Struct -> files.any { file -> file.impls.values.any { impl -> (impl.type as? Type.Basic)?.let { resolveTypeId(it, BlockContext(file, file, mutableMapOf())) } == holder.declarationId && function in impl.functions } }
		is Instance.Enum -> files.any { file -> file.impls.values.any { impl -> (impl.type as? Type.Basic)?.let { resolveTypeId(it, BlockContext(file, file, mutableMapOf())) } == holder.declarationId && function in impl.functions } }
		is Instance.Object -> function === holder.functions[function.name]
		is Type.Basic -> {
			val holderId = resolveTypeId(holder, context)
			files.any { file -> file.staticImpls.values.any { impl ->
				val implId = (impl.type as? Type.Basic)?.let { resolveTypeId(it, BlockContext(file, file, mutableMapOf())) }
				(if (holderId == null) impl.type == holder else implId == holderId) && function in impl.functions
			} }
		}
		is Node.Object -> function === holder.functions[function.name]
		is Node.File -> function === holder.functions[function.name]
		else -> false
	}

	private fun findStructDeclaration(name: String, context: BlockContext): Pair<Node.File, Node.Struct>? =
		resolveImportedDeclaration(name, context, Node.ModuleSymbolKind.STRUCT)?.let { (file, sourceName) ->
			findStructInFile(file, sourceName)
		} ?: findStructInFile(context.file, name)

	private fun findStructInFile(file: Node.File, name: String): Pair<Node.File, Node.Struct>? =
		(file.structs[name] ?: file.sealeds.values.firstNotNullOfOrNull { sealed -> sealed.structs.firstOrNull { it.name == name } })?.let { file to it }

	private fun findObjectDeclaration(name: String, context: BlockContext): Pair<Node.File, Node.Object>? =
		resolveImportedDeclaration(name, context, Node.ModuleSymbolKind.OBJECT)?.let { (file, sourceName) ->
			findObjectInFile(file, sourceName)
		} ?: findObjectInFile(context.file, name)

	private fun findObjectInFile(file: Node.File, name: String): Pair<Node.File, Node.Object>? =
		(file.objects[name] ?: file.sealeds.values.firstNotNullOfOrNull { sealed -> sealed.objects.firstOrNull { it.name == name } })?.let { file to it }

	private fun resolveImportedDeclaration(
		name: String,
		context: BlockContext,
		kind: Node.ModuleSymbolKind,
	): Pair<Node.File, String>? {
		val symbol = context.file.importedSymbols[name] ?: return null
		if (symbol.kind != kind) return null
		val file = files.singleOrNull { it.path.normalize() == symbol.declarationPath.normalize() }
			?: runtimeError(context, "Imported symbol $name refers to missing declaration file ${symbol.declarationPath}")
		validateImportedSymbol(name, symbol, file, context)
		return file to symbol.sourceName
	}

	private fun validateImportedSymbol(name: String, symbol: Node.ModuleSymbol, file: Node.File, context: BlockContext) {
		runtimeCheck(context, symbol.sourceName != "main") { "main cannot be imported as $name" }
		runtimeCheck(context, symbol.packageId == file.packageId) { "Imported symbol $name has a tampered package identity" }
		var actualEnclosing: String? = null
		val actualVisibility = when (symbol.kind) {
			Node.ModuleSymbolKind.FUNCTION -> file.functions[symbol.sourceName]?.visibility
			Node.ModuleSymbolKind.VARIABLE -> file.variables[symbol.sourceName]?.visibility
			Node.ModuleSymbolKind.CONSTANT -> file.constants[symbol.sourceName]?.visibility
			Node.ModuleSymbolKind.STRUCT -> file.structs[symbol.sourceName]?.visibility ?: file.sealeds.values.firstNotNullOfOrNull { sealed ->
				sealed.structs.firstOrNull { it.name == symbol.sourceName }?.let {
					actualEnclosing = sealed.name
					mostRestrictive(sealed.visibility, it.visibility)
				}
			}
			Node.ModuleSymbolKind.SEALED -> file.sealeds[symbol.sourceName]?.visibility
			Node.ModuleSymbolKind.TRAIT -> file.traits[symbol.sourceName]?.visibility
			Node.ModuleSymbolKind.OBJECT -> file.objects[symbol.sourceName]?.visibility ?: file.sealeds.values.firstNotNullOfOrNull { sealed ->
				sealed.objects.firstOrNull { it.name == symbol.sourceName }?.let {
					actualEnclosing = sealed.name
					mostRestrictive(sealed.visibility, it.visibility)
				}
			}
			Node.ModuleSymbolKind.ENUM -> file.enums[symbol.sourceName]?.visibility
		}
		runtimeCheck(context, actualVisibility != null && actualVisibility == symbol.visibility) { "Imported symbol $name does not match its declaration" }
		runtimeCheck(context, actualEnclosing == symbol.enclosingTypeName) { "Imported symbol $name has a tampered enclosing declaration" }
		runtimeCheck(context, when (actualVisibility) {
			CrescentToken.Visibility.PUBLIC -> true
			CrescentToken.Visibility.INTERNAL -> context.file.packageId == file.packageId
			CrescentToken.Visibility.PRIVATE -> context.file === file
			null -> false
		}) { "Imported symbol $name is not accessible from ${context.file.path}" }
	}

	private fun mostRestrictive(
		left: CrescentToken.Visibility,
		right: CrescentToken.Visibility,
	): CrescentToken.Visibility = listOf(left, right).maxBy { visibilityRank(it) }

	private fun visibilityRank(visibility: CrescentToken.Visibility): Int = when (visibility) {
		CrescentToken.Visibility.PUBLIC -> 0
		CrescentToken.Visibility.INTERNAL -> 1
		CrescentToken.Visibility.PRIVATE -> 2
	}

	private fun isAccessible(visibility: CrescentToken.Visibility, declarationFile: Node.File?, callerFile: Node.File): Boolean = when (visibility) {
		CrescentToken.Visibility.PUBLIC -> true
		CrescentToken.Visibility.INTERNAL -> declarationFile?.packageId == callerFile.packageId
		CrescentToken.Visibility.PRIVATE -> declarationFile === callerFile
	}

	data class DeclarationId(
		val packageId: String,
		val declarationPath: java.nio.file.Path,
		val kind: Node.ModuleSymbolKind,
		val sourceName: String,
		val enclosingTypeName: String? = null,
	)

	private fun declarationId(file: Node.File, name: String, kind: Node.ModuleSymbolKind, enclosing: String? = null) =
		DeclarationId(file.packageId, file.path.normalize(), kind, name, enclosing)

	private fun declarationIdFor(file: Node.File, name: String, kind: Node.ModuleSymbolKind): DeclarationId {
		val enclosing = when (kind) {
			Node.ModuleSymbolKind.STRUCT -> file.sealeds.values.firstOrNull { sealed -> sealed.structs.any { it.name == name } }?.name
			Node.ModuleSymbolKind.OBJECT -> file.sealeds.values.firstOrNull { sealed -> sealed.objects.any { it.name == name } }?.name
			else -> null
		}
		return declarationId(file, name, kind, enclosing)
	}

	private fun resolveTypeId(type: Type.Basic, context: BlockContext?): DeclarationId? {
		if (type.name in BUILT_IN_TYPE_NAMES || context == null) return null
		val ownKind = when {
			type.name in context.file.structs -> Node.ModuleSymbolKind.STRUCT
			context.file.sealeds.values.any { sealed -> sealed.structs.any { it.name == type.name } } -> Node.ModuleSymbolKind.STRUCT
			type.name in context.file.sealeds -> Node.ModuleSymbolKind.SEALED
			type.name in context.file.traits -> Node.ModuleSymbolKind.TRAIT
			type.name in context.file.objects -> Node.ModuleSymbolKind.OBJECT
			context.file.sealeds.values.any { sealed -> sealed.objects.any { it.name == type.name } } -> Node.ModuleSymbolKind.OBJECT
			type.name in context.file.enums -> Node.ModuleSymbolKind.ENUM
			else -> null
		}
		if (ownKind != null) return declarationIdFor(context.file, type.name, ownKind)
		val symbol = context.file.importedSymbols[type.name] ?: return null
		val file = files.singleOrNull { it.path.normalize() == symbol.declarationPath.normalize() } ?: return null
		validateImportedSymbol(type.name, symbol, file, context)
		return DeclarationId(symbol.packageId, symbol.declarationPath.normalize(), symbol.kind, symbol.sourceName, symbol.enclosingTypeName)
	}

	private fun implementsTrait(dynamicId: DeclarationId, expectedTraitId: DeclarationId): Boolean {
		val visited = mutableSetOf<DeclarationId>()
		fun visit(typeId: DeclarationId): Boolean {
			if (!visited.add(typeId)) return false
			for (file in files) {
				val fileContext = BlockContext(file, file, mutableMapOf())
				for (implementation in file.impls.values) {
					val implementationId = (implementation.type as? Type.Basic)?.let { resolveTypeId(it, fileContext) }
					if (implementationId != typeId) continue
					for (parentType in implementation.extends.filterIsInstance<Type.Basic>()) {
						val parentId = resolveTypeId(parentType, fileContext) ?: continue
						if (parentId == expectedTraitId || visit(parentId)) return true
					}
				}
			}
			return false
		}
		return visit(dynamicId)
	}

	private fun findEnumDeclaration(name: String, context: BlockContext): Pair<Node.File, Node.Enum>? =
		resolveImportedDeclaration(name, context, Node.ModuleSymbolKind.ENUM)?.let { (file, sourceName) ->
			file.enums[sourceName]?.let { file to it }
		} ?: context.file.enums[name]?.let { context.file to it }

	private fun instantiateEnum(
		enum: Node.Enum,
		entryName: String,
		context: BlockContext,
		overrideArguments: List<Node>? = null,
	): Instance.Enum {
		val entry = enum.structs.firstOrNull { it.name == entryName }
			?: throw CrescentRuntimeException(context.file.path, "Enum ${enum.name} has no entry named $entryName")
		val declarationFile = files.firstOrNull { it.enums.values.any { declaration -> declaration === enum } }
			?: runtimeError(context, "Enum ${enum.name} is not part of the linked program")
		val providedArguments = overrideArguments ?: entry.arguments
		val minimumArguments = enum.parameters.indexOfLast { it is Node.Parameter.Basic } + 1
		runtimeCheck(context, providedArguments.size in minimumArguments..enum.parameters.size) {
			"Enum entry ${enum.name}.$entryName expects $minimumArguments..${enum.parameters.size} values, got ${providedArguments.size}"
		}
		val properties = mutableMapOf<String, Variable>()
		val declarationContext = BlockContext(declarationFile, declarationFile, properties, mutableMapOf())
		enum.parameters.forEachIndexed { index, parameter ->
			val isProvided = index < providedArguments.size
			val value = when {
				isProvided && overrideArguments != null -> providedArguments[index]
				isProvided -> runNode(providedArguments[index], declarationContext)
				parameter is Node.Parameter.WithDefault -> runNode(parameter.defaultValue, declarationContext)
				else -> runtimeError(declarationContext, "Missing required enum property '${parameter.name}'")
			}
			val valueContext = if (isProvided && overrideArguments != null) context else declarationContext
			val type = parameterType(parameter)
			val coercedValue = coerceBoundary(type, value, valueContext) { "Enum property ${parameter.name} has the wrong type" }
			checkIsSameType(parameter, coercedValue, valueContext) { "Enum property ${parameter.name} has the wrong type" }
			properties[parameter.name] = Variable(parameter.name, boundaryInstance(type, coercedValue), true, type, declarationFile)
		}
		return Instance.Enum(enum.name, entryName, properties, declarationIdFor(declarationFile, enum.name, Node.ModuleSymbolKind.ENUM))
	}

	private fun Node.Variable.Basic.hasDefaultValue(): Boolean =
		when (value) {
			is Node.Expression -> value.nodes.isNotEmpty()
			else -> true
		}

	private fun findFunction(name: String, context: BlockContext): Pair<Node.File, Node.Function>? {
		val holderFunction = when (val holder = context.holder) {
			is Instance.Struct -> findImplementationFunction(holder.type, holder.declarationId, name, static = false, context)
			is Instance.Enum -> findImplementationFunction(holder.type, holder.declarationId, name, static = false, context)
			is Instance.Object -> holder.declarationId?.let(::findObjectDeclaration)?.let { (file, declaration) ->
				declaration.functions[name]?.let { file to it }
			}
			is Type.Basic -> findImplementationFunction(holder, resolveTypeId(holder, context), name, static = true, context)
			is Node.Object -> holder.functions[name]?.let { context.file to it }
			is Node.File -> holder.functions[name]?.let { holder to it }
			else -> null
		}
		holderFunction?.let { (file, function) ->
			runtimeCheck(context, isAccessible(function.visibility, file, context.file)) {
				"Function ${function.name} is not accessible from ${context.file.path}"
			}
			return file to function
		}
		if (context.explicitHolder) return null
		context.file.functions[name]?.let { return context.file to it }
		val symbol = context.file.importedSymbols[name] ?: return null
		if (symbol.kind != Node.ModuleSymbolKind.FUNCTION) return null
		val file = files.singleOrNull { it.path.normalize() == symbol.declarationPath.normalize() }
			?: runtimeError(context, "Imported function $name refers to a missing file")
		validateImportedSymbol(name, symbol, file, context)
		return file.functions[symbol.sourceName]?.let { file to it }
	}

	private fun findObjectDeclaration(id: DeclarationId): Pair<Node.File, Node.Object>? {
		val file = files.singleOrNull { it.path.normalize() == id.declarationPath } ?: return null
		return findObjectInFile(file, id.sourceName)
	}

	private fun findImplementationFunction(
		type: Type,
		declarationId: DeclarationId?,
		name: String,
		static: Boolean,
		context: BlockContext,
	): Pair<Node.File, Node.Function>? {
		val matches = files.flatMap { file ->
			val implementations = if (static) file.staticImpls.values else file.impls.values
			implementations.filter { implementation ->
				if (declarationId == null) implementation.type == type
				else (implementation.type as? Type.Basic)?.let { resolveTypeId(it, BlockContext(file, file, mutableMapOf())) } == declarationId
			}.flatMap { implementation ->
				implementation.functions.filter { it.name == name }.map { file to it }
			}
		}
		if (matches.size > 1) {
			runtimeError(context, "Ambiguous ${if (static) "static " else ""}implementation method $type.$name")
		}
		return matches.singleOrNull()
	}

	private fun parameterType(parameter: Node.Parameter): Type = when (parameter) {
		is Node.Parameter.Basic -> parameter.type
		is Node.Parameter.WithDefault -> parameter.type
	}

	private fun validateLinkedDeclarations() {
		val declarations = mutableMapOf<String, MutableList<String>>()
		val globals = mutableMapOf<String, MutableList<java.nio.file.Path>>()
		val namespace = mutableMapOf<String, MutableList<Pair<String, java.nio.file.Path>>>()
		fun registerNamespace(name: String, category: String, file: Node.File) {
			namespace.getOrPut("${file.packageId}::$name") { mutableListOf() } += category to file.path
		}
		fun register(name: String, kind: String, file: Node.File) {
			declarations.getOrPut("${file.packageId}::$name") { mutableListOf() } += "$kind in ${file.path}"
			registerNamespace(name, "type", file)
		}
		for (file in files) {
			(file.variables.keys + file.constants.keys).forEach { name ->
				globals.getOrPut("${file.packageId}::variable $name") { mutableListOf() }.add(file.path)
				registerNamespace(name, "global", file)
			}
			file.functions.keys.forEach { name ->
				globals.getOrPut("${file.packageId}::function $name") { mutableListOf() }.add(file.path)
				registerNamespace(name, "function", file)
			}
			file.structs.values.forEach {
				register(it.name, "struct", file)
				validateStructDefaults(file, it)
			}
			file.sealeds.values.forEach { sealed ->
				register(sealed.name, "sealed type", file)
				sealed.structs.forEach {
					register(it.name, "sealed struct", file)
					validateStructDefaults(file, it)
				}
				sealed.objects.forEach { register(it.name, "sealed object", file) }
			}
			file.traits.values.forEach { register(it.name, "trait", file) }
			file.objects.values.forEach { register(it.name, "object", file) }
			file.enums.values.forEach { register(it.name, "enum", file) }
		}
		declarations.entries.firstOrNull { it.value.size > 1 }?.let { (name, locations) ->
			throw CrescentRuntimeException(mainFile.path, "Ambiguous linked declaration ${name.substringAfter("::")}: ${locations.joinToString()}")
		}
		globals.entries.firstOrNull { it.value.size > 1 }?.let { (description, paths) ->
			throw CrescentRuntimeException(mainFile.path, "Ambiguous linked ${description.substringAfter("::")} declared in ${paths.joinToString()}")
		}
		namespace.entries.firstOrNull { (_, entries) -> entries.map { it.first }.distinct().size > 1 }?.let { (name, entries) ->
			val details = entries.joinToString { (category, path) -> "$category in $path" }
			throw CrescentRuntimeException(
				mainFile.path,
				"Ambiguous linked declaration ${name.substringAfter("::")} crosses the package namespace: $details",
			)
		}

		val methods = mutableMapOf<Triple<Boolean, Any, String>, MutableList<java.nio.file.Path>>()
		for (file in files) {
			val context = BlockContext(file, file, mutableMapOf())
			for ((static, implementations) in listOf(false to file.impls.values, true to file.staticImpls.values)) {
				for (implementation in implementations) {
					val identity: Any = (implementation.type as? Type.Basic)?.let { resolveTypeId(it, context) } ?: implementation.type
					for (function in implementation.functions) {
						methods.getOrPut(Triple(static, identity, function.name)) { mutableListOf() }.add(file.path)
					}
				}
			}
		}
		methods.entries.firstOrNull { it.value.size > 1 }?.let { (key, paths) ->
			throw CrescentRuntimeException(
				mainFile.path,
				"Ambiguous linked ${if (key.first) "static " else ""}implementation method ${key.second}.${key.third} in ${paths.joinToString()}",
			)
		}
	}

	private fun validateStructDefaults(file: Node.File, struct: Node.Struct) {
		var foundDefault = false
		for (field in struct.variables) {
			if (field.hasDefaultValue()) {
				foundDefault = true
			} else if (foundDefault) {
				throw CrescentRuntimeException(
					file.path,
					"Required field ${field.name} cannot follow a default field in struct ${struct.name}",
				)
			}
		}
	}

	private fun validateImplementations() {
		for (file in files) {
			val context = BlockContext(file, file, mutableMapOf())
			for (implementation in file.impls.values + file.staticImpls.values) {
				val target = implementation.type as? Type.Basic
					?: throw CrescentRuntimeException(file.path, "Implementation target must be a named type, got ${implementation.type}")
				if (target.name !in BUILT_IN_TYPE_NAMES && resolveTypeId(target, context) == null) {
					throw CrescentRuntimeException(file.path, "Unknown implementation target ${target.name}")
				}
				for (extendedType in implementation.extends) {
					val extended = extendedType as? Type.Basic
						?: throw CrescentRuntimeException(
							file.path,
							"Implementation supertype for ${implementation.type} must be a named type, got $extendedType",
						)
					if (extended.name !in BUILT_IN_TYPE_NAMES && resolveTypeId(extended, context) == null) {
						throw CrescentRuntimeException(file.path, "Unknown implementation supertype ${extended.name} for ${implementation.type}")
					}
				}
			}

			for (implementation in file.impls.values) {
				for (extendedType in implementation.extends) {
					if (extendedType !is Type.Basic) {
						throw CrescentRuntimeException(
							file.path,
							"Implementation supertype for ${implementation.type} must be a named type, got $extendedType",
						)
					}
					val traitId = resolveTypeId(extendedType, context) ?: continue
					if (traitId.kind != Node.ModuleSymbolKind.TRAIT) continue
					val traitFile = files.singleOrNull { it.path.normalize() == traitId.declarationPath } ?: continue
					val trait = traitFile.traits[traitId.sourceName] ?: continue
					for (required in trait.functionTraits) {
						val function = implementation.functions.firstOrNull { it.name == required.name }
							?: throw CrescentRuntimeException(file.path, "${implementation.type} must implement ${trait.name}.${required.name}")
						if (!signaturesMatch(function, context, required, BlockContext(traitFile, traitFile, mutableMapOf()))) {
							throw CrescentRuntimeException(file.path, "${implementation.type}.${function.name} does not match trait ${trait.name}")
						}
					}
				}
			}
		}
	}

	private fun validateRuntimeSemantics() {
		val reserved = setOf(
			"success", "failure", "sqrt", "sin", "round", "print", "println",
			"readLine", "readBoolean", "readDouble", "readInt", "await", "typeOf",
		)
		for (file in files) {
			val validationContext = BlockContext(file, file, mutableMapOf())
			for ((alias, symbol) in file.importedSymbols) {
				val declarationFile = files.singleOrNull { it.path.normalize() == symbol.declarationPath.normalize() }
					?: throw CrescentRuntimeException(file.path, "Imported symbol $alias refers to missing declaration file ${symbol.declarationPath}")
				validateImportedSymbol(alias, symbol, declarationFile, validationContext)
			}
			val declared = file.functions.keys + file.variables.keys + file.constants.keys + file.structs.keys +
				file.sealeds.keys + file.traits.keys + file.objects.keys + file.enums.keys + file.importedSymbols.keys
			(declared intersect reserved).firstOrNull()?.let {
				throw CrescentRuntimeException(file.path, "'$it' is reserved by the Crescent runtime")
			}
			for (function in file.functions.values) validateFunctionModifiers(file, function, null, static = false)
			for (objectNode in file.objects.values) {
				for (function in objectNode.functions.values) validateFunctionModifiers(file, function, null, static = false)
			}
			for (implementation in file.impls.values) {
				val inherited = collectTraitRequirements(implementation, file)
				for (requirement in inherited) {
					val implemented = implementation.functions.any { function ->
						signaturesMatch(function, validationContext, requirement.function, requirement.context)
					}
					if (!implemented) {
						throw CrescentRuntimeException(file.path, "${implementation.type} must implement ${requirement.trait.name}.${requirement.function.name}")
					}
				}
				for (function in implementation.functions) {
					validateFunctionModifiers(file, function, implementation, static = false)
					val match = inherited.firstOrNull { requirement ->
						signaturesMatch(function, validationContext, requirement.function, requirement.context)
					}
					val matching = match != null
					val overrides = CrescentToken.Modifier.OVERRIDE in function.modifiers
					if (matching != overrides) {
						throw CrescentRuntimeException(file.path, "${implementation.type}.${function.name} ${if (matching) "must" else "must not"} be declared override")
					}
					match?.let { requirement ->
						val trait = requirement.trait
						if (visibilityRank(function.visibility) > visibilityRank(trait.visibility)) {
							throw CrescentRuntimeException(file.path, "Override ${function.name} narrows visibility of trait ${trait.name}")
						}
					}
				}
			}
			for (implementation in file.staticImpls.values) {
				if (implementation.modifiers != listOf(CrescentToken.Modifier.STATIC)) {
					throw CrescentRuntimeException(file.path, "Static implementation must have exactly the static modifier")
				}
				for (function in implementation.functions) validateFunctionModifiers(file, function, implementation, static = true)
			}
			file.mainFunction?.let { main ->
				if (main.modifiers.isNotEmpty()) throw CrescentRuntimeException(file.path, "main cannot have modifiers")
				if (main.visibility != CrescentToken.Visibility.PUBLIC) throw CrescentRuntimeException(file.path, "main must be public")
			}
		}
	}

	private fun validateFunctionModifiers(file: Node.File, function: Node.Function, implementation: Node.Impl?, static: Boolean) {
		val modifiers = function.modifiers.toSet()
		if (modifiers.size != function.modifiers.size) throw CrescentRuntimeException(file.path, "Duplicate modifier on ${function.name}")
		if (CrescentToken.Modifier.STATIC in modifiers) throw CrescentRuntimeException(file.path, "static is only valid on an impl target")
		if (CrescentToken.Modifier.ASYNC in modifiers && modifiers.any { it in setOf(CrescentToken.Modifier.INLINE, CrescentToken.Modifier.OPERATOR, CrescentToken.Modifier.OVERRIDE) }) {
			throw CrescentRuntimeException(file.path, "async ${function.name} cannot be inline, operator, or override")
		}
		if (static && modifiers.any { it in setOf(CrescentToken.Modifier.OPERATOR, CrescentToken.Modifier.OVERRIDE, CrescentToken.Modifier.INFIX) }) {
			throw CrescentRuntimeException(file.path, "Static implementation method ${function.name} cannot be operator, override, or infix")
		}
		if (CrescentToken.Modifier.OVERRIDE in modifiers && implementation == null) throw CrescentRuntimeException(file.path, "override is only valid in an impl")
		if (CrescentToken.Modifier.INFIX in modifiers) {
			if (implementation == null || static || function.params.size != 1 || function.params.single() !is Node.Parameter.Basic) {
				throw CrescentRuntimeException(file.path, "infix ${function.name} requires one non-defaulted parameter in a non-static impl")
			}
		}
		if (CrescentToken.Modifier.OPERATOR in modifiers) validateOperatorFunction(file, function, implementation, static)
	}

	private fun validateOperatorFunction(file: Node.File, function: Node.Function, implementation: Node.Impl?, static: Boolean) {
		if (implementation == null || static) throw CrescentRuntimeException(file.path, "operator ${function.name} requires a non-static impl")
		val spec = operatorSpecs[function.name]
			?: throw CrescentRuntimeException(file.path, "Unsupported operator function ${function.name}")
		if (function.params.size != spec.arity || function.params.any { it !is Node.Parameter.Basic }) {
			throw CrescentRuntimeException(file.path, "operator ${function.name} requires ${spec.arity} non-defaulted parameter(s)")
		}
		val receiverType = implementation.type
		val validReturn = when (spec.returnKind) {
			OperatorReturn.BOOLEAN -> function.returnType == Primitive.Boolean.type
			OperatorReturn.I32 -> function.returnType == Type.Basic("I32")
			OperatorReturn.RECEIVER -> {
				val context = BlockContext(file, file, mutableMapOf())
				canonicalType(function.returnType, context) == canonicalType(receiverType, context)
			}
			OperatorReturn.NON_UNIT -> function.returnType != Type.unit
			OperatorReturn.UNIT -> function.returnType == Type.unit
		}
		if (!validReturn) throw CrescentRuntimeException(file.path, "operator ${function.name} has invalid return type ${function.returnType}")
	}

	private fun signaturesMatch(
		function: Node.Function,
		functionContext: BlockContext,
		required: Node.FunctionTrait,
		requiredContext: BlockContext,
	): Boolean =
		function.name == required.name && canonicalType(function.returnType, functionContext) == canonicalType(required.returnType, requiredContext) &&
			function.params.map { canonicalType(parameterType(it), functionContext) } == required.params.map { canonicalType(parameterType(it), requiredContext) } &&
			function.params.map { it is Node.Parameter.WithDefault } == required.params.map { it is Node.Parameter.WithDefault }

	private fun canonicalType(type: Type, context: BlockContext): String = when (type) {
		is Type.Array -> "Array<${canonicalType(type.type, context)}>"
		is Type.Result -> "Result<${canonicalType(type.type, context)}>"
		is Type.Basic -> resolveTypeId(type, context)?.let { "decl:$it" } ?: "builtin:${type.name}"
		else -> type.toString()
	}

	private data class TraitRequirement(
		val traitId: DeclarationId,
		val trait: Node.Trait,
		val function: Node.FunctionTrait,
		val context: BlockContext,
	)

	private fun collectTraitRequirements(implementation: Node.Impl, implementationFile: Node.File): List<TraitRequirement> {
		val requirements = mutableListOf<TraitRequirement>()
		val visited = mutableSetOf<DeclarationId>()
		val visiting = mutableSetOf<DeclarationId>()

		fun visit(type: Type.Basic, fromFile: Node.File) {
			val fromContext = BlockContext(fromFile, fromFile, mutableMapOf())
			val id = resolveTypeId(type, fromContext) ?: return
			if (id.kind != Node.ModuleSymbolKind.TRAIT || id in visited) return
			if (!visiting.add(id)) throw CrescentRuntimeException(fromFile.path, "Cyclic trait inheritance involving ${type.name}")
			val traitFile = files.singleOrNull { it.path.normalize() == id.declarationPath }
				?: throw CrescentRuntimeException(fromFile.path, "Missing trait declaration ${type.name}")
			val trait = traitFile.traits[id.sourceName]
				?: throw CrescentRuntimeException(fromFile.path, "Missing trait declaration ${type.name}")
			val traitContext = BlockContext(traitFile, traitFile, mutableMapOf())
			requirements += trait.functionTraits.map { TraitRequirement(id, trait, it, traitContext) }
			for (candidateFile in files) {
				val candidateContext = BlockContext(candidateFile, candidateFile, mutableMapOf())
				for (candidate in candidateFile.impls.values) {
					val targetId = (candidate.type as? Type.Basic)?.let { resolveTypeId(it, candidateContext) }
					if (targetId == id) candidate.extends.filterIsInstance<Type.Basic>().forEach { visit(it, candidateFile) }
				}
			}
			visiting.remove(id)
			visited += id
		}

		implementation.extends.filterIsInstance<Type.Basic>().forEach { visit(it, implementationFile) }
		return requirements.distinctBy { requirement ->
			listOf(
				requirement.traitId.toString(),
				requirement.function.name,
				canonicalType(requirement.function.returnType, requirement.context),
			) + requirement.function.params.map { canonicalType(parameterType(it), requirement.context) }
		}
	}

	private enum class OperatorReturn { BOOLEAN, I32, RECEIVER, NON_UNIT, UNIT }
	private data class OperatorSpec(val arity: Int, val returnKind: OperatorReturn)
	private val operatorSpecs = buildMap {
		put("unaryMinus", OperatorSpec(0, OperatorReturn.RECEIVER))
		put("not", OperatorSpec(0, OperatorReturn.BOOLEAN))
		listOf("plus", "minus", "times", "div", "rem", "pow").forEach { put(it, OperatorSpec(1, OperatorReturn.NON_UNIT)) }
		put("equals", OperatorSpec(1, OperatorReturn.BOOLEAN))
		put("compareTo", OperatorSpec(1, OperatorReturn.I32))
		put("contains", OperatorSpec(1, OperatorReturn.BOOLEAN))
		put("get", OperatorSpec(1, OperatorReturn.NON_UNIT))
		put("set", OperatorSpec(2, OperatorReturn.UNIT))
		listOf("bitShiftLeft", "bitShiftRight", "unsignedBitShiftRight", "bitAnd", "bitOr", "bitXor").forEach {
			put(it, OperatorSpec(1, OperatorReturn.RECEIVER))
		}
	}


	sealed class Instance : Node {

		abstract val type: Type

		class Array internal constructor(
			values: kotlin.Array<CrescentAST.Node>,
			elementType: Type,
			tentative: Boolean,
		) : Instance() {
			internal data class Constraint(val type: Type, val tentative: Boolean)

			private val storageLock = Any()
			private val storage = values.copyOf()
			private var elementConstraint = Constraint(elementType, tentative)

			override val type: Type
				get() = Type.Array(constraint().type)

			internal val size: Int
				get() = storage.size

			internal fun snapshot(): kotlin.Array<CrescentAST.Node> = synchronized(storageLock) { storage.copyOf() }

			internal operator fun get(index: Int): CrescentAST.Node = synchronized(storageLock) { storage[index] }

			internal operator fun set(index: Int, value: CrescentAST.Node) {
				synchronized(storageLock) { storage[index] = value }
			}

			internal fun update(index: Int, transform: (CrescentAST.Node) -> CrescentAST.Node) {
				synchronized(storageLock) { storage[index] = transform(storage[index]) }
			}

			internal fun transformAll(transform: (CrescentAST.Node) -> CrescentAST.Node) {
				synchronized(storageLock) {
					storage.indices.forEach { index -> storage[index] = transform(storage[index]) }
				}
			}

			internal fun constraint(): Constraint = synchronized(storageLock) { elementConstraint.copy() }

			internal fun constrain(requestedType: Type, finalize: Boolean): Constraint? = synchronized(storageLock) {
				val current = elementConstraint
				elementConstraint = when {
					current.tentative && finalize -> Constraint(requestedType, tentative = false)
					current.type == requestedType -> current
					else -> return@synchronized null
				}
				elementConstraint.copy()
			}

			internal fun freeze() {
				synchronized(storageLock) { elementConstraint = elementConstraint.copy(tentative = false) }
			}
		}

		data class Node(
			override val type: Type,
			val value: CrescentAST.Node,
		) : Instance()

		class Struct(
			val name: String,
			variables: Map<String, Variable>,
			val declarationId: DeclarationId? = null,
		) : Instance() {
			val variables: Map<String, Variable> = Collections.unmodifiableMap(LinkedHashMap(variables))

			override val type = Type.Basic(name)

		}

		class Enum(
			val name: String,
			val entryName: String,
			properties: Map<String, Variable>,
			val declarationId: DeclarationId? = null,
		) : Instance() {
			val properties: Map<String, Variable> = Collections.unmodifiableMap(LinkedHashMap(properties))
			override val type = Type.Basic(name)
		}

		data class Future(internal val value: CompletableFuture<CrescentAST.Node>) : Instance() {
			override val type = Type.Basic("Future")
		}

		sealed class Result : Instance() {
			data class Success(
				val value: CrescentAST.Node,
				override val type: Type = Type.Result((value as? Instance)?.type ?: (value as? Node.Typed)?.type ?: Type.any),
			) : Result()
			data class Failure(
				val error: CrescentAST.Node,
				override val type: Type = Type.Result(Type.any),
			) : Result()
		}

		class Object(
			val name: String,
			override val type: Type,
			constants: Map<String, Variable>,
			variables: Map<String, Variable>,
			functions: Map<String, CrescentAST.Node.Function>,
			val declarationId: DeclarationId? = null,
		) : Instance() {
			val constants: Map<String, Variable> = Collections.unmodifiableMap(LinkedHashMap(constants))
			val variables: Map<String, Variable> = Collections.unmodifiableMap(LinkedHashMap(variables))
			val functions: Map<String, CrescentAST.Node.Function> = Collections.unmodifiableMap(LinkedHashMap(functions))
		}

	}

	class Variable(
		val name: String,
		instance: Instance,
		val isFinal: Boolean,
		val declaredType: Type = instance.type,
		val declarationFile: Node.File? = null,
		val visibility: CrescentToken.Visibility = CrescentToken.Visibility.PUBLIC,
	) {
		private val storageLock = Any()
		private var storedInstance: Instance = instance

		var instance: Instance
			get() = synchronized(storageLock) { storedInstance }
			internal set(value) = synchronized(storageLock) { storedInstance = value }

		internal fun updateInstance(transform: (Instance) -> Instance) {
			synchronized(storageLock) { storedInstance = transform(storedInstance) }
		}
	}


	/**
	 * @property variables Name -> Variable
	 * @constructor
	 */
	data class BlockContext(
		val file: Node.File,
		val holder: Node,
		val parameters: MutableMap<String, Variable>,
		val variables: MutableMap<String, Variable> = mutableMapOf(),
		val explicitHolder: Boolean = false,
		val parent: BlockContext? = null,
		val returnType: Type? = null,
	) {
		fun childScope(): BlockContext = copy(
			parameters = mutableMapOf(),
			variables = mutableMapOf(),
			explicitHolder = false,
			parent = this,
			returnType = null,
		)

		fun findLexicalVariable(name: String): Variable? =
			parameters[name] ?: variables[name] ?: parent?.findLexicalVariable(name)

		fun enclosingReturnType(): Type? = returnType ?: parent?.enclosingReturnType()
	}

	private class PropagateResult(val failure: Instance.Result.Failure) : RuntimeException(null, null, false, false)

}
