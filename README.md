<p align="center">
  <img src="CrescentIcon.png" alt="Crescent icon" width="100" height="100">
</p>

# CrescentLang

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Gradle build](https://github.com/GekkoQuest/CrescentLang/actions/workflows/gradle-wrapper.yml/badge.svg)](https://github.com/GekkoQuest/CrescentLang/actions/workflows/gradle-wrapper.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00.svg)](https://adoptium.net/temurin/releases/?version=21)

CrescentLang is an experimental Kotlin/JVM implementation of the Crescent
language. The repository contains a lexer, parser, direct interpreter, a
source-qualified lower IR with its own VM, a legacy stack-IR compatibility
path, and Kotlin/PoderTechIR translators. The lower IR retains normalized
source identity but no parser token, AST node, or `java.nio.file.Path`.

The project is useful for language experimentation, but it is not a published
general-purpose toolchain or standard library. Supported behavior is the
behavior covered by the automated tests and the smoke commands below; files
ending in `.unimplemented` are design sketches.

## Build and smoke test

Requirements:

- A Java 21 JDK
- No system Gradle installation; use the checked-in wrapper

On Linux or macOS:

```shell
./gradlew clean test
./gradlew run --args="run src/main/resources/crescent/examples/hello_world.moo"
./gradlew run --args="ir src/main/resources/crescent/examples/hello_world.moo"
./gradlew run --args="ptir src/main/resources/crescent/examples/hello_world.moo"
```

On Windows, use the same commands with `gradlew.bat`:

```powershell
.\gradlew.bat clean test
.\gradlew.bat run --args="run src/main/resources/crescent/examples/hello_world.moo"
```

`./gradlew installDist` creates `build/install/CrescentLang/bin/crescent`;
on Windows, run `.\gradlew.bat installDist` and use
`.\build\install\CrescentLang\bin\crescent.bat`.

The build currently pins Kotlin 2.4.10, Gradle 9.6.1, JUnit 6.1.2, and a Java 21
toolchain. Foojay toolchain resolution is configured when a matching local JDK
is unavailable.

## Command line

```text
crescent run <file-or-directory> [-- program args]
crescent <file-or-directory> [-- program args]
crescent ir <file-or-directory> [-- program args]
crescent ptir <file-or-directory>
crescent kotlin-to-crescent <file.kt>
```

`run` may be omitted. Program arguments are accepted by `run` and `ir` only,
and must follow `--`; extra tokens before the separator are rejected instead of
being silently ignored.

Passing a directory recursively discovers `.crescent`, `.moon`, and `.moo`
files in deterministic path order, and exactly one user file must declare
`main`. A file's package is its project-relative parent directory: root files
share the root package, while `math/vector.moo` belongs to `math`. Package
components use the same Unicode identifier rules as Crescent source; a dotted
directory name is not treated as nested packages. Sibling files implicitly
share `internal` and `public` declarations; `private` remains file-only. Other
packages require exact, aliased, or wildcard imports, and only `public`
declarations cross package boundaries. Leading `::` imports resolve from the
root/current package context. Missing, inaccessible, duplicate, conflicting,
and ambiguous imports fail while the project is linked.
For compatibility, bare structs and objects nested in a `sealed` declaration
are package declarations too. Their effective visibility is the more restrictive
of the member and enclosing sealed visibility.

The CLI also loads a deliberately minimal Crescent-authored library from a
classpath manifest: `crescent.std.core` provides `identity`, and
`crescent.std.math` provides `clamp`. These functions are not implicit; import
them like project declarations:

```crescent
import crescent.std.math::clamp
fun main { println(clamp(12, 0, 10)) }
```

## Verified scope

The automated suite exercises:

- tokenization, statement boundaries, interpolation escapes, and the syntax
  used by the test fixtures;
- direct and independently lowered execution of functions, lexical blocks,
  lazy `&&`/`||`, checked casts/type tests, `Result` propagation, arrays,
  structs, objects, traits, implementations, validated modifiers, and custom
  operators;
- typed numeric boundaries, async lifecycle, initializer safety, and atomic
  builtin compound updates of shared variables, fields, and array slots;
- directory packages, exact/wildcard/aliased/root imports, declaration and
  member visibility, deterministic project discovery, and entry-point checks;
- legacy stack-IR serialization/execution compatibility, structured
  Kotlin-subset translation, and structural PoderTechIR export; and
- explicit runtime I/O routing with UTF-8 input decoding and caller-supplied
  output streams in the direct VM, lower-IR VM, legacy VM, and CLI.

The legacy `loadLibrary` and `createInstance` commands remain readable and
serializable for compatibility, but deliberately have no legacy runtime
implementation. `inline` is validated as a legal modifier and currently has no
observable optimization effect. Compound assignment is a VM-builtin atomic
operation and never dispatches a user operator.

This list intentionally does not promise that every parser production, Kotlin
construct, or resource sketch has complete runtime semantics. Diagnostics are
source-path and token/command-index aware, not full source spans. See the
[language guide](docs/language-reference.md) for tested examples and explicit
limitations, and [the resource notes](src/main/resources/crescent/README.md)
before using bundled sources.
