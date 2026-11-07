package dev.hermeskotlin.ui.split

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.ResizeHorizontalPointer
import dev.hermeskotlin.designsystem.components.ResizeVerticalPointer
import dev.hermeskotlin.designsystem.stroke

/** What a finger or a mouse aims at; the line it draws is [SplitLine] of it. */
private val SplitGrip = 24.dp
private val SplitLine = 1.dp

/**
 * The split tree on screen: a branch is a row or a column of its two parts, a pane is [pane]. The grip between two
 * parts floats over them, so a 50/50 split really is half the width — it doesn't pay for the handle.
 */
@Composable
fun SplitContainer(
    layout: SplitLayout,
    onResize: (branchId: String, fraction: Float) -> Unit,
    onReset: (branchId: String) -> Unit,
    modifier: Modifier = Modifier,
    pane: @Composable (SplitNode.Pane) -> Unit,
) {
    Box(modifier.fillMaxSize()) { SplitNodeView(layout.root, onResize, onReset, pane) }
}

@Composable
private fun SplitNodeView(
    node: SplitNode,
    onResize: (branchId: String, fraction: Float) -> Unit,
    onReset: (branchId: String) -> Unit,
    pane: @Composable (SplitNode.Pane) -> Unit,
) {
    when (node) {
        // keyed by pane, so a new fraction re-lays the tree out without resetting the chat inside a pane.
        is SplitNode.Pane -> key(node.id) { pane(node) }
        is SplitNode.Branch -> SplitBranch(node, onResize, onReset, pane)
    }
}

@Composable
private fun SplitBranch(
    branch: SplitNode.Branch,
    onResize: (branchId: String, fraction: Float) -> Unit,
    onReset: (branchId: String) -> Unit,
    pane: @Composable (SplitNode.Pane) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val row = branch.axis == SplitAxis.Row
        if (row) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(branch.fraction).fillMaxHeight()) {
                    SplitNodeView(branch.first, onResize, onReset, pane)
                }
                Box(Modifier.weight(1f - branch.fraction).fillMaxHeight()) {
                    SplitNodeView(branch.second, onResize, onReset, pane)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(branch.fraction).fillMaxWidth()) {
                    SplitNodeView(branch.first, onResize, onReset, pane)
                }
                Box(Modifier.weight(1f - branch.fraction).fillMaxWidth()) {
                    SplitNodeView(branch.second, onResize, onReset, pane)
                }
            }
        }
        SplitGrip(branch, if (row) maxWidth else maxHeight, onResize, onReset)
    }
}

/** The line, the 24 dp strip around it, and what a drag and a double tap on it do. */
@Composable
private fun SplitGrip(
    branch: SplitNode.Branch,
    size: Dp,
    onResize: (branchId: String, fraction: Float) -> Unit,
    onReset: (branchId: String) -> Unit,
) {
    val row = branch.axis == SplitAxis.Row
    val fraction by rememberUpdatedState(branch.fraction)
    val sizePx by rememberUpdatedState(with(LocalDensity.current) { size.toPx() })
    val grab = if (row) Modifier.fillMaxHeight().width(SplitGrip).offset(x = size * fraction - SplitGrip / 2)
    else Modifier.fillMaxWidth().height(SplitGrip).offset(y = size * fraction - SplitGrip / 2)
    Box(
        grab
            .pointerHoverIcon(if (row) ResizeHorizontalPointer else ResizeVerticalPointer)
            .pointerInput(branch.id) { detectTapGestures(onDoubleTap = { onReset(branch.id) }) }
            .pointerInput(branch.id) {
                // Kept in the gesture, not in the composition: a drag emits one fraction per move without recomposing.
                var at = 0f
                detectDragGestures(
                    onDragStart = { at = fraction * sizePx },
                    onDrag = { change, drag ->
                        change.consume()
                        at += if (row) drag.x else drag.y
                        onResize(branch.id, at / sizePx)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (row) Modifier.fillMaxHeight().width(SplitLine) else Modifier.fillMaxWidth().height(SplitLine),
        ) {
            Box(Modifier.fillMaxSize().background(Theme[colors][stroke]))
        }
    }
}
