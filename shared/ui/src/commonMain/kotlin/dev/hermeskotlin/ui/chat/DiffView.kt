package dev.hermeskotlin.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.hermeskotlin.designsystem.components.ScrollbarBox
import dev.hermeskotlin.designsystem.components.SyntaxColors
import dev.hermeskotlin.designsystem.components.highlightedLine
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
 * A file tool's edit as GitHub shows a commit: each file named with how much it gained and lost, then its lines with
 * the number they have in the file, removals red and additions green, and the code colored.
 *
 * The numbers, the sign and the hunk header hold their place; only the code slides sideways, every line of a file on
 * one shared scroll state and given the widest line's width, so dragging any row moves them all.
 *
 * The lines are a list: only those on screen are built and measured, where laying out a whole file at once stalled the
 * frame it landed in. Lines past [MAX_LINES] are cut: a gateway diff can be thousands of lines, and the phone needn't
 * hold them all.
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
        CommentableSelection(source) {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .border(1.dp, Theme[colors][stroke], shape)
                    .background(Theme[colors][background], shape),
            ) {
                // A row's tint reaches across the box even when its code is shorter.
                val viewport = maxWidth
                // A file's widths are measured here, since a row of the list sees only its own line.
                val rows = files.map { file -> key(file.path) { rememberFileRows(file, viewport) } }
                // One list for the box where it was one column, so the files still scroll together as they did.
                ScrollbarBox { list ->
                    LazyColumn(Modifier.fillMaxWidth(), state = list) {
                        if (files.isEmpty()) {
                            // Not a diff at all: show what the tool said rather than nothing.
                            item {
                                Text(
                                    diff.trimEnd(),
                                    style = Theme[typography][code].copy(color = Theme[colors][textColor]),
                                    modifier = Modifier.defaultMinSize(minWidth = viewport).padding(10.dp),
                                )
                            }
                        }
                        // Each file's name stays put; only its lines move sideways, so the header can't slide away.
                        rows.forEach { DiffFileBlock(it, viewport) }
                    }
                }
            }
        }
    }
}

/** Everything one file's rows draw with, measured once: the list builds a row at a time and can't measure the file. */
private class FileRows(
    val file: DiffFile,
    val style: TextStyle,
    val base: Color,
    val palette: SyntaxColors,
    val language: String?,
    val gutter: Dp,
    val codeWidth: Dp,
    val twoSides: Boolean,
    val sideways: ScrollState,
)

@Composable
private fun rememberFileRows(file: DiffFile, viewport: Dp): FileRows {
    val style = Theme[typography][code].copy(fontSize = 12.sp, lineHeight = 18.sp)
    val base = Theme[colors][textColor]
    val palette = if (Theme[colors][background].luminance() < 0.5f) SyntaxColors.Dark else SyntaxColors.Light
    val language = remember(file.path) { languageOfPath(file.path) }
    // Numbers share a width per file, so the columns line up down the whole diff.
    val widest = remember(file) { file.lines.maxOfOrNull { maxOf(it.oldNumber ?: 0, it.newNumber ?: 0) }?.toString()?.length ?: 1 }
    val gutter = (widest * 7 + 10).dp
    // A file that only gains lines has one number per line; a removal-only one shows the number it had.
    val twoSides = file.removed > 0
    // The numbers, the sign and the hunk header hold their place; only the code moves sideways, all of it together.
    val sideways = rememberScrollState()
    // Every line is given the widest line's room, so a short line still scrolls with the rest of the file.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val codeWidth = remember(file, style, gutter, viewport) {
        val widestCode = file.lines.take(MAX_LINES).maxOfOrNull {
            measurer.measure(AnnotatedString(it.text), style, softWrap = false).size.width
        } ?: 0
        maxOf(viewport - gutter, with(density) { widestCode.toDp() } + 16.dp)
    }
    return FileRows(file, style, base, palette, language, gutter, codeWidth, twoSides, sideways)
}

/** One file in the diff's list: its name, then its lines, each a row the list builds as it comes into view. */
private fun LazyListScope.DiffFileBlock(rows: FileRows, viewport: Dp) {
    val file = rows.file
    item(contentType = FileNameType) {
        Row(
            Modifier
                .defaultMinSize(minWidth = viewport)
                .background(Theme[colors][surface])
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The path on one line, cut where it says least and leaving room for the counts beside it.
            BasicText(
                file.path.ifEmpty { "changes" },
                style = Theme[typography][code].copy(fontSize = 11.5.sp, color = Theme[colors][textSecondary]),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = viewport - 64.dp),
            )
            if (file.added > 0) Text("+${file.added}", style = Theme[typography][caption], color = Theme[colors][success])
            if (file.removed > 0) Text("−${file.removed}", style = Theme[typography][caption], color = Theme[colors][danger])
        }
    }
    val lines = file.lines.take(MAX_LINES)
    // The item's own index is its key, so a line keeps its slot as long as the diff it came from stands.
    items(lines.size, contentType = { lines[it].kind }) { index ->
        val line = lines[index]
        when (line.kind) {
            // The hunk header stays put and reads on one line; the numbers it carries are the point of it.
            DiffLineKind.Hunk -> Row(
                Modifier
                    .fillMaxWidth()
                    .background(Theme[colors][surface])
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                BasicText(
                    line.text,
                    style = rows.style.copy(color = Theme[colors][textTertiary]),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DiffLineKind.Note -> Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)) {
                Text(line.text, style = rows.style.copy(color = Theme[colors][textTertiary]))
            }
            else -> DiffRow(
                line,
                rows.style,
                rows.base,
                rows.palette,
                rows.language,
                rows.gutter,
                rows.codeWidth,
                rows.sideways,
                rows.twoSides,
            )
        }
    }
}

/** One changed or unchanged line: its number, the sign, and the code in its colors over its row's tint. */
@Composable
private fun DiffRow(
    line: DiffLine,
    style: TextStyle,
    base: Color,
    palette: SyntaxColors,
    language: String?,
    gutter: Dp,
    codeWidth: Dp,
    sideways: ScrollState,
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
    // The tint stays on the row, which never moves; only the code inside it slides under it.
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
            BasicText(
                text,
                style = style.copy(color = base),
                softWrap = false,
                modifier = Modifier.defaultMinSize(minWidth = codeWidth).padding(start = 6.dp, end = 10.dp),
            )
        }
    }
}

/** A diff longer than this is cut; the box scrolls, and the phone stays quick. */
private const val MAX_LINES = 400

// A file's name and a line lay out differently, so the list keeps their slots apart.
private const val FileNameType = "file-name"
