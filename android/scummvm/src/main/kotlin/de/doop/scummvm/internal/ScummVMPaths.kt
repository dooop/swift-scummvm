package de.doop.scummvm.internal

import android.content.Context
import java.io.File

/**
 * On-disk layout the engine is pointed at.
 *
 * Everything lives under the app's private `filesDir`, matching what upstream's
 * `ScummVMActivity` settled on: external storage is neither reliably available
 * nor writable without user interaction on modern Android.
 */
internal class ScummVMPaths(context: Context) {
    val baseDir: File = context.filesDir

    /** Where the AAR assets get unpacked; handed to the engine as a sys archive. */
    val assetsDir: File = File(baseDir, "assets")

    val docDir: File = File(baseDir, "doc")

    val configFile: File = File(baseDir, "scummvm.ini")

    val logFile: File = File(baseDir, "scummvm.log")

    /**
     * Creates `scummvm.ini` if it does not exist yet.
     *
     * An existing config is never rewritten -- it belongs to the user at that
     * point, and [gamesDirectory] is only a first-run convenience.
     */
    fun ensureConfiguration(gamesDirectory: File?) {
        if (configFile.exists()) return

        val lines = buildList {
            add("[scummvm]")
            gamesDirectory?.let { add("browser_lastpath=${it.absolutePath}") }
        }
        configFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
