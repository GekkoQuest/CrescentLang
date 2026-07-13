package dev.twelveoclock.lang.crescent.vm

import dev.twelveoclock.lang.crescent.iterator.PeekingNodeIterator
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Primitive
import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.Type
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.project.checkEquals
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

// TODO: Add a way to add external functions
// TODO: Find a way to remove recursion
// TODO: Don't modify the AST
class CrescentVM(val files: List<Node.File>, val mainFile: Node.File) {

	// Object name -> Object
	val objects = mutableMapOf<String, Instance.Object>()
	private val coroutineExecutor = Executors.newVirtualThreadPerTaskExecutor()

	init {
		require(mainFile in files) { "The main file must be part of the VM file set" }
		validateTraits()
	}


	fun invoke(args: List<String> = emptyList()) {
		invokeAsync(args).join()
	}

	fun invokeAsync(args: List<String> = emptyList()): CompletableFuture<Node> = CompletableFuture.supplyAsync({

		val mainFunction = mainFile.mainFunction
			?: throw CrescentRuntimeException(mainFile.path, "No main function was declared")

		val functionArgs = if (mainFunction.params.isEmpty()) {
			emptyList()
		}
		else {
			listOf(Node.Array(Array(args.size) { Primitive.String(args[it]) }))
		}

		runFunction(
			mainFunction,
			functionArgs,
			BlockContext(mainFile, mainFile, mutableMapOf(), mutableMapOf())
		)
	}, coroutineExecutor)

	fun runFunction(function: Node.Function, args: List<Node>, context: BlockContext): Node {

		val requiredParameters = function.params.count { it is Node.Parameter.Basic }
		check(args.size in requiredParameters..function.params.size) {
			"Function ${function.name} expects $requiredParameters..${function.params.size} arguments, got ${args.size}"
		}

		// Need to manually copy since .copy uses the same map instance, .toMutableMap() clones properly
		val functionContext = BlockContext(
			context.file,
			context.holder,
			context.parameters.toMutableMap(),
			context.variables.toMutableMap()
		)

		function.params.forEachIndexed { index, parameter ->

			val arg = args.getOrNull(index) ?: when (parameter) {
				is Node.Parameter.WithDefault -> runNode(parameter.defaultValue, functionContext)
				is Node.Parameter.Basic -> error("Missing required argument '${parameter.name}'")
			}

			checkIsSameType(parameter, arg) { parameterType ->
				"Parameter type doesn't match argument: $parameterType != ${findType(args[index])}"
			}

			functionContext.parameters[parameter.name] = Variable(parameter.name, instanceOf(arg), true)
		}

		return when (val result = runBlock(function.innerCode, functionContext)) {
			is Node.Return -> runNode(result.expression, functionContext)
			else -> result
		}
	}

	// TODO: Have a return value
	fun runBlock(block: Node.Statement.Block, context: BlockContext): Node {

		block.nodes.forEachIndexed { index, node ->

			val result = runNode(node, context)

			if (index + 1 == block.nodes.size || result is Node.Return || result == CrescentToken.Keyword.BREAK || result == CrescentToken.Keyword.CONTINUE) {
				return result
			}
		}

		return Type.unit
	}

