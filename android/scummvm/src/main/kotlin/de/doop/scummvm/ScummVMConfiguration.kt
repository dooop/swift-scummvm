package de.doop.scummvm

import androidx.compose.runtime.Immutable
import java.io.File

/**
 * Start-up options for the embedded engine.
 *
 * These are read once, when the engine thread is launched; changing them
 * afterwards has no effect (see [ScummVMEngine] for why the engine cannot be
 * restarted in-process).
 */
@Immutable
data class ScummVMConfiguration(
    /**
     * A configured game target to launch straight away, e.g. `"monkey"`.
     * `null` opens ScummVM's own launcher UI.
     */
    val target: String? = null,

    /**
     * Directory the launcher's "Add Game" browser opens at. Only applied when
     * `scummvm.ini` is created for the first time, so a user's own choice is
     * never overwritten.
     */
    val gamesDirectory: File? = null,

    /** Extra command line arguments appended after the ones derived above. */
    val extraArguments: List<String> = emptyList(),
)

/** Lifecycle of the embedded engine. */
sealed interface ScummVMState {
    /** Nothing has been started yet. */
    data object Idle : ScummVMState

    /** Runtime data (themes, engine data) is being unpacked from the AAR assets. */
    data object PreparingData : ScummVMState

    /** Waiting for the surface, or already rendering. */
    data object Running : ScummVMState

    /** The engine's `main()` returned. The process cannot host another run. */
    data class Stopped(val exitCode: Int) : ScummVMState

    /** Start-up failed before or during the engine run. */
    data class Failed(val cause: Throwable) : ScummVMState
}

/**
 * How touch input is translated for the engine. Mirrors the `TOUCH_MODE_*`
 * constants the native backend expects.
 */
enum class ScummVMTouchMode(internal val nativeValue: Int) {
    /** Relative cursor movement, tap to click. The ScummVM default. */
    Touchpad(0),

    /** The cursor follows the finger directly. */
    DirectMouse(1),

    /** Raw multi-touch, forwarded to the engine's on-screen gamepad. */
    Gamepad(2),
    ;

    internal companion object {
        fun fromNative(value: Int): ScummVMTouchMode =
            entries.firstOrNull { it.nativeValue == value } ?: Touchpad
    }
}
