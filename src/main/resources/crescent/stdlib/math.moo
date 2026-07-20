public fun clamp(value: I32, minimum: I32, maximum: I32) -> I32 {
    if (value < minimum) { -> minimum }
    if (value > maximum) { -> maximum }
    -> value
}
