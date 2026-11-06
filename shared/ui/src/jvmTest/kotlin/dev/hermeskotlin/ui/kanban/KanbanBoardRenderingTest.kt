package dev.hermeskotlin.ui.kanban

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.composeunstyled.theme.ColorScheme
import dev.hermeskotlin.core.kanban.KanbanBoard
import dev.hermeskotlin.core.kanban.KanbanColumn
import dev.hermeskotlin.core.kanban.KanbanLinkCounts
import dev.hermeskotlin.core.kanban.KanbanProgress
import dev.hermeskotlin.core.kanban.KanbanState
import dev.hermeskotlin.core.kanban.KanbanTask
import dev.hermeskotlin.core.kanban.KanbanWarnings
import dev.hermeskotlin.designsystem.HermesTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The board the screen draws: eight lanes, a card with every badge, the empty board and the first load. */
@OptIn(ExperimentalTestApi::class)
class KanbanBoardRenderingTest {

    private val card = KanbanTask(
        id = "t_f9bdee25",
        title = "Fix the driveway",
        status = "blocked",
        assignee = "coder",
        priority = 8,
        tenant = "acme",
        commentCount = 4,
        linkCounts = KanbanLinkCounts(parents = 5, children = 1),
        progress = KanbanProgress(done = 3, total = 7),
        warnings = KanbanWarnings(count = 3),
        blockKind = "needs_input",
        blockRecurrences = 2,
    )

    private fun board(vararg tasks: KanbanTask) = KanbanBoard(
        columns = kanbanLanes.map { lane -> KanbanColumn(lane.first, tasks.filter { it.status == lane.first }) },
        assignees = listOf("coder", "reviewer"),
    )

    @Test
    fun allEightLanesRender() = runComposeUiTest {
        setContent { HermesTheme(ColorScheme.Dark) { KanbanBoardContent(KanbanState(board = board(card)), "Dev", desktop = true) } }
        for (label in listOf("Triage", "To Do", "Scheduled", "Ready", "Running", "Blocked", "Review", "Done")) {
            onNodeWithText(label).assertExists()
        }
    }

    @Test
    fun cardCarriesEveryBadge() = runComposeUiTest {
        setContent {
            HermesTheme(ColorScheme.Dark) { KanbanBoardContent(KanbanState(board = board(card)), "Dev", desktop = true) }
        }
        onNodeWithText("Fix the driveway").assertExists()
        onNodeWithText("t_f9bdee25").assertExists()
        onNodeWithText("acme").assertExists()
        onNodeWithText("3/7").assertExists()
        onNodeWithText("needs input").assertExists()
        onNodeWithText("×2").assertExists()
        onNodeWithContentDescription("Assignee coder").assertExists()
        onNodeWithContentDescription("Priority 8").assertExists()
        onNodeWithContentDescription("4 comments").assertExists()
        onNodeWithContentDescription("5 parents, 1 child").assertExists()
        onNodeWithContentDescription("3 warnings").assertExists()
    }

    @Test
    fun runningCardShowsItsClock() = runComposeUiTest {
        val running = card.copy(status = "running", blockKind = null, blockRecurrences = 0, currentRunStartedAt = 4_000)
        setContent {
            HermesTheme(ColorScheme.Dark) {
                // Not visible: the clock shows the server's reading and no tick loop runs inside the test clock.
                KanbanBoardContent(KanbanState(board = board(running).copy(now = 7_725)), "Dev", visible = false, desktop = true)
            }
        }
        onNodeWithText("1h 02m").assertExists()
    }

    @Test
    fun tappingACardOpensItsDetails() = runComposeUiTest {
        var opened: String? = null
        val todo = card.copy(status = "todo")
        setContent {
            HermesTheme(ColorScheme.Dark) {
                KanbanBoardContent(KanbanState(board = board(todo)), "Dev", desktop = true, onOpenTask = { opened = it })
            }
        }
        onNodeWithText("Fix the driveway").performClick()
        assertEquals("t_f9bdee25", opened)
    }

    @Test
    fun emptyBoardInvitesTheFirstTask() = runComposeUiTest {
        setContent { HermesTheme(ColorScheme.Dark) { KanbanBoardContent(KanbanState(board = board()), "Dev", desktop = true) } }
        onNodeWithText("No tasks").assertExists()
        onNodeWithText("New task").assertExists()
    }

    @Test
    fun firstLoadIsASkeleton() = runComposeUiTest {
        setContent { HermesTheme(ColorScheme.Dark) { KanbanBoardContent(KanbanState(board = KanbanBoard()), "Dev", desktop = true) } }
        onNodeWithTag("kanban-skeleton").assertExists()
    }

    @Test
    fun theSmallTextOnACard() {
        assertEquals("CO", initials("coder"))
        assertEquals("RA", initials("review agent"))
        assertEquals("?", initials(null))
        assertEquals("needs input", blockKindLabel("needs_input"))
        assertEquals(null, blockKindLabel(null))
        assertEquals("12s", formatRunClock(12))
        assertEquals("4m 07s", formatRunClock(247))
        assertEquals("1h 02m", formatRunClock(3_725))
        assertEquals("0s", formatRunClock(0))
    }
}
