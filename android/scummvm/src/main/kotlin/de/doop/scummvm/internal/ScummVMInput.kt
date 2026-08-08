package de.doop.scummvm.internal

import android.content.Context
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import de.doop.scummvm.ScummVMTouchMode
import org.scummvm.scummvm.ScummVM

/**
 * Translates Android input into the engine's `pushEvent` / `updateTouch` protocol.
 *
 * A port of the parts of upstream's `ScummVMEvents` that do not depend on
 * `ScummVMActivity`. Two deliberate simplifications relative to upstream:
 *
 *  * `MouseHelper`'s hover/mouse-capture handling is not reproduced; physical
 *    mice arrive as ordinary pointer events instead.
 *  * The multi-touch recogniser is a compact reimplementation. It still reports
 *    the two- and three-finger gestures the engine maps to right and middle
 *    click, but it skips upstream's delayed "is this going to become a third
 *    finger / a scroll?" arbitration, so a two-finger gesture is reported as
 *    soon as the second finger lands.
 */
internal class ScummVMInput(
    context: Context,
    private val engine: ScummVM,
) : View.OnTouchListener, View.OnKeyListener, GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener {

    // Event type ids shared with backends/platform/android/events.cpp.
    private companion object {
        const val JE_SYS_KEY = 0
        const val JE_KEY = 1
        const val JE_DOWN = 3
        const val JE_SCROLL = 4
        const val JE_TAP = 5
        const val JE_DOUBLE_TAP = 6
        const val JE_MULTI = 7
        const val JE_GAMEPAD = 14
        const val JE_JOYSTICK = 15
        const val JE_QUIT = 0x1000

        const val JACTION_DOWN = 0
        const val JACTION_MOVE = 1
        const val JACTION_UP = 2
        const val JACTION_CANCEL = 3

        const val JOYSTICK_AXIS_MAX = 32767
        const val JOYSTICK_AXIS_HAT_SCALE = 0.66f

        const val JOYSTICK_AXIS_X_BF = 0x01
        const val JOYSTICK_AXIS_Y_BF = 0x02
        const val JOYSTICK_AXIS_HAT_X_BF = 0x04
        const val JOYSTICK_AXIS_HAT_Y_BF = 0x08
        const val JOYSTICK_AXIS_Z_BF = 0x10
        const val JOYSTICK_AXIS_RZ_BF = 0x20
        const val JOYSTICK_AXIS_LTRIGGER_BF = 0x40
        const val JOYSTICK_AXIS_RTRIGGER_BF = 0x80
    }

    private val gestureDetector = GestureDetector(context, this).also {
        it.setOnDoubleTapListener(this)
    }

    /** Set while a two-or-three finger gesture owns the stream of events. */
    private var multitouchActive = false
    private var doubleTapMode = false

    @Volatile
    var touchMode: ScummVMTouchMode = ScummVMTouchMode.Touchpad
        private set

    fun setTouchMode(mode: ScummVMTouchMode) {
        val previous = touchMode
        if (previous == mode) return
        if (previous == ScummVMTouchMode.Gamepad) {
            // Drop any fingers the engine still believes are down.
            engine.updateTouch(JACTION_CANCEL, 0, 0, 0)
        }
        touchMode = mode
        engine.setupTouchMode(previous.nativeValue, mode.nativeValue)
    }

    fun sendQuit() {
        engine.pushEvent(JE_QUIT, 0, 0, 0, 0, 0, 0)
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (touchMode == ScummVMTouchMode.Gamepad) {
            forwardGamepadTouch(event)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            view.performClick()
        }

        if (handleMultitouch(event)) return true
        return gestureDetector.onTouchEvent(event)
    }

    private fun forwardGamepadTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                engine.updateTouch(
                    JACTION_DOWN,
                    event.getPointerId(index),
                    event.getX(index).toInt(),
                    event.getY(index).toInt(),
                )
                forwardAllPointers(event)
            }

            MotionEvent.ACTION_MOVE -> forwardAllPointers(event)

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                engine.updateTouch(
                    JACTION_UP,
                    event.getPointerId(index),
                    event.getX(index).toInt(),
                    event.getY(index).toInt(),
                )
            }

            MotionEvent.ACTION_CANCEL -> engine.updateTouch(JACTION_CANCEL, 0, 0, 0)
        }
    }

    private fun forwardAllPointers(event: MotionEvent) {
        for (index in 0 until event.pointerCount) {
            engine.updateTouch(
                JACTION_MOVE,
                event.getPointerId(index),
                event.getX(index).toInt(),
                event.getY(index).toInt(),
            )
        }
    }

    /** @return true when the event belongs to a multi-finger gesture. */
    private fun handleMultitouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multitouchActive = false
                return false
            }

            MotionEvent.ACTION_CANCEL -> {
                val wasActive = multitouchActive
                multitouchActive = false
                return wasActive
            }
        }

        val fingers = event.pointerCount
        if (fingers > 3) {
            // Upstream ignores anything past three fingers but keeps owning the
            // gesture so it does not leak into the single-touch path.
            multitouchActive = true
            return true
        }

        if (fingers > 1) multitouchActive = true
        if (!multitouchActive) return false

        if (fingers == 1) {
            // Tail of a multi-finger gesture: swallow until every finger is up.
            if (event.actionMasked == MotionEvent.ACTION_UP) multitouchActive = false
            return true
        }

        // arg1 = fingers down, arg2 = the raw action (events.cpp needs the
        // pointer-index bits), arg3/arg4 = the last finger to go down.
        val index = (fingers - 1).coerceIn(0, event.pointerCount - 1)
        engine.pushEvent(
            JE_MULTI,
            fingers,
            event.action,
            event.getX(index).toInt(),
            event.getY(index).toInt(),
            0,
            0,
        )
        return true
    }

    // ------------------------------------------------------------------
    // GestureDetector.OnGestureListener
    // ------------------------------------------------------------------

    override fun onDown(e: MotionEvent): Boolean {
        engine.pushEvent(JE_DOWN, e.x.toInt(), e.y.toInt(), 0, 0, 0, 0)
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float,
    ): Boolean {
        if (e1 == null) return true
        engine.pushEvent(
            JE_SCROLL,
            e1.x.toInt(),
            e1.y.toInt(),
            e2.x.toInt(),
            e2.y.toInt(),
            distanceX.toInt(),
            distanceY.toInt(),
        )
        return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        // arg3 carries the press duration; the engine derives right/middle
        // clicks from how long the tap was held.
        engine.pushEvent(JE_TAP, e.x.toInt(), e.y.toInt(), (e.eventTime - e.downTime).toInt(), 0, 0, 0)
        return true
    }

    override fun onShowPress(e: MotionEvent) = Unit

    override fun onLongPress(e: MotionEvent) = Unit // Interferes with drag & drop.

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float) = true

    // ------------------------------------------------------------------
    // GestureDetector.OnDoubleTapListener
    // ------------------------------------------------------------------

    override fun onDoubleTap(e: MotionEvent): Boolean {
        doubleTapMode = true
        return true
    }

    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) doubleTapMode = false
        engine.pushEvent(JE_DOUBLE_TAP, e.x.toInt(), e.y.toInt(), e.action, 0, 0, 0)
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent) = true

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    override fun onKey(view: View, keyCode: Int, event: KeyEvent): Boolean {
        // Undocumented code emitted around ACTION_HOVER_ENTER/EXIT.
        if (keyCode == 238) return false

        val unicodeChar = if (event.deviceId != 0) {
            KeyCharacterMap.load(event.deviceId).get(event.keyCode, event.metaState)
        } else {
            event.unicodeChar
        }

        val type = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            ->
                // Soft-keyboard arrows are text navigation; a real D-pad is a
                // game controller and goes through ScummVM's keymapper.
                if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == KeyEvent.FLAG_SOFT_KEYBOARD) {
                    JE_KEY
                } else {
                    JE_GAMEPAD
                }

            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_Z,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            -> JE_GAMEPAD

            // Reported with SOURCE_KEYBOARD by some sticks, so classify by code.
            KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_4,
            -> JE_JOYSTICK

            else -> if (event.isSystem) JE_SYS_KEY else JE_KEY
        }

        engine.pushEvent(
            type,
            event.action,
            keyCode,
            unicodeChar and KeyCharacterMap.COMBINING_ACCENT_MASK,
            event.metaState,
            event.repeatCount,
            (event.eventTime - event.downTime).toInt(),
        )
        return true
    }

    // ------------------------------------------------------------------
    // Game controller axes
    // ------------------------------------------------------------------

    /**
     * Axes forwarded to the engine, in the bit order `events.cpp` expects:
     * left stick, hat, right stick, triggers.
     */
    private val joystickAxes = listOf(
        Triple(MotionEvent.AXIS_X, JOYSTICK_AXIS_X_BF, 1.0f),
        Triple(MotionEvent.AXIS_Y, JOYSTICK_AXIS_Y_BF, 1.0f),
        // The hat is digital. At full scale it reads as a complete press and the
        // keymapper swallows it; upstream scales to 2/3 for the same reason.
        Triple(MotionEvent.AXIS_HAT_X, JOYSTICK_AXIS_HAT_X_BF, JOYSTICK_AXIS_HAT_SCALE),
        Triple(MotionEvent.AXIS_HAT_Y, JOYSTICK_AXIS_HAT_Y_BF, JOYSTICK_AXIS_HAT_SCALE),
        Triple(MotionEvent.AXIS_Z, JOYSTICK_AXIS_Z_BF, 1.0f),
        Triple(MotionEvent.AXIS_RZ, JOYSTICK_AXIS_RZ_BF, 1.0f),
        Triple(MotionEvent.AXIS_LTRIGGER, JOYSTICK_AXIS_LTRIGGER_BF, 1.0f),
        Triple(MotionEvent.AXIS_RTRIGGER, JOYSTICK_AXIS_RTRIGGER_BF, 1.0f),
    )

    fun onGenericMotion(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return false

        val device = event.device
        for ((axisId, bitFlag, scale) in joystickAxes) {
            val value = centeredAxisValue(event, device, axisId)
            // events.cpp switches on arg4, so exactly one axis flag per event.
            engine.pushEvent(
                JE_JOYSTICK,
                MotionEvent.ACTION_MOVE,
                (value * JOYSTICK_AXIS_MAX * scale).toInt(),
                0,
                bitFlag,
                0,
                0,
            )
        }
        return true
    }

    /**
     * Reads an axis with the device's own dead zone applied -- a stick at rest
     * rarely reports exactly 0, and without this the cursor drifts.
     */
    private fun centeredAxisValue(event: MotionEvent, device: InputDevice?, axisId: Int): Float {
        val range = device?.getMotionRange(axisId, event.source) ?: return 0.0f
        val value = event.getAxisValue(range.axis, event.actionIndex)
        return if (kotlin.math.abs(value) > range.flat) value else 0.0f
    }
}
