package dev.hermeskotlin.core.chat

/**
 * One block of a reply, in the order the model made it. A turn is a loop — think, run a call, say something, run
 * more calls, think again — and this is what keeps that loop's shape; [ChatMessage.Assistant.text], `reasoning` and
 * `tools` alone would only say what the reply holds, not in which order.
 */
sealed interface ReplyPart {
    /** What it thought before what comes next. */
    data class Reasoning(val text: String) : ReplyPart

    /** What it said. */
    data class Text(val text: String) : ReplyPart

    /** A call it ran; the call itself is in [ChatMessage.Assistant.tools], by [id]. */
    data class Tool(val id: String) : ReplyPart
}

/** [this] reply with one more block at its end. */
internal fun ChatMessage.Assistant.plusPart(part: ReplyPart): ChatMessage.Assistant =
    copy(parts = parts.plusPart(part))

/** [this] reply with more blocks at its end. */
internal fun ChatMessage.Assistant.plusParts(more: List<ReplyPart>): ChatMessage.Assistant =
    copy(parts = more.fold(parts) { parts, part -> parts.plusPart(part) })

/** [this] sequence with one more block at its end. */
internal fun List<ReplyPart>.plusPart(part: ReplyPart): List<ReplyPart> {
    val last = lastOrNull()
    return when {
        // Text and reasoning land a token at a time: one block, not one per delta.
        last is ReplyPart.Text && part is ReplyPart.Text -> dropLast(1) + ReplyPart.Text(last.text + part.text)
        last is ReplyPart.Reasoning && part is ReplyPart.Reasoning -> dropLast(1) + ReplyPart.Reasoning(last.text + part.text)
        // The same call announced twice (a start repeated, or folded from rows already read) is still one block.
        part is ReplyPart.Tool && any { it is ReplyPart.Tool && it.id == part.id } -> this
        else -> this + part
    }
}

/**
 * The reply's blocks in the order it made them. Anything [ChatMessage.Assistant.parts] doesn't account for goes
 * last, so nothing is lost: a call with no block of its own, or text written without one.
 *
 * A reply whose parts no longer add up to its text (its text was replaced whole by the gateway, say) is shown as the
 * three piles instead — the old shape, but all of it. Better an ordered-looking reply than a truncated one.
 */
fun ChatMessage.Assistant.blocks(): List<ReplyPart> {
    val parts = parts
    if (parts.isEmpty() || !parts.agreeWith(text, reasoning)) {
        return buildList {
            reasoning.takeIf { it.isNotBlank() }?.let { add(ReplyPart.Reasoning(it)) }
            tools.forEach { add(ReplyPart.Tool(it.id)) }
            text.takeIf { it.isNotBlank() }?.let { add(ReplyPart.Text(it)) }
        }
    }
    val known = parts.filter { it !is ReplyPart.Tool || tools.any { tool -> tool.id == it.id } }.toMutableList()
    tools.filter { tool -> known.none { it is ReplyPart.Tool && it.id == tool.id } }.forEach { known += ReplyPart.Tool(it.id) }
    if (known.none { it is ReplyPart.Text } && text.isNotBlank()) known += ReplyPart.Text(text)
    if (known.none { it is ReplyPart.Reasoning } && reasoning.isNotBlank()) known += ReplyPart.Reasoning(reasoning)
    return known
}

/** Whether the blocks carry all of [text] and [reasoning]. Steps are joined with blank lines, so spacing is free. */
private fun List<ReplyPart>.agreeWith(text: String, reasoning: String): Boolean {
    val said = filterIsInstance<ReplyPart.Text>().joinToString("") { it.text }
    val thought = filterIsInstance<ReplyPart.Reasoning>().joinToString("") { it.text }
    return said.withoutSpaces() == text.withoutSpaces() && thought.withoutSpaces() == reasoning.withoutSpaces()
}

private fun String.withoutSpaces() = filterNot { it.isWhitespace() }
