plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseAar = providers.gradleProperty("scummvm.releaseAar")
    .orElse(layout.projectDirectory.file("libs/scummvm-release.aar").asFile.absolutePath)

android {
    // Must differ from the consumed AAR's namespace; AGP rejects duplicate
    // namespaces during manifest merging. The application id and Activity
    // package remain de.doop.scummvm.
    namespace = "de.doop.scummvm.app"
    compileSdk {
        version = release(37)
    }
    enableKotlin = true

    defaultConfig {
        applicationId = "de.doop.scummvm"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "ENGINE_SOURCE", "\"local project\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "ENGINE_SOURCE", "\"prebuilt AAR\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/libscummvm.so"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    debugImplementation(project(":scummvm"))
    releaseImplementation(files(releaseAar))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
}

val verifyReleaseAar = tasks.register("verifyReleaseAar") {
    group = "verification"
    description = "Checks that the prebuilt ScummVM AAR for release builds exists."
    doLast {
        val aar = file(releaseAar.get())
        require(aar.isFile) {
            "Release builds require a prebuilt ScummVM AAR at ${aar.path}.\n" +
                "Copy an uploaded release artifact to app/libs/scummvm-release.aar or pass " +
                "-Pscummvm.releaseAar=/absolute/path/to/scummvm-release.aar."
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(verifyReleaseAar)
}
