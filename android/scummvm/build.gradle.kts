import org.gradle.process.ExecOperations
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    // AGP 9 has Kotlin support built in (see android.enableKotlin below); the
    // standalone org.jetbrains.kotlin.android plugin must not be applied.
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Upstream engine location
// ---------------------------------------------------------------------------
// Everything C/C++ and the JNI-facing Java classes are consumed straight out of
// the git submodule; nothing under Sources/ScummVMEngine is ever modified.
val upstreamDir: File = rootProject.file("../Sources/ScummVMEngine")
val upstreamAndroidJavaDir = File(upstreamDir, "backends/platform/android/org/scummvm/scummvm")

require(File(upstreamDir, "configure").isFile) {
    "The ScummVM submodule is not checked out at ${upstreamDir.path}.\n" +
        "Run: git submodule update --init --recursive"
}

/**
 * Java sources vendored out of the upstream Android backend, relative to
 * `backends/platform/android/org/scummvm/scummvm/`.
 *
 * Deliberately a hand-picked subset rather than the whole tree: every file here
 * depends on neither `ScummVMActivity` nor any Android resource, so they can
 * ship inside a library AAR without dragging in upstream's launcher UI, its
 * manifest entries or its `res/` folder. `ScummVM.java` is the JNI contract with
 * `jni-android.cpp`, and the `net/` package is looked up by `FindClass` from
 * `backends/networking/basic/android/jni.cpp` (the Android port builds with
 * libcurl disabled and talks HTTP through Java); both must stay byte-identical
 * to upstream.
 *
 * Deliberately excluded: `ScummVMActivity`, `ScummVMEvents`, `SplashActivity`,
 * `ShortcutCreatorActivity`, `BackupManager`, the custom keyboard views and the
 * `zip/` package -- all of them are launcher-app concerns or need `R`.
 */
val upstreamJavaSources = listOf(
    "ScummVM.java",
    "CompatHelpers.java",
    "SAFFSTree.java",
    "ExternalStorage.java",
    "INIParser.java",
    "Version.java",
    "net/HTTPManager.java",
    "net/HTTPRequest.java",
    "net/LETrustManager.java",
    "net/SSocket.java",
    "net/TLSSocketFactory.java",
)

val abis: List<String> = (providers.gradleProperty("scummvm.abis").orNull ?: "arm64-v8a")
    .split(",")
    .map(String::trim)
    .filter(String::isNotEmpty)

val prebuiltLibsDir: String? = providers.gradleProperty("scummvm.prebuiltLibsDir").orNull
    ?.takeIf(String::isNotBlank)

android {
    namespace = "de.doop.scummvm"
    compileSdk {
        version = release(36)
    }
    enableKotlin = true

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // libscummvm.so is stripped by the upstream build already; let AGP pass
        // it through untouched rather than re-stripping it.
        jniLibs.keepDebugSymbols += "**/libscummvm.so"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Referenced by the vendored upstream Java sources (@Keep, @NonNull, ...).
    api(libs.androidx.annotation)
}

// ---------------------------------------------------------------------------
// SDK / NDK discovery
// ---------------------------------------------------------------------------

/**
 * The NDK revision upstream's `configure` insists on: it greps `ndkVersion` out
 * of `dists/android/build.gradle` and aborts on a mismatch. Read it from that
 * same file so a submodule bump can never silently desync.
 */
fun requiredNdkVersion(): String {
    val gradleFile = File(upstreamDir, "dists/android/build.gradle")
    require(gradleFile.isFile) {
        "Missing ${gradleFile.path}. Run: git submodule update --init --recursive"
    }
    val line = gradleFile.readLines().firstOrNull { it.trimStart().startsWith("ndkVersion") }
        ?: error("No ndkVersion declaration found in ${gradleFile.path}")
    return line.replace(Regex("[^0-9.]"), "")
}

