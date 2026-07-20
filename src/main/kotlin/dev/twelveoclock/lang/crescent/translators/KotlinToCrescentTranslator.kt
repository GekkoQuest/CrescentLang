package dev.twelveoclock.lang.crescent.translators

/**
 * Translates the structurally parsed Kotlin subset that has a faithful Crescent
 * representation. Unsupported Kotlin semantics fail with an exact source offset.
 */
object KotlinToCrescentTranslator {
	fun translate(kotlinSource: String): String = KotlinSubsetParser(kotlinSource).translate()
}
