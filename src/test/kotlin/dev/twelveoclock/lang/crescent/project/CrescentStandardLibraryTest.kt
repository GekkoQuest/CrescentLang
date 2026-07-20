package dev.twelveoclock.lang.crescent.project

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CrescentStandardLibraryTest {

	@Test
	fun `bundled manifest exposes the documented concrete package surface`() {
		val functionsByPackage = CrescentStandardLibrary.load().associate { file ->
			file.packageId to file.functions.keys.toSet()
		}

		assertEquals(
			mapOf(
				"crescent.std.collections" to setOf(
					"singletonI32", "pairI32", "sameI32", "swapPairI32", "sumPairI32",
				),
				"crescent.std.core" to setOf("identity", "choose"),
				"crescent.std.math" to setOf(
					"minI32", "maxI32", "clamp", "absoluteI32", "signI32", "isEvenI32", "isOddI32",
				),
				"crescent.std.text" to setOf("concatText", "isEmptyText", "repeatText", "surroundText"),
			),
			functionsByPackage,
		)
	}

	@Test
	fun `allows package composition across resources and rejects duplicate resources and symbols`() {
		val composed = resources(
			"crescent/stdlib/modules.list" to "crescent.std.one=second.moo\ncrescent.std.one=first.moo",
			"first.moo" to "public fun first {}",
			"second.moo" to "public fun second {}",
		)
		val files = CrescentStandardLibrary.load(composed)
		assertTrue(files.all { it.packageId == "crescent.std.one" })
		assertTrue(files[0].path.toString().replace('\\', '/').endsWith("first.moo"))
		assertTrue(files[1].path.toString().replace('\\', '/').endsWith("second.moo"))

		val duplicateResource = resources(
			"crescent/stdlib/modules.list" to "crescent.std.one=same.moo\ncrescent.std.two=same.moo",
		)
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentStandardLibrary.load(duplicateResource)
			}.message.orEmpty().contains("Duplicate standard-library resource 'same.moo'"),
		)

		val duplicateSymbols = resources(
			"crescent/stdlib/modules.list" to "crescent.std.one=first.moo\ncrescent.std.one=second.moo",
			"first.moo" to "public fun duplicate {}",
			"second.moo" to "public fun duplicate {}",
		)
		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentModuleResolver.link(
				java.nio.file.Path.of("unused"),
				emptyList(),
				CrescentStandardLibrary.load(duplicateSymbols),
			)
		}
		assertTrue(error.message.orEmpty().contains("Duplicate symbol 'duplicate' in package 'crescent.std.one'"))
	}

	@Test
	fun `reports missing resources and retains parser failure cause`() {
		val missing = resources(
			"crescent/stdlib/modules.list" to "crescent.std.missing=missing.moo",
		)
		assertTrue(
			assertFailsWith<CrescentModuleResolutionException> {
				CrescentStandardLibrary.load(missing)
			}.message.orEmpty().contains("source 'missing.moo' is missing"),
		)

		val broken = resources(
			"crescent/stdlib/modules.list" to "crescent.std.broken=broken.moo",
			"broken.moo" to "fun 123 {}",
		)
		val error = assertFailsWith<CrescentModuleResolutionException> {
			CrescentStandardLibrary.load(broken)
		}
		assertTrue(error.message.orEmpty().contains("Could not parse bundled standard-library source 'broken.moo'"))
		assertNotNull(error.cause)
	}

	private fun resources(vararg entries: Pair<String, String>): ClassLoader {
		val content = entries.toMap().mapValues { it.value.toByteArray() }
		return object : ClassLoader(null) {
			override fun getResourceAsStream(name: String): InputStream? =
				content[name]?.let(::ByteArrayInputStream)
		}
	}
}
