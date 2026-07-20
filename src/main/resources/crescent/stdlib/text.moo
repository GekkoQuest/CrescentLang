public fun concatText(left: String, right: String) -> String {
    -> left + right
}

public fun isEmptyText(text: String) -> Boolean {
    -> text == ""
}

public fun repeatText(text: String, times: I32) -> String {
    var result = ""
    for index in 1..times {
        result += text
    }
    -> result
}

public fun surroundText(text: String, prefix: String, suffix: String) -> String {
    -> prefix + text + suffix
}
