# Bundled Crescent sources

`examples/hello_world.moo` is the active bundled smoke-test program used by the
documented `run`, `ir`, and `ptir` commands.

`stdlib/modules.list` is the complete packaged-source manifest, not a claim of
a broad standard library. It assigns explicit package IDs to the two active,
valid Crescent modules without relying on filesystem or JAR directory
enumeration:

- `crescent.std.core` provides `identity`.
- `crescent.std.math` provides the Crescent-authored `clamp` implementation.

The project loader always links these modules, but programs import their public
declarations explicitly, for example `import crescent.std.math::clamp`. They do
not declare `main` and cannot affect user entry-point selection.

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
