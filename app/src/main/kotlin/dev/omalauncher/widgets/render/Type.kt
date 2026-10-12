package dev.omalauncher.widgets.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.omalauncher.ui.Fonts
import dev.omalauncher.ui.Palette
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Gradient
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.num
import dev.omalauncher.widgets.runtime.WidgetInstance

/**
 * The type of Text, Button and Input: `font` (any, see [Fonts]; the launcher's own font without one),
 * `fontSize` (sp, else [size]), `bold` or `fontWeight` (100–900 or thin…black), `italic`, `letterSpacing`
 * and `lineHeight` (sp), `underline`, `strike`, `fontFeatures` ("tnum, smcp") and, on Text, `shadow`.
 */
@Composable
internal fun typeStyle(
    p: Props,
    size: TextUnit,
): TextStyle {
    val inst = p.inst
    val spec = p.observe("font")?.takeIf { it != Null }?.toString()
    // Read, so text recomposes once a font it asked for has loaded.
    inst.fontsLoaded.intValue
    val family = spec?.let { Fonts.builtIn(it) ?: inst.font(it) } ?: Palette.current.font
    val density = LocalDensity.current.density
    return TextStyle(
        fontFamily = family,
        fontSize = p.observe("fontSize")?.num()?.sp ?: size,
        fontWeight = fontWeight(p.observe("fontWeight")) ?: if (p.observe("bold")?.truthy == true) FontWeight.Bold else null,
        fontStyle = if (p.observe("italic")?.truthy == true) FontStyle.Italic else null,
        letterSpacing = p.observe("letterSpacing")?.num()?.sp ?: TextUnit.Unspecified,
        lineHeight = p.observe("lineHeight")?.num()?.sp ?: TextUnit.Unspecified,
        textDecoration = decoration(p.observe("underline")?.truthy == true, p.observe("strike")?.truthy == true),
        fontFeatureSettings = p.observe("fontFeatures")?.takeIf { it != Null }?.toString()?.let(::featureSettings),
        shadow = p.observe("shadow")?.takeIf { it != Null }?.let { textShadow(p, it, density) },
    )
}

/** 100–900, or a weight's name. */
internal fun fontWeight(v: Value?): FontWeight? {
    if (v == null || v == Null) return null
    v.num()?.let { return FontWeight(it.toInt().coerceIn(1, 1000)) }
    return when (v.toString()) {
        "thin" -> FontWeight.Thin
        "light" -> FontWeight.Light
        "normal" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "semibold" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        "black" -> FontWeight.Black
        else -> null
    }
}

private fun decoration(
    underline: Boolean,
    strike: Boolean,
): TextDecoration? =
    when {
        underline && strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        underline -> TextDecoration.Underline
        strike -> TextDecoration.LineThrough
        else -> null
    }

/** CSS's font-feature-settings, which Android takes: "tnum, smcp 0" becomes "'tnum', 'smcp' 0". */
private fun featureSettings(s: String): String =
    if ('\'' in s || '"' in s) {
        s
    } else {
        s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { f ->
            val parts = f.split(Regex("\\s+"), limit = 2)
            "'${parts[0]}'" + (parts.getOrNull(1)?.let { " $it" } ?: "")
        }
    }

/** `shadow: color` glows around the text; `shadow: {color, x, y, blur}` (dp) casts it. */
private fun textShadow(
    p: Props,
    v: Value,
    density: Float,
): Shadow? {
    val o = (v as? Obj)?.fields
    val color = p.color(o?.get("color") ?: v) ?: return null
    val x = o?.get("x")?.num()?.toFloat() ?: 0f
    val y = o?.get("y")?.num()?.toFloat() ?: 0f
    val blur = o?.get("blur")?.num()?.toFloat() ?: 6f
    return Shadow(color, Offset(x * density, y * density), (blur * density).coerceAtLeast(0f))
}

/** A gradient from linear(...) or radial(...), its coordinates in dp from the element's top left. */
internal fun gradientBrush(
    g: Gradient,
    inst: WidgetInstance,
    density: Float,
): Brush {
    val colors = g.colors.map { Color(Builtins.argb(it, inst) ?: 0) }
    if (colors.size < 2) return SolidColor(colors.firstOrNull() ?: Color.Transparent)
    val c = g.coords.map { it * density }
    return if (g.radial) {
        Brush.radialGradient(colors, Offset(c[0], c[1]), c[2].coerceAtLeast(0.01f))
    } else {
        Brush.linearGradient(colors, Offset(c[0], c[1]), Offset(c[2], c[3]))
    }
}