fun localProperty(name: String): String? {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return null
    val props = Properties()
    file.inputStream().use(props::load)
    return props.getProperty(name)?.takeIf(String::isNotBlank)
}

fun resolveSdkDir(): File {
    val candidate = localProperty("sdk.dir")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: System.getenv("ANDROID_HOME")
        ?: error(
            "Android SDK not found. Set sdk.dir in android/local.properties or export ANDROID_SDK_ROOT.",
        )
    return File(candidate).also {
        require(it.isDirectory) { "Android SDK directory does not exist: $it" }
    }
}

fun resolveNdkDir(): File {
    val version = requiredNdkVersion()
    val candidate = localProperty("ndk.dir")
        ?: System.getenv("ANDROID_NDK_ROOT")
        ?: System.getenv("ANDROID_NDK_HOME")
        ?: File(resolveSdkDir(), "ndk/$version").path
    val dir = File(candidate)
    require(dir.isDirectory) {
        "Android NDK $version not found at $dir.\n" +
            "Install it with:\n" +
            "  \"\$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager\" \"ndk;$version\"\n" +
            "or point ANDROID_NDK_ROOT at an existing install of that revision."
    }
    val revision = File(dir, "source.properties").takeIf(File::isFile)
        ?.readLines()
        ?.firstOrNull { it.startsWith("Pkg.Revision") }
        ?.substringAfter('=')
        ?.trim()
    require(revision == null || revision == version) {
        "Upstream configure requires NDK $version but $dir is $revision.\n" +
            "ScummVM's configure aborts on a version mismatch; install that exact revision."
    }
    return dir
}

fun resolveCxxRuntime(abi: String): File {
    // A fully self-contained prebuilt directory may supply the runtime used to
    // link its libraries. Prefer that exact copy when it is available.
    prebuiltLibsDir?.let { root ->
        val alongside = File(root, "$abi/libc++_shared.so")
        if (alongside.isFile) return alongside
    }

    val targetTriple = when (abi) {
        "armeabi-v7a" -> "arm-linux-androideabi"
        "arm64-v8a" -> "aarch64-linux-android"
        "x86" -> "i686-linux-android"
        "x86_64" -> "x86_64-linux-android"
        else -> error("Unsupported Android ABI: $abi")
    }
    val prebuiltRoot = File(resolveNdkDir(), "toolchains/llvm/prebuilt")
    val runtime = prebuiltRoot.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .map { host -> File(host, "sysroot/usr/lib/$targetTriple/libc++_shared.so") }
        .firstOrNull(File::isFile)

    return requireNotNull(runtime) {
        "libc++_shared.so for $abi was not found under ${prebuiltRoot.path}.\n" +
            "Install the required NDK or place it next to the prebuilt engine at " +
            "<scummvm.prebuiltLibsDir>/$abi/libc++_shared.so."
    }
}

// ---------------------------------------------------------------------------
// Oboe
// ---------------------------------------------------------------------------
// Upstream's Android mixer backend links against Oboe (`-loboe`, appended by
// `configure` for the android host; see backends/mixer/android/android-mixer.cpp
// and configure's android host block). It isn't part of the submodule, and
// this build shells out to upstream's own configure/make rather than
// reimplementing it via AGP's native/CMake integration, so there is no
// automatic Prefab wiring -- fetch the AAR from Google's Maven repository
// ourselves and pass its headers/libs to configure via CPPFLAGS/LDFLAGS.
val oboeVersion = "1.10.0"

val oboe: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    oboe("com.google.oboe:oboe:$oboeVersion")
}

val oboeDir: Provider<Directory> = layout.buildDirectory.dir("oboe")

val extractOboe = tasks.register<Copy>("extractOboe") {
    group = "scummvm"
    description = "Unpacks the Oboe AAR's Prefab headers and per-ABI libraries."
    from({ project.zipTree(oboe.singleFile) })
    into(oboeDir)
}

