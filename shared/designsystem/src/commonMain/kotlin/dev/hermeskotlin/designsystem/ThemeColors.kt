package dev.hermeskotlin.designsystem

import androidx.compose.ui.graphics.Color

/**
 * The Herald "modern" palette: graphite surfaces in dark, cool off-white in light. Surfaces step up in
 * lightness (surface → surface 2 → surface 3) instead of casting shadows, and hairlines are translucent.
 * Kept apart from HermesTheme.kt, which loads the fonts, so tests can read the colors without Android resources.
 */
internal object Palette {
    val Bg = Color(0xFF0A0A0C)
    val Surface = Color(0xFF16161A)
    val Surface2 = Color(0xFF1C1C22)
    val Surface3 = Color(0xFF232329)
    val Well = Color(0xFF121215)
    val Thumb = Color(0xFF2C2C33)
    val Sheet = Color(0xFF18181D)
    val Line = Color.White.copy(alpha = 0.07f)
    val LineStrong = Color.White.copy(alpha = 0.12f)
    val Text = Color(0xFFF4F4F6)
    val Text2 = Color(0xFFD4D4D8)
    val Text4 = Color(0xFF8A8A94)
    val Text5 = Color(0xFF6B6B75)

    val LightBg = Color(0xFFF7F7F9)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurface2 = Color(0xFFF1F1F5)
    val LightSurface3 = Color(0xFFE9E9EF)
    val LightWell = Color(0xFFF3F3F7)
    val LightLine = Color(0xFF0A0A1E).copy(alpha = 0.08f)
    val LightLineStrong = Color(0xFF0A0A1E).copy(alpha = 0.14f)
    val LightText = Color(0xFF0A0A0C)
    val LightText2 = Color(0xFF27272A)
    val LightText4 = Color(0xFF6B6B75)
    val LightText5 = Color(0xFF8E8E98)

    // Pure black keeps the graphite steps, each pulled a little toward black.
    val BlackSurface = Color(0xFF111114)
    val BlackSurface2 = Color(0xFF17171C)
    val BlackSurface3 = Color(0xFF1E1E24)
    val BlackWell = Color(0xFF0C0C0E)
    val BlackSheet = Color(0xFF141418)

    val Danger = Color(0xFFF85149)
    val Success = Color(0xFF3FB950)
    val Warning = Color(0xFFF5A524)
    val LightDanger = Color(0xFFCF222E)
    val LightSuccess = Color(0xFF1A7F37)
    val LightWarning = Color(0xFFB76E00)
}

internal fun lightColors(accentColors: AccentColors) = mapOf(
    background to Palette.LightBg,
    surface to Palette.LightSurface,
    surface2 to Palette.LightSurface2,
    surface3 to Palette.LightSurface3,
    well to Palette.LightWell,
    thumb to Palette.LightSurface,
    surfaceElevated to Palette.LightSurface,
    input to Palette.LightSurface,
    sidebar to Palette.LightSurface,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to Palette.LightText,
    textSecondary to Palette.LightText2,
    textTertiary to Palette.LightText4,
    textMuted to Palette.LightText5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
    inverse to Palette.LightText,
    onInverse to Color.White,
    stroke to Palette.LightLine,
    strokeStrong to Palette.LightLineStrong,
    danger to Palette.LightDanger,
    success to Palette.LightSuccess,
    warning to Palette.LightWarning,
    dangerSoft to Palette.LightDanger.copy(alpha = 0.09f),
    successSoft to Palette.LightSuccess.copy(alpha = 0.10f),
    warningSoft to Palette.LightWarning.copy(alpha = 0.10f),
)

/** Pure black: the dark roles on a true black ground, with each graphite step pulled toward black. */
internal fun pureBlackColors(accentColors: AccentColors) = darkColors(
    accentColors, Color.Black, Palette.BlackSurface, Palette.BlackSurface2, Palette.BlackSurface3, Palette.BlackWell, Palette.BlackSheet,
)

/**
 * Ayu's own colors, in every accent: a navy-black ground, warm off-white text and Ayu's gold, which is what the
 * scheme is known for. It ignores the accent picker, unlike the app's own schemes.
 */
private val AyuAccent = AccentColors(
    Color(0xFFE6B450), Color(0xFF0B0E14),
    text = Color(0xFFFFB454), soft = Color(0xFFE6B450).copy(alpha = 0.14f),
)

