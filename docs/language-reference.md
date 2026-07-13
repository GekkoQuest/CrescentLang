# Crescent language guide

## Program entry point

```crescent
fun main(args: [String]) {
    println("Hello ${args[0]}")
}
```

Functions use `->` for return types and return expressions. Parameters may have defaults.

```crescent
fun greet(name: String = "Moon") -> String {
    -> "Hello $name"
}
```

## Values and control flow

Use `val` for immutable values, `var` for mutable values, and `const` for file/object constants. Crescent supports `if`/`else`, `when`, `while`, and inclusive `for` ranges.

```crescent
var total = 0
for index in 1..3 {
    total += index
}
```

## Objects, structs, impls, and traits

```crescent
trait Named {
    fun name() -> String
}

struct Cat(val value: String)

impl Cat : Named {
    fun name() -> String { -> value }
}

impl static Cat {
    fun create() -> Cat { -> Cat("Luna") }
}
```

Trait function signatures are checked when a VM links the project. Instance impl methods resolve fields through their struct holder; `impl static` methods are called on the type.

## Enums and `when`

```crescent
enum Color(label: String) {
    RED("red")
    BLUE("blue")
}

val color = Color.RED
when(color) {
    .RED -> { println(color.label) }
    else -> { println("other") }
}
```

Enums expose constructor properties, entries, `values()`, and `random()`.

## Multiple files

Pass a directory to the CLI. Every `.crescent`, `.moon`, and `.moo` file below it is parsed and linked. Declarations can be resolved across the linked files, and exactly one file must provide `main`.

## Async functions

```crescent
async fun answer() -> I32 { -> 42 }

fun main {
    println(await(answer()))
}
```

Async functions use Java 21 virtual threads and return a `Future`. `await` resolves the value or propagates its error.

## Kotlin and PoderTechIR translation

`kotlin-to-crescent` translates the source-level Kotlin subset shared by the languages. `ptir` lowers linked Crescent declarations and function bodies into dependency-free PoderTechIR modules. These modules avoid the upstream project's removed `jdk.incubator.foreign` dependency and are safe to use on Java 21.
