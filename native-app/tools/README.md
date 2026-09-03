# GAMA Android build-time tools

This directory holds the small Java programs that adapt the **GAMA platform jars**
(the desktop/`java.awt`-based engine, extension, and UI libraries) so they can run on
Android. Because GAMA is a desktop framework, pieces of its code cannot run on ART/D8
as-is; these tools rewrite the binaries at build time before the app is assembled.

## Layout

```
tools/
├── AndroidTaskWrapper.java        runtime source compiled into the gama.api bundle
├── patchers/                      every active ASM bytecode patcher, grouped by the
│   │                              GAMA subsystem each one repairs (edit these)
│   ├── parallel/       parallel agent execution (`ask`/`parallel`)
│   ├── stepping/       how simulations progress / the experiment clock
│   ├── layers/         display layers: 3D, image, mesh, grid, layer manager
│   ├── charts/         chart output & chart text/colour rendering
│   ├── projection/     geographic projection / CRS (GeoTools)
│   ├── gridfile/       ASCII grid (`.asc`) file import
│   ├── gambuild/       GAML model compilation & validation
│   ├── runtime/        built-in runtime globals (e.g. `world`/`simulation`)
│   ├── workspace/      Eclipse/workspace stubs
│   └── javacompat/     Java-version bridges (modern javac → Android ART)
└── archive/                       retired / superseded patchers kept for reference only
    └── *(not compiled, not run by the build)*
```

## What each group repairs

| Group | GAMA concern | Patchers |
|---|---|---|
| `parallel/` | parallel agent execution | `ParallelRunnerPatcher` |
| `stepping/` | simulation step / experiment clock | `SimulationRunnerPatcher` |
| `layers/` | display layers (3D, image, mesh, grid) | `Display3D`, `ImageLayer`, `MeshLayer`, `GridColor`, `LayerManager` |
| `charts/` | chart rendering & text/colour | `ChartOutput`, `ColorBrewer`, `Colors`, `AwtFontMetrics`, `FontRenderContext` |
| `projection/` | geographic projection / CRS | `Crs`, `Projection`, `MapProjection`, `Spi` |
| `gridfile/` | ASCII grid (`.asc`) import | `GridFileFallback`, `AscEnvelope` |
| `gambuild/` | GAML model compilation/validation | `GamlModelBuilder`, `VarValidatorNullGuard` |
| `runtime/` | runtime globals (`world`/`simulation`) | `WorldGlobal` |
| `workspace/` | Eclipse/workspace stubs | `EclipseCore` |
| `javacompat/` | Java-version bridges (switches, Guava, cached, XML) | `TypeSwitch`, `EnumSwitch`, `GuavaJreCompat`, `CacheBuilder`, `StaxNewFactory` |

## How it works

1. `app/libs/pristine/` holds the **untouched** engine jars from the
   `native-app-deps` release bundle.
2. The Gradle task `app:patchGamaJars`:
   - restores `app/libs/` from the pristine copies,
   - compiles each `patchers/*Patcher.java` with `javac` (using ASM from the
     Gradle cache) and runs it against the relevant jars,
   - compiles `AndroidTaskWrapper.java` into `gama.api` (it supplies
     `ForkJoinPool` compatibility for parallel agents).
3. The patched jars are then assembled into the debug/release APK.

The list of patchers actually invoked is declared explicitly in
`app/build.gradle` (the `patchers` table inside the `patchGamaJars` task). A
patcher only runs if it is listed there — files in `patchers/` that are not in
that table are not executed.

## Editing a patcher

Each `*Patcher.java` is a **self-contained CLI program**: `public static void
main(String[] args)` receives the jar paths to patch, opens each jar with ASM
(`ClassReader`/`ClassWriter`), rewrites the target classes, and writes the jar
back. To change an engine fix, edit the `.java` source — the build recompiles it
every time. Never rely on, commit, or edit any `*.class` files; the build
regenerates them fresh into `$buildDir/tmp/<group>_<Class>` (e.g.
`$buildDir/tmp/charts_ChartOutputPatcher`) and they are git-ignored.

## Adding or removing a patcher

- **Add**: create a `Patcher.java` in the matching subsystem folder under
  `patchers/` following the existing `main(String[])` signature, then add a
  `['group/MyFixPatcher', 'MyFixPatcher', [jarToPatch, ...]]` row to the
  `patchers` table in `app/build.gradle` (first field = path under
  `tools/patchers/`, second = main class).
- **Remove**: delete the row from `app/build.gradle`; move the `.java` to
  `archive/` if it might be needed again. A patcher not listed in the table is
  never compiled or run.

## Why a dirty `tools/` (or `app/libs`) breaks the build

`app/libs` and any `*.class` files are **git-ignored** — they are build
artifacts, not source. If patchers are run repeatedly against an already-patched
`app/libs`, the jars get double-patched and silently corrupt the resulting APK
(seen historically as major runtime slowdowns). Always restore `app/libs` from
`pristine/` (the build's `patchGamaJars` does this automatically) and keep this
directory clean: **source `.java` only, no compiled artifacts.**