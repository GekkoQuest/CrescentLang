# Bundled Crescent sources

`examples/hello_world.moo` is the active bundled smoke-test program used by the
documented `run`, `ir`, `ptir`, and `ptir-run` commands.

`stdlib/modules.list` is the complete packaged-source manifest, not a claim of
a broad standard library. It assigns explicit package IDs to four active,
valid Crescent modules with 18 concrete exports, without relying on filesystem
or JAR directory enumeration:

- `crescent.std.core`: `identity`, `choose`
- `crescent.std.math`: `minI32`, `maxI32`, `clamp`, `absoluteI32`, `signI32`,
  `isEvenI32`, `isOddI32`
- `crescent.std.collections`: `singletonI32`, `pairI32`, `sameI32`,
  `swapPairI32`, `sumPairI32`
- `crescent.std.text`: `concatText`, `isEmptyText`, `repeatText`,
  `surroundText`

The project loader always links these modules, but programs import their public
declarations explicitly, for example `import crescent.std.math::clamp`. They do
not declare `main` and cannot affect user entry-point selection.
The fixed-width `absoluteI32` contract intentionally leaves `I32.MIN` unchanged
because negating that boundary wraps to itself in both supported runtimes.

Files ending in `.unimplemented` are design sketches. The CLI deliberately
does not discover them as Crescent sources, and the Gradle resource/distribution
pipeline excludes them. This includes historical examples, aspirational
library sketches under `std/`, and legacy language-design material under
`builtin/`.

Host primitives are Kotlin runtime services, not modules loaded from the
legacy `builtin/` sketches. Console primitives are routed through injectable
`RuntimeIO`: stream adapters decode input as UTF-8, write through the supplied
`PrintStream`, use independent input/output locks, and never close the
caller-owned streams. Do not treat quarantined sketches or host primitives as
additional standard-library packages.
