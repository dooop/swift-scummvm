package de.doop.scummvm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * Runs ScummVM inside a composable.
 *
 * The engine starts as soon as the surface enters the window and is asked to
 * quit when this composable leaves the composition -- the same start-on-appear /
 * stop-on-disappear contract the SwiftUI wrapper in this repository uses.
 *
 * ```kotlin
 * ScummVM(
 *     modifier = Modifier.fillMaxSize(),
 *     configuration = ScummVMConfiguration(target = "monkey"),
 * )
 * ```
 *
 * Because the native engine is a process-wide singleton it can only run once
 * per process: after this composable is disposed, showing it again reports
 * [ScummVMState.Failed]. If your app needs to return to ScummVM repeatedly,
 * keep this composable in the composition rather than tearing it down.
 *
 * @param onExit invoked when the engine's `main()` returns, with its exit code.
 */
@Composable
fun ScummVM(
    modifier: Modifier = Modifier,
    configuration: ScummVMConfiguration = ScummVMConfiguration(),
    engine: ScummVMEngine = rememberScummVMEngine(configuration),
    onExit: (Int) -> Unit = {},
) {
    ScummVMView(engine = engine, modifier = modifier)

    val state by engine.state.collectAsState()
    LaunchedEffect(state) {
        (state as? ScummVMState.Stopped)?.let { onExit(it.exitCode) }
    }

    DisposableEffect(engine) {
        onDispose { engine.stop() }
    }
}

/** Convenience accessor for [ScummVMEngine.state] in a composition. */
@Composable
fun ScummVMEngine.stateAsState(): State<ScummVMState> = state.collectAsState()
