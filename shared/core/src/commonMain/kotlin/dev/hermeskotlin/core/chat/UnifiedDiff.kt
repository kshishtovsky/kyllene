package dev.hermeskotlin.core.chat

/** What a diff line is. */
enum class DiffLineKind {
    /** Unchanged, shown for context. */
    Context,
    Added,
    Removed,

    /** A hunk's `@@ … @@` header. */
    Hunk,

    /** Anything else a unified diff carries: file headers, index lines, "\ No newline at end of file". */
    Note,
}

/** One line of a diff: its numbers on each side (null where it doesn't exist) and the text without its sign. */
data class DiffLine(val kind: DiffLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String)

/** One file's changes: [path] as the new side names it (the old one when the file was only deleted). */
data class DiffFile(val path: String, val lines: List<DiffLine>) {
    val added: Int get() = lines.count { it.kind == DiffLineKind.Added }
    val removed: Int get() = lines.count { it.kind == DiffLineKind.Removed }
}

/**
 * Reads the unified diff a file tool reports (`inline_diff`, `patch`'s `diff`) into lines that know their place:
 * the sign is dropped and each line carries the number it has on either side, which is what a GitHub-style view
 * needs to show two gutters.
 *
 * Tolerant on purpose — the gateway's diffs come from several tools — so a line it doesn't recognise is kept as a
 * [DiffLineKind.Note] rather than dropped, and a diff with no file header still reads as one file.
 */
fun parseUnifiedDiff(diff: String): List<DiffFile> {
    val files = mutableListOf<DiffFile>()
    var path = ""
    var oldPath = ""
    var lines = mutableListOf<DiffLine>()
    // Where the next line lands on each side; a hunk header resets them.
    var oldAt = 0
    var newAt = 0
    var inHunk = false

    fun flush() {
        // Lines with no sign that no hunk follows (a mode change, an index line) say nothing to show.
        if (lines.any { it.kind != DiffLineKind.Note }) files += DiffFile(path.ifEmpty { oldPath }, lines)
        lines = mutableListOf()
    }

    diff.replace("\r\n", "\n").lines().forEach { raw ->
        // The gateway's inline diff is the CLI's own coloured output: drop the colour, keep the line.
        val line = ANSI.replace(raw, "")
        when {
            // `  ┊ review diff` is the CLI's caption above the diff; it isn't part of it.
            line.trimStart().startsWith("┊") -> {}
            // `diff --git a/x b/x` opens a file too; the path on its right is the one to show.
            line.startsWith("diff --git ") -> {
                flush()
                path = GIT_HEADER.find(line)?.groupValues?.getOrNull(4)?.removePrefix("b/")?.trim().orEmpty()
                oldPath = ""
                inHunk = false
            }
            // The gateway also names a file as `a/x → b/x`, with no `---`/`+++` pair at all.
            ARROW_HEADER.matches(line) -> {
                flush()
                oldPath = headerPath(line.substringBefore(" → "))
                path = headerPath(line.substringAfter(" → "))
                inHunk = false
            }
            // A new file starts: whatever was gathered belongs to the one before.
            line.startsWith("--- ") -> {
                flush()
                oldPath = headerPath(line.removePrefix("--- "))
                inHunk = false
            }
            line.startsWith("+++ ") -> {
                path = headerPath(line.removePrefix("+++ "))
            }
            line.startsWith("@@") -> {
                inHunk = true
                val start = hunkStart(line)
                oldAt = start.first
                newAt = start.second
                lines += DiffLine(DiffLineKind.Hunk, null, null, line.trim())
            }
            // "\ No newline at end of file" explains the line above; it has no number of its own.
            line.startsWith("\\") -> lines += DiffLine(DiffLineKind.Note, null, null, line)
            inHunk && line.startsWith("+") -> lines += DiffLine(DiffLineKind.Added, null, newAt++, line.removePrefix("+"))
            inHunk && line.startsWith("-") -> lines += DiffLine(DiffLineKind.Removed, oldAt++, null, line.removePrefix("-"))
            // A context line starts with a space; an empty one is a context line whose space was trimmed away.
            inHunk && (line.startsWith(" ") || line.isEmpty()) -> {
                lines += DiffLine(DiffLineKind.Context, oldAt++, newAt++, line.removePrefix(" "))
            }
            // Anything else with no sign ends the hunk (the next file's metadata, or a note about this one).
            else -> {
                inHunk = false
                lines += DiffLine(DiffLineKind.Note, null, null, line)
            }
        }
    }
    flush()
    return files
}

/** `a/src/x.kt` → `src/x.kt`; `/dev/null` and Git's timestamps are not part of a path. */
private fun headerPath(raw: String): String = raw.trim().substringBefore('\t').removePrefix("a/").removePrefix("b/").takeUnless { it == "/dev/null" }.orEmpty()

/** `@@ -12,7 +14,9 @@ fn main()` → old side starts at 12, new at 14. */
private fun hunkStart(header: String): Pair<Int, Int> {
    val match = HUNK.find(header) ?: return 0 to 0
    return (match.groupValues[1].toIntOrNull() ?: 0) to (match.groupValues[2].toIntOrNull() ?: 0)
}

private val HUNK = Regex("""@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?""")

/** `diff --git a/x b/x` → the two paths, unquoted only as far as Git quotes nothing here. */
private val GIT_HEADER = Regex("""^diff --git ("?)(.+?)\1 ("?)(.+?)\1$""")

/** The gateway's own header for an inline diff: `a/src/x.kt → b/src/x.kt`. */
private val ARROW_HEADER = Regex("""^\s*[ab]/.+\s→\s[ab]/.+$""")

/** Colour the CLI wrapped around its diff: not part of what a phone shows. */
private val ANSI = Regex("""\u001B\[[0-9;?]*[a-zA-Z]""")

/** The language a diff's [path] is written in, for coloring: its extension, or its name when it has none. */
fun languageOfPath(path: String): String? {
    val name = path.substringAfterLast('/').lowercase()
    if (name.isEmpty()) return null
    return name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != name } ?: name.takeIf { '.' !in name }
}
