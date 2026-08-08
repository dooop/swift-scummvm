# Repository AGENTS.md

## Project goal
- Provide a thin SwiftUI wrapper around the upstream ScummVM codebase.
- Reuse as much upstream C/C++ as possible via the git submodule.
- Keep wrapper changes minimal and localized to the Swift/ObjC++ glue.

## Structure map
- `Package.swift` defines Swift Package targets, exclusions, and build flags.
- `Sources/ScummVMEngine/` is the upstream ScummVM git submodule (do not edit).
- `Sources/ScummVM/` contains SwiftUI wrappers (`ScummVM`, `ScummVMView`, `ScummVMViewModel`, `ScummVMGamePathResolver`).
- `Sources/ScummVMEngine/` contains the engine target glue and overrides.
- `Sources/ScummVMEngineOverrides/` contains replacement translation units for build fixes.
- `Sources/ScummVMiOS/` and `Sources/ScummVMmacOS/` contain ObjC++ platform glue.
- `Sources/ScummVMiOS/include/ScummVMEngine.h` and `Sources/ScummVMmacOS/include/ScummVMEngine.h` are the public ObjC APIs.
- `Sources/ScummVMtvOS/` is a distinct tvOS glue target with its own requirements (not a copy of iOS).
- The runtime payload (engine-data, themes, soundfonts, platform assets) always comes from the submodule - via SwiftPM resource rules in source mode, baked into the framework by the release pipeline in binary mode.
- `Scripts/build-engine-slice.sh` and `Scripts/make-engine-xcframework.sh` produce the prebuilt engine XCFrameworks.

## Build modes
- Default is **binary mode**: `ScummVMiOS`/`ScummVMmacOS` resolve to prebuilt XCFrameworks and the submodule is not needed.
- Set `SCUMMVM_BUILD_FROM_SOURCE=1` to compile the engine from the submodule. Required for engine, override, glue or build-flag changes. Run `swift package reset` when switching.
- Engine-affecting changes reach consumers only after `.github/workflows/release-engine.yml` publishes a new release and `Package.swift` is bumped.
- [ZIPFoundation](https://github.com/weichsel/ZIPFoundation) 0.9.20+ is a Swift Package dependency used by `ScummVMGamePathResolver` for archive extraction.

## Skills
- `scummvm-build-triage`: Diagnose build failures and choose the minimal fix surface. (`.agents/skills/scummvm-build-triage/SKILL.md`)
- `scummvm-override-workflow`: Add minimal override translation units with synchronized `Package.swift` exclusions. (`.agents/skills/scummvm-override-workflow/SKILL.md`)
- `objcxx-bridge-lifecycle`: Maintain SwiftUI/ObjC++ start-stop lifecycle and bridge threading rules. (`.agents/skills/objcxx-bridge-lifecycle/SKILL.md`)
- `plugins-table-maintainer`: Maintain plugin/detection override tables safely. (`.agents/skills/plugins-table-maintainer/SKILL.md`)
- `xcframework-linkage-check`: Diagnose linker failures and XCFramework slice/dependency mismatches. (`.agents/skills/xcframework-linkage-check/SKILL.md`)
- `scummvm-engine-architecture`: Map wrapper-to-engine architecture and change impact before patching. (`.agents/skills/scummvm-engine-architecture/SKILL.md`)
- `scummvm-submodule-sync`: Update Sources/ScummVMEngine, then reconcile override and exclusion drift safely. (`.agents/skills/scummvm-submodule-sync/SKILL.md`)
- `package-swift-auditor`: Audit Package.swift target membership, exclusions, binary targets, and platform conditions. (`.agents/skills/package-swift-auditor/SKILL.md`)

### Skill trigger rule
- If the user explicitly names one of the skills or the task clearly matches a skill description, open and apply that skill for the turn.

## Non-negotiable rules (read first)
- Never modify anything under `Sources/ScummVMEngine/`. It is a git submodule of upstream ScummVM.
- Never delete, reformat, or "fix" upstream sources. Keep upstream code intact.
- All changes must be in wrapper/glue code or in `Package.swift`.
- If a build issue requires source changes, add a replacement file in `Sources/ScummVMEngineOverrides/` and exclude the upstream file in `Package.swift`.

## Allowed edit surface
- SwiftUI wrapper code: `Sources/ScummVM/`
- ObjC++ glue: `Sources/ScummVMiOS/`, `Sources/ScummVMmacOS/`, `Sources/ScummVMtvOS/`
- Override translation units: `Sources/ScummVMEngineOverrides/`
- Build configuration: `Package.swift`
- Android Compose wrapper and its Gradle build: `android/`
- Documentation: `README.md`, `android/README.md`
- Repository skills: `.agents/skills/`

## When build issues occur
- Capture the exact error text.
- First try fixes in wrappers or `Package.swift` (missing headers, flags, exclusions).
- Only if unavoidable: add a replacement file under `Sources/ScummVMEngineOverrides/` and exclude the upstream file in `Package.swift`.
- Overrides must be minimal diffs from the upstream original to make future resyncs tractable. Do not rewrite; change only what is necessary.

## Public API stability
- Keep public API small and stable (`ScummVM`, `ScummVMView`, `ScummVMEngine`).
- Do not add new public surface area without explicit user request.

## Build and platform expectations
- Supported platforms: iOS 17+, tvOS 17+, macOS 15+.
- Supported architecture: Apple Silicon/arm64 only; Intel macOS and x86_64 simulators are unsupported.
- Swift tools version: 6.0.
- tvOS glue (`Sources/ScummVMtvOS/`) has distinct requirements from iOS and must be documented separately as it evolves.

## Threading and lifecycle (current state)
- The `start`/`stop` SwiftUI lifecycle is fully implemented via a state machine in `ScummVMViewModel` (`idle`, `resolvingPath`, `startRequested`, `stopRequested`).
- Game path resolution runs asynchronously via `ScummVMGamePathResolver` (a Swift `actor`) before the engine starts. Start tokens prevent races on path changes.
- `ScummVM(gamePath: URL?)` and `ScummVM(gamePath: Binding<URL?>)` are the public API. Nil means open the launcher UI; a non-nil URL is resolved and passed to the engine.
- Archives (`.zip`, `.scummvm`) are extracted by ZIPFoundation to a platform-specific cache directory before launch. Directories on iOS/tvOS are copied into the sandbox if not already accessible.
- iOS/tvOS creates UIKit/backend state on the main thread, then runs the engine loop on a background queue.
- macOS sets up SDL/OSystem and runs `scummvm_main` on the main queue. Moving macOS execution to a background thread is a planned next step; do not add main-thread assumptions that would block that migration.
- Keep thread-crossing explicit and minimal.

## Output expectations
- For reviews, list findings first, ordered by severity, with file links.
- Keep README in sync with setup steps, limitations, and known issues.
- Default to ASCII and keep comments minimal and focused.
