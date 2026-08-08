package de.doop.scummvm

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.doop.scummvm.internal.ScummVMAssets
import de.doop.scummvm.internal.ScummVMInput
import de.doop.scummvm.internal.ScummVMPaths
import org.scummvm.scummvm.ScummVM
import org.scummvm.scummvm.ScummVMHost
import org.scummvm.scummvm.ScummVMHostDelegate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one run of the ScummVM engine.
 *
 * ### One run per process
 *
 * The native engine is a process-wide singleton with no teardown path that
 * leaves it re-initialisable -- upstream's launcher kills its own process when
 * the engine thread will not exit. This class enforces the same rule: a second
 * [ScummVMEngine] cannot be started in the same process, and once [state]
 * reaches [ScummVMState.Stopped] the host app has to restart to play again.
 * Plan the surrounding UI around that.
 *
 * Obtain one through [rememberScummVMEngine] rather than constructing it
 * directly.
 */
class ScummVMEngine internal constructor(
    context: Context,
    private val configuration: ScummVMConfiguration,
) : ScummVMHostDelegate {

    private val appContext: Context = context.applicationContext
    private val paths = ScummVMPaths(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ScummVMState>(ScummVMState.Idle)

    /** Lifecycle of this engine run. */
    val state: StateFlow<ScummVMState> = _state.asStateFlow()

    private val _windowCaption = MutableStateFlow<String?>(null)

    /** Title the engine would put in a window caption, e.g. the running game. */
    val windowCaption: StateFlow<String?> = _windowCaption.asStateFlow()

    private val _currentGame = MutableStateFlow<String?>(null)

    /** Target id of the game currently being played, or null in the launcher. */
    val currentGame: StateFlow<String?> = _currentGame.asStateFlow()

    private val _touchMode = MutableStateFlow(ScummVMTouchMode.Touchpad)

    /** How touch input is currently interpreted. */
    val touchMode: StateFlow<ScummVMTouchMode> = _touchMode.asStateFlow()

    private var host: ScummVMHost? = null
    private var input: ScummVMInput? = null
    private var hostView: View? = null

    @Volatile
    private var thread: Thread? = null

    /** Read by the preparation thread, so a teardown mid-extraction is honoured. */
    @Volatile
    private var stopRequested = false

    /**
     * Set by [ScummVMView] when a document-tree picker is wired up. Invoked on
     * the main thread; the answer comes back through [deliverPickedFolder].
     */
    internal var folderPickerLauncher: ((Uri?) -> Unit)? = null

    // Single-slot handoff from the picker callback to the blocked engine thread.
    // The element is a one-item array so a "user cancelled" null can be queued.
    private val pickedFolders = ArrayBlockingQueue<Array<Uri?>>(1)

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /**
     * Pauses or resumes the engine and all of its native threads.
     *
     * [ScummVMView] drives this from the host lifecycle; call it directly only
     * to pause for app-specific reasons.
     */
    fun setPaused(paused: Boolean) {
        host?.setPause(paused)
    }

    /** Switches how touch input is translated. */
    fun setTouchMode(mode: ScummVMTouchMode) {
        input?.setTouchMode(mode)
        _touchMode.value = mode
    }

    /**
     * Asks the engine to quit and waits briefly for its thread to unwind.
     *
     * Safe to call more than once. The engine cannot be started again
     * afterwards; see the class documentation.
     */
    fun stop() {
        // Set before anything else: a preparation still in flight checks this
        // and will not hand the engine a thread it can no longer be stopped on.
        stopRequested = true

        // Unblock a folder picker the engine thread might be sitting on.
        pickedFolders.offer(arrayOf(null))

        val runningThread = thread
        if (runningThread != null) {
            thread = null
            input?.sendQuit()
            // The quit event is only observed while the engine is polling.
            host?.setPause(false)
            try {
                runningThread.join(SHUTDOWN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.i(TAG, "Interrupted while joining the ScummVM thread", e)
            }
            if (runningThread.isAlive) {
                // Upstream kills its own process here. A library must not do that
                // to its host, so report it and leave the thread be -- the engine
                // is unusable from now on either way.
                Log.e(TAG, "ScummVM thread did not exit within ${SHUTDOWN_TIMEOUT_MS}ms")
            }
        }

        host = null
        input = null
        hostView = null
    }

    // ------------------------------------------------------------------
    // Wiring, driven by ScummVMView
    // ------------------------------------------------------------------

    internal fun attach(view: ScummVMSurfaceView) {
        if (host != null) return
        if (!engineClaimed.compareAndSet(false, true)) {
            _state.value = ScummVMState.Failed(
                IllegalStateException(
                    "The ScummVM engine has already run in this process. It is a native " +
                        "singleton and cannot be restarted; the app must be relaunched.",
                ),
            )
            return
        }

        hostView = view
        _state.value = ScummVMState.PreparingData

        val newHost = ScummVMHost(appContext, paths, this, view.holder) { exitCode ->
            mainHandler.post {
                thread = null
                _state.value = ScummVMState.Stopped(exitCode)
            }
        }
        host = newHost

        val newInput = ScummVMInput(appContext, newHost)
        input = newInput
        view.input = newInput
        view.setOnTouchListener(newInput)
        view.setOnKeyListener(newInput)
        view.requestFocus()
        newHost.registerSystemInsets(view)

        // Asset extraction is tens of megabytes of file copying on first run and
        // must not block the frame the surface was created on.
        Thread({ prepareAndLaunch(newHost) }, "ScummVM-prepare").start()
    }

    private fun prepareAndLaunch(host: ScummVMHost) {
        val assetsUpdated = try {
            updateAudioDefaults()
            paths.ensureConfiguration(configuration.gamesDirectory)
            ScummVMAssets.extractIfNeeded(appContext.assets, paths)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepare ScummVM data", e)
            mainHandler.post { _state.value = ScummVMState.Failed(e) }
            return
        }

        if (stopRequested) {
            // Torn down while unpacking; never start the engine at all.
            mainHandler.post { _state.value = ScummVMState.Stopped(0) }
            return
        }

        host.setAssetsUpdated(assetsUpdated)
        host.setArgs(buildArguments())

        // 8 MB, as upstream uses: some engines recurse deeply.
        val engineThread = Thread(null, host, "ScummVM", ENGINE_STACK_SIZE)
        thread = engineThread
        mainHandler.post { _state.value = ScummVMState.Running }
        engineThread.start()
    }

    private fun buildArguments(): Array<String> = buildList {
        add("ScummVM")
        configuration.target?.let { add(it) }
        addAll(configuration.extraArguments)
    }.toTypedArray()

    /**
     * Hands the platform's preferred audio buffer geometry to the engine.
     * Ignored by AAudio on Oreo and later, but still read on older releases.
     */
    private fun updateAudioDefaults() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        val sampleRate = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: return
        val framesPerBurst = audioManager
            .getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull() ?: return
        ScummVM.setDefaultAudioValues(sampleRate, framesPerBurst)
    }

    /** Called from the picker callback on the main thread. */
    internal fun deliverPickedFolder(uri: Uri?) {
        if (uri != null) {
            try {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not persist access to $uri", e)
            }
        }
        pickedFolders.offer(arrayOf(uri))
    }

    // ------------------------------------------------------------------
    // ScummVMHostDelegate
    // ------------------------------------------------------------------

    override fun onWindowCaption(caption: String?) {
        _windowCaption.value = caption
    }

    override fun onCurrentGame(target: String?) {
        _currentGame.value = target
    }

    override fun onVirtualKeyboardRequested(show: Boolean) {
        val view = hostView ?: return
        val imm = appContext.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        if (show) {
            view.requestFocus()
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onOnScreenControlsRequested(mask: Int) {
        // Upstream draws a menu / input-mode button overlay from its own
        // resources. This library leaves that to the embedding app, which can
        // render whatever it likes on top of ScummVMView.
        Log.d(TAG, "Engine requested on-screen controls (mask=$mask)")
    }

    override fun currentTouchMode(): Int = _touchMode.value.nativeValue

    override fun requestTouchMode(mode: Int) {
        setTouchMode(ScummVMTouchMode.fromNative(mode))
    }

    override fun requestOrientation(orientation: Int) {
        val activity = hostView?.context?.findActivity() ?: return
        activity.requestedOrientation = orientation
    }

    override fun pickFolder(write: Boolean, initialUri: String?, prompt: String?): Uri? {
        val launcher = folderPickerLauncher ?: return null
        pickedFolders.clear()
        val initial = initialUri?.takeIf(String::isNotEmpty)?.let(Uri::parse)
        mainHandler.post { launcher(initial) }
        return try {
            pickedFolders.poll(FOLDER_PICKER_TIMEOUT_MINUTES, TimeUnit.MINUTES)?.firstOrNull()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private companion object {
        const val TAG = "ScummVM"
        const val ENGINE_STACK_SIZE = 8L * 1024 * 1024
        const val SHUTDOWN_TIMEOUT_MS = 2_000L
        const val FOLDER_PICKER_TIMEOUT_MINUTES = 5L

        /** Process-wide: the native engine can only be initialised once. */
        val engineClaimed = AtomicBoolean(false)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
