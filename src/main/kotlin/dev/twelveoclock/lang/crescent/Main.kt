package dev.twelveoclock.lang.crescent

import dev.twelveoclock.lang.crescent.cli.CrescentCli
import kotlin.system.exitProcess

object Main {

	@JvmStatic
	fun main(args: Array<String>) {
		val exitCode = CrescentCli.run(args)
		if (exitCode != 0) exitProcess(exitCode)
	}
}
