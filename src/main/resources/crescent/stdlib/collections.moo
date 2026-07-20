public fun singletonI32(value: I32) -> [I32] {
    -> [value]
}

public fun pairI32(first: I32, second: I32) -> [I32] {
    -> [first, second]
}

public fun sameI32(left: [I32], right: [I32]) -> Boolean {
    -> left == right
}

public fun swapPairI32(pair: [I32]) -> [I32] {
    -> [pair[1], pair[0]]
}

public fun sumPairI32(pair: [I32]) -> I32 {
    -> pair[0] + pair[1]
}