fun oboeIncludeDir(): Provider<String> =
    oboeDir.map { it.dir("prefab/modules/oboe/include").asFile.absolutePath }

fun oboeLibDir(abi: String): Provider<String> =
    oboeDir.map { it.dir("prefab/modules/oboe/libs/android.$abi").asFile.absolutePath }

// ---------------------------------------------------------------------------
// Task types
// ---------------------------------------------------------------------------

/**
 * Copies an explicit set of files into a generated source/asset/jniLibs folder.
 *
 * The layout is spelled out file by file rather than as copy specs so that the
 * task has precise inputs -- Gradle must never be asked to snapshot the ~2 GB
 * engine submodule.
 */
abstract class StageFiles : DefaultTask() {
    @get:Inject abstract val fs: FileSystemOperations

    /** Destination path (relative to [outputDir]) -> absolute source path. */
    @get:Input abstract val entries: MapProperty<String, String>

    /** Same files again, so Gradle tracks their contents and producing tasks. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceFiles: ConfigurableFileCollection

    /**
     * Writes the `MD5SUMS` manifest upstream's `android.mk` generates, which
     * `ScummVMAssets` uses to decide whether to re-extract on the device.
     */
    @get:Input abstract val writeChecksums: Property<Boolean>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val out = outputDir.get().asFile
        fs.delete { delete(out) }
        out.mkdirs()

        entries.get().forEach { (relative, source) ->
            val target = File(out, relative)
            target.parentFile?.mkdirs()
            File(source).copyTo(target, overwrite = true)
        }

        if (!writeChecksums.get()) return

        val manifest = out.walkTopDown()
            .filter(File::isFile)
            .map { md5(it) to it.relativeTo(out).invariantSeparatorsPath }
            .sortedBy { it.second }
            .joinToString("\n", postfix = "\n") { (digest, path) -> "$digest  $path" }
        File(out, "MD5SUMS").writeText(manifest)
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

abstract class ScummVMConfigure : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations

    @get:Input abstract val abi: Property<String>

    @get:Input abstract val extraArgs: ListProperty<String>

    @get:Input abstract val sdkDir: Property<String>

    @get:Input abstract val ndkDir: Property<String>

    @get:Input abstract val oboeIncludeDir: Property<String>

    @get:Input abstract val oboeLibDir: Property<String>

    @get:Internal abstract val configurePath: Property<String>

    @get:Internal abstract val nativeBuildDir: DirectoryProperty

    @get:OutputFile abstract val configMk: RegularFileProperty

    @TaskAction
    fun run() {
        val workDir = nativeBuildDir.get().asFile
        workDir.mkdirs()
        val args = buildList {
            add(configurePath.get())
            add("--host=android-${abi.get()}")
            addAll(extraArgs.get())
        }
        logger.lifecycle("ScummVM configure (${abi.get()}): ${args.joinToString(" ")}")
        execOps.exec {
            workingDir = workDir
            commandLine(args)
            environment("ANDROID_SDK_ROOT", sdkDir.get())
            environment("ANDROID_NDK_ROOT", ndkDir.get())
            // Picked up by configure (folded into CXXFLAGS/LDFLAGS and baked
            // into the generated config.mk) so android-mixer.cpp finds Oboe.
            environment("CPPFLAGS", "-I${oboeIncludeDir.get()}")
            environment("LDFLAGS", "-L${oboeLibDir.get()}")
        }
    }
}

abstract class ScummVMMake : DefaultTask() {
    @get:Inject abstract val execOps: ExecOperations

    @get:Input abstract val jobs: Property<Int>

    @get:Input abstract val sdkDir: Property<String>

    @get:Input abstract val ndkDir: Property<String>

    @get:Internal abstract val nativeBuildDir: DirectoryProperty

    @get:OutputFile abstract val library: RegularFileProperty

