# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Thin platform wrappers around the upstream ScummVM codebase: a SwiftUI wrapper packaged as a Swift Package (Apple platforms), and a Jetpack Compose wrapper packaged as an AAR (Android, under `android/`). Both reuse as much upstream C/C++ as possible via the same git submodule; keep wrapper changes minimal and localized to glue code.

## Build modes

`Package.swift` reads `SCUMMVM_BUILD_FROM_SOURCE` at manifest-load time and switches the whole engine graph:

- **Binary mode (default)** — `ScummVMiOS`/`ScummVMtvOS`/`ScummVMmacOS` resolve to prebuilt dynamic-framework XCFrameworks downloaded from `engineBinaryBaseURL`. The frameworks absorb the third-party static libraries, link the system frameworks themselves and carry the runtime payload in their own `Resources`, so the consumer graph is one artifact per platform and no submodule. This is what every downstream repo gets. iOS and tvOS ship as two separate XCFrameworks (rather than one bundling both) so a single-platform consumer isn't forced to download the other platform's slices too — SwiftPM has no partial fetch for binary targets.
- **Source mode (`SCUMMVM_BUILD_FROM_SOURCE=1`)** — the `ScummVMEngine`, `ScummVMiOS` and `ScummVMmacOS` source targets exist and ~7,700 upstream translation units are compiled. Required for any change to engine sources, overrides, platform glue or engine build flags.

The two modes are mutually exclusive and deliberately reuse the same target names, so no dependent target's dependency list differs between them. SwiftPM caches the parsed manifest by content, not by environment — run `swift package reset` after flipping the switch.

**Any change that affects compiled engine output is invisible to consumers until a new engine release is published.** Run `.github/workflows/release-engine.yml` and merge the resulting `Package.swift` bump.

## Commands

Initialize the submodule before building in source mode (required once per clone):
```sh
git submodule update --init --recursive
```

Build (macOS host, other platforms need Xcode):
```sh
SCUMMVM_BUILD_FROM_SOURCE=1 swift build
```