/** Ayu dark: the same roles as dark, on Ayu's grounds, with its text warm and its reds and greens its own. */
internal fun ayuColors() = darkColors(
    AyuAccent,
    bg = Color(0xFF0B0E14),
    surfaceColor = Color(0xFF0F141C),
    surface2Color = Color(0xFF151B26),
    surface3Color = Color(0xFF1B2230),
    wellColor = Color(0xFF090C11),
    sheet = Color(0xFF10161F),
) + mapOf(
    thumb to Color(0xFF28313F),
    text to Color(0xFFBFBDB6),
    textSecondary to Color(0xFFA9A79F),
    textTertiary to Color(0xFF828C9C),
    textMuted to Color(0xFF657078),
    inverse to Color(0xFFBFBDB6),
    onInverse to Color(0xFF0B0E14),
    danger to Color(0xFFF07178),
    success to Color(0xFF7FD962),
    warning to Color(0xFFFFB454),
    dangerSoft to Color(0xFFF07178).copy(alpha = 0.14f),
    successSoft to Color(0xFF7FD962).copy(alpha = 0.13f),
    warningSoft to Color(0xFFFFB454).copy(alpha = 0.13f),
)

/** Gruvbox's gold-orange on its warm grays: the palette morhetz's scheme is known for. */
private val GruvboxAccent = AccentColors(
    Color(0xFFFE8019), Color(0xFF282828),
    text = Color(0xFFFF9D4D), soft = Color(0xFFFE8019).copy(alpha = 0.14f),
)

/** Gruvbox dark: bg0 through bg3 as the steps up from the ground, its fg1 text, its bright reds and greens. */
internal fun gruvboxColors() = darkColors(
    GruvboxAccent,
    bg = Color(0xFF282828),
    surfaceColor = Color(0xFF32302F),
    surface2Color = Color(0xFF3C3836),
    surface3Color = Color(0xFF504945),
    wellColor = Color(0xFF1D2021),
    sheet = Color(0xFF302E2C),
) + mapOf(
    thumb to Color(0xFF665C54),
    text to Color(0xFFEBDBB2),
    textSecondary to Color(0xFFD5C4A1),
    textTertiary to Color(0xFFBDAE93),
    textMuted to Color(0xFF928374),
    inverse to Color(0xFFEBDBB2),
    onInverse to Color(0xFF282828),
    danger to Color(0xFFFB4934),
    success to Color(0xFFB8BB26),
    warning to Color(0xFFFABD2F),
    dangerSoft to Color(0xFFFB4934).copy(alpha = 0.14f),
    successSoft to Color(0xFFB8BB26).copy(alpha = 0.13f),
    warningSoft to Color(0xFFFABD2F).copy(alpha = 0.13f),
)

/** Kanagawa's crystal blue on sumi ink. */
private val KanagawaAccent = AccentColors(
    Color(0xFF7E9CD8), Color(0xFF1F1F28),
    text = Color(0xFF7FB4CA), soft = Color(0xFF7E9CD8).copy(alpha = 0.16f),
)

/** Kanagawa's wave: sumiInk grounds, fujiWhite text, its spring green and wave red. */
internal fun kanagawaColors() = darkColors(
    KanagawaAccent,
    bg = Color(0xFF1F1F28),
    surfaceColor = Color(0xFF2A2A37),
    surface2Color = Color(0xFF363646),
    surface3Color = Color(0xFF40405A),
    wellColor = Color(0xFF16161D),
    sheet = Color(0xFF22222B),
) + mapOf(
    thumb to Color(0xFF54546D),
    text to Color(0xFFDCD7BA),
    textSecondary to Color(0xFFC8C093),
    textTertiary to Color(0xFFA8A49A),
    textMuted to Color(0xFF727169),
    inverse to Color(0xFFDCD7BA),
    onInverse to Color(0xFF1F1F28),
    danger to Color(0xFFE46876),
    success to Color(0xFF98BB6C),
    warning to Color(0xFFE6C384),
    dangerSoft to Color(0xFFE46876).copy(alpha = 0.14f),
    successSoft to Color(0xFF98BB6C).copy(alpha = 0.13f),
    warningSoft to Color(0xFFE6C384).copy(alpha = 0.13f),
)

/** Vesper's warm peach, the one colour the whole scheme is remembered by. */
private val VesperAccent = AccentColors(
    Color(0xFFFFCFA8), Color(0xFF101010),
    text = Color(0xFFFFCFA8), soft = Color(0xFFFFCFA8).copy(alpha = 0.13f),
)

