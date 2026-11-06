package dev.hermeskotlin.ui.kanban

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.hermeskotlin.core.kanban.KanbanLive

/**
 * Runs [live] only while the board is on screen and the app is in front: Android's `ON_STOP` closes the
 * socket's connection, coming back opens one at the board's cursor.
 */
@Composable
fun KanbanLiveEffect(live: KanbanLive) {
    val started = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(live, started) {
        if (started) live.start() else live.stop()
        onDispose { if (started) live.stop() }
    }
}
