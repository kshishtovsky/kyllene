package dev.hermeskotlin.ui.split

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The split container: every part gets its share, the grip moves the line, a double tap puts it back. */
@OptIn(ExperimentalTestApi::class)
class SplitContainerTest {

    /** A 600×400 stage, the pane under test tagging itself so its bounds can be read. */
    private fun content(
        layout: SplitLayout,
        onResize: (String, Float) -> Unit = { _, _ -> },
        onReset: (String) -> Unit = {},
        pane: @Composable (SplitNode.Pane) -> Unit = { p ->
            Box(Modifier.fillMaxSize().testTag("pane-${p.id}"))
        },
    ): @Composable () -> Unit = {
        HermesTheme(ColorScheme.Dark) {
            Box(Modifier.size(600.dp, 400.dp).testTag("stage")) {
                SplitContainer(layout, onResize, onReset, Modifier.fillMaxSize(), pane)
            }
        }
    }

    @Test
    fun rowSplitsTheWidthInHalf() = runComposeUiTest {
        val layout = SplitLayout.single("p1").split("p1", SplitAxis.Row, "p2")!!
        setContent(content(layout))
        val first = onNodeWithTag("pane-p1").getUnclippedBoundsInRoot()
        val second = onNodeWithTag("pane-p2").getUnclippedBoundsInRoot()
        assertEquals(0f, first.left.value, 2f)
        assertEquals(300f, first.right.value, 2f)
        assertEquals(300f, second.left.value, 2f)
        assertEquals(600f, second.right.value, 2f)
        assertEquals(400f, (first.bottom - first.top).value, 2f)
        assertEquals(400f, (second.bottom - second.top).value, 2f)
    }

    @Test
    fun columnSplitsTheHeightInHalf() = runComposeUiTest {
        val layout = SplitLayout.single("p1").split("p1", SplitAxis.Column, "p2")!!
        setContent(content(layout))
        val first = onNodeWithTag("pane-p1").getUnclippedBoundsInRoot()
        val second = onNodeWithTag("pane-p2").getUnclippedBoundsInRoot()
        assertEquals(0f, first.top.value, 2f)
        assertEquals(200f, first.bottom.value, 2f)
        assertEquals(200f, second.top.value, 2f)
        assertEquals(400f, second.bottom.value, 2f)
        assertEquals(600f, (first.right - first.left).value, 2f)
        assertEquals(600f, (second.right - second.left).value, 2f)
    }

    @Test
    fun nestedBranchKeepsItsShareOfItsParent() = runComposeUiTest {
        val layout = SplitLayout.single("p1")
            .split("p1", SplitAxis.Row, "p2")!!
            .split("p2", SplitAxis.Column, "p3")!!
        setContent(content(layout))
        val first = onNodeWithTag("pane-p1").getUnclippedBoundsInRoot()
        val second = onNodeWithTag("pane-p2").getUnclippedBoundsInRoot()
        val third = onNodeWithTag("pane-p3").getUnclippedBoundsInRoot()
        // p1 is the left half; p2 and p3 share the right half, one above the other.
        assertEquals(300f, first.right.value, 2f)
        assertEquals(300f, second.left.value, 2f)
        assertEquals(600f, second.right.value, 2f)
        assertEquals(200f, second.bottom.value, 2f)
        assertEquals(200f, third.top.value, 2f)
        assertEquals(600f, third.right.value, 2f)
    }

    @Test
    fun draggingTheGripMovesTheLine() = runComposeUiTest {
        val layout = SplitLayout.single("p1").split("p1", SplitAxis.Row, "p2")!!
        val fractions = mutableListOf<Float>()
        setContent(content(layout, onResize = { _, f -> fractions += f }))
        onNodeWithTag("stage").performTouchInput {
            swipe(start = percentOffset(0.5f, 0.5f), end = percentOffset(0.75f, 0.5f), durationMillis = 200)
        }
        waitForIdle()
        assertTrue(fractions.isNotEmpty(), "A drag over the grip must report the new share")
        assertTrue(abs(fractions.last() - 0.5f) > 0.05f, "The share moved, but only as far as ${fractions.last()}")
    }

    @Test
    fun doubleTapOnTheGripResetsTheSplit() = runComposeUiTest {
        var reset: String? = null
        setContent(content(SplitLayout.single("p1").split("p1", SplitAxis.Row, "p2")!!, onReset = { reset = it }))
        onNodeWithTag("stage").performTouchInput { doubleClick(percentOffset(0.5f, 0.5f)) }
        waitForIdle()
        assertEquals("b:p2", reset)
    }

    @Test
    fun aNewShareDoesNotComposeAPaneAgain() = runComposeUiTest {
        var layout by mutableStateOf(SplitLayout.single("p1").split("p1", SplitAxis.Row, "p2")!!)
        val calls = mutableMapOf<String, Int>()
        setContent(
            content(
                layout,
                onResize = { id, f -> layout = layout.resize(id, f) },
                pane = { p ->
                    calls[p.id] = (calls[p.id] ?: 0) + 1
                    Box(Modifier.fillMaxSize().testTag("pane-${p.id}"))
                },
            ),
        )
        assertEquals(mapOf("p1" to 1, "p2" to 1), calls)
        onNodeWithTag("stage").performTouchInput {
            swipe(start = percentOffset(0.5f, 0.5f), end = percentOffset(0.7f, 0.5f), durationMillis = 200)
        }
        waitForIdle()
        val branch = layout.root as? SplitNode.Branch
        assertTrue(branch != null && branch.fraction != 0.5f, "The drag must have moved the share")
        assertEquals(mapOf("p1" to 1, "p2" to 1), calls, "A new share must not compose a chat again")
    }
}