    @TaskAction
    fun run() {
        execOps.exec {
            workingDir = nativeBuildDir.get().asFile
            commandLine(listOf("make", "-j${jobs.get()}", "libscummvm.so"))
            environment("ANDROID_SDK_ROOT", sdkDir.get())
            environment("ANDROID_NDK_ROOT", ndkDir.get())
        }
    }
}

// ---------------------------------------------------------------------------
// Native build: upstream ./configure + make, one out-of-tree build per ABI
// ---------------------------------------------------------------------------

val configureArgs: List<String> = (providers.gradleProperty("scummvm.configureArgs").orNull ?: "")
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)

val makeJobs: Int = providers.gradleProperty("scummvm.buildJobs").orNull
    ?.toIntOrNull()
    ?: Runtime.getRuntime().availableProcessors()

// Resolved lazily so a `-Pscummvm.prebuiltLibsDir` build never needs an NDK.
val sdkDirProvider: Provider<String> = providers.provider { resolveSdkDir().absolutePath }
val ndkDirProvider: Provider<String> = providers.provider { resolveNdkDir().absolutePath }

val nativeLibraryTasks: Map<String, TaskProvider<ScummVMMake>> =
    if (prebuiltLibsDir != null) {
        emptyMap()
    } else {
        abis.associateWith { abi ->
            val suffix = abi.split(Regex("[-_]")).joinToString("") { part ->
                part.replaceFirstChar(Char::uppercaseChar)
            }
            val nativeBuild = layout.buildDirectory.dir("native/$abi")

            val configureTask = tasks.register<ScummVMConfigure>("configureScummVM$suffix") {
                group = "scummvm"
                description = "Runs upstream ./configure for $abi."
                this.abi.set(abi)
                extraArgs.set(configureArgs)
                sdkDir.set(sdkDirProvider)
                ndkDir.set(ndkDirProvider)
                oboeIncludeDir.set(oboeIncludeDir())
                oboeLibDir.set(oboeLibDir(abi))
                configurePath.set(File(upstreamDir, "configure").absolutePath)
                nativeBuildDir.set(nativeBuild)
                configMk.set(nativeBuild.map { it.file("config.mk") })
                dependsOn(extractOboe)
            }

            tasks.register<ScummVMMake>("buildScummVM$suffix") {
                group = "scummvm"
                description = "Builds libscummvm.so for $abi."
                dependsOn(configureTask)
                jobs.set(makeJobs)
                sdkDir.set(sdkDirProvider)
                ndkDir.set(ndkDirProvider)
                nativeBuildDir.set(nativeBuild)
                library.set(nativeBuild.map { it.file("libscummvm.so") })
                // `make` is already incremental and knows far better than Gradle
                // whether 3000+ translation units are stale, so always defer to it.
                outputs.upToDateWhen { false }
            }
        }
    }

// ---------------------------------------------------------------------------
// Generated sources, assets and jniLibs
// ---------------------------------------------------------------------------

val stageUpstreamJava = tasks.register<StageFiles>("stageUpstreamJava") {
    group = "scummvm"
    description = "Copies the JNI-facing upstream Java classes into the build."
    writeChecksums.set(false)
    upstreamJavaSources.forEach { relative ->
        val source = File(upstreamAndroidJavaDir, relative)
        require(source.isFile) {
            "Upstream Java source $relative is missing from ${upstreamAndroidJavaDir.path}.\n" +
                "Re-check upstreamJavaSources in android/scummvm/build.gradle.kts after a submodule sync."
        }
        entries.put("org/scummvm/scummvm/$relative", source.absolutePath)
        sourceFiles.from(source)
    }
}

/**
 * Runtime data the engine loads by path: themes, engine data, soundfonts, the
 * virtual keyboard packs and the bundled documentation.
 *
 * Everything lands under `assets/`, which `ScummVMAssets` unpacks to
 * `<filesDir>/assets` and hands to the engine as its sys-archive search path.
 * The lists mirror `DIST_FILES_*` in upstream's `Makefile.common`.
 */
