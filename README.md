# swift-scummvm

SwiftUI wrapper around the upstream ScummVM codebase, packaged as a Swift Package with minimal ObjC++ glue, plus a Jetpack Compose wrapper packaged as an Android AAR (see [`android/`](android/README.md)). The core goal is to reuse as much upstream C/C++ as possible while keeping platform-specific wrapper code small and focused.

Upstream ScummVM repository: [scummvm/scummvm](https://github.com/scummvm/scummvm).

## Goals
- Reuse upstream ScummVM code directly via the `Sources/ScummVMEngine/` git submodule.
- Keep Swift and ObjC++ wrappers thin and localized to `Sources/`.
- Avoid long-lived forks or large downstream patches in the submodule.
- Ship as a Swift Package that can be embedded in iOS, tvOS, and macOS apps, and as an AAR that can be embedded in Android apps.

## Build modes

The package builds in one of two modes, selected by the `SCUMMVM_BUILD_FROM_SOURCE` environment variable at manifest-load time.

**Binary mode (default).** The engine and platform glue are consumed as prebuilt dynamic-framework XCFrameworks from a GitHub Release. Consumers compile four Swift files instead of ~7,700 C/C++ translation units and do not need the ~534 MB upstream submodule at all. The frameworks link the third-party static libraries and system frameworks into themselves and carry the runtime payload in their own `Resources` directory, so a binary-mode graph resolves one artifact per platform instead of twenty-six. iOS and tvOS publish as separate XCFrameworks (`ScummVMiOS`, `ScummVMtvOS`) rather than one bundling both, so a single-platform consumer only downloads that platform's slices.

**Source mode (`SCUMMVM_BUILD_FROM_SOURCE=1`).** The engine is compiled from `Sources/ScummVMEngine/`. Required when changing engine code, overrides, platform glue or the engine build flags — and used by the release pipeline itself.

```sh
SCUMMVM_BUILD_FROM_SOURCE=1 swift build
```

SwiftPM caches the parsed manifest by content, not by environment, so run `swift package reset` when switching modes.

## Architecture
- [`ScummVM`](Sources/ScummVM/) (SwiftUI target) exposes the public Swift UI.
- [`ScummVMEngine`](Sources/ScummVMEngine/) (C/C++ target, source mode only) wraps the upstream submodule and build flags.
- [`ScummVMiOS`](Sources/ScummVMiOS/) and [`ScummVMmacOS`](Sources/ScummVMmacOS/) provide platform glue. In source mode these are ObjC++ targets; in binary mode the same names resolve to the prebuilt XCFrameworks, so every dependent target's dependency list is identical either way.
- [`ScummVMtvOS`](Sources/ScummVMtvOS/) — in source mode, a thin Swift target that re-exports `ScummVMiOS` via `@_exported import`. In binary mode it's its own prebuilt XCFramework, built from the same `ScummVMiOS` source but published separately so a tvOS-only (or iOS-only) consumer isn't forced to download the other platform's slices.
- The runtime payload (engine-data, themes, soundfonts, asset catalogs, privacy manifests) always comes from the submodule: in source mode through SwiftPM resource rules, in binary mode because the release pipeline copies it into the framework bundle. Nothing is checked into this repo.
- [`ScummVMApp`](Sources/ScummVMApp/) (macOS executable target) is a minimal macOS app for development and testing that embeds `ScummVM`.
- Binary XCFramework zips are hosted in GitHub Releases and referenced as remote SwiftPM binary targets in `Package.swift`.
- [`Sources/ScummVMEngineOverrides/`](Sources/ScummVMEngineOverrides/) contains replacement translation units used when upstream sources need package-specific build fixes.

## Package targets (from `Package.swift`)
Source and executable targets:
- [`ScummVM`](Sources/ScummVM/)
- [`ScummVMApp`](Sources/ScummVMApp/)
- [`ScummVMEngine`](Sources/ScummVMEngine/) (source mode only; overrides in [`Sources/ScummVMEngineOverrides/`](Sources/ScummVMEngineOverrides/))
- [`ScummVMiOS`](Sources/ScummVMiOS/) (source mode only)
- [`ScummVMmacOS`](Sources/ScummVMmacOS/) (source mode only)
- [`ScummVMtvOS`](Sources/ScummVMtvOS/)

Third-party binary targets (source mode only - in binary mode they are already inside the engine frameworks; downloaded from the pinned GitHub Release configured in `Package.swift`, currently `0.2.0`):
- `a52`, `bz2`, `curl`, `faad`, `ffi`, `FLAC`, `fluidsynth`, `freetype`, `fribidi`, `gif`, `glib-2.0`, `intl`, `jpeg`, `mad`, `mikmod`, `mpeg2`, `ogg`, `png`, `SDL2_net`, `SDL2`, `theoradec`, `vorbis`, `vorbisfile`, `vpx`

Engine binary targets (binary mode only, from the `engineBinaryBaseURL` release):
- `ScummVMiOS.xcframework` — slices `ios-arm64`, `ios-arm64-simulator`
- `ScummVMtvOS.xcframework` — slices `tvos-arm64`, `tvos-arm64-simulator`
- `ScummVMmacOS.xcframework` — slice `macos-arm64`

The package supports Apple Silicon only. Every engine slice is arm64; Intel macOS and `x86_64` simulators are unsupported.

Key entry points:
- `ScummVM` SwiftUI view manages start/stop lifecycle and initializes the shared engine instance.
- `ScummVMView` bridges the engine UI into SwiftUI (iOS/tvOS uses a `UIViewController`, macOS provides an empty host view while SDL owns its window).
- `ScummVMEngine` ObjC API is the minimal bridge used by Swift.

## Android

The Android wrapper lives in [`android/`](android/README.md) and is a separate Gradle build:

```sh
cd android && ./gradlew :scummvm:assembleRelease
```

It produces an AAR containing `libscummvm.so` (built from the same submodule via upstream's own `configure`/`make`), the JNI-facing upstream Java classes, ScummVM's runtime data as assets, and a Compose `ScummVM` composable. It needs JDK 17+, the Android SDK, and **NDK 23.2.8568313 exactly** — upstream's `configure` refuses any other revision. Read [`android/README.md`](android/README.md) for the build properties and the known limitations before embedding it.

## Requirements
- Apple platforms: iOS 17+, tvOS 17+, macOS 15+.
- Android: minSdk 21, compileSdk 36.
- Swift tools version: 6.0 (see `Package.swift`).
- Apple Silicon host and target. Intel macOS and `x86_64` simulators are unsupported.
- The `ScummVMEngine/` submodule is required only in source mode.
- Internet access is required on first package resolve/build so SwiftPM can download the XCFramework zips from the pinned GitHub Releases (they are cached locally after download).
- [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) 0.9.20+ (declared as a Swift Package dependency in `Package.swift`).

## Setup

### Consuming the package
Add it as a Swift Package dependency and build for your platform target. SwiftPM downloads the XCFramework zip assets on first resolve/build. No submodule checkout needed.

### Working on the wrapper
1. Initialize the submodule (source mode only):
   ```sh
   git submodule update --init --recursive
   ```
2. Open the package in Xcode or build from the CLI:
   ```sh
   SCUMMVM_BUILD_FROM_SOURCE=1 swift build
   ```

## Releasing the prebuilt engine
`.github/workflows/release-engine.yml` builds all five slices in parallel, assembles the three XCFrameworks, publishes them as release assets and opens a PR bumping `engineBinaryBaseURL` plus all three checksums in `Package.swift`. Run it after any change to engine sources, overrides, glue or engine build flags — otherwise consumers keep linking the previous engine.

```sh
gh workflow run release-engine.yml -f tag=engine-0.2.0
```

The scripts it drives ([`Scripts/build-engine-slice.sh`](Scripts/build-engine-slice.sh), [`Scripts/make-engine-xcframework.sh`](Scripts/make-engine-xcframework.sh)) also run standalone. Each release carries a `SOURCES.txt` naming the exact upstream and wrapper commits the binaries were built from, which is what the GPL requires when distributing binaries.

The release workflow validates the freshly assembled local XCFrameworks before publishing them. `SCUMMVM_ENGINE_ARTIFACTS_DIR` is reserved for that check and points binary mode at an XCFramework directory relative to the package root.

## Usage

### SwiftUI (recommended)
```swift
import ScummVM

struct ContentView: View {
	var body: some View {
		// No game path — opens the ScummVM launcher UI.
		ScummVM()
	}
}
```

Pass a game path URL to launch directly into a specific game:
```swift
import ScummVM

struct ContentView: View {
	// Points to a game directory or a .zip / .scummvm archive.
	let gameURL: URL

	var body: some View {
		ScummVM(game: gameURL)
	}
}
```

`ScummVM` automatically calls `start()` on appear and `stop()` on disappear. The iOS/tvOS runtime ignores repeated `start()` calls while the engine is already running.

### Lower-level control
If you need manual control, you can use `ScummVMView` and call `ScummVMEngineSharedInstance().start(gamePath:)` / `stop()` yourself.

- iOS/tvOS: call `ScummVMEngineSharedInstance().ui()` to obtain the `UIViewController` backing the engine UI if you need to embed it manually.
- macOS: the SDL backend creates and manages its own window. The macOS `ScummVMEngine` API does not expose a `ui()` method; use the `ScummVM` SwiftUI view as the host.

## Runtime data and savegames
- iOS/tvOS savegames are created in the app's Documents directory under `Savegames/` at engine startup.
- macOS savegames are created in the Documents directory under `Savegames/` during engine setup.
- Wrapper-managed stop requests perform a best-effort autosave before engine shutdown.
- When a non-nil game path is used, startup passes `--save-slot` (native autosave slot) so ScummVM restores autosave when available.
- On macOS, explicit game-path launches first try to resolve an existing configured target and launch it directly with native `save_slot` restore; if no target matches, startup falls back to `--path` + `--auto-detect`. When game path is nil, launcher-only behavior is preserved and wrapper-owned save-slot hints are cleared.
- Theme and engine-data paths are resolved by scanning the app bundle; iOS/tvOS uses `appbundle:/` virtual paths and prefers `scummremastered.zip` when present, while macOS adds `--themepath`, `--iconspath`, and `--extrapath` with absolute bundle paths when needed.
- ScummVM configuration and game data files follow upstream behavior and are not customized here.

## Game path resolution and archive extraction
`ScummVMGamePathResolver` (an `actor` in `Sources/ScummVM/`) resolves the `game` URL into a concrete directory before the engine starts. Resolution is asynchronous and cancellation-aware.

### Archive extraction (all platforms)
- Supported archive extensions: `.zip`, `.scummvm`.
- Archives are extracted via ZIPFoundation to a platform-specific cache directory:
  - iOS/tvOS: `Documents/ScummVM/ImportedArchives/<name>-<hash>/`
  - macOS: `Application Support/ScummVM/ImportedArchives/<name>-<hash>/`
- The extraction is cached by a FNV1a-64 hash of the archive's full path. Re-extraction is skipped if the cached directory already exists and is non-empty.
- If the archive contains a single top-level subdirectory, the resolver descends into it automatically to locate the actual game root.

### Directory import (iOS/tvOS only)
- Game directories already within `Documents/` or the app bundle are used in place without copying.
- Directories outside those locations (e.g. received via the Files app or document picker) are copied to `Documents/ScummVM/ImportedDirectories/<name>-<hash>/`.
- macOS does not copy directories; the path is passed directly to the engine.

## Build notes and troubleshooting
- The engine target links against remote XCFramework binary targets from the pinned GitHub Release in `Package.swift`. If you see missing symbols, verify the uploaded XCFrameworks include the platform slice you are building for and that the checksums in `Package.swift` match the uploaded zip assets.
- iOS/tvOS uses the upstream iOS7 backend; macOS uses the SDL backend.
- In source mode, `ScummVMtvOS` is a thin Swift re-export of `ScummVMiOS` (`@_exported import ScummVMiOS`) with no separate ObjC++ glue of its own, and it packages the tvOS assets directly. In binary mode it's its own `ScummVMtvOS.xcframework`, built from the same `ScummVMiOS` source/scheme but published as a distinct product/module so iOS and tvOS consumers don't share a download.
- The payload layout is load-bearing: the backend locates it by recursively scanning a bundle for `engine_data_core.mk` and `scummmodern.zip`, preferring the theme zip next to the engine-data directory. Keep `engine-data/` as the only subdirectory and everything else flat beside it, in both the `Package.swift` resource rules and `Scripts/build-engine-slice.sh`.
- On iOS/tvOS `mainBundle.resourcePath` is the `.app` root, so the recursive scan reaches the embedded framework on its own. On macOS it is `Contents/Resources` and the framework sits outside it, which is why `Sources/ScummVMmacOS/ScummVMAppContext.mm` searches the framework bundle explicitly.
- Changing engine code without publishing a new engine release is a silent no-op for consumers — they keep linking the last released binary. Run the release workflow.
- `ScummVMEngine` sources are taken from the upstream submodule (`ScummVMEngine/`).
- Override-only files live in `Sources/ScummVMEngineOverrides/` (plus platform glue in `Sources/ScummVMiOS/` and `Sources/ScummVMmacOS/`).
- If a submodule source file causes an SPM-only issue, exclude it in `Package.swift` and add a replacement translation unit under `Sources/ScummVMEngineOverrides/`.

## Quick start (read before making changes)
- Never modify anything under `Sources/ScummVMEngine/`. It is a git submodule of upstream ScummVM and must remain untouched.
- Allowed edit surface: `Sources/ScummVM/`, `Sources/ScummVMiOS/`, `Sources/ScummVMmacOS/`, `Sources/ScummVMtvOS/`, `Sources/ScummVMEngineOverrides/`, `Scripts/`, `Package.swift`, `README.md`.
- If a build issue needs source changes, add a replacement file in `Sources/ScummVMEngineOverrides/` and exclude the upstream file in `Package.swift`.
- Keep public API small and stable: `ScummVM`, `ScummVMView`, `ScummVMEngine`.
- Capture exact build errors before proposing fixes; prefer minimal wrapper or build-flag changes.

## Status and next steps
This wrapper is under active development. Current state:
- `ScummVMViewModel` uses a simplified lifecycle policy: resolve path on demand, start when requested, and stop on disappear/background.
- Game path resolution and archive extraction run asynchronously using Swift structured concurrency before engine startup. A request token ensures only the latest start request is applied.
- iOS/tvOS creates UIKit/backend state on the main thread, then runs the engine loop (`iOS7_init`) on a background queue.
- macOS sets up SDL/OSystem and runs `scummvm_main` on the main queue.
- Moving macOS engine execution to a background thread is a known next step; do not add main-thread assumptions that would block that migration.
- Cross-platform lifecycle/threading behavior is still evolving and not yet unified.
- Consider a macOS-specific SwiftUI host that can focus or resize the SDL window alongside the empty placeholder view.
- `ScummVMApp` is macOS-only; a tvOS/iOS equivalent app target may be useful for standalone testing.

## License
This wrapper follows the upstream ScummVM licensing. See the license files inside the `Sources/ScummVMEngine/` submodule for details.
