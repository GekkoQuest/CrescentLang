# Crescent language guide

This guide describes the syntax exercised by CrescentLang's automated tests.
Crescent is experimental: parsing a construct does not by itself guarantee that
every runtime, IR, or translator path implements it.

## Program entry point

A project must have exactly one `main`. It may omit both parentheses and an
argument parameter:

```crescent
fun main {
    println("Hello world")
}
```

To receive CLI arguments, declare an array of strings. Arguments passed before
the CLI's `--` separator belong to the Crescent command; arguments after it are
given to the program.

```crescent
fun main(args: [String]) {
    println(args[0])
}
```

Functions use `->` for a return type and for a returned expression. Parameters
may have trailing default values. Defaults are evaluated in the declaration's
lexical environment and their results are checked against the declared type.

```crescent
fun greet(name: String = "Moon") -> String {
    -> "Hello $name"
}
```

## Values and control flow

`val` declares an immutable value and `var` a mutable value. The direct and
lower-IR tests cover arithmetic, assignment, `if`/`else`, `when`, `while`,
string interpolation, and inclusive `for` ranges. A `when` may contain one
`else`, and it must be the final clause.

```crescent
var total = 0
for index in 1..3 {
    total += index
}
```

Array types may be nested, and array literals may contain expressions or other
arrays. Index reads and assignments are supported by both executable paths.
Every literal evaluation creates a fresh recursive value, including literals
used as defaults, so mutation does not leak between calls.

An individual array-slot read or write is linearizable on the array identity,
including through aliases. Compound slot updates such as `values[0] += 1` are
atomic VM-builtin read/modify/write operations. A separately written sequence
such as `values[0] = values[0] + 1` is not atomic.

```crescent
val matrix: [[I32]] = [[1 + 1], [3 * 4]]
matrix[0] = [5, 6]
```

Boolean `&&` and `||` are lazy: the right operand is evaluated only when the
left operand cannot determine the result. Checked `as` conversions reject
numeric overflow, fractions for integral targets, invalid signed/unsigned
crossings, and incompatible arrays, results, or declared types. `is` and
`!is` perform the same visibility-aware type test without conversion.

`success(value)` and `failure(error)` construct `Result` values. Postfix `?`
unwraps a success or returns the identical failure from the current
Result-returning function; it never propagates across a caller or async frame.
Result functions must explicitly return or propagate a Result value.

Every block, branch, and loop iteration has a lexical child scope. A child may
shadow an outer name, same-scope redeclaration fails, and assignment updates the
nearest existing binding. Newlines and semicolons are statement boundaries.
Postfix and infix parsing does not cross them; on one logical line, a valid
receiver followed by a key is an infix call, so separate adjacent calls should
be delimited.

## Objects, structs, and impls

The VM tests exercise object state, struct construction, field access,
instance/static impl calls, trait contracts, sealed declarations, and enum
members. Struct fields and enum parameters may have trailing defaults; required
fields may not follow defaulted fields, and constructor values are type checked.

```crescent
struct Cat(val name: String)

impl Cat {
    fun speak() -> String { -> name + " says meow" }
}
```

Trait contracts and implementation targets are checked with exact declaration
identity and visibility. Required inherited methods use `override`; spurious or
mismatched overrides fail. `operator` methods have validated names, arities,
and return shapes for unary, arithmetic, equality/comparison, containment,
indexing, shifts, and bitwise syntax. `infix` methods take one required
parameter. Static implementations cannot declare instance-only modifiers.
`async` is rejected with incompatible modifiers, and `inline` is a validated
no-op optimization hint rather than an interpreter behavior change.

Object initializers run in their declaration file's lexical environment.
Constants initialize before variables because the current AST stores those
groups separately; source order is retained within each group. Earlier members
are visible, while forward and cyclic initialization fail explicitly.

## Multiple files and imports

Pass a directory to the CLI to recursively discover `.crescent`, `.moon`, and
`.moo` files. The files are sorted by normalized path. Their project-relative
parent directory defines the package: files at the root share the root package,
`math/vector.moo` is in `math`, and `deep/math/util.moo` is in `deep.math`.
Directory components must be Crescent identifiers, including the language's
supported Unicode letters; dotted directory names are rejected rather than
being confused with nested directories.

Multiple files may contribute to one package. A file sees all of its own
declarations, including `private` ones, plus `internal` and `public`
declarations in sibling files. `private` is file-only, `internal` is
package-only, and `public` may be imported by another package. Parent and child
packages have no implicit access to one another.
Bare structs and objects nested in a `sealed` declaration are also addressable
as package declarations. Their effective visibility is the more restrictive of
the member and enclosing sealed visibility, and their names participate in the
same package-wide duplicate checks.

Imports select declarations with `package::Name`, optionally renamed with
`as`, or expose public declarations with `package::*`. An import beginning
with `::` resolves the root package from a nested package and the current root
package from a root file; root exact imports may use aliases, while wildcard
imports may not. Exact imports take precedence over wildcard candidates.
Ambiguous wildcards, conflicting aliases, missing packages/symbols,
inaccessible declarations, and duplicate symbol names within a package are
link errors.

```crescent
import geometry.shapes::Rectangle as Box
import crescent.std.math::clamp

fun main {
    println(clamp(12, 0, 10))
}
```