	fun runNode(node: Node, context: BlockContext): Node {
		when (node) {

			is Primitive.String,
			is Primitive.Number,
			is Primitive.Char,
			is Primitive.Boolean,
			is Node.Array,
			is Instance,
			-> {
				return node
			}

			is Node.Identifier -> {

				val holderVariable: Node? = when (val holder = context.holder) {
					is Instance.Struct -> holder.variables[node.name]?.instance?.asNode()
					is Instance.Object -> holder.constants[node.name]?.instance?.asNode()
						?: holder.variables[node.name]?.instance?.asNode()
					is Instance.Enum -> holder.properties[node.name]?.instance?.asNode()
					is Type.Basic -> findEnum(holder.name)?.let { instantiateEnum(it, node.name, context) }
					is Node.Object -> holder.constants[node.name]?.value ?: holder.variables[node.name]?.value
					is Node.File -> holder.constants[node.name]?.value ?: holder.variables[node.name]?.value
					else -> null
				}

				return holderVariable
					?: context.parameters[node.name]?.instance?.asNode()
					?: context.variables[node.name]?.instance?.asNode()
					?: context.file.constants[node.name]?.value
					?: files.firstNotNullOfOrNull { it.constants[node.name]?.value }
					?: findObject(node.name)?.let { runObject(it, context) }
					?: findEnum(node.name)?.let { Type.Basic(it.name) }
					?: findStruct(node.name)?.let { Type.Basic(it.name) }
					?: node
			}

			is Node.IdentifierCall -> {
				// TODO: Determine if it's a constructor call
				return runFunctionCall(node, context)
			}

			is Node.Return -> {
				return node
			}

			// TODO: Account for operator overloading
			is Node.GetCall -> {

				val arrayNode = ((context.parameters[node.identifier]?.instance
					?: context.variables.getValue(node.identifier).instance) as Instance.Node).value as Node.Array

				return arrayNode.values[(runNode(node.arguments[0], context) as Primitive.Number).toI32().data]
			}

			is Node.DotChain -> {

				var lastNode: Node? = null

				node.nodes.forEach {

					if (lastNode == null) {
						lastNode = runNode(it, context)
						return@forEach
					}

					lastNode = runNode(it, context.copy(holder = checkNotNull(lastNode)))
				}

				return lastNode!!
			}

			is Node.Expression -> {
				return runExpression(node, context)
			}

			is Node.Statement.If -> {

				val result =
					if ((runNode(node.predicate, context) as Primitive.Boolean).data) {
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
				while ((runNode(node.predicate, context) as Primitive.Boolean).data) {
					when (val result = runBlock(node.block, context)) {
						is Node.Return -> return result
						CrescentToken.Keyword.BREAK -> break
						CrescentToken.Keyword.CONTINUE -> continue
						else -> Unit
					}
				}
			}

			is Node.Statement.When -> {
				val argument = runNode(node.argument, context)
				for (clause in node.predicateToBlock) {
					val matches = when (val predicate = clause.ifExpressionNode) {
						null -> true
						is Node.Statement.When.EnumShortHand -> argument is Instance.Enum && argument.entryName == predicate.name
						else -> valuesEqual(argument, runNode(predicate, context))
					}
					if (matches) return runBlock(clause.thenBlock, context)
				}
				return Type.unit
			}

			is Node.Statement.For -> {

				val forContext = context.copy()

				val ranges = if (node.ranges.size == 1 && node.identifiers.size > 1) {
					node.identifiers.map {
						(node.ranges[0].start as Primitive.Number).toI32().data..(node.ranges[0].end as Primitive.Number).toI32().data
					}
				} else {
					node.ranges.map {
						(it.start as Primitive.Number).toI32().data..(it.end as Primitive.Number).toI32().data
					}
				}

				val counters = node.identifiers.mapIndexed { index, identifier ->

					val counter = Variable(
						identifier.name,
						Primitive.Number.I32(ranges.getOrNull(index)?.first ?: ranges[0].first).let {
							Instance.Node(Primitive.Number.I32.type, it)
						},
						isFinal = true
					)

					forContext.variables[counter.name] = counter

					return@mapIndexed counter
				}

				// Moo's version of For N Loop TODO: Merge

				val range = ranges[ranges.size - 1]
				val count = counters[ranges.size - 1]

				while (true) {

					// Go through last range
					range.forEach {
						(count.instance as Instance.Node).value = Primitive.Number.I32(it)
						runBlock(node.block, forContext)
					}

					// Set range
					var tmpIndex = ranges.size - 2

					while (tmpIndex > -1) {
						if (((counters[tmpIndex].instance as Instance.Node).value as Primitive.Number.I32).data >= ranges[tmpIndex].last) {
							(counters[tmpIndex].instance as Instance.Node).value = Primitive.Number.I32(ranges[tmpIndex].first)
							tmpIndex--
						} else {
							(counters[tmpIndex].instance as Instance.Node).value = Primitive.Number.I32(((counters[tmpIndex].instance as Instance.Node).value as Primitive.Number.I32).data + 1)
							break
						}
					}

					if (tmpIndex < 0) {
						break
					}
				}


				/*
				// N For Loop - Kat version TODO: Merge with Moo's version
				/*
				 Broken case:
				    fun main {
                        for x, y, z in 0..3, 0..2, 0..1 {
                            println("$x $y $z")
                        }
					}

					prints 0 0 0 twice
				 */
				while (counters.anyIndexed { index, count -> (count.instance.value as Primitive.Number.I32).data != ranges[index].last }) {

					for (rangeIndex in ranges.indices.reversed()) {

						val range = ranges[rangeIndex]
						val count = counters[rangeIndex]

						if ((count.instance.value as Primitive.Number.I32).data < range.last) {

							runBlock(node.block, forContext)

							count.instance.value = Primitive.Number.I32(
								(count.instance.value as Primitive.Number.I32).data + range.step
							)

							break
						}
						else {

							if (rangeIndex == 0) {
								break
							}

							count.instance.value = Primitive.Number.I32(range.first)
						}
					}
				}

				runBlock(node.block, forContext)*/
			}

			is Node.Variable.Basic,
			is Node.Variable.Local -> {
				val variable = runVariable(node, context)
				context.variables[variable.name] = variable
			}

			else -> error("Unexpected node: $node")
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
				else {
					error("Constant $node declared not in an Object or File!")
				}
			}

			else -> error("Not a recognized variable node: $node")
		}
		val type = if (node.type is Type.Implicit) findType(value) else node.type

		return Variable(node.name, if (value is Instance) value else Instance.Node(type, value), node.isFinal)
	}

