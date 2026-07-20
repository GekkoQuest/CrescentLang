public fun minI32(left: I32, right: I32) -> I32 {
    if (left < right) { -> left }
    -> right
}

public fun maxI32(left: I32, right: I32) -> I32 {
    if (left > right) { -> left }
    -> right
}

public fun clamp(value: I32, minimum: I32, maximum: I32) -> I32 {
    if (value < minimum) { -> minimum }
    if (value > maximum) { -> maximum }
    -> value
}

public fun absoluteI32(value: I32) -> I32 {
    if (value < 0) { -> -value }
    -> value
}

public fun signI32(value: I32) -> I32 {
    if (value < 0) { -> -1 }
    if (value > 0) { -> 1 }
    -> 0
}

public fun isEvenI32(value: I32) -> Boolean {
    -> value % 2 == 0
}

public fun isOddI32(value: I32) -> Boolean {
    -> value % 2 != 0
}
