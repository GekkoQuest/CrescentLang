public fun identity(value: Any) -> Any {
    -> value
}

public fun choose(condition: Boolean, whenTrue: Any, whenFalse: Any) -> Any {
    if (condition) { -> whenTrue }
    -> whenFalse
}