val stageAssets = tasks.register<StageFiles>("stageScummVMAssets") {
    group = "scummvm"
    description = "Stages ScummVM's runtime data files as library assets."
    writeChecksums.set(true)

    fun stage(sourceDir: File, destination: String, filter: (File) -> Boolean) {
        val files = sourceDir.listFiles()?.filter { it.isFile && filter(it) }.orEmpty()
        require(files.isNotEmpty()) { "No files matched in ${sourceDir.path}" }
        files.forEach { file ->
            entries.put("$destination/${file.name}", file.absolutePath)
            sourceFiles.from(file)
        }
    }

    fun named(sourceDir: File, destination: String, vararg names: String) =
        stage(sourceDir, destination) { it.name in names }

    named(
        File(upstreamDir, "gui/themes"), "assets",
        "scummmodern.zip", "scummclassic.zip", "scummremastered.zip", "residualvm.zip",
        "gui-icons.dat", "shaders.dat", "translations.dat",
    )
    // Top-level payload files only: engine-data's fonts/ and patches/ folders are
    // build inputs for the .dat bundles, and *.mk / *.sh / README are plumbing.
    stage(File(upstreamDir, "dists/engine-data"), "assets") { file ->
        file.extension !in setOf("mk", "sh") && file.name != "README"
    }
    named(File(upstreamDir, "dists/networking"), "assets", "wwwroot.zip")
    named(
        File(upstreamDir, "dists/soundfonts"), "assets",
        "Roland_SC-55.sf2", "COPYRIGHT.Roland_SC-55",
    )
    named(
        File(upstreamDir, "backends/vkeybd/packs"), "assets",
        "vkeybd_default.zip", "vkeybd_small.zip",
    )
    named(File(upstreamDir, "dists/android"), "assets", "android-help.zip", "gamepad.svg")
    named(File(upstreamDir, "dists"), "assets", "pred.dic")
    named(upstreamDir, "doc", "COPYING", "COPYRIGHT", "AUTHORS", "NEWS.md", "README.md")
}

val stageJniLibs = tasks.register<StageFiles>("stageScummVMJniLibs") {
    group = "scummvm"
    description = "Stages ScummVM, Oboe, and the shared C++ runtime into jniLibs."
    writeChecksums.set(false)
    dependsOn(extractOboe)

    if (prebuiltLibsDir != null) {
        val root = file(prebuiltLibsDir)
        abis.forEach { abi ->
            val library = File(root, "$abi/libscummvm.so")
            require(library.isFile) {
                "scummvm.prebuiltLibsDir is set but ${library.path} is missing."
            }
            entries.put("$abi/libscummvm.so", library.absolutePath)
            sourceFiles.from(library)
        }
    } else {
        nativeLibraryTasks.forEach { (abi, makeTask) ->
            val library = makeTask.flatMap { it.library }
            entries.put("$abi/libscummvm.so", library.map { it.asFile.absolutePath })
            sourceFiles.from(library)
        }
    }

    abis.forEach { abi ->
        val oboeLibrary = oboeDir.map {
            it.file("prefab/modules/oboe/libs/android.$abi/liboboe.so").asFile
        }
        entries.put("$abi/liboboe.so", oboeLibrary.map { it.absolutePath })
        sourceFiles.from(oboeLibrary)

        val cxxRuntime = providers.provider { resolveCxxRuntime(abi) }
        entries.put("$abi/libc++_shared.so", cxxRuntime.map { it.absolutePath })
        sourceFiles.from(cxxRuntime)
    }
}

androidComponents {
    onVariants { variant ->
        // addGeneratedSourceDirectory picks the output location and wires the
        // task dependency, so these folders are always built before they are read.
        variant.sources.java?.addGeneratedSourceDirectory(stageUpstreamJava, StageFiles::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(stageAssets, StageFiles::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageJniLibs, StageFiles::outputDir)
    }
}
