package dev.twelveoclock.lang.crescent.compiler

import dev.twelveoclock.lang.crescent.language.ast.CrescentAST.Node.File
import dev.twelveoclock.lang.crescent.language.ir.CrescentIR

/**
 * Links parsed Crescent files into the canonical high-level IR consumed by
 * [dev.twelveoclock.lang.crescent.vm.CrescentIRVM].
 *
 * Keeping the typed AST in this IR stage preserves declarations needed by
 * structs, traits, impls, enums, imports, and async functions. The legacy stack
 * commands are still accepted by the IR VM and parser for backwards compatibility.
 */
object CrescentIRCompiler {

	fun invoke(file: File): CrescentIR = invoke(listOf(file), file)

	fun invoke(files: List<File>, mainFile: File): CrescentIR {
		require(mainFile in files) { "The main file must be included in the compiler input" }
		return CrescentIR(listOf(CrescentIR.Command.Program(files.toList(), mainFile)))
	}
}
