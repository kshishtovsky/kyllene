package dev.hermeskotlin.ui.split

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitLayoutTest {

    /** The saver only asks whether a value may be written; a `String` always may. */
    private object AnyScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    private fun save(layout: SplitLayout): String = with(SplitLayout.Saver) { AnyScope.save(layout) }!!

    private fun restore(text: String): SplitLayout? = SplitLayout.Saver.restore(text)

    private fun chat(session: String) = PaneChat(session, "chat $session", "dev")

    private fun twoPanes() = SplitLayout.single("p1", chat("s1")).split("p1", SplitAxis.Row, "p2")!!

    private fun threePanes() = twoPanes().split("p2", SplitAxis.Column, "p3")!!

    private fun fraction(layout: SplitLayout) = (layout.root as SplitNode.Branch).fraction

    private val SplitLayout.branchId get() = (root as SplitNode.Branch).id

    @Test
    fun splittingTheOnlyPaneGivesTwoAndFocusesTheNewOne() {
        val split = SplitLayout.single("p1", chat("s1")).split("p1", SplitAxis.Row, "p2")

        assertNotNull(split)
        assertEquals(2, split.count)
        assertEquals(listOf("p1", "p2"), split.leaves.map { it.id })
        assertEquals("p2", split.focused)
        assertEquals(chat("s1"), split.leaves[0].chat)
        // The new pane starts empty but keeps the profile the other pane talks to.
        assertEquals(PaneChat(null, null, "dev"), split.leaves[1].chat)
        assertEquals(0.5f, fraction(split))
    }

    @Test
    fun fourPanesAreTheMostAndTheFifthSplitIsRefused() {
        val two = twoPanes()
        assertEquals(2, two.count)

        val three = two.split("p2", SplitAxis.Row, "p3")
        assertNotNull(three)
        assertEquals(3, three.count)

        val four = three.split("p3", SplitAxis.Row, "p4")
        assertNotNull(four)
        assertEquals(4, four.count)
        assertEquals(MAX_PANES, four.count)

        assertNull(four.split("p4", SplitAxis.Row, "p5"))
    }

    @Test
    fun aPartThatWouldFallUnderTheMinimumIsNotSplit() {
        val layout = SplitLayout.single("p1", chat("s1"))

        // 700 dp gives two parts of 350 dp, 400 dp would give 200 dp under the 240 dp minimum.
        assertNotNull(layout.split("p1", SplitAxis.Column, "p2", paneDp = 700f, minPaneDp = MIN_PANE_WIDTH_DP))
        assertNull(layout.split("p1", SplitAxis.Column, "p2", paneDp = 400f, minPaneDp = MIN_PANE_WIDTH_DP))
        assertEquals(1, layout.count)
    }

    @Test
    fun closingOnePaneOfThreeCollapsesItsBranch() {
        val three = threePanes()
        assertEquals(listOf("p1", "p2", "p3"), three.leaves.map { it.id })
        assertEquals("p3", three.focused)

        val after = three.close("p3")

        assertEquals(2, after.count)
        assertEquals(listOf("p1", "p2"), after.leaves.map { it.id })
        assertEquals("p2", after.focused)
        // The branch that held p2 and p3 is gone: its parent took p2, so no branch has a single child.
        val root = after.root as SplitNode.Branch
        assertEquals(SplitAxis.Row, root.axis)
        assertEquals("p1", (root.first as SplitNode.Pane).id)
        assertEquals("p2", (root.second as SplitNode.Pane).id)
    }

    @Test
    fun closingKeepsTheFocusWhenAnotherPaneHasIt() {
        val three = threePanes().focus("p1")

        val after = three.close("p3")

        assertEquals("p1", after.focused)
        assertEquals(2, after.count)
    }

    @Test
    fun theLastPaneCannotBeClosed() {
        val single = SplitLayout.single("p1", chat("s1"))

        assertEquals(single, single.close("p1"))
        assertEquals(1, single.close("p1").count)
    }

    @Test
    fun resizingStopsAtThePanesMinimumOnScreen() {
        val layout = twoPanes()
        val branch = layout.branchId

        // 1000 dp wide with a 240 dp minimum: a part is never under 0.24 or over 0.76 of the width.
        assertEquals(0.24f, fraction(layout.resize(branch, 0.05f, containerDp = 1000f, minPaneDp = 240f)))
        assertEquals(0.76f, fraction(layout.resize(branch, 0.98f, containerDp = 1000f, minPaneDp = 240f)))
        assertEquals(0.4f, fraction(layout.resize(branch, 0.4f, containerDp = 1000f, minPaneDp = 240f)))
        // A container too small for two minimum panes goes down the middle rather than under the minimum.
        assertEquals(0.5f, fraction(layout.resize(branch, 0.1f, containerDp = 300f, minPaneDp = 240f)))
    }

    @Test
    fun resizingWithoutAContainerSizeOnlyHonoursTheShareLimits() {
        val layout = twoPanes()
        val branch = layout.branchId

        assertEquals(MIN_FRACTION, fraction(layout.resize(branch, 0f)))
        assertEquals(MIN_FRACTION, fraction(layout.resize(branch, 0.01f)))
        assertEquals(MAX_FRACTION, fraction(layout.resize(branch, 1f)))
        assertEquals(MAX_FRACTION, fraction(layout.resize(branch, 0.99f)))
        assertEquals(0.3f, fraction(layout.resize(branch, 0.3f)))
        assertEquals(0.5f, fraction(layout.resize("nope", 0.3f)))
        // A container measured at nothing is as good as none.
        assertEquals(MAX_FRACTION, fraction(layout.resize(branch, 0.99f, containerDp = 0f)))
    }

    @Test
    fun theSaverRoundTripsTwoThreeAndFourPanes() {
        // A single empty pane is what a fresh screen has.
        assertRoundTrip(SplitLayout.single("p1"))

        // The first pane carries text with the saver's own delimiters in it.
        val two = SplitLayout.single("p1", PaneChat("s/1,2", "t(1)|x:y", "dev:main"))
            .split("p1", SplitAxis.Row, "p2")!!
            .resize("b:p2", 0.30000001f)
        assertEquals(0.3f, fraction(two), "a share is kept to three places, so the saver brings it back exactly")
        assertRoundTrip(two)

        val three = two.split("p2", SplitAxis.Column, "p3")!!.resize("b:p3", 0.7f)
        assertEquals(3, three.count)
        assertRoundTrip(three)

        val four = three.split("p3", SplitAxis.Row, "p4")!!.resize("b:p4", 0.15f)
        assertEquals(4, four.count)
        assertTrue(four.leaves.any { it.chat == PaneChat(null, null, "dev:main") })
        assertRoundTrip(four)
    }

    private fun assertRoundTrip(layout: SplitLayout) {
        val text = save(layout)
        assertEquals(layout, restore(text))
        assertEquals(text, save(restore(text)!!))
    }

    @Test
    fun theSaverRefusesGarbageInsteadOfThrowing() {
        assertNull(restore(""))
        assertNull(restore("nonsense"))
        assertNull(restore("p1|Q:p1///"))
        assertNull(restore("p1|R0.5(P:p1///,P:p2///"))        // no closing bracket
        assertNull(restore("p1|R0.5(P:p1///,P:p2///) junk"))  // trailing rubbish
        assertNull(restore("p1|P:%zz///"))                    // broken escape
        assertNull(restore("p1|Rbad(P:p1///,P:p2///)"))       // a share that is not a number
        assertNull(restore("p1|R0.5(P:p1///,P:p2///,"))       // a branch that lost its second pane
        assertNull(restore("p1|R(P:p1///,P:p2///)"))          // a branch without its own id
        assertNull(restore("p1|P:///"))                       // a pane without its own id
        assertNull(restore("p1|P:p1//"))                      // a pane with too few fields
        assertNull(restore("p1|Pp1///"))                      // a pane without its colon
        assertNull(restore("p1|R:(P:p1///,P:p2///)"))         // a branch with an empty id
        assertNull(restore("p1|P:p1///%"))                    // a cut-off escape
        // More panes than the model allows never comes back.
        assertNull(restore("p1|R0.5(R0.5(R0.5(P:p1///,P:p2///),P:p3///),R0.5(P:p4///,P:p5///))"))
    }

    @Test
    fun aRestoredLayoutFocusesAnExistingPane() {
        // The saved focus points at a pane the tree no longer has.
        assertEquals("p2", restore("p1|P:p2/dev/s2/chat s2")?.focused)
    }

    @Test
    fun splitLeavesTheOriginalTreeAlone() {
        val before = SplitLayout.single("p1", chat("s1"))
        val untouched = SplitLayout.single("p1", chat("s1"))

        before.split("p1", SplitAxis.Row, "p2")
        before.resize("b:p2", 0.2f)
        before.close("p1")

        assertEquals(untouched, before)
        assertEquals(1, before.count)
        assertEquals("p1", before.focused)
    }

    @Test
    fun operationsOnPaneIdsThatAreNotThereChangeNothing() {
        val layout = threePanes()

        assertEquals(layout, layout.focus("nope"))
        assertEquals(layout, layout.close("nope"))
        assertEquals(layout, layout.replace("nope", chat("x")))
        assertNull(layout.split("nope", SplitAxis.Row, "p9"))
    }

    @Test
    fun replaceAndFocusTouchOnePane() {
        val layout = threePanes().focus("p1")

        val swapped = layout.replace("p2", PaneChat("s9", null, null))

        assertEquals(PaneChat("s9", null, null), swapped.leaves[1].chat)
        assertEquals("p1", swapped.focused)
        assertEquals(chat("s1"), swapped.leaves[0].chat)
    }
}