Run tests (macOS host only — `ScummVMTests` exercises `ScummVMGamePathResolver`'s pure-Swift path-resolution logic and does not touch the C/C++ engine):
```sh
swift test
```

Build for iOS/tvOS/macOS in Xcode: open `Package.swift` directly, or add it as a Swift Package dependency to a host app, then build the desired platform target. First build/resolve requires internet access to download XCFramework binary zips from the pinned GitHub Release referenced in `Package.swift`.

`ScummVMApp` (`Sources/ScummVMApp/`) is a minimal macOS executable target useful for manually exercising the `ScummVM` SwiftUI view during development — build/run it directly for a quick end-to-end check on macOS.

Build the Android AAR (needs JDK 17+, the Android SDK, and NDK 23.2.8568313 exactly — see `android/README.md`):
```sh
cd android && ./gradlew :scummvm:assembleRelease
```

CI (`.github/workflows/ci.yml`) runs two Apple tiers on GitHub-hosted `macos-15` runners: a *binary build* on macOS/iOS/tvOS that exercises exactly what consumers get (plus a symbol and resource smoke test on macOS), and a *source build* that only runs when engine-facing paths changed — `Package.swift`, `Sources/ScummVMEngineOverrides/`, `Sources/ScummVMiOS/`, `Sources/ScummVMmacOS/`, `Scripts/`, the submodule pointer — or on pushes to `main`. The Android AAR is built on `ubuntu-latest` and always runs, since Android has no prebuilt-binary mode to fall back on.

Publish a new prebuilt engine (`.github/workflows/release-engine.yml`):
```sh
gh workflow run release-engine.yml -f tag=engine-0.2.0
```

## Non-negotiable rules

- **Never modify anything under `Sources/ScummVMEngine/`.** It is a git submodule of upstream `scummvm/scummvm` and must remain untouched — no edits, deletions, or reformatting.
- All changes must live in wrapper/glue code, `Package.swift`, or `android/`.
- If a build issue requires changing upstream source, add a replacement translation unit under `Sources/ScummVMEngineOverrides/` at the mirrored path and exclude the original upstream file in `Package.swift`'s `exclude` list. This is the *only* sanctioned way to alter engine behavior.
- Overrides must be minimal diffs from the upstream original (to keep future submodule resyncs tractable) — change only what's necessary, don't rewrite.
- Keep the public API surface small and stable — Swift: `ScummVM`, `ScummVMView`, `ScummVMEngine`; Android: `ScummVM`, `ScummVMView`, `ScummVMEngine`, `ScummVMConfiguration`, `ScummVMState`, `ScummVMTouchMode`. Don't add new public API without an explicit request.
- The Android build must keep consuming upstream Java verbatim through `stageUpstreamJava`; never hand-edit a copy of an upstream `.java` file into `android/scummvm/src/`.

## Allowed edit surface

- `Sources/ScummVM/` — SwiftUI wrapper (Swift)
- `Sources/ScummVMiOS/`, `Sources/ScummVMmacOS/`, `Sources/ScummVMtvOS/` — platform glue (ObjC++/Swift)
- `Sources/ScummVMEngineOverrides/` — replacement translation units for upstream build fixes
- `Scripts/` — engine XCFramework build/packaging scripts
- `Package.swift` — build configuration
- `android/` — Jetpack Compose wrapper + Gradle build producing an AAR
- `README.md`, `android/README.md` — documentation

## Architecture

### Package targets (`Package.swift`)
- **`ScummVM`** (Swift, SwiftUI library) — the public API: `ScummVM` view, `ScummVMView`, `ScummVMViewModel`, `ScummVMGamePathResolver`. Depends conditionally on `ScummVMmacOS`/`ScummVMiOS`/`ScummVMtvOS` and on ZIPFoundation.
- **`ScummVMEngine`** (C/C++ target, source mode only) — builds the upstream submodule sources plus overrides. This target's `exclude` list in `Package.swift` is long and load-bearing: it drops non-runtime upstream directories (devtools, dists, test, doc, po, icons), disables platforms/backends this package doesn't target (Android, PSP, 3DS, Wii, DS, Atari, Dreamcast, win32, etc.), disables engines not enabled via `ENABLE_*` defines, excludes x86 SIMD and ARM32 assembly files incompatible with the target architectures, and swaps in override files by excluding the matching upstream original. Also declares `cxxSettings` defines (`ENABLE_*`, `USE_*`) that control which ScummVM engines and features are compiled in.
- **`ScummVMiOS`** / **`ScummVMmacOS`** — platform glue exposing a public `ScummVMEngine.h` Objective-C API (`include/ScummVMEngine.h`) that Swift calls into. ObjC++ targets in source mode; prebuilt dynamic-framework XCFrameworks in binary mode. The framework bundle name *is* the Swift module name, which is why there are separate XCFrameworks per platform rather than one: `ScummVMiOS.xcframework` (ios-arm64, ios-arm64-simulator), `ScummVMtvOS.xcframework` (tvos-arm64, tvos-arm64-simulator) and `ScummVMmacOS.xcframework` (macos-arm64). `Scripts/build-engine-slice.sh` builds the tvOS slices from the `ScummVMiOS` Xcode scheme/source but stamps the output as a distinct `ScummVMtvOS` product/module — splitting iOS from tvOS this way means a single-platform consumer isn't stuck downloading the other platform's slices, since SwiftPM always fetches a binary target's whole zip.
- **`ScummVMtvOS`** — in source mode, a thin Swift `@_exported import ScummVMiOS` re-export target (not a copy of the iOS glue; has no ObjC++ of its own) built from the same `Sources/ScummVMiOS` sources SwiftPM compiles for the tvOS platform slice. In binary mode it's its own prebuilt XCFramework (see above), with no Swift wrapper needed — same as `ScummVMiOS`/`ScummVMmacOS`.
- **Runtime payload** — never checked in; it comes from the submodule in both modes (SwiftPM resource rules in source mode, copied into the framework by `Scripts/build-engine-slice.sh` in binary mode). The layout is load-bearing: the backend recursively scans a bundle for `engine_data_core.mk` and `scummmodern.zip` and prefers the theme zip next to engine-data, so `engine-data/` stays the only subdirectory. On iOS/tvOS `mainBundle.resourcePath` is the `.app` root, so the scan reaches the embedded framework by itself; on macOS it is `Contents/Resources` and the framework sits outside it, which is why `Sources/ScummVMmacOS/ScummVMAppContext.mm` searches the framework bundle explicitly.
- **`ScummVMApp`** (executable, macOS only) — minimal dev/test host app embedding `ScummVM`.
- Third-party binary XCFramework targets (`a52`, `bz2`, `curl`, `faad`, `ffi`, `FLAC`, `fluidsynth`, `freetype`, `fribidi`, `gif`, `glib-2.0`, `intl`, `jpeg`, `mad`, `mikmod`, `mpeg2`, `ogg`, `png`, `SDL2_net`, `SDL2`, `theoradec`, `vorbis`, `vorbisfile`, `vpx`) are downloaded from a pinned GitHub Release URL at the top of `Package.swift` (`binaryBaseURL`) — bump that version string plus checksums when a new release of the prebuilt dependencies ships. They exist in source mode only — in binary mode the release pipeline has already linked them into the engine frameworks, so the consumer never sees them. The system-framework list in `ScummVMEngine`'s `linkerSettings` is mirrored in `Scripts/build-engine-slice.sh`; the two must stay in sync.

### Runtime layers (thin -> thick)
1. **SwiftUI** (`Sources/ScummVM/ScummVM.swift`, `ScummVMView.swift`) — `ScummVM` calls `start()` on appear / `stop()` on disappear via `ScummVMViewModel`. `ScummVMView` hosts a `UIViewController` on iOS/tvOS; on macOS it's an empty host view because SDL owns its own window.
2. **View model / state machine** (`ScummVMViewModel.swift`) — states `idle`, `resolvingPath`, `startRequested`, `stopRequested`; uses a monotonically increasing `startRequestToken` to discard stale async path-resolution results when `start()`/`stop()`/game-path changes race each other. On iOS it additionally tracks host-view attach count and `ScenePhase` to decide start/stop policy (`applyHostLifecyclePolicy`).
3. **Game path resolution** (`ScummVMGamePathResolver.swift`, a Swift `actor`) — resolves a `URL?` into a concrete on-disk game directory before the engine starts, asynchronously and cancellation-aware. Handles `.zip`/`.scummvm` archive extraction via ZIPFoundation into a per-platform cache dir (keyed by FNV1a-64 hash of the archive path, skipped if already extracted), descends into a single top-level subdirectory if present, and (iOS/tvOS only) copies external directories into the app sandbox under `Documents/ScummVM/ImportedDirectories/`.
4. **ObjC++ engine facade** (`ScummVMiOS`/`ScummVMmacOS` `ScummVMEngine.mm` + public `include/ScummVMEngine.h`) — `ScummVMEngineSharedInstance()` singleton exposes only `start`/`stop` (+ `ui()` on iOS/tvOS). Keep this surface minimal.
5. **Platform app context** (`ScummVMAppContext.mm`) — iOS/tvOS builds UIKit/backend state on the main thread then runs the engine loop (`iOS7_init`) on a background queue; macOS sets up SDL/`OSystem_MacOSX` and runs `scummvm_main` on the main queue (moving this off the main thread is a known planned change — don't add main-thread assumptions that would block it).
6. **Engine + resources** — the `ScummVMEngine` target and its `Package.swift` configuration described above.

### Threading rule of thumb
Keep thread-crossing explicit and minimal. iOS/tvOS: UI setup on main thread, engine loop on background queue. macOS: everything currently on the main queue.

### Override workflow
When an upstream file in the submodule won't build for this package's target platforms:
1. Add an exclusion for the upstream path in `ScummVMEngine`'s `exclude` list in `Package.swift`.
2. Add a replacement translation unit at the mirrored path under `Sources/ScummVMEngineOverrides/` (see existing examples: `common/recorderfile.cpp`, `gui/onscreendialog.cpp`, `gui/recorderdialog.cpp`, `base/base_main_override.cpp`, `engines/plugins_table.h`, `engines/detection_table.h`).
3. Keep the override as close to the original as possible — a targeted diff, not a rewrite — so future submodule syncs stay tractable.
4. Verify in source mode (`SCUMMVM_BUILD_FROM_SOURCE=1`), then publish an engine release — the override does nothing for consumers until then.

### Android build (`android/`)
A separate Gradle build producing an AAR; see `android/README.md` for the full picture.
- **`:scummvm`** (`com.android.library`) — the only module. Public Compose API in `org.scummvm.compose`; glue that needs upstream package-private access lives in `org.scummvm.scummvm`.
- **Native**: `configureScummVM<Abi>` + `buildScummVM<Abi>` run upstream's own `./configure --host=android-<abi>` and `make libscummvm.so` out-of-tree, mirroring `backends/platform/android/fatbundle.mk`. There is deliberately no CMake/ndk-build reimplementation of the engine build. `-Pscummvm.prebuiltLibsDir=<dir>` skips it.
- **Upstream Java**: `stageUpstreamJava` copies a hand-picked, `R`- and `ScummVMActivity`-free subset of `backends/platform/android/org/scummvm/scummvm/` verbatim (`ScummVM.java`, `CompatHelpers`, `SAFFSTree`, `ExternalStorage`, `INIParser`, `Version`, `net/`). Re-verify that list after every submodule sync.
- **Assets**: `stageScummVMAssets` mirrors `DIST_FILES_*` from `Makefile.common` into `assets/assets/` plus the `MD5SUMS` manifest `ScummVMAssets` uses on-device.
- Generated java/assets/jniLibs are wired through the AGP variant API (`addGeneratedSourceDirectory`), not `preBuild` hacks.
- Toolchain pins track upstream's `dists/android/build.gradle`: AGP 9.2.1, Gradle 9.6.1, and **NDK 23.2.8568313 exactly** (upstream's `configure` aborts on a mismatch; the build reads the required revision from that file).
- Known limitation: the engine is a process-wide singleton, so `ScummVMEngine` allows exactly one run per process.

### Submodule sync workflow
When updating `Sources/ScummVMEngine` to a newer upstream commit: record the current SHA first (`git -C Sources/ScummVMEngine rev-parse HEAD`) as a rollback point, run `git submodule update --remote Sources/ScummVMEngine`, then diff file additions/removals against the old SHA to catch new files that could clash with the `ScummVMEngine` target and stale `Package.swift` exclusions that no longer point at real files. Re-check every file under `Sources/ScummVMEngineOverrides/` against the corresponding upstream diff. Check whether the runtime payload's file list changed — the same set is spelled out twice, in the `resources:` rules in `Package.swift` and in `Scripts/build-engine-slice.sh`, and both have to agree. On the Android side, re-check `upstreamJavaSources` in `android/scummvm/build.gradle.kts` (files moved or gained an `R`/`ScummVMActivity` dependency?), the `stageScummVMAssets` file lists against `Makefile.common`'s `DIST_FILES_*`, and whether `dists/android/build.gradle`'s `ndkVersion` changed. Then publish a new engine release.

## Repository skills

`.agents/skills/` contains detailed workflow skills (mirrored as prompts in `.github/prompts/`) — open the matching one before working in that area:

| Task | Skill |
|---|---|
| Build failure (compile/link/header/macro) | `scummvm-build-triage` |
| Adding/updating an override in `Sources/ScummVMEngineOverrides/` | `scummvm-override-workflow` |
| Editing lifecycle/bridge code in `Sources/ScummVM/`, `ScummVMiOS/`, `ScummVMmacOS/`, `ScummVMtvOS/` | `objcxx-bridge-lifecycle` |
| Editing `plugins_table.h` or `detection_table.h` | `plugins-table-maintainer` |
| Reviewing/planning architecture-level changes | `scummvm-engine-architecture` |
| Linker errors / XCFramework slice mismatches | `xcframework-linkage-check` |
| Updating the `ScummVMEngine` submodule to a newer upstream commit | `scummvm-submodule-sync` |
| Reviewing `Package.swift` target/exclusion/dependency consistency | `package-swift-auditor` |
| Adding a new prebuilt framework dependency | see `.github/prompts/add-framework-dependency.prompt.md` |

## Platform requirements

- Apple platforms: iOS 17+, tvOS 17+, macOS 15+ (per `Package.swift`; treat it as authoritative if other docs drift).
- Android: minSdk 21, compileSdk 36, JDK 17 bytecode (per `android/scummvm/build.gradle.kts`; authoritative in the same way).
- Swift tools version: 6.0.
- ScummVM sources are compiled with `-UDEBUG` unconditionally in `cxxSettings`/`cSettings` because SwiftPM debug builds otherwise inject `DEBUG=1`, enabling upstream debug-only code paths that reference symbols not present in this build configuration.

## Conventions

- Default to ASCII; keep comments minimal and focused on non-obvious rationale.
- For code reviews, list findings first, ordered by severity, with file links.
- Keep `README.md` in sync with setup steps, limitations, and known issues.
