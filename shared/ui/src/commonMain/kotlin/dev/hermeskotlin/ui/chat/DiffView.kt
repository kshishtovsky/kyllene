package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composeunstyled.Text
import com.composeunstyled.theme.Theme
import dev.hermeskotlin.core.chat.DiffFile
import dev.hermeskotlin.core.chat.DiffLine
import dev.hermeskotlin.core.chat.DiffLineKind
import dev.hermeskotlin.core.chat.languageOfPath
import dev.hermeskotlin.core.chat.parseUnifiedDiff
import dev.hermeskotlin.designsystem.background
import dev.hermeskotlin.designsystem.caption
import dev.hermeskotlin.designsystem.code
import dev.hermeskotlin.designsystem.colors
import dev.hermeskotlin.designsystem.components.CopyButton
import dev.hermeskotlin.designsystem.components.SyntaxColors
import dev.hermeskotlin.designsystem.components.highlightedLine
import dev.hermeskotlin.designsystem.components.verticalScrollWithBar
import dev.hermeskotlin.designsystem.danger
import dev.hermeskotlin.designsystem.dangerSoft
import dev.hermeskotlin.designsystem.radii
import dev.hermeskotlin.designsystem.radiusSmall
import dev.hermeskotlin.designsystem.stroke
import dev.hermeskotlin.designsystem.success
import dev.hermeskotlin.designsystem.successSoft
import dev.hermeskotlin.designsystem.surface
import dev.hermeskotlin.designsystem.text as textColor
import dev.hermeskotlin.designsystem.textSecondary
import dev.hermeskotlin.designsystem.textTertiary
import dev.hermeskotlin.designsystem.typography

/**
 * A file tool's edit as GitHub shows a commit: each file named with how much it gained and lost, then its lines in
 * two gutters — the old number and the new one — with removals red and additions green, and the code colored.
 *
 * Lines past [MAX_LINES] are cut: a gateway diff can be thousands of lines, and the phone needn't hold them all.
 */
@Composable
internal fun DiffView(diff: String, title: String = "Changes", source: CommentSource? = null) {
    val files = remember(diff) { parseUnifiedDiff(diff) }
    val shape = RoundedCornerShape(Theme[radii][radiusSmall])
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Theme[typography][caption], color = Theme[colors][textSecondary], modifier = Modifier.weight(1f))
            CopyButton(diff)
        }
        // One scroll for every line's text, so the gutters stay put while the code moves sideways.
        val sideways = rememberScrollState()
        CommentableSelection(source) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .border(1.dp, Theme[colors][stroke], shape)
                    .background(Theme[colors][background], shape)
                    .verticalScrollWithBar(),
            ) {
                if (files.isEmpty()) {
                    // Not a diff at all: show what the tool said rather than nothing.
                    Text(
                        diff.trimEnd(),
                        style = Theme[typography][code].copy(color = Theme[colors][textColor]),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                files.forEach { file -> key(file.path) { DiffFileBlock(file, sideways) } }
            }
        }
    }
}

@Composable
private fun DiffFileBlock(file: DiffFile, sideways: androidx.compose.foundation.ScrollState) {
    val style = Theme[typography][code].copy(fontSize = 12.sp, lineHeight = 18.sp)
    val base = Theme[colors][textColor]
    val palette = if (Theme[colors][background].luminance() < 0.5f) SyntaxColors.Dark else SyntaxColors.Light
    val language = remember(file.path) { languageOfPath(file.path) }
    // Numbers share a width per file, so the columns line up down the whole diff.
    val widest = remember(file) { file.lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) }?.toString()?.length ?: 1 }
    val gutter = (widest * 7 + 10).dp
    // A file that only gains lines has one number per line; a removal-only one shows the number it had.
    val twoSides = file.removed > 0
    var shown = 0
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Theme[colors][surface])
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The path on one line: a long one is cut at the front, where it says the least (the file name stays).
            BasicText(
                file.path.ifEmpty { "changes" },
                style = Theme[typography][code].copy(fontSize = 11.5.sp, color = Theme[colors][textSecondary]),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (file.added > 0) Text("+${file.added}", style = Theme[typography][caption], color = Theme[colors][success])
            if (file.removed > 0) Text("−${file.removed}", style = Theme[typography][caption], color = Theme[colors][danger])
        }
        file.lines.forEach { line ->
            if (shown >= MAX_LINES) return@forEach
            shown++
            when (line.kind) {
                DiffLineKind.Hunk -> Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Theme[colors][surface])
                        .horizontalScroll(sideways)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    // The whole header, however long: its tail names the function the hunk is in.
                    BasicText(line.text, style = style.copy(color = Theme[colors][textTertiary]), softWrap = false)
                }
                DiffLineKind.Note -> Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(line.text, style = style.copy(color = Theme[colors][textTertiary]))
                }
                else -> DiffRow(line, style, base, palette, language, gutter, sideways, twoSides)
            }
        }
    }
}

/** One changed or unchanged line: its number, the sign, and the code in its colors over its row's tint. */
@Composable
private fun DiffRow(
    line: DiffLine,
    style: androidx.compose.ui.text.TextStyle,
    base: Color,
    palette: SyntaxColors,
    language: String?,
    gutter: androidx.compose.ui.unit.Dp,
    sideways: androidx.compose.foundation.ScrollState,
    twoSides: Boolean,
) {
    val added = line.kind == DiffLineKind.Added
    val removed = line.kind == DiffLineKind.Removed
    val tint = when {
        added -> Theme[colors][successSoft]
        removed -> Theme[colors][dangerSoft]
        else -> Color.Transparent
    }
    val numberColor = when {
        added -> Theme[colors][success]
        removed -> Theme[colors][danger]
        else -> Theme[colors][textTertiary]
    }
    val text = remember(line.text, language, palette) { highlightedLine(line.text, language, palette) }
    Row(Modifier.fillMaxWidth().background(tint)) {
        // One gutter unless the file also loses lines: an insertion-only edit would print every number twice.
        if (twoSides) Text(
            line.oldNumber?.toString().orEmpty(),
            style = style.copy(color = numberColor),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = gutter).padding(end = 4.dp),
        )
        Text(
            (line.newNumber ?: line.oldNumber)?.toString().orEmpty(),
            style = style.copy(color = numberColor),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = gutter).padding(end = 6.dp),
        )
        Text(if (added) "+" else if (removed) "-" else " ", style = style.copy(color = numberColor))
        Box(Modifier.weight(1f).horizontalScroll(sideways)) {
            BasicText(text, style = style.copy(color = base), softWrap = false, modifier = Modifier.padding(start = 6.dp, end = 10.dp))
        }
    }
}

/** A diff longer than this is cut; the box scrolls, and the phone stays quick. */
private const val MAX_LINES = 400
