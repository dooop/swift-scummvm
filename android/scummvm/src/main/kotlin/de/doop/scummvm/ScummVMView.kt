package de.doop.scummvm

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.text.InputType
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.doop.scummvm.internal.ScummVMInput

/**
 * The surface the engine renders into.
 *
 * A plain [SurfaceView]; the engine drives EGL itself from
 * `ScummVM.java`'s holder callbacks. The `onCreateInputConnection` override is
 * the standard "give me raw key events" trick, so a soft keyboard reaches the
 * engine at all. Upstream's `EditableSurfaceView` goes considerably further to
 * work around bugs in specific Latin IMEs; that is not reproduced here.
 */
internal class ScummVMSurfaceView(context: Context) : SurfaceView(context) {

    internal var input: ScummVMInput? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // The engine picks its own EGL config; RGBA_8888 is what every device
        // from API 17 on reports anyway.
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL makes the IME fall back to dispatching key events, which is
        // the only thing the engine understands.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return BaseInputConnection(this, false)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (input?.onGenericMotion(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    // The touch listener is what actually handles taps; this exists so the
    // accessibility tooling (and lint) stay happy.
    override fun performClick(): Boolean = super.performClick()
}

/**
 * Hosts [engine]'s rendering surface and forwards input to it.
 *
 * This is the low-level building block: it starts the engine when the surface
 * enters the window and pauses it with the host lifecycle, but never stops it.
 * Use [ScummVM] unless you need that control.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ScummVMView(
    engine: ScummVMEngine,
    modifier: Modifier = Modifier,
) {
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        engine.deliverPickedFolder(uri)
    }

    DisposableEffect(engine) {
        // Lets the engine's "add game folder" flow reach the system picker.
        engine.folderPickerLauncher = { initialUri -> folderPicker.launch(initialUri) }
        onDispose { engine.folderPickerLauncher = null }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> engine.setPaused(false)
                Lifecycle.Event.ON_PAUSE -> engine.setPaused(true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ScummVMSurfaceView(context).apply {
                // Deferred to window attach: the engine registers its system
                // inset listener on the root view, which does not exist yet
                // while the factory runs.
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            engine.attach(this@apply)
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    },
                )
            }
        },
    )
}

/** Remembers a [ScummVMEngine] bound to the current composition. */
@Composable
fun rememberScummVMEngine(
    configuration: ScummVMConfiguration = ScummVMConfiguration(),
): ScummVMEngine {
    val context = LocalContext.current
    return remember(configuration) { ScummVMEngine(context, configuration) }
}
