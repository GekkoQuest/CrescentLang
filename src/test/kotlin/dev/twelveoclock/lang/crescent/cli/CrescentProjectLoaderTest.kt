package dev.twelveoclock.lang.crescent.cli

import dev.twelveoclock.lang.crescent.utils.collectSystemOut
import dev.twelveoclock.lang.crescent.vm.CrescentVM
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrescentProjectLoaderTest {

	@TempDir
	lateinit var tempDirectory: Path

	@Test
	fun `discovers supported sources recursively in deterministic order`() {
		val nested = tempDirectory.resolve("nested").createDirectories()
		tempDirectory.resolve("z.moo").writeText("fun z {}")
		nested.resolve("a.CRESCENT").writeText("fun a {}")
		nested.resolve("ignored.moo.unimplemented").writeText("fun ignored {}")
		nested.resolve("notes.txt").writeText("not source")

		val relative = CrescentProjectLoader.discoverSources(tempDirectory).map(tempDirectory::relativize)

		assertEquals(listOf(Path.of("nested", "a.CRESCENT"), Path.of("z.moo")), relative)
	}

	@Test
	fun `rejects unsupported individual files`() {
		val text = tempDirectory.resolve("example.txt")
		text.writeText("fun main {}")

		val error = assertFailsWith<CliExecutionException> { CrescentProjectLoader.discoverSources(text) }
		assertTrue(error.message.orEmpty().startsWith("Unsupported Crescent source extension:"))
	}

	@Test
	fun `reports empty projects`() {
		val error = assertFailsWith<CliExecutionException> { CrescentProjectLoader.discoverSources(tempDirectory) }
		assertTrue(error.message.orEmpty().startsWith("No Crescent source files were found"))
	}

	@Test
	fun `loads all files and selects their only main`() {
		tempDirectory.resolve("library.moon").writeText("fun greeting -> String { -> \"hello\" }")
		val main = tempDirectory.resolve("main.moo")
		main.writeText("fun main { println(greeting()) }")

		val project = CrescentProjectLoader.load(tempDirectory)

		assertEquals(2, project.userFiles.size)
		assertTrue(project.files.size > project.userFiles.size)
		assertTrue(project.files.any { it.packageId == "crescent.std.core" })
		assertEquals(main.toAbsolutePath().normalize(), project.mainFile.path)
	}

	@Test
	fun `loads linked source sets without main and executes nested imports from root`() {
		val library = tempDirectory.resolve("library.moo")
		library.writeText("public fun greeting -> String { -> \"hello\" }")

		val sourceSet = CrescentProjectLoader.loadSourceSet(tempDirectory)
		assertEquals(1, sourceSet.userFiles.size)
		assertTrue(sourceSet.files.size > sourceSet.userFiles.size)

		val nested = tempDirectory.resolve("app").createDirectories().resolve("main.moo")
		nested.writeText("import ::greeting fun main { println(greeting()) }")
		val project = CrescentProjectLoader.load(tempDirectory)
		val output = collectSystemOut { CrescentVM(project.files, project.mainFile).use { it.invoke() } }

		assertEquals("hello\n", output)
		assertEquals("", project.mainFile.importedSymbols.getValue("greeting").packageId)
	}

	@Test
	fun `loads and resolves the bundled standard library without creating another main`() {
		val main = tempDirectory.resolve("main.moo")
		main.writeText("import crescent.std.core::identity fun main { println(identity(\"moon\")) }")

		val project = CrescentProjectLoader.load(tempDirectory)
		val identity = project.mainFile.importedSymbols.getValue("identity")

		assertEquals("crescent.std.core", identity.packageId)
		assertEquals("identity", identity.sourceName)
		assertEquals(1, project.userFiles.count { it.mainFunction != null })
		assertEquals(0, project.files.filter { it.packageId.startsWith("crescent.std") }.count { it.mainFunction != null })
	}

	@Test
	fun `project file lists are immutable snapshots`() {
		tempDirectory.resolve("main.moo").writeText("fun main {}")
		val project = CrescentProjectLoader.load(tempDirectory)

		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(project.files as MutableList<*>).removeAt(0)
		}
		assertFailsWith<UnsupportedOperationException> {
			@Suppress("UNCHECKED_CAST")
			(project.userFiles as MutableList<*>).removeAt(0)
		}
	}

	@Test
	fun `executes an explicitly imported Crescent-authored standard library function`() {
		val main = tempDirectory.resolve("main.moo")
		main.writeText("import crescent.std.math::clamp fun main { println(clamp(12, 0, 10)) }")
		val project = CrescentProjectLoader.load(tempDirectory)

		val output = collectSystemOut {
			CrescentVM(project.files, project.mainFile).use { it.invoke() }
		}

		assertEquals("10\n", output)
	}

	@Test
	fun `distinguishes missing and duplicate main functions`() {
		val first = tempDirectory.resolve("first.moo")
		first.writeText("fun helper {}")
		assertEquals(
			"Project does not declare a main function",
			assertFailsWith<CliExecutionException> { CrescentProjectLoader.load(tempDirectory) }.message,
		)

		val second = tempDirectory.resolve("second.moo")
		first.writeText("fun main {}")
		second.writeText("fun main(args: [String]) {}")
		val duplicate = assertFailsWith<CliExecutionException> { CrescentProjectLoader.load(tempDirectory) }
		assertTrue(duplicate.message.orEmpty().startsWith("Project declares multiple main functions:"))
		assertTrue(duplicate.message.orEmpty().contains(first.toString()))
		assertTrue(duplicate.message.orEmpty().contains(second.toString()))
	}

	@Test
	fun `parse failures identify their source file`() {
		val broken = tempDirectory.resolve("broken.moo")
		Files.writeString(broken, "fun 123 {}")

		val error = assertFailsWith<CliExecutionException> { CrescentProjectLoader.load(broken) }
		assertTrue(error.message.orEmpty().startsWith("Could not parse ${broken.toAbsolutePath().normalize()}:"))
	}
}