/** Vesper: a near-black ground, white-washed text and peach, as its light-on-dark editor is. */
internal fun vesperColors() = darkColors(
    VesperAccent,
    bg = Color(0xFF101010),
    surfaceColor = Color(0xFF161616),
    surface2Color = Color(0xFF232323),
    surface3Color = Color(0xFF282828),
    wellColor = Color(0xFF0A0A0A),
    sheet = Color(0xFF1A1A1A),
) + mapOf(
    thumb to Color(0xFF343434),
    text to Color(0xFFCCCCCC),
    textSecondary to Color(0xFFA0A0A0),
    textTertiary to Color(0xFF969696),
    textMuted to Color(0xFF65737E),
    inverse to Color(0xFFCCCCCC),
    onInverse to Color(0xFF101010),
    danger to Color(0xFFFF8080),
    success to Color(0xFF82D9C2),
    warning to Color(0xFFFFC799),
    dangerSoft to Color(0xFFFF8080).copy(alpha = 0.14f),
    successSoft to Color(0xFF82D9C2).copy(alpha = 0.13f),
    warningSoft to Color(0xFFFFC799).copy(alpha = 0.13f),
)

/** Hyperliquid's mint, the one colour its trading screen is known for. */
private val HyperliquidAccent = AccentColors(
    Color(0xFF97FCE4), Color(0xFF04060C),
    text = Color(0xFF97FCE4), soft = Color(0xFF97FCE4).copy(alpha = 0.14f),
)

/**
 * Hyperliquid: its own tokens taken from the trading app's CSS — a navy-black ground, its surface #0F1A1F, the
 * #273035 inset, mint #97FCE4 as the accent and #ED7088 as the red.
 */
internal fun hyperliquidColors() = darkColors(
    HyperliquidAccent,
    bg = Color(0xFF0F121D),
    surfaceColor = Color(0xFF0F1A1F),
    surface2Color = Color(0xFF1F2D33),
    surface3Color = Color(0xFF273035),
    wellColor = Color(0xFF0B0E16),
    sheet = Color(0xFF131C22),
) + mapOf(
    thumb to Color(0xFF35464E),
    text to Color(0xFFF6FEFD),
    textSecondary to Color(0xFFC2CBCE),
    textTertiary to Color(0xFF9AA3A4),
    textMuted to Color(0xFF7E888A),
    inverse to Color(0xFFF6FEFD),
    onInverse to Color(0xFF0F121D),
    danger to Color(0xFFED7088),
    success to Color(0xFF50D2C1),
    warning to Color(0xFFF3B579),
    dangerSoft to Color(0xFFED7088).copy(alpha = 0.14f),
    successSoft to Color(0xFF50D2C1).copy(alpha = 0.13f),
    warningSoft to Color(0xFFF3B579).copy(alpha = 0.13f),
)

/** Dark and pure black share every role but the grounds they sit on. */
internal fun darkColors(
    accentColors: AccentColors,
    bg: Color = Palette.Bg,
    surfaceColor: Color = Palette.Surface,
    surface2Color: Color = Palette.Surface2,
    surface3Color: Color = Palette.Surface3,
    wellColor: Color = Palette.Well,
    sheet: Color = Palette.Sheet,
) = mapOf(
    background to bg,
    surface to surfaceColor,
    surface2 to surface2Color,
    surface3 to surface3Color,
    well to wellColor,
    // One step above surface 3, whatever the ground.
    thumb to Palette.Thumb,
    surfaceElevated to sheet,
    input to surfaceColor,
    sidebar to surfaceColor,
    userBubble to accentColors.bubble,
    userBubbleStroke to accentColors.bubbleStroke,
    onUserBubble to accentColors.onAccent,
    text to Palette.Text,
    textSecondary to Palette.Text2,
    textTertiary to Palette.Text4,
    textMuted to Palette.Text5,
    accent to accentColors.accent,
    onAccent to accentColors.onAccent,
    accentText to accentColors.text,
    accentSoft to accentColors.soft,
    inverse to Palette.Text,
    onInverse to Palette.Bg,
    stroke to Palette.Line,
    strokeStrong to Palette.LineStrong,
    danger to Palette.Danger,
    success to Palette.Success,
    warning to Palette.Warning,
    dangerSoft to Palette.Danger.copy(alpha = 0.14f),
    successSoft to Palette.Success.copy(alpha = 0.13f),
    warningSoft to Palette.Warning.copy(alpha = 0.13f),
)
