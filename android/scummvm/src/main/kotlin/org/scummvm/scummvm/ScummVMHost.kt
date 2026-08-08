package org.scummvm.scummvm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import de.doop.scummvm.internal.ScummVMPaths

/**
 * Concrete [ScummVM] the native engine calls back into.
 *
 * Lives in `org.scummvm.scummvm` on purpose: `ScummVM.java` takes the
 * package-private `MyScummVMDestroyedCallback` and implements the
 * package-private `CompatHelpers.SystemInsets.SystemInsetsListener`, neither of
 * which can be named from outside the package. Everything policy-related is
 * delegated to [ScummVMHostDelegate] so the Compose layer stays in charge.
 *
 * This is the wrapper's stand-in for the `MyScummVM` inner class of upstream's
 * `ScummVMActivity`. Callbacks arrive on the engine thread unless noted.
 */
internal class ScummVMHost(
    private val context: Context,
    private val paths: ScummVMPaths,
    private val delegate: ScummVMHostDelegate,
    holder: SurfaceHolder,
    onDestroyed: (Int) -> Unit,
) : ScummVM(context.assets, holder, MyScummVMDestroyedCallback { onDestroyed(it) }) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val clipboard: ClipboardManager?
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /**
     * Subscribes the engine to system inset changes so its GUI keeps clear of
     * cutouts and gesture areas.
     *
     * Has to live here: `CompatHelpers.SystemInsets` is package-private.
     * Upstream registers on the root view because something between the surface
     * and the root swallows the insets.
     */
    fun registerSystemInsets(view: android.view.View) {
        CompatHelpers.SystemInsets.registerSystemInsetsListener(view.rootView, this)
    }

    override fun getDPI(values: FloatArray) {
        val metrics = context.resources.displayMetrics
        values[0] = metrics.xdpi
        values[1] = metrics.ydpi
        // Matches upstream: density scaled by the user's font-size preference,
        // since ScummVM renders its own UI and cannot use sp units.
        values[2] = metrics.density * context.resources.configuration.fontScale
    }

    override fun displayMessageOnOSD(msg: String?) {
        if (msg == null) return
        mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun openUrl(url: String?) {
        if (url == null) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(LOG_TAG, "No activity can open $url", e)
        }
    }

    override fun hasTextInClipboard(): Boolean {
        val clip = clipboard?.primaryClip ?: return false
        return clip.itemCount > 0 && clip.getItemAt(0).text != null
    }

    override fun getTextFromClipboard(): String? {
        val clip = clipboard?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    override fun setTextInClipboard(text: String?): Boolean {
        val manager = clipboard ?: return false
        manager.setPrimaryClip(ClipData.newPlainText("ScummVM clip", text))
        return true
    }

    // ACCESS_NETWORK_STATE is the host app's call to declare (the library manifest
    // merges nothing), so the missing-permission case is handled at runtime instead.
    @android.annotation.SuppressLint("MissingPermission")
    override fun isConnectionLimited(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        return try {
            manager.isActiveNetworkMetered
        } catch (e: SecurityException) {
            // The host app did not declare ACCESS_NETWORK_STATE. Assume the
            // connection is limited so the engine stays conservative.
            Log.w(LOG_TAG, "ACCESS_NETWORK_STATE not granted; treating network as metered", e)
            true
        }
    }

    override fun setWindowCaption(caption: String?) {
        mainHandler.post { delegate.onWindowCaption(caption) }
    }

    override fun showVirtualKeyboard(enable: Boolean) {
        mainHandler.post { delegate.onVirtualKeyboardRequested(enable) }
    }

    override fun showOnScreenControls(enableMask: Int) {
        mainHandler.post { delegate.onOnScreenControlsRequested(enableMask) }
    }

    override fun setTouchMode(touchMode: Int) {
        if (delegate.currentTouchMode() == touchMode) return
        mainHandler.post { delegate.requestTouchMode(touchMode) }
    }

    override fun getTouchMode(): Int = delegate.currentTouchMode()

    override fun setOrientation(orientation: Int) {
        mainHandler.post { delegate.requestOrientation(orientation) }
    }

    override fun getScummVMBasePath(): String = paths.baseDir.path

    override fun getScummVMConfigPath(): String = paths.configFile.path

    override fun getScummVMLogPath(): String = paths.logFile.path

    override fun setCurrentGame(target: String?) {
        delegate.onCurrentGame(target)
    }

    override fun notifyHTTPService(localPort: Int, minimal: Boolean) {
        // Upstream advertises the built-in web server over NSD so it shows up in
        // "ScummVM on the local network". A library has no business registering
        // a service on the host app's behalf, so this is left to the app.
        Log.d(LOG_TAG, "HTTP service on port $localPort (minimal=$minimal); not advertised")
    }

    override fun getSysArchives(): Array<String> = arrayOf(paths.assetsDir.path)

    override fun getAllStorageLocations(): Array<String> =
        try {
            ExternalStorage.getAllStorageLocations(context.applicationContext).toTypedArray()
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "Storage locations unavailable without read permission", e)
            emptyArray()
        }

    override fun getNewSAFTree(write: Boolean, initialURI: String?, prompt: String?): SAFFSTree? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val uri = delegate.pickFolder(write, initialURI, prompt) ?: return null
        return SAFFSTree.newTree(context, uri)
    }

    override fun getSAFTrees(): Array<SAFFSTree> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyArray()
        return SAFFSTree.getTrees(context) ?: emptyArray()
    }

    override fun findSAFTree(name: String?): SAFFSTree? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return SAFFSTree.findTree(context, name)
    }

    override fun exportBackup(prompt: String?): Int = BACKUP_CANCELLED

    override fun importBackup(prompt: String?, path: String?): Int = BACKUP_CANCELLED

    private companion object {
        /**
         * `BackupManager.ERROR_CANCELLED`. Backup import/export needs upstream's
         * `BackupManager` plus a document picker and an app restart, all of which
         * are launcher-app concerns; report them as user-cancelled instead.
         */
        const val BACKUP_CANCELLED = 1
    }
}

/**
 * Everything [ScummVMHost] hands back to the Compose layer.
 *
 * Implemented by `ScummVMEngine`. Unless stated otherwise these are invoked on
 * the main thread.
 */
internal interface ScummVMHostDelegate {
    fun onWindowCaption(caption: String?)

    fun onCurrentGame(target: String?)

    fun onVirtualKeyboardRequested(show: Boolean)

    fun onOnScreenControlsRequested(mask: Int)

    /** Called on the engine thread. */
    fun currentTouchMode(): Int

    fun requestTouchMode(mode: Int)

    fun requestOrientation(orientation: Int)

    /**
     * Called on the engine thread and expected to block until the user has
     * picked a folder (or dismissed the picker). Returns null when unavailable.
     */
    fun pickFolder(write: Boolean, initialUri: String?, prompt: String?): Uri?
}
