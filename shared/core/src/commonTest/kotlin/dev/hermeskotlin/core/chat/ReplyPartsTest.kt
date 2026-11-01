package dev.hermeskotlin.core.chat

import dev.hermeskotlin.core.sessions.SessionMessage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** A reply keeps the order the model worked in: thought, calls, words, then round again. */
class ReplyPartsTest {

    private fun toolCall(id: String, name: String) = buildJsonArray {
        add(buildJsonObject { put("id", id); put("function", buildJsonObject { put("name", name) }) })
    }

    @Test
    fun storedStepsComeBackInTheOrderTheyWereWritten() {
        val messages = historyToMessages(
            listOf(
                SessionMessage(id = 1, role = "user", content = JsonPrimitive("tidy up")),
                // Round one: thought, ran a call, said something.
                SessionMessage(id = 2, role = "assistant", content = JsonPrimitive("Looking first."), reasoning = "I need the listing.", toolCalls = toolCall("c1", "terminal")),
                SessionMessage(id = 3, role = "tool", content = JsonPrimitive("a.txt"), toolCallId = "c1"),
                // Round two: two more calls, then the answer.
                SessionMessage(id = 4, role = "assistant", content = JsonPrimitive(""), toolCalls = toolCall("c2", "read_file")),
                SessionMessage(id = 5, role = "tool", content = JsonPrimitive("contents"), toolCallId = "c2"),
                SessionMessage(id = 6, role = "assistant", content = JsonPrimitive(""),
                    toolCalls = toolCall("c3", "terminal")),
                SessionMessage(id = 7, role = "tool", content = JsonPrimitive(""), toolCallId = "c3"),
                SessionMessage(id = 8, role = "assistant", content = JsonPrimitive("All done.")),
            ),
        )

        val reply = messages.last() as ChatMessage.Assistant
        assertEquals(
            listOf(
                ReplyPart.Reasoning("I need the listing."),
                ReplyPart.Text("Looking first."),
                ReplyPart.Tool("c1"),
                ReplyPart.Tool("c2"),
                ReplyPart.Tool("c3"),
                ReplyPart.Text("All done."),
            ),
            reply.blocks(),
        )
        // The three piles still hold the whole reply, for everything that reads them (copy, export, search).
        assertEquals("Looking first.\n\nAll done.", reply.text)
        assertEquals("I need the listing.", reply.reasoning)
        assertEquals(listOf("c1", "c2", "c3"), reply.tools.map { it.id })
    }

    @Test
    fun whatIsSaidBetweenCallsSplitsTheRun() {
        val reply = ChatMessage.Assistant(
            key = "live-1",
            text = "First.Second.",
            reasoning = "Thinking",
            tools = listOf(ToolActivity("t1", "terminal"), ToolActivity("t2", "read_file")),
            parts = listOf(
                ReplyPart.Reasoning("Thinking"),
                ReplyPart.Text("First."),
                ReplyPart.Tool("t1"),
                ReplyPart.Tool("t2"),
                ReplyPart.Text("Second."),
            ),
        )

        assertEquals(
            listOf(
                ReplyPart.Reasoning("Thinking"), ReplyPart.Text("First."), ReplyPart.Tool("t1"),
                ReplyPart.Tool("t2"), ReplyPart.Text("Second."),
            ),
            reply.blocks(),
        )
    }

    @Test
    fun aReplyWithoutPartsKeepsTheOldThreePiles() {
        val reply = ChatMessage.Assistant("a", text = "Done", reasoning = "Hmm", tools = listOf(ToolActivity("t1", "terminal")))
        assertEquals(
            listOf(ReplyPart.Reasoning("Hmm"), ReplyPart.Tool("t1"), ReplyPart.Text("Done")),
            reply.blocks(),
        )
    }

    @Test
    fun aCallThePartsDontNameStillShows() {
        // The live copy's steps were folded onto a reply whose parts came from stored rows: the later call has no
        // block of its own, and dropping it would hide work that was really done.
        val reply = ChatMessage.Assistant(
            key = "live-1",
            text = "Done",
            tools = listOf(ToolActivity("t1", "terminal"), ToolActivity("t2", "read_file")),
            parts = listOf(ReplyPart.Text("Done")),
        )
        assertEquals(listOf(ReplyPart.Text("Done"), ReplyPart.Tool("t1"), ReplyPart.Tool("t2")), reply.blocks())
    }

    @Test
    fun partsThatDontAddUpShowTheThreePilesRatherThanLessText() {
        // The gateway replaced the text whole; where it split we no longer know, so show all of it unordered.
        val reply = ChatMessage.Assistant(
            key = "live-1",
            text = "All of it, written since",
            tools = listOf(ToolActivity("t1", "terminal")),
            parts = listOf(ReplyPart.Text("Half"), ReplyPart.Tool("t1")),
        )
        assertEquals(
            listOf(ReplyPart.Tool("t1"), ReplyPart.Text("All of it, written since")),
            reply.blocks(),
        )
    }

    @Test
    fun deltasOfOneKindAreOneBlock() {
        val reply = ChatMessage.Assistant("live-1")
            .plusPart(ReplyPart.Reasoning("Th"))
            .plusPart(ReplyPart.Reasoning("inking"))
            .plusPart(ReplyPart.Text("Wri"))
            .plusPart(ReplyPart.Text("ting"))
        assertEquals(listOf(ReplyPart.Reasoning("Thinking"), ReplyPart.Text("Writing")), reply.parts)
    }

    @Test
    fun theSameCallAnnouncedTwiceIsOneBlock() {
        val reply = ChatMessage.Assistant("live-1").plusPart(ReplyPart.Tool("t1")).plusPart(ReplyPart.Tool("t1"))
        assertEquals(listOf(ReplyPart.Tool("t1")), reply.parts)
    }
}
