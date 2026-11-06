package dev.hermeskotlin.ui.kanban

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.kanban.KanbanBoard
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanState
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.settings.DarkStyle
import dev.hermeskotlin.designsystem.HermesTheme
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.ui.darkScheme
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The board in every dark palette the settings offer: it draws, its labels are there, and they read on its ground. */
@OptIn(ExperimentalTestApi::class)
class KanbanBoardThemeTest {

    private val card = KanbanTask(id = "t_1", title = "Fix the driveway", status = "todo", assignee = "coder", priority = 8)

    private val board = KanbanBoard(
        columns = kanbanLanes.map { lane -> KanbanColumn(lane.first, listOfNotNull(card.takeIf { it.status == lane.first })) },
    )

    @Test
    fun theBoardDrawsInEveryDarkStyle() {
        val ratios = mutableMapOf<DarkStyle, Double>()
        for (style in DarkStyle.entries) {
            runComposeUiTest {
                var ground = Color.Unspecified
                var laneText = Color.Unspecified
                setContent {
                    HermesTheme(darkScheme(style)) {
                        ground = Theme[colors][background]
                        // What a lane's label is drawn in: the quietest text the board paints a whole line with.
                        laneText = Theme[colors][textSecondary]
                        KanbanBoardContent(KanbanState(board = board), "Dev", desktop = true)
                    }
                }
                for (label in listOf("Triage", "To Do", "Scheduled", "Ready", "Running", "Blocked", "Review", "Done")) {
                    onNodeWithText(label).assertExists()
                }
                onNodeWithText("Fix the driveway").assertExists()
                ratios[style] = contrast(laneText, ground)
            }
        }
        // WCAG AA for normal text: a lane label is 13 sp, so anything under 4.5 is unreadable on a bad screen.
        for ((style, ratio) in ratios) {
            assertTrue(ratio >= 4.5, "$style draws its lane labels at ${"%.2f".format(ratio)}:1 on the board's ground")
        }
        assertEquals(DarkStyle.entries.size, ratios.size)
    }

    private fun contrast(foreground: Color, background: Color): Double {
        val a = foreground.luminance() + 0.05
        val b = background.luminance() + 0.05
        return max(a, b) / min(a, b)
    }
}