Exactly one user file must declare `main`. Bundled standard-library modules are
loaded into the linked file set but never participate in entry-point selection.

## Async and translation

Async functions and `await` have targeted VM coverage on Java 21. They are an
experimental runtime feature, not a compatibility promise for arbitrary code.
Concurrent functions share globals, singleton-object fields, and aliased
aggregate values. Cell reads and writes are linearizable. Builtin compound
assignments (`+=`, `-=`, `*=`, `/=`, `%=`, and `^=`) atomically update a
resolved variable, field, or array slot after evaluating its receiver, index,
and right-hand side exactly once. Compound assignment never dispatches a user
operator; use `x = x + value` when custom dispatch is required, with the
understanding that the separately expressed read/compute/write is not atomic.

File globals and singleton objects initialize once per VM. Both successful and
failed results are sticky, and concurrent callers observe the same result.
Initialization cycles fail explicitly. Launching async work or awaiting an
async result from a file/global or object initializer also fails immediately,
preventing initializer/future deadlocks. Closing a VM is idempotent and does not
cancel work accepted before close; later async submissions fail.

Console primitives use an explicit `RuntimeIO` supplied by the host. Its stream
adapter decodes input as UTF-8, has independent read and write locks so a
blocked read does not stop output, and never closes caller-owned streams. The
CLI routes its input/output through this interface; direct, lower-IR, and legacy
execution also accept injected I/O while retaining system-stream defaults for
compatibility.

`kotlin-to-crescent` translates the explicitly tested Kotlin subset; it is not a
general Kotlin compiler. Its structured parser covers the declaration,
container, literal, cast/type-test, return, and control-flow shapes exercised by
the translator tests while preserving comments and quoted text. Faithful shapes
include enum classes, direct sealed class/object variants, subject/named/
subjectless `when`, inclusive-range loops, regular and raw string templates,
and Kotlin reference equality. Allowlisted `@JvmStatic`, `@Suppress`, and
`@Deprecated` annotations become non-semantic provenance comments; semantic or
unsupported annotations reject. Nullable types, safe calls, Elvis expressions,
generics, qualified/function types, unsupported operators, malformed
declarations, and bare returns are rejected with exact half-open source spans
instead of being rewritten approximately.

`ptir` prints a structural export with source-qualified callable identity,
visibility, modifiers, recursive types, declarations, and defensive snapshots
of executable nodes. It accepts a linked source set without `main`, so it can
export a library. `ptir-run` executes the same model using
`PoderTechIrExecutor`, an independent interpreter that does not delegate to the
direct or lower-IR VM. It accepts a zero-parameter `main` or one `[String]`
parameter, forwards arguments after `--`, and uses caller-injected `RuntimeIO`.
Unsupported semantics, loss boundaries, or future AST shapes fail explicitly
instead of being silently dropped.

## Execution models and boundaries

- The compiler produces a genuine source-qualified lower IR. It contains
  defensive lowered declarations, statements, expressions, symbols, and
  normalized string paths; it retains no AST node, lexer token, or
  `java.nio.file.Path`. `CrescentIRVM` executes that model independently of the
  direct VM.
- The serialized stack IR is a separate compatibility format, not a lowering
  of the whole source language. Its `loadLibrary` and `createInstance` commands
  remain parseable/serializable but deliberately fail if executed.
- The type system is intentionally bounded by the tested language model.
  `inline` is validated but has no optimization effect. Atomic compound
  assignment supports builtin numeric operations only and does not dispatch a
  user operator; write `x = x + value` for custom dispatch, accepting that the
  separate read/compute/write is not atomic.
- Source-tied lexer, parser, linker, compiler, runtime, PTIR, and Kotlin
  translation failures use `path:line:column-endLine:endColumn: error: message`.
  Offsets and columns count UTF-16 code units, lines and columns are one-based,
  CRLF is one newline, and the end is exclusive. Filesystem/usage failures that
  do not originate in source naturally have no source span.
- The active `crescent.std.core`, `crescent.std.math`,
  `crescent.std.collections`, and `crescent.std.text` packages are
  Crescent-authored; host primitives are Kotlin runtime services exposed
  through `RuntimeIO`. The library is useful but intentionally bounded.
  `absoluteI32(I32.MIN)` retains `I32.MIN` under the tested fixed-width wrapping
  behavior. Other bundled `.unimplemented` files remain design sketches and are
  excluded from processed resources and distributions.
- The build publishes `dev.twelveoclock.lang:crescent-lang:0.0.1` with main,
  sources, and maintained-documentation JARs. `verifyMavenPublication` checks an
  isolated repository and compiles a clean consumer; `publishToMavenLocal`
  installs locally. Remote deployment and signing remain unconfigured.
- Kotlin translation remains a principled, precisely rejected subset, and the
  repository does not promise a stable general-purpose toolchain.

## Bundled examples

`src/main/resources/crescent/examples/hello_world.moo` is the public smoke
example for `run`, `ir`, `ptir`, and `ptir-run`. Active standard-library sources
live under `crescent/stdlib/` and are loaded from `modules.list` with explicit
package IDs. Historical examples, aspirational library sketches, and legacy
`builtin/` definitions keep the `.unimplemented` suffix and are not loaded.
