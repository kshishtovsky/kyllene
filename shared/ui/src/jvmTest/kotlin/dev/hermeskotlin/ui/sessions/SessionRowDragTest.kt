package dev.hermeskotlin.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.sessions.SessionSummary
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.ui.split.ChatDrag
import dev.hermeskotlin.ui.split.ChatDropTarget
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A row's hold: let go where it was, it opens its menu; moved, the chat comes out of the list instead. */
@OptIn(ExperimentalTestApi::class)
class SessionRowDragTest {

    private val session = SessionSummary(id = "s1", title = "A chat")

    @AfterTest
    fun clearDrag() {
        ChatDrag.cancel()
        ChatDrag.forget(PROBE)
    }

    private fun row(onClick: () -> Unit, onActions: () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        HermesTheme(ColorScheme.Dark) {
            Box(Modifier.size(320.dp, 60.dp)) {
                SessionRow(
                    session = session,
                    selected = false,
                    showSnippet = false,
                    onClick = onClick,
                    onActions = onActions,
                    modifier = Modifier.testTag("row"),
                )
            }
        }
    }

    @Test
    fun aHoldWithoutMovingOpensTheRowMenu() = runComposeUiTest {
        var opened = 0
        var actions = 0
        setContent(row(onClick = { opened++ }, onActions = { actions++ }))
        onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        waitForIdle()
        assertEquals(1, actions)
        assertEquals(0, opened)
        assertNull(ChatDrag.chat, "A hold that never moved must not leave a chat in the hand")
    }

    @Test
    fun aHoldThatMovesDragsTheChatAndOpensNoMenu() = runComposeUiTest {
        var opened = 0
        var actions = 0
        var dropped: String? = null
        // The pane the pointer lets go over: the whole screen, as a pane of the split screen would be.
        ChatDrag.hold(PROBE, ChatDropTarget({ Rect(0f, 0f, 2000f, 2000f) }) { held, _ -> dropped = held.chatId })
        setContent(row(onClick = { opened++ }, onActions = { actions++ }))
        onNodeWithTag("row").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 60f))
            up()
        }
        waitForIdle()
        assertEquals("s1", dropped, "The chat must land where it was let go")
        assertEquals(0, actions, "A hold that moved is a drag, not the row's menu")
        assertEquals(0, opened, "A tap inside the drag must not open the chat")
        assertNull(ChatDrag.chat)
    }

    private companion object {
        const val PROBE = "probe-pane"
    }
}
