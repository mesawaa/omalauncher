package dev.omalauncher.ui

import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.omalauncher.R
import dev.omalauncher.style.Colors
import dev.omalauncher.style.Themes

/** `#rrggbb` or `#aarrggbb` as a color; null if it isn't one. */
fun hexColor(hex: String?): Color? {
    val h = hex?.trim()?.removePrefix("#") ?: return null
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** The launcher's colors and font, parsed once when they change rather than on every read. */
@Immutable
class ThemePalette(
    colors: Map<String, String>,
    /** The launcher's own text; see style.toml's `font`. */
    val font: FontFamily = FontFamily.Monospace,
) {
    /** Every color by name (accent, red, yellow, color0, ...), as widgets use them. */
    val named: Map<String, Color> = colors.mapNotNull { (k, v) -> hexColor(v)?.let { k to it } }.toMap()

    private fun color(
        key: String,
        fallback: Long,
    ) = named[key] ?: Color(fallback)

    val isLight = colors["mode"] == "light"
    val background = color("background", 0xFF000000)
    val surface = color("lighter_background", 0xFF111111)
    val overlay = background.copy(alpha = 0.9f)
    val foreground = color("foreground", 0xFFFFFFFF)
    val muted = color("dark_foreground", 0xFF8A8A8A)
    val accent = color("accent", 0xFFFFFFFF)
    val border = color("muted", 0xFF333333)

    /** A window's title bar, and the badge behind monochrome app icons. */
    val bar = color("selection", 0xFF1C1C1C)
    val bright = color("bright_foreground", 0xFFFFFFFF)
    val red = color("red", 0xFFD71921)
    val green = color("green", 0xFFB6B6B6)
    val mono = TextStyle(fontFamily = font, fontSize = TextSize.Body, color = foreground)
}

/**
 * Launcher colors: the picked theme ([Themes]), with whatever style.toml changes
 * (see [dev.omalauncher.style.StyleStore]).
 */
object Palette {
    var current by mutableStateOf(ThemePalette(Colors.resolve(Themes.colors(Themes.DEFAULT))))
        private set

    fun apply(
        colors: Map<String, String>,
        font: FontFamily = current.font,
    ) {
        current = ThemePalette(colors, font)
    }

    fun named(name: String): Color? = current.named[name]

    val isLight get() = current.isLight
    val background get() = current.background
    val surface get() = current.surface
    val overlay get() = current.overlay
    val foreground get() = current.foreground
    val muted get() = current.muted
    val accent get() = current.accent
    val border get() = current.border
    val bar get() = current.bar
    val bright get() = current.bright
    val red get() = current.red
    val green get() = current.green

    /** The translucent fill of tiles, bars and rails over the wallpaper. */
    val glass get() = background.copy(alpha = 0.88f)
}

/** How the launcher draws itself, independent of its colors. Saved by StyleStore. */
object LauncherStyle {
    /** App icons as silhouettes in the accent color (Omarchy) rather than their own colors. */
    var monochromeIcons by mutableStateOf(false)

    /** Home screen apps as bare icons on the wallpaper instead of framed tiles. */
    var bareAppIcons by mutableStateOf(false)

    /** The strip of recently used apps above the bar on home. */
    var recentApps by mutableStateOf(true)

    /** How frames (tiles, panels, menus, fields) round their corners. */
    var corners by mutableStateOf(Corners.Edge)

    /** An icon pack's package (see [dev.omalauncher.IconPack]); null for the apps' own icons. */
    var iconPack by mutableStateOf<String?>(null)

    /** The shape frames use: square, or [radius] scaled by the corner style. */
    fun shape(radius: Dp = 16.dp): Shape = if (corners == Corners.Edge) RectangleShape else RoundedCornerShape(radius * corners.scale)
}

/** Corner styles, from Omarchy's sharp edges to stock Android's round shapes; [scale] multiplies each frame's radius. */
enum class Corners(
    /** Saved in preferences: never rename. */
    val id: String,
    val label: String,
    val scale: Float,
) {
    Edge("edge", "edge", 0f),
    Soft("soft", "soft", 0.5f),
    Round("round", "round", 1f),
    ;

    companion object {
        /** Also takes the enum names ("Round") launchers before versioning saved. */
        fun of(id: String?) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Edge
    }
}

/**
 * A framed surface in the user's corner style: clipped, filled, then outlined with one shape.
 * A [width] of 0 leaves out the outline (Compose's border would draw a hairline).
 */
fun Modifier.frame(
    width: Dp,
    color: Color,
    fill: Color = Color.Transparent,
    radius: Dp = 16.dp,
): Modifier {
    val shape = LauncherStyle.shape(radius)
    val filled = clip(shape).background(fill, shape)
    return if (width > 0.dp) filled.border(width, color, shape) else filled
}

/**
 * Omarchy's gap between framed surfaces: between home's tiles, from them to the screen's
 * safe edges, and from full-screen panels to those edges, so a panel's frame lines up with the tiles'.
 */
val HomeGap = 6.dp

/** The widest a column of text and rows gets on a big screen: the bar beside landscape home, text-heavy panels. */
val ReadingWidth = 640.dp

/**
 * One of the launcher's own surfaces over the wallpaper (the prompt bar, the rails, boxed app tiles):
 * a Navi window's thin frame, in the accent while [focused]. Widget tiles aren't drawn with it: a
 * widget draws its own frame, or none (see [widgetTile]).
 */
fun Modifier.tile(focused: Boolean = false): Modifier = frame(1.dp, if (focused) Palette.accent else Palette.border, Palette.glass)

/**
 * A widget's tile: clipped to the corner style and nothing else drawn around it. Without a
 * `background` of its own the widget gets the launcher's glass fill, so its text stays readable.
 */
fun Modifier.widgetTile(ownBackground: Boolean): Modifier {
    val shape = LauncherStyle.shape()
    return clip(shape).then(if (ownBackground) Modifier else Modifier.background(Palette.glass, shape))
}

/** The launcher's text style: its font (style.toml's `font`), in the foreground color. */
val Mono get() = Palette.current.mono

/**
 * The launcher's text sizes. Nothing is smaller than [Small], which is still
 * readable on a phone at arm's length; Android's font size scales them all.
 */
object TextSize {
    /** Second lines, statuses, hints and labels under icons. */
    val Small = 12.sp

    /** Everything else; [Mono]'s size. */
    val Body = 14.sp

    /** List items and menu entries. */
    val Item = 15.sp

    /** Fields and dialog text. */
    val Large = 16.sp

    /** Screen and dialog titles. */
    val Title = 18.sp
}

/**
 * The few Nerd Font glyphs omarchy uses that Android lacks: weather (U+E300–E3E3, see
 * weatherIcon()) plus the launcher's own [Glyph]s. A subset of Symbols Nerd Font 3.5.1, made
 * with pyftsubset; Weather Icons by Erik Flowers, SIL OFL 1.1; Material Design Icons, Apache 2.0.
 */
val NerdSymbols = FontFamily(Font(R.font.nerd_symbols))

/** The Material Design Icons in [NerdSymbols] the launcher draws with [Symbol]. */
object Glyph {
    /** nf-md-check 󰄬 */
    const val Check = "\uDB80\uDD2C"

    /** nf-md-close 󰅖 */
    const val Close = "\uDB80\uDD56"

    /** nf-md-chevron_down 󰅀 */
    const val ChevronDown = "\uDB80\uDD40"

    /** nf-md-chevron_right 󰅂 */
    const val ChevronRight = "\uDB80\uDD42"

    /** nf-md-chevron_up 󰅃 */
    const val ChevronUp = "\uDB80\uDD43"
}

/** Status/navigation bar icons follow the palette's light or dark mode. */
fun applySystemBars(
    activity: androidx.activity.ComponentActivity,
    light: Boolean,
) {
    val transparent = android.graphics.Color.TRANSPARENT
    val style =
        if (light) {
            androidx.activity.SystemBarStyle.light(transparent, transparent)
        } else {
            androidx.activity.SystemBarStyle.dark(transparent)
        }
    activity.enableEdgeToEdge(style, style)
}
