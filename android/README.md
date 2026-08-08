# ScummVM for Android — Jetpack Compose wrapper

A Gradle build that packages the upstream ScummVM engine as an Android library
(`.aar`) with a Jetpack Compose view on top. It is the Android counterpart of the
Swift package in this repository and follows the same rule: the engine comes
from the `Sources/ScummVMEngine` git submodule and is never modified.

```
android/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── app/                         ← Compose test app, produces the APK
│   ├── build.gradle.kts         ← debug/local and release/AAR selection
│   ├── libs/                    ← default location for an uploaded AAR
│   └── src/main/kotlin/de/doop/scummvm/
└── scummvm/                     ← the library module, produces the AAR
    ├── build.gradle.kts         ← native build + asset staging + packaging
    ├── consumer-rules.pro
    └── src/main/
        ├── java/org/scummvm/scummvm/   ← one shim for an upstream declaration
        └── kotlin/
            ├── de/doop/scummvm/        ← the public Compose API
            └── org/scummvm/scummvm/    ← glue that needs upstream package access
```

The library namespace, public Compose API, application ID, and Activity package
are `de.doop.scummvm`. The test app uses `de.doop.scummvm.app` only for its
generated-code namespace because Android Gradle rejects an app and a consumed
AAR with identical namespaces. The JNI compatibility classes must remain in
`org.scummvm.scummvm`: upstream's native backend looks them up by that exact
binary name, including when a prebuilt `libscummvm.so` is used.

## Usage

```kotlin
import de.doop.scummvm.ScummVM
import de.doop.scummvm.ScummVMConfiguration

setContent {
    ScummVM(
        modifier = Modifier.fillMaxSize(),
        configuration = ScummVMConfiguration(
            // null opens ScummVM's own launcher
            target = null,
            gamesDirectory = File(filesDir, "games"),
        ),
        onExit = { exitCode -> finish() },
    )
}
```

Public API surface, intentionally small and mirroring the Swift package's
`ScummVM` / `ScummVMView` / `ScummVMEngine` split:

| Type | Role |
|---|---|
| `ScummVM` | Composable. Starts the engine on appear, stops it on dispose. |
| `ScummVMView` | Composable. Just hosts the surface and forwards input; you drive the lifecycle. |
| `ScummVMEngine` | The engine facade: `state`, `currentGame`, `setPaused`, `setTouchMode`, `stop`. |
| `ScummVMConfiguration` | Start-up options (target, games directory, extra arguments). |
| `ScummVMState` | `Idle` / `PreparingData` / `Running` / `Stopped` / `Failed`. |
| `ScummVMTouchMode` | `Touchpad` / `DirectMouse` / `Gamepad`. |

### What the consuming app must declare

The library manifest is deliberately empty — it merges nothing into your app.
Add what you actually need:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-feature android:name="android.hardware.opengles.aep" android:required="false" />
```

`INTERNET` is only needed for ScummVM's cloud sync and its built-in web server;
without `ACCESS_NETWORK_STATE` the engine conservatively assumes a metered
connection. The Activity hosting `ScummVM` should be
`android:configChanges="orientation|screenSize|keyboardHidden"` so the engine is
not torn down on rotation, and `android:hardwareAccelerated="true"`.

## Building

Prerequisites:

* JDK 17 or newer.
* Android SDK, with `sdk.dir` in `android/local.properties` or `ANDROID_SDK_ROOT`
  exported.
* **NDK 23.2.8568313, exactly.** Upstream's `configure` greps `ndkVersion` out of
  `Sources/ScummVMEngine/dists/android/build.gradle` and aborts on any mismatch.
  The build reads the required revision from that same file, so it stays correct
  across submodule bumps.

  ```sh
  "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "ndk;23.2.8568313"
  ```

* The submodule: `git submodule update --init --recursive`.

Then:

```bash
cd android && ./gradlew :scummvm:assembleRelease
```

The AAR lands in `android/scummvm/build/outputs/aar/`.

### Test app build modes

The `:app` module opens ScummVM's launcher in a full-screen Compose host. Its
engine dependency follows the Android build type:

| App build | Engine dependency |
|---|---|
| `debug` | Local `:scummvm` project, including the local native engine build |
| `release` | Prebuilt AAR, so the app can test an artifact downloaded from CI or a release |

Build and install the local debug version:

```bash
./gradlew :app:installDebug
```

For release, copy an uploaded artifact to `app/libs/scummvm-release.aar` and
build normally:

```bash
./gradlew :app:assembleRelease
```

The artifact can also remain anywhere on disk:

```bash
./gradlew :app:assembleRelease \
  -Pscummvm.releaseAar=/absolute/path/to/scummvm-release.aar
