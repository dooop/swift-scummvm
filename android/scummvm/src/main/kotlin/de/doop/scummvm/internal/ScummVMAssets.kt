package de.doop.scummvm.internal

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Unpacks ScummVM's runtime data from the AAR's assets into app storage.
 *
 * The engine reads themes, engine data and soundfonts through plain file paths,
 * so they cannot stay inside the APK. This is a port of the `checkAssets()` /
 * `extractAssets()` pair in upstream's `ScummVMActivity`, keeping the same
 * `MD5SUMS` protocol: the manifest generated at build time is compared against
 * the copy left behind by the previous extraction, and everything is re-unpacked
 * whenever they differ.
 */
internal object ScummVMAssets {
    private const val TAG = "ScummVM"
    private const val MANIFEST = "MD5SUMS"

    /** Roots inside `assets/` that get extracted, in the order upstream uses. */
    private val ROOTS = listOf("assets", "doc")

    /**
     * @return true when files were (re-)extracted. The engine needs to know:
     *   it re-reads bundled data that it would otherwise cache.
     */
    fun extractIfNeeded(assetManager: AssetManager, paths: ScummVMPaths): Boolean {
        val bundled = try {
            assetManager.open(MANIFEST).use { it.readBytes() }
        } catch (e: IOException) {
            throw IOException(
                "$MANIFEST is missing from the library assets -- the AAR was built without " +
                    "the syncScummVMAssets task output.",
                e,
            )
        }

        val installed = File(paths.baseDir, MANIFEST)
        if (installed.isFile && installed.readBytes().contentEquals(bundled)) {
            Log.d(TAG, "$MANIFEST is already up to date")
            return false
        }

        for (root in ROOTS) {
            extract(assetManager, root, File(paths.baseDir, root))
        }

        // Written last: a crash mid-extraction leaves the old manifest in place
        // so the next launch retries instead of trusting a partial install.
        installed.writeBytes(bundled)
        return true
    }

    /**
     * Copies the asset tree at [assetPath] to [target].
     *
     * @return true if [assetPath] was a directory. `AssetManager` gives no direct
     *   way to tell a file from a directory, so -- as upstream does -- an empty
     *   listing is taken to mean "this is a file".
     */
    private fun extract(assetManager: AssetManager, assetPath: String, target: File): Boolean {
        val entries = assetManager.list(assetPath) ?: emptyArray()
        if (entries.isEmpty()) {
            if (target.isDirectory) target.deleteRecursively()
            return false
        }

        if (target.isDirectory) {
            // Drop anything the previous version of the library left behind.
            val expected = entries.toSet()
            target.listFiles()?.forEach { stale ->
                if (stale.name !in expected) stale.deleteRecursively()
            }
        } else {
            target.delete()
        }

        if (!target.isDirectory && !target.mkdirs()) {
            throw IOException("Failed to create directory ${target.path}")
        }

        for (name in entries) {
            val childAsset = if (assetPath.isEmpty()) name else "$assetPath/$name"
            val childFile = File(target, name)
            if (extract(assetManager, childAsset, childFile)) continue

            assetManager.open(childAsset).use { input ->
                childFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return true
    }
}
