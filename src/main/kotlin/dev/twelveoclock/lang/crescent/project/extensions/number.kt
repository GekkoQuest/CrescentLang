package dev.twelveoclock.lang.crescent.project.extensions

import java.math.BigInteger

/** Returns this value in the smallest primitive number type that represents it exactly. */
fun Number.minimize(): Number = when (this) {
	is Byte -> this
	is BigInteger -> minimizeBigInteger()
	is Float -> if (toInt().toFloat() == this) minimizeIntegral() else this
	is Double -> when {
		toLong().toDouble() == this -> minimizeIntegral()
		toFloat().toDouble() == this -> toFloat()
		else -> this
	}
	else -> minimizeIntegral()
}

private fun BigInteger.minimizeBigInteger(): Number = when {
	this in BYTE_MIN..BYTE_MAX -> toByte()
	this in SHORT_MIN..SHORT_MAX -> toShort()
	this in INT_MIN..INT_MAX -> toInt()
	this in LONG_MIN..LONG_MAX -> toLong()
	else -> this
}

private fun Number.minimizeIntegral(): Number = when (toLong()) {
	in Byte.MIN_VALUE..Byte.MAX_VALUE -> toByte()
	in Short.MIN_VALUE..Short.MAX_VALUE -> toShort()
	in Int.MIN_VALUE..Int.MAX_VALUE -> toInt()
	else -> this
}

private val BYTE_MIN = BigInteger.valueOf(Byte.MIN_VALUE.toLong())
private val BYTE_MAX = BigInteger.valueOf(Byte.MAX_VALUE.toLong())
private val SHORT_MIN = BigInteger.valueOf(Short.MIN_VALUE.toLong())
private val SHORT_MAX = BigInteger.valueOf(Short.MAX_VALUE.toLong())
private val INT_MIN = BigInteger.valueOf(Int.MIN_VALUE.toLong())
private val INT_MAX = BigInteger.valueOf(Int.MAX_VALUE.toLong())
private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)
private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
