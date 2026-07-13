<p align="center">
  <img src="CrescentIcon.png" alt="Crescent icon" width="100" height="100">
</p>

# CrescentLang

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Gradle build](https://github.com/GekkoQuest/CrescentLang/actions/workflows/gradle-wrapper.yml/badge.svg)](https://github.com/GekkoQuest/CrescentLang/actions/workflows/gradle-wrapper.yml)
[![Java 21](https://img.shields.io/badge/Java-21-ED8B00.svg)](https://adoptium.net/temurin/releases/?version=21)

CrescentLang is a Kotlin/JVM implementation of the Crescent programming language. It includes a lexer, parser, typed AST, interpreter, high-level IR compiler and VM, PoderTechIR exporter, and a Kotlin-source translator.

## Requirements

- Java 21
- No system Gradle installation; use the included wrapper

The build uses Kotlin 2.4.0, Gradle 9.5.0 (the newest Gradle release fully supported by Kotlin 2.4.0), and JUnit 6.1.2. Missing Java 21 toolchains can be resolved through Foojay.

```shell
./gradlew build
./gradlew run --args="run src/main/resources/crescent/examples/hello_world.moo"
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Command line

```text
crescent run <file-or-directory> [-- program args]
crescent ir <file-or-directory> [-- program args]
crescent ptir <file-or-directory>
crescent kotlin-to-crescent <file.kt>
```

Directories are linked as one project and may contain `.crescent`, `.moon`, or `.moo` files. Exactly one file must declare `main`.

## Feature support

### Core

- [x] Lexer
- [x] Parser
- [x] VM
- [x] IR compiler and VM
- [x] [Language guide](docs/language-reference.md)
- [x] [PoderTechIR](https://github.com/Moocow9m/PoderTechIR) translator
- [x] Compiler
- [x] Translation from Kotlin source to Crescent

### VM

- [x] Math
- [x] Functions and default parameters
- [x] Variables and assignment operators
- [x] While loops
- [x] String interpolation
- [x] For loops
- [x] Objects
- [x] Structs
- [x] Impl and static impl blocks
- [x] Traits with runtime contract validation
- [x] Enums and shorthand `when` matching
- [x] Source-aware runtime errors
- [x] Multiple linked files
- [x] Async functions and `await` on Java 21 virtual threads

### IR

The canonical high-level IR preserves the complete linked program, so its VM has feature parity with the direct VM:

- [x] Math
- [x] Functions and variables
- [x] While and for loops
- [x] Objects and structs
- [x] Impl blocks and traits
- [x] Enums
- [x] Source-aware runtime errors
- [x] Multiple linked files
- [x] Async functions and `await`

Legacy stack-based Crescent IR remains accepted by `CrescentIRParser` and `CrescentIRVM` for compatibility.

## Translator APIs

- `KotlinToCrescentTranslator.translate(source)` translates the Kotlin subset shared with Crescent: functions, variables, primitive and array types, returns, ranges, loops, objects, and data-class records. Kotlin `suspend fun` becomes Crescent `async fun`.
- `PoderTechIrTranslator.translate(files)` exports Java-21-safe PoderTechIR modules without depending on PoderTechIR's obsolete incubator-FFM runtime. The original `CrescentToPTIR` and `PoderTranslator` entry-point names remain available.

See the [language guide](docs/language-reference.md) for syntax and examples.