	fun runObject(objectNode: Node.Object, context: BlockContext): Instance.Object {
		objects[objectNode.name]?.let { return it }

		val objectContext = context.copy(holder = objectNode, variables = mutableMapOf())

		val instance = Instance.Object(
			objectNode.name,
			objectNode.type,
			objectNode.constants.mapValues { runVariable(it.value, objectContext) },
			objectNode.variables.mapValues { runVariable(it.value, objectContext) },
			objectNode.functions,
		)
		objects[objectNode.name] = instance

		return instance
	}

	// TODO: Take in a stack or something
	fun runExpression(expression: Node.Expression, context: BlockContext): Node {

		val stack = LinkedList<Node>()

		val nodeIterator = PeekingNodeIterator(expression.nodes)

		while (nodeIterator.hasNext()) {
			when (val node = nodeIterator.next()) {

				// TODO: Run operator function
				is CrescentToken.Operator -> {
					when (node) {

						CrescentToken.Operator.NOT -> {
							val value = runNode(stack.pop(), context) as Primitive.Boolean
							stack.push(Primitive.Boolean(!value.data))
						}

						// TODO: Override operators for these in Primitive.Number
						CrescentToken.Operator.ADD -> {

							val pop1 = runNode(stack.pop(), context)
							val pop2 = runNode(stack.pop(), context)

							stack.push(
								if (pop2 is Primitive.String || pop1 is Primitive.String) {
									Primitive.String(pop2.asString() + pop1.asString())
								} else {
									(pop2 as Primitive.Number) + (pop1 as Primitive.Number)
								}
							)
						}
						CrescentToken.Operator.SUB -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number)
							val pop2 = stack.poll()?.let { (runNode(it, context) as Primitive.Number) }

							if (pop2 == null) {
								stack.push(pop1.multiply(Primitive.Number.I8(-1)))
							}
							else {
								stack.push(pop2 - pop1)
							}
						}
						CrescentToken.Operator.MUL -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number)
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number)

							stack.push(pop2.multiply(pop1))
						}
						CrescentToken.Operator.DIV -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number)
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number)

							stack.push(pop2 / pop1)
						}
						CrescentToken.Operator.POW -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number)
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number)

							stack.push(pop2.pow(pop1))
						}
						CrescentToken.Operator.REM -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number)
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number)

							stack.push(pop2 % pop1)
						}

						CrescentToken.Operator.ASSIGN -> {

							val value = runNode(stack.pop(), context)

							when (val pop2 = stack.pop()) {

								is Node.GetCall -> {
									checkEquals(1, pop2.arguments.size)
									val index = (pop2.arguments.first() as Primitive.Number).toI32().data
									((context.variables.getValue(pop2.identifier).instance as Instance.Node).value as Node.Array).values[index] = value
								}

								is Node.Identifier -> assignValue(pop2, value, context)
							}

							return Type.unit
						}

						CrescentToken.Operator.ADD_ASSIGN,
						CrescentToken.Operator.SUB_ASSIGN,
						CrescentToken.Operator.MUL_ASSIGN,
						CrescentToken.Operator.DIV_ASSIGN,
						CrescentToken.Operator.REM_ASSIGN,
						CrescentToken.Operator.POW_ASSIGN -> {
							val right = runNode(stack.pop(), context)
							val target = stack.pop() as Node.Identifier
							val left = runNode(target, context)
							val value = when (node) {
								CrescentToken.Operator.ADD_ASSIGN -> if (left is Primitive.String || right is Primitive.String) {
									Primitive.String(left.asString() + right.asString())
								} else (left as Primitive.Number) + (right as Primitive.Number)
								CrescentToken.Operator.SUB_ASSIGN -> (left as Primitive.Number) - (right as Primitive.Number)
								CrescentToken.Operator.MUL_ASSIGN -> (left as Primitive.Number).multiply(right as Primitive.Number)
								CrescentToken.Operator.DIV_ASSIGN -> (left as Primitive.Number) / (right as Primitive.Number)
								CrescentToken.Operator.REM_ASSIGN -> (left as Primitive.Number) % (right as Primitive.Number)
								CrescentToken.Operator.POW_ASSIGN -> (left as Primitive.Number).pow(right as Primitive.Number)
							}
							assignValue(target, value, context)
							return Type.unit
						}

						CrescentToken.Operator.OR_COMPARE -> {

							val pop1 = runNode(stack.pop(), context) as Primitive.Boolean
							val pop2 = runNode(stack.pop(), context) as Primitive.Boolean

							stack.push(Primitive.Boolean(pop2.data || pop1.data))
						}

						CrescentToken.Operator.AND_COMPARE -> {

							val pop1 = runNode(stack.pop(), context) as Primitive.Boolean
							val pop2 = runNode(stack.pop(), context) as Primitive.Boolean

							stack.push(Primitive.Boolean(pop2.data && pop1.data))
						}

						CrescentToken.Operator.EQUALS_COMPARE -> {

							val pop1 = runNode(stack.pop(), context)
							val pop2 = runNode(stack.pop(), context)

							// TODO: Override !=, ==, >=, <=, <, > on number, then merging this if statement into one statement and remove pop1 and pop2
							if (pop1 is Primitive.Number && pop2 is Primitive.Number) {
								stack.push(Primitive.Boolean(pop2.toF64().data == pop1.toF64().data))
							} else {
								stack.push(Primitive.Boolean(pop2 == pop1))
							}
						}
						CrescentToken.Operator.LESSER_EQUALS_COMPARE -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data

							stack.push(Primitive.Boolean(pop2 <= pop1))
						}
						CrescentToken.Operator.GREATER_EQUALS_COMPARE -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data

							stack.push(Primitive.Boolean(pop2 >= pop1))
						}

						CrescentToken.Operator.LESSER_COMPARE -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data

							stack.push(Primitive.Boolean(pop2 < pop1))
						}
						CrescentToken.Operator.GREATER_COMPARE -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toF64().data

							stack.push(Primitive.Boolean(pop2 > pop1))
						}

						CrescentToken.Operator.EQUALS_REFERENCE_COMPARE -> {
							val pop1 = runNode(stack.pop(), context)
							val pop2 = runNode(stack.pop(), context)
							stack.push(Primitive.Boolean(pop2 === pop1))
						}

						CrescentToken.Operator.NOT_EQUALS_COMPARE -> {

							val pop1 = runNode(stack.pop(), context)
							val pop2 = runNode(stack.pop(), context)

							// TODO: Override !=, ==, >=, <=, <, >, xor, or, and, etc on number, then merging this if statement into one statement and remove pop1 and pop2
							if (pop1 is Primitive.Number && pop2 is Primitive.Number) {
								stack.push(Primitive.Boolean(pop2.toF64().data != pop1.toF64().data))
							} else {
								stack.push(Primitive.Boolean(pop2 != pop1))
							}
						}

						CrescentToken.Operator.NOT_EQUALS_REFERENCE_COMPARE -> {
							val pop1 = runNode(stack.pop(), context)
							val pop2 = runNode(stack.pop(), context)
							stack.push(Primitive.Boolean(pop2 !== pop1))
						}
						CrescentToken.Operator.CONTAINS,
						CrescentToken.Operator.NOT_CONTAINS -> {
							val needle = runNode(stack.pop(), context)
							val haystack = runNode(stack.pop(), context)
							val contains = when (haystack) {
								is Primitive.String -> haystack.data.contains(needle.asString())
								is Node.Array -> haystack.values.any { valuesEqual(it, needle) }
								else -> false
							}
							stack.push(Primitive.Boolean(if (node == CrescentToken.Operator.CONTAINS) contains else !contains))
						}
						CrescentToken.Operator.RANGE_TO -> {
							val end = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val start = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							stack.push(Node.Array((start..end).map { Primitive.Number.I32(it) }.toTypedArray()))
						}
						CrescentToken.Operator.TYPE_PREFIX -> error("Type declarations are not runtime expressions")
						CrescentToken.Operator.RETURN -> {
							return stack.poll() ?: Type.unit
						}
						CrescentToken.Operator.RESULT -> stack.push(runNode(stack.pop(), context))
						CrescentToken.Operator.COMMA -> error("Comma is only valid between arguments")
						CrescentToken.Operator.DOT -> error("Dot access must be represented as a dot chain")
						CrescentToken.Operator.AS -> Unit
						CrescentToken.Operator.IMPORT_SEPARATOR -> error("Import separators are not runtime expressions")

						CrescentToken.Operator.INSTANCE_OF -> {

							val target = stack.pop()
							val pop2 = runNode(stack.pop(), context)
							val targetType = when (target) {
								is Node.Identifier -> Type.Basic(target.name)
								is Type.Basic -> target
								else -> error("Expected a type after 'is', got $target")
							}
							stack.push(Primitive.Boolean(isAssignable(targetType, pop2)))
						}

						CrescentToken.Operator.BIT_SHIFT_RIGHT -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 shr pop1))
						}

						CrescentToken.Operator.BIT_SHIFT_LEFT -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 shl pop1))
						}

						CrescentToken.Operator.UNSIGNED_BIT_SHIFT_RIGHT -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 ushr pop1))
						}

						CrescentToken.Operator.BIT_OR -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 or pop1))
						}

						CrescentToken.Operator.BIT_AND -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 and pop1))
						}
						CrescentToken.Operator.BIT_XOR -> {

							val pop1 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data
							val pop2 = (runNode(stack.pop(), context) as Primitive.Number).toI32().data

							stack.push(Primitive.Number.I32(pop2 xor pop1))
						}

						CrescentToken.Operator.NOT_INSTANCE_OF -> {
							val target = stack.pop()
							val pop2 = runNode(stack.pop(), context)
							val targetType = Type.Basic((target as Node.Identifier).name)
							stack.push(Primitive.Boolean(!isAssignable(targetType, pop2)))
						}
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

				else -> error("Unexpected node: $node")
			}
		}

		checkEquals(1, stack.size)

		return runNode(stack.pop(), context)
	}

	fun runFunctionCall(node: Node.IdentifierCall, context: BlockContext): Node {

		when (node.identifier) {

			"sqrt" -> {
				checkEquals(1, node.arguments.size)
				return Primitive.Number.F64(sqrt((runNode(node.arguments[0], context) as Primitive.Number).toF64().data))
			}

			"sin" -> {
				checkEquals(1, node.arguments.size)
				return Primitive.Number.F64(sin((runNode(node.arguments[0], context) as Primitive.Number).toF64().data))
			}

			"round" -> {
				checkEquals(1, node.arguments.size)
				return Primitive.Number.F64(round((runNode(node.arguments[0], context) as Primitive.Number).toF64().data))
			}

			"print" -> {
				checkEquals(1, node.arguments.size)
				print(runNode(node.arguments[0], context).asString())
			}

			"println" -> {

				check(node.arguments.size <= 1) {
					"Too many args for println call!"
				}

				if (node.arguments.isEmpty()) {
					println()
				}
				else {
					println(runNode(node.arguments[0], context).asString())
				}
			}

			"readLine" -> {
				checkEquals(1, node.arguments.size)
				println(runNode(node.arguments[0], context).asString())
				return Primitive.String(readln())
			}

			"readBoolean" -> {
				checkEquals(1, node.arguments.size)
				println(runNode(node.arguments[0], context).asString())
				return Primitive.Boolean(readln().toBooleanStrict())
			}

			"readDouble" -> {
				checkEquals(1, node.arguments.size)
				println(runNode(node.arguments[0], context).asString())
				return Primitive.Number.F64(readln().toDouble())
			}

			"readInt" -> {
				checkEquals(1, node.arguments.size)
				println(runNode(node.arguments[0], context).asString())
				return Primitive.Number.I32(readln().toInt())
			}

			"await" -> {
				checkEquals(1, node.arguments.size)
				val future = runNode(node.arguments.single(), context) as? Instance.Future
					?: error("await expects an async function result")
				return future.value.join()
			}

			// TODO: Make this return a special type struct instance
			"typeOf" -> {
				checkEquals(1, node.arguments.size)
				return findType(runNode(node.arguments[0], context))
			}

			else -> {
				val argumentValues = node.arguments.map { runNode(it, context) }
				val holderType = (context.holder as? Type.Basic)?.name
				val enum = holderType?.let(::findEnum)

				if (enum != null) {
					return when (node.identifier) {
						"random" -> instantiateEnum(enum, enum.structs.random().name, context)
						"values" -> Node.Array(enum.structs.map { instantiateEnum(enum, it.name, context) }.toTypedArray())
						else -> instantiateEnum(enum, node.identifier, context, argumentValues)
					}
				}

				val struct = if (context.holder is Type.Basic) null else findStruct(node.identifier)

				if (struct != null) {
					check(struct.variables.size == argumentValues.size) {
						"Struct ${struct.name} expects ${struct.variables.size} arguments, got ${argumentValues.size}"
					}
					val parameters = mutableMapOf<String, Variable>()

					struct.variables.forEachIndexed { index, variable ->

						val argument = argumentValues[index]

						checkIsSameType(variable.type, argument) {
							"Variable ${variable.name} had an argument of type ${findType(argumentValues[index])}, expected ${variable.type}"
						}

						parameters[variable.name] = Variable(variable.name, instanceOf(argument), variable.isFinal)
					}

					return Instance.Struct(struct.name, parameters)
				}

				val (functionFile, function) = findFunction(node.identifier, context)
					?: throw CrescentRuntimeException(context.file.path, "Unknown function: ${node.identifier}(${argumentValues.joinToString { it.asString() }})")
				val functionContext = context.copy(file = functionFile)
				return if (CrescentToken.Modifier.ASYNC in function.modifiers) {
					Instance.Future(CompletableFuture.supplyAsync({ runFunction(function, argumentValues, functionContext) }, coroutineExecutor))
				} else {
					runFunction(function, argumentValues, functionContext)
				}
			}

		}

		return Type.unit
	}

	fun Node.asString(): String {

		return when (this) {

			is Primitive.String -> {
				this.data
			}

			is Type -> {
				"$this"
			}

			is Primitive.Char -> {
				"${this.data}"
			}

			is Primitive.Number -> {
				"$this"
			}

			is Primitive.Boolean -> {
				"${this.data}"
			}

			is Node.Array -> {
				"${this.values.map { it.asString() }}"
			}

			is Node.Identifier -> {
				this.name
			}

			is Instance.Struct -> "${this.name}(${this.variables.values.joinToString { "${it.name}=${it.instance.asNode().asString()}" }})"
			is Instance.Object -> this.name
			is Instance.Enum -> this.entryName
			is Instance.Future -> if (this.value.isDone) this.value.join().asString() else "Future(pending)"

			else -> {
				// TODO: Attempt to find a toString()
				error("Unknown node ${this::class}")
			}
		}
	}


	fun findType(value: Node) = when (value) {

		is Instance -> value.type
		is Node.Typed -> value.type
		is Type.Basic -> value
		is Node.Array -> Type.Array(Type.any) // TODO: Do better

		is Node.IdentifierCall -> {
			if (value.identifier in this.mainFile.structs) {
				Type.Basic(value.identifier)
			}
			else {
				// TODO: Resolve the function return type
				Type.any
			}
		}

		else -> error("Unexpected value: ${value::class}")
	}

	fun checkIsSameType(parameter: Node.Parameter, value: Node, errorBlock: (parameterType: Type) -> String) =
		when (parameter) {

			is Node.Parameter.Basic -> {
				checkIsSameType(parameter.type, value) {
					errorBlock(parameter.type)
				}
			}

			is Node.Parameter.WithDefault -> {
				checkIsSameType(parameter.type, value) {
					errorBlock(parameter.type)
				}
			}
		}

	// TODO: Use typeOf instead
	fun checkIsSameType(type: Type, value: Node, errorBlock: () -> String): Unit {
		when (type) {

		/*
        is Type.Array -> {
            check(arg is Node.Array)
            checkEquals(parameter.type, typeOf(arg.values.first()))
        }
        */

		is Type.Array -> {
			check(value is Node.Array && value.values.all { isAssignable(type.type, it) }) { errorBlock() }
		}

		is Type.Basic -> {
			if (type != Type.any) {
				check(isAssignable(type, value)) {
					errorBlock()
					"Expected ${type.name}, got ${value::class.qualifiedName}"
				}
			} else {
				// Do nothing
			}
		}

		is Type.Implicit -> Unit
		is Type.Result -> checkIsSameType(type.type, value, errorBlock)
		else -> {
			error("Expected $type, got ${value::class.qualifiedName}")
		}
		}
	}

	fun isAssignable(expected: Type, value: Node): Boolean {
		if (expected == Type.any || expected is Type.Implicit) return true
		if (expected is Type.Basic && expected.name in numericTypeNames && value is Primitive.Number) return true
		val actual = findType(value)
		if (expected == actual) return true
		if (expected is Type.Array && value is Node.Array) return value.values.all { isAssignable(expected.type, it) }
		if (expected !is Type.Basic || actual !is Type.Basic) return false

		val sealed = files.asSequence().flatMap { it.sealeds.values.asSequence() }.firstOrNull { it.name == expected.name }
		if (sealed != null && (sealed.structs.any { it.name == actual.name } || sealed.objects.any { it.name == actual.name })) return true

		return files.any { file ->
			file.impls.values.firstOrNull { it.type == actual }?.extends?.any { it == expected } == true
		}
	}

	private val numericTypeNames = setOf("I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F32", "F64")

	private fun valuesEqual(left: Node, right: Node): Boolean = when {
		left is Primitive.Number && right is Primitive.Number -> left.toF64().data == right.toF64().data
		left is Instance.Enum && right is Instance.Enum -> left.name == right.name && left.entryName == right.entryName
		else -> left == right
	}

	private fun instanceOf(value: Node): Instance = value as? Instance ?: Instance.Node(findType(value), value)

	private fun Instance.asNode(): Node = when (this) {
		is Instance.Node -> value
		else -> this
	}

	private fun assignValue(identifier: Node.Identifier, value: Node, context: BlockContext) {
		val variable = context.variables[identifier.name]
			?: (context.holder as? Instance.Struct)?.variables?.get(identifier.name)
			?: (context.holder as? Instance.Object)?.variables?.get(identifier.name)
			?: throw CrescentRuntimeException(context.file.path, "Variable ${identifier.name} was not found for reassignment")

		check(!variable.isFinal) { "Variable ${variable.name} is not mutable" }
		checkIsSameType(variable.instance.type, value) {
			"Variable ${variable.name}: ${variable.instance.type} cannot be assigned to ${findType(value)}"
		}
		variable.instance = instanceOf(value)
	}

	private fun findStruct(name: String): Node.Struct? = files.firstNotNullOfOrNull { file ->
		file.structs[name] ?: file.sealeds.values.firstNotNullOfOrNull { sealed -> sealed.structs.firstOrNull { it.name == name } }
	}

	private fun findObject(name: String): Node.Object? = files.firstNotNullOfOrNull { file ->
		file.objects[name] ?: file.sealeds.values.firstNotNullOfOrNull { sealed -> sealed.objects.firstOrNull { it.name == name } }
	}

	private fun findEnum(name: String): Node.Enum? = files.firstNotNullOfOrNull { it.enums[name] }

	private fun instantiateEnum(
		enum: Node.Enum,
		entryName: String,
		context: BlockContext,
		overrideArguments: List<Node>? = null,
	): Instance.Enum {
		val entry = enum.structs.firstOrNull { it.name == entryName }
			?: throw CrescentRuntimeException(context.file.path, "Enum ${enum.name} has no entry named $entryName")
		val values = overrideArguments ?: entry.arguments.map { runNode(it, context) }
		check(values.size == enum.parameters.size) {
			"Enum entry ${enum.name}.$entryName expects ${enum.parameters.size} values, got ${values.size}"
		}
		val properties = enum.parameters.mapIndexed { index, parameter ->
			checkIsSameType(parameter, values[index]) { "Enum property ${parameter.name} has the wrong type" }
			parameter.name to Variable(parameter.name, instanceOf(values[index]), true)
		}.toMap()
		return Instance.Enum(enum.name, entryName, properties)
	}

	private fun findFunction(name: String, context: BlockContext): Pair<Node.File, Node.Function>? {
		return when (val holder = context.holder) {
			is Instance.Struct -> files.firstNotNullOfOrNull { file -> file.impls.values.firstOrNull { it.type == holder.type }?.functions?.firstOrNull { it.name == name }?.let { file to it } }
			is Instance.Enum -> files.firstNotNullOfOrNull { file -> file.impls.values.firstOrNull { it.type == holder.type }?.functions?.firstOrNull { it.name == name }?.let { file to it } }
			is Instance.Object -> files.firstNotNullOfOrNull { file -> file.objects[holder.name]?.functions?.get(name)?.let { file to it } }
			is Type.Basic -> files.firstNotNullOfOrNull { file -> file.staticImpls.values.firstOrNull { it.type == holder }?.functions?.firstOrNull { it.name == name }?.let { file to it } }
			is Node.Object -> holder.functions[name]?.let { context.file to it }
			is Node.File -> holder.functions[name]?.let { holder to it }
			else -> null
		} ?: context.file.functions[name]?.let { context.file to it }
			?: files.firstNotNullOfOrNull { file -> file.functions[name]?.let { file to it } }
	}

	private fun validateTraits() {
		for (file in files) {
			for (implementation in file.impls.values) {
				for (extendedType in implementation.extends) {
					val traitName = (extendedType as? Type.Basic)?.name ?: continue
					val trait = files.firstNotNullOfOrNull { it.traits[traitName] } ?: continue
					for (required in trait.functionTraits) {
						val function = implementation.functions.firstOrNull { it.name == required.name }
							?: throw CrescentRuntimeException(file.path, "${implementation.type} must implement ${trait.name}.${required.name}")
						check(function.params.map { (it as Node.Typed).type } == required.params.map { (it as Node.Typed).type } && function.returnType == required.returnType) {
							"${implementation.type}.${function.name} does not match trait ${trait.name}"
						}
					}
				}
			}
		}
	}


	sealed class Instance : Node {

		abstract val type: Type

		data class Node(
			override val type: Type,
			var value: CrescentAST.Node,
		) : Instance()

		data class Struct(
			val name: String,
			val variables: Map<String, Variable>,
		) : Instance() {

			override val type = Type.Basic(name)

		}

		data class Enum(
			val name: String,
			val entryName: String,
			val properties: Map<String, Variable>,
		) : Instance() {
			override val type = Type.Basic(name)
		}

		data class Future(val value: CompletableFuture<CrescentAST.Node>) : Instance() {
			override val type = Type.Basic("Future")
		}

		data class Object(
			val name: String,
			override val type: Type,
			val constants: Map<String, Variable>,
			val variables: Map<String, Variable>,
			val functions: Map<String, CrescentAST.Node.Function>,
		) : Instance()

	}

	data class Variable(
		val name: String,
		var instance: Instance,
		val isFinal: Boolean,
	)


	/**
	 * @property variables Name -> Variable
	 * @constructor
	 */
	data class BlockContext(
		val file: Node.File,
		val holder: Node,
		val parameters: MutableMap<String, Variable>,
		//val variables: MutableMap<String, Variable(Node.Variable, )> = mutableMapOf(),
		val variables: MutableMap<String, Variable> = mutableMapOf(),
	)

}
