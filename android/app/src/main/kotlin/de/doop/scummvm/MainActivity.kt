package de.doop.scummvm

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.doop.scummvm.app.BuildConfig

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val engine = rememberScummVMEngine()
            val state by engine.stateAsState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                ScummVM(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    onExit = { finishAndRemoveTask() },
                )

                when (val current = state) {
                    ScummVMState.Idle,
                    ScummVMState.PreparingData,
                    -> Status("Preparing ScummVM (${BuildConfig.ENGINE_SOURCE})...")

                    is ScummVMState.Failed -> Status(
                        text = current.cause.message ?: "ScummVM failed to start",
                        color = Color(0xFFFF8A80),
                    )

                    ScummVMState.Running,
                    is ScummVMState.Stopped,
                    -> Unit
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.BoxScope.Status(
    text: String,
    color: Color = Color.White,
) {
    BasicText(
        text = text,
        modifier = Modifier
            .align(Alignment.TopStart)
            .background(Color(0xAA000000))
            .padding(12.dp),
        style = androidx.compose.ui.text.TextStyle(color = color),
    )
}