```

Release builds fail early with a focused message when the AAR is missing. A
flat AAR has no dependency metadata, so the app declares the wrapper's AndroidX,
Compose, and coroutine runtime dependencies itself.

### How the native build works

There is no CMake or `ndk-build` reimplementation of ScummVM here. Rebuilding
its hand-rolled `configure` in Gradle would be a large, permanently drifting
fork of upstream's build system. Instead, per ABI, Gradle runs upstream's own
build out-of-tree — exactly what `backends/platform/android/fatbundle.mk` does:

```
configureScummVM<Abi>   →  <submodule>/configure --host=android-<abi> …
buildScummVM<Abi>       →  make -j libscummvm.so
stageScummVMJniLibs     →  jni/<abi>/libscummvm.so
```

The same staging task also packages Oboe's `liboboe.so` and the NDK's
`libc++_shared.so` for each selected ABI. ScummVM links to Oboe dynamically, and
Oboe links to the shared C++ runtime, so all three libraries must be present in
the AAR and final APK.

`make` is left to decide what is stale, so the build task never reports
up-to-date; use the prebuilt escape hatch below when iterating on Kotlin.

### Build properties

Set in `gradle.properties` or on the command line with `-P`.

| Property | Default | Meaning |
|---|---|---|
| `scummvm.abis` | `arm64-v8a,x86_64` | Comma-separated ABIs. `armeabi-v7a` and `x86` additionally need the NDK cpufeatures sources. |
| `scummvm.configureArgs` | *(empty)* | Extra `./configure` flags, e.g. `--disable-all-engines --enable-engine=scumm,sky`. Empty means every stable engine — a long build. |
| `scummvm.buildJobs` | *(CPU count)* | `make -j` parallelism. |
| `scummvm.prebuiltLibsDir` | *(unset)* | Skip the native build and package `<dir>/<abi>/libscummvm.so` instead. An optional adjacent `libc++_shared.so` is preferred over the pinned NDK copy. |

Iterating on the Kotlin layer without rebuilding the engine:

```bash
./gradlew :scummvm:assembleRelease -Pscummvm.prebuiltLibsDir=/path/to/libs
```

### CI

`.github/workflows/ci.yml` has a `build-android` job on `ubuntu-latest`. It
installs the pinned NDK, builds `arm64-v8a` with a representative subset of
engines (a full engine build does not fit comfortably in a CI run), caches the
native build directory against the submodule SHA, builds the release test app
against the just-produced AAR, and uploads both artifacts.

## How upstream code is reused

**Native.** All of it, unmodified, via the submodule.

**Java.** A hand-picked subset of `backends/platform/android/org/scummvm/scummvm/`
is copied verbatim into the build by the `stageUpstreamJava` task:
`ScummVM.java` (the JNI contract), `CompatHelpers`, `SAFFSTree`,
`ExternalStorage`, `INIParser`, `Version` and the `net/` package (looked up by
`FindClass` from `backends/networking/basic/android/jni.cpp`, since the Android
port builds without libcurl). Every one of these compiles with no reference to
`ScummVMActivity` and no reference to `R`, which is what makes them safe to ship
in a library.

Upstream's `ScummVMActivity`, `ScummVMEvents`, `SplashActivity`,
`ShortcutCreatorActivity`, `BackupManager`, the custom keyboard views and the
`zip/` package are *not* included: they are launcher-app concerns, need
`dists/android/res/`, and would leak upstream's UI into every consuming app.
Their engine-facing behaviour is reimplemented in Kotlin instead —
`ScummVMHost` (the `ScummVM` subclass the engine calls back into),
`ScummVMInput` (event translation) and `ScummVMAssets` (asset extraction).

One shim is added by hand: `MyScummVMDestroyedCallback`, which upstream declares
at the bottom of `ScummVMActivity.java` but which `ScummVM.java`'s constructor
requires.

## Known limitations

* **One engine run per process.** The native engine is a process-wide singleton
  with no re-initialisable teardown path; upstream's own launcher kills its
  process rather than restart it. `ScummVMEngine` enforces the same rule — once
  `state` is `Stopped`, a new engine reports `Failed` and the app has to be
  relaunched. Keep the `ScummVM` composable in the composition if the user
  should be able to come back to it.
* **No on-screen control overlay.** When the engine asks for its menu / input
  mode buttons, the library only logs it; draw your own controls on top of
  `ScummVMView`.
* **Simplified multi-touch.** Two- and three-finger gestures (right and middle
  click) work, but upstream's delayed arbitration between "second finger",
  "third finger" and "two-finger scroll" is not reproduced, so a two-finger
  gesture is reported as soon as the second finger lands.
* **No mouse capture / hover handling.** Upstream's `MouseHelper` is not ported;
  a physical mouse arrives as ordinary pointer events.
* **Backup import/export is reported as cancelled.** It needs upstream's
  `BackupManager`, a document picker and an app restart.
* **Simplified IME integration.** The surface requests raw key events via a
  `TYPE_NULL` input connection; upstream's `EditableSurfaceView` carries extra
  workarounds for specific Latin IMEs.
* **The AAR is large** (~80 MB), dominated by `fonts-cjk.dat` and the bundled
  soundfont. Trim the asset list in `stageScummVMAssets` if your app does not
  need them.
* `scummvm.ini` is only seeded on first run; `gamesDirectory` never overwrites a
  configuration the user already has.
