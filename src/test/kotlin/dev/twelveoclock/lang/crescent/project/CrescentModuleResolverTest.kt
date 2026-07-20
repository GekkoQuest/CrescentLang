package dev.twelveoclock.lang.crescent.project

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node
import dev.twelveoclock.lang.crescent.language.token.CrescentToken
import dev.twelveoclock.lang.crescent.lexers.CrescentLexer
import dev.twelveoclock.lang.crescent.parsers.CrescentParser
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrescentModuleResolverTest {

	@TempDir
	lateinit var root: Path

	@Test
	fun `directory defines package and sibling internal and public symbols are visible`() {
		val main = source("main.moo", "fun main {}")
		val sibling = source(
			"helper.moo",
			"internal fun packageHelper {} private fun fileHelper {} public fun publicHelper {}",
		)
		val nested = source("deep/tools.moo", "public fun nestedHelper {}")

		val linked = CrescentModuleResolver.link(root, listOf(main, sibling, nested))
		val linkedMain = linked.single { it.path.fileName.toString() == "main.moo" }

		assertEquals("", linkedMain.packageId)
		assertEquals("deep", linked.single { it.path.fileName.toString() == "tools.moo" }.packageId)
		assertTrue("packageHelper" in linkedMain.importedSymbols)
		assertTrue("publicHelper" in linkedMain.importedSymbols)
		assertFalse("fileHelper" in linkedMain.importedSymbols)
		assertFalse("nestedHelper" in linkedMain.importedSymbols)
	}

	@Test
	fun `exact aliases and wildcards expose only cross-package public symbols`() {
		val main = source(
			"main.moo",
			"import math::add as sum import math::* fun main {}",
		)
		val math = source(
			"math/numbers.moo",
			"public fun add {} public fun multiply {} internal fun secret {} private fun hidden {}",
		)

		val linkedMain = CrescentModuleResolver.link(root, listOf(main, math)).single { it.packageId.isEmpty() }

		assertEquals("add", linkedMain.importedSymbols.getValue("sum").sourceName)
		assertEquals("multiply", linkedMain.importedSymbols.getValue("multiply").sourceName)
		assertFalse("secret" in linkedMain.importedSymbols)
		assertFalse("hidden" in linkedMain.importedSymbols)
	}

	@Test
	fun `current-package alias may bind own private but not sibling private`() {
		val own = source("own.moo", "import ::hidden as local private fun hidden {} fun main {}")
		val linkedOwn = CrescentModuleResolver.link(root, listOf(own)).single()
		assertEquals("hidden", linkedOwn.importedSymbols.getValue("local").sourceName)

		val main = source("main.moo", "import ::siblingSecret fun main {}")
		val sibling = source("sibling.moo", "private fun siblingSecret {}")
		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(main, sibling))
		}
		assertTrue(error.message.orEmpty().contains("cannot access private symbol"))
	}

	@Test
	fun `leading separator imports root package from nested packages and remains root in root files`() {
		val rootApi = source("api.moo", "public fun rootHelper {}")
		val nested = source(
			"nested/main.moo",
			"import ::rootHelper import ::rootHelper as helper fun main { rootHelper(); helper() }",
		)
		val rootClient = source("client.moo", "import ::rootHelper as helper fun client { helper() }")

		val linked = CrescentModuleResolver.link(root, listOf(rootApi, nested, rootClient))
		val linkedNested = linked.single { it.path.fileName.toString() == "main.moo" }
		val linkedRootClient = linked.single { it.path.fileName.toString() == "client.moo" }

		assertEquals("", linkedNested.importedSymbols.getValue("rootHelper").packageId)
		assertEquals("rootHelper", linkedNested.importedSymbols.getValue("helper").sourceName)
		assertEquals("", linkedRootClient.importedSymbols.getValue("helper").packageId)
	}

	@Test
	fun `internal and private symbols cannot be imported across packages`() {
		for ((name, visibility) in listOf("inside" to "internal", "hidden" to "private")) {
			val main = source("case-$name/main.moo", "import library::$name fun main {}")
			val library = source("case-$name/library/api.moo", "$visibility fun $name {}")
			val caseRoot = root.resolve("case-$name")
			val error = assertFailsWith<CrescentModuleResolutionException> {
				CrescentModuleResolver.link(caseRoot, listOf(main, library))
			}
			assertTrue(error.message.orEmpty().contains("cannot access $visibility symbol"))
		}
	}

	@Test
	fun `duplicate names fail within a package but are allowed across packages`() {
		val first = source("duplicates/first.moo", "fun same {}")
		val second = source("duplicates/second.moo", "val same = 1")
		val duplicate = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(first, second))
		}
		assertTrue(duplicate.message.orEmpty().contains("Duplicate symbol 'same' in package 'duplicates'"))

		val other = source("other/second.moo", "fun same {}")
		val linked = CrescentModuleResolver.link(root, listOf(first, other))
		assertEquals(setOf("duplicates", "other"), linked.map(Node.File::packageId).toSet())
	}

	@Test
	fun `bare sealed members are package declarations with the outer visibility and exact file`() {
		val main = source("main.moo", "import lib::Box import lib::Empty fun main {}")
		val library = source(
			"lib/api.moo",
			"public sealed Family { struct Box() object Empty }",
		)

		val linkedMain = CrescentModuleResolver.link(root, listOf(main, library)).single { it.packageId.isEmpty() }
		val box = linkedMain.importedSymbols.getValue("Box")
		val empty = linkedMain.importedSymbols.getValue("Empty")
		assertEquals(Node.ModuleSymbolKind.STRUCT, box.kind)
		assertEquals(Node.ModuleSymbolKind.OBJECT, empty.kind)
		assertEquals("Family", box.enclosingTypeName)
		assertEquals("Family", empty.enclosingTypeName)
		assertEquals(library.path, box.declarationPath)
		assertEquals(library.path, empty.declarationPath)

		val collision = source(
			"collision/api.moo",
			"public sealed Family { struct Box() } public struct Box()",
		)
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentModuleResolver.link(root.resolve("collision"), listOf(collision))
			}.message.orEmpty().contains("Duplicate symbol 'Box'"),
		)
	}

	@Test
	fun `bare sealed members use the most restrictive outer and inner visibility`() {
		val main = source("private-main.moo", "import private_lib::Hidden fun main {}")
		val library = source("private_lib/api.moo", "public sealed Family { private struct Hidden() }")

		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(main, library))
		}
		assertTrue(error.message.orEmpty().contains("cannot access private symbol 'Hidden'"))
	}

	@Test
	fun `nested package symbols require imports and exact imports beat wildcard collisions`() {
		val withoutImport = source("plain/main.moo", "fun main {}")
		val nested = source("plain/nested/api.moo", "public fun answer {}")
		val plainRoot = root.resolve("plain")
		val unlinkedMain = CrescentModuleResolver.link(plainRoot, listOf(withoutImport, nested)).single { it.packageId.isEmpty() }
		assertFalse("answer" in unlinkedMain.importedSymbols)

		val main = source(
			"imports/main.moo",
			"import one::* import two::* import one::choice fun main {}",
		)
		val one = source("imports/one/api.moo", "public fun choice {}")
		val two = source("imports/two/api.moo", "public fun choice {}")
		val importsRoot = root.resolve("imports")
		val linkedMain = CrescentModuleResolver.link(importsRoot, listOf(main, one, two)).single { it.packageId.isEmpty() }
		assertEquals("one", linkedMain.importedSymbols.getValue("choice").packageId)
	}

	@Test
	fun `multiple wildcard candidates fail deterministically`() {
		val main = source("main.moo", "import one::* import two::* fun main {}")
		val one = source("one/api.moo", "public fun choice {}")
		val two = source("two/api.moo", "public fun choice {}")

		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(main, one, two))
		}
		assertTrue(error.message.orEmpty().contains("Ambiguous wildcard import 'choice'"))
		assertTrue(error.message.orEmpty().indexOf("one::choice") < error.message.orEmpty().indexOf("two::choice"))
	}

	@Test
	fun `missing packages symbols and reserved user packages are diagnosed`() {
		val missingPackage = source("missing-package.moo", "import absent::thing fun main {}")
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentModuleResolver.link(root, listOf(missingPackage))
			}.message.orEmpty().contains("missing package 'absent'"),
		)

		val missingSymbol = source("missing-symbol/main.moo", "import lib::absent fun main {}")
		val library = source("missing-symbol/lib/api.moo", "public fun present {}")
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentModuleResolver.link(root.resolve("missing-symbol"), listOf(missingSymbol, library))
			}.message.orEmpty().contains("missing symbol 'absent'"),
		)

		val reserved = source("reserved/crescent/std/user.moo", "fun main {}")
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentModuleResolver.link(root.resolve("reserved"), listOf(reserved))
			}.message.orEmpty().contains("reserved package 'crescent.std'"),
		)
	}

	@Test
	fun `resolved import maps are defensive immutable snapshots`() {
		val main = source("main.moo", "import lib::value fun main {}")
		val library = source("lib/api.moo", "public val value = 1")
		val imported = CrescentModuleResolver.link(root, listOf(main, library))
			.single { it.packageId.isEmpty() }.importedSymbols

		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(imported as MutableMap<String, Node.ModuleSymbol>).clear()
		}
	}

	@Test
	fun `linked files snapshot caller-owned declaration collections and returned file list`() {
		val parsed = source("main.moo", "fun main {} fun helper {}")
		val mutableImports = parsed.imports.toMutableList()
		val mutableFunctions = parsed.functions.toMutableMap()
		val callerOwned = parsed.copy(imports = mutableImports, functions = mutableFunctions)

		val linked = CrescentModuleResolver.link(root, listOf(callerOwned))
		mutableImports += Node.Import("missing", "value")
		mutableFunctions.clear()

		assertTrue(linked.single().imports.isEmpty())
		assertEquals(setOf("main", "helper"), linked.single().functions.keys)
		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(linked as MutableList<Node.File>).clear()
		}
	}

	@Test
	fun `linked files deeply detach and freeze nested declaration and executable collections`() {
		val type = Node.Type.Basic("I32")
		val literal = Node.Primitive.Number.I32(1)
		val defaultNodes = mutableListOf<Node>(literal)
		val defaultParameter = Node.Parameter.WithDefault("value", type, Node.Expression(defaultNodes))
		val functionParameters = mutableListOf<Node.Parameter>(defaultParameter)
		val arrayValues = arrayOf<Node>(literal)
		val functionBodyNodes = mutableListOf<Node>(Node.Array(arrayValues))
		val functionModifiers = mutableListOf(CrescentToken.Modifier.ASYNC)
		val function = Node.Function(
			"work",
			functionModifiers,
			CrescentToken.Visibility.PUBLIC,
			functionParameters,
			type,
			Node.Statement.Block(functionBodyNodes),
		)
		val field = Node.Variable.Basic("field", type, literal, false, CrescentToken.Visibility.PUBLIC)
		val constant = Node.Variable.Constant("constant", type, literal, CrescentToken.Visibility.PUBLIC)
		val structFields = mutableListOf(field)
		val sealedStructFields = mutableListOf(field)
		val sealedStructs = mutableListOf(Node.Struct("Nested", sealedStructFields))
		val objectVariables = mutableMapOf("field" to field)
		val objectConstants = mutableMapOf("constant" to constant)
		val objectFunctions = mutableMapOf("work" to function)
		val sealedObjectVariables = mutableMapOf("field" to field)
		val nestedObject = Node.Object("NestedObject", sealedObjectVariables, mutableMapOf(), mutableMapOf())
		val sealedObjects = mutableListOf(nestedObject)
		val traitParameters = mutableListOf<Node.Parameter>(Node.Parameter.Basic("input", type))
		val traitFunctions = mutableListOf(Node.FunctionTrait("contract", traitParameters, type))
		val enumParameters = mutableListOf<Node.Parameter>(Node.Parameter.Basic("code", type))
		val enumArguments = mutableListOf<Node>(literal)
		val enumEntries = mutableListOf(Node.EnumEntry("One", enumArguments))
		val implModifiers = mutableListOf(CrescentToken.Modifier.OVERRIDE)
		val implExtends = mutableListOf<Node.Type>(Node.Type.Basic("Parent"))
		val implFunctions = mutableListOf(function)

		val parsed = source("deep-snapshot.moo", "fun main {}")
		val callerOwned = parsed.copy(
			structs = mutableMapOf("Record" to Node.Struct("Record", structFields)),
			sealeds = mutableMapOf("Family" to Node.Sealed("Family", sealedStructs, sealedObjects)),
			impls = mutableMapOf("Record" to Node.Impl(Node.Type.Basic("Record"), implModifiers, implExtends, implFunctions)),
			traits = mutableMapOf("Contract" to Node.Trait("Contract", traitFunctions)),
			objects = mutableMapOf(
				"Registry" to Node.Object("Registry", objectVariables, objectConstants, objectFunctions),
			),
			enums = mutableMapOf("Choice" to Node.Enum("Choice", enumParameters, enumEntries)),
			functions = mutableMapOf("main" to parsed.mainFunction!!, "work" to function),
		)

		val linked = CrescentModuleResolver.link(root, listOf(callerOwned)).single()
		structFields.clear()
		sealedStructFields.clear()
		sealedStructs.clear()
		sealedObjectVariables.clear()
		sealedObjects.clear()
		objectVariables.clear()
		objectConstants.clear()
		objectFunctions.clear()
		traitParameters.clear()
		traitFunctions.clear()
		enumParameters.clear()
		enumArguments.clear()
		enumEntries.clear()
		implModifiers.clear()
		implExtends.clear()
		implFunctions.clear()
		functionModifiers.clear()
		functionParameters.clear()
		functionBodyNodes.clear()
		defaultNodes.clear()
		arrayValues[0] = Node.Primitive.Number.I32(9)

		assertEquals(1, linked.structs.getValue("Record").variables.size)
		assertEquals(1, linked.sealeds.getValue("Family").structs.size)
		assertEquals(1, linked.sealeds.getValue("Family").structs.single().variables.size)
		assertEquals(1, linked.sealeds.getValue("Family").objects.size)
		assertEquals(1, linked.sealeds.getValue("Family").objects.single().variables.size)
		assertEquals(setOf("field"), linked.objects.getValue("Registry").variables.keys)
		assertEquals(setOf("constant"), linked.objects.getValue("Registry").constants.keys)
		assertEquals(setOf("work"), linked.objects.getValue("Registry").functions.keys)
		assertEquals(1, linked.traits.getValue("Contract").functionTraits.single().params.size)
		assertEquals(1, linked.enums.getValue("Choice").parameters.size)
		assertEquals(1, linked.enums.getValue("Choice").structs.single().arguments.size)
		assertEquals(1, linked.impls.getValue("Record").modifiers.size)
		assertEquals(1, linked.impls.getValue("Record").extends.size)
		assertEquals(1, linked.impls.getValue("Record").functions.size)
		assertEquals(1, linked.functions.getValue("work").params.size)
		assertEquals(1, linked.functions.getValue("work").innerCode.nodes.size)
		assertEquals(1, (linked.functions.getValue("work").params.single() as Node.Parameter.WithDefault).defaultValue.nodes.size)
		assertEquals(literal, ((linked.functions.getValue("work").innerCode.nodes.single() as Node.Array).values.single()))

		assertFrozen(linked.structs.getValue("Record").variables)
		assertFrozen(linked.sealeds.getValue("Family").structs)
		assertFrozen(linked.sealeds.getValue("Family").structs.single().variables)
		assertFrozen(linked.sealeds.getValue("Family").objects.single().variables)
		assertFrozen(linked.objects.getValue("Registry").variables)
		assertFrozen(linked.traits.getValue("Contract").functionTraits)
		assertFrozen(linked.traits.getValue("Contract").functionTraits.single().params)
		assertFrozen(linked.enums.getValue("Choice").structs)
		assertFrozen(linked.enums.getValue("Choice").structs.single().arguments)
		assertFrozen(linked.impls.getValue("Record").functions)
		assertFrozen(linked.functions.getValue("work").params)
		assertFrozen(linked.functions.getValue("work").innerCode.nodes)
		assertFrozen((linked.functions.getValue("work").params.single() as Node.Parameter.WithDefault).defaultValue.nodes)
	}

	@Test
	fun `dotted directory component cannot alias nested package components`() {
		val dotted = source("foo.bar/api.moo", "public fun dotted {}")
		val nested = source("foo/bar/api.moo", "public fun nested {}")

		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(dotted, nested))
		}
		assertTrue(error.message.orEmpty().contains("Invalid package directory 'foo.bar'"))
	}

	@Test
	fun `package directory identifiers use the same Unicode rules as source identifiers`() {
		val main = source("main.moo", "import λογική.β2::answer fun main {}")
		val library = source("λογική/β2/api.moo", "public fun answer {}")

		val linked = CrescentModuleResolver.link(root, listOf(main, library))
		val linkedMain = linked.single { it.packageId.isEmpty() }
		assertEquals("λογική.β2", linked.single { it.path.fileName.toString() == "api.moo" }.packageId)
		assertEquals("λογική.β2", linkedMain.importedSymbols.getValue("answer").packageId)

		val leadingDigit = source("٢bad/api.moo", "public fun invalid {}")
		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(root, listOf(leadingDigit))
		}
		assertTrue(error.message.orEmpty().contains("Invalid package directory '٢bad'"))
	}

	private fun source(relativePath: String, text: String): Node.File {
		val path = root.resolve(relativePath)
		path.parent.createDirectories()
		path.writeText(text)
		return CrescentParser.invoke(path.toAbsolutePath().normalize(), CrescentLexer.invoke(text))
	}

	private fun assertFrozen(values: Collection<*>) {
		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(values as MutableCollection<Any?>).clear()
		}
	}

	private fun assertFrozen(values: Map<*, *>) {
		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(values as MutableMap<Any?, Any?>).clear()
		}
	}
}
