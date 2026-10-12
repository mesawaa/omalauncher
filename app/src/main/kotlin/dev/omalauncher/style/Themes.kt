package dev.omalauncher.style

/**
 * The launcher's built-in themes, by the names style.toml's `theme` and settings › style use.
 * Each sets every color widgets use by name; style.toml can change any of them on top.
 */
object Themes {
    /** Pure black and white, for AMOLED screens: grey frames and title bars, Nothing's red only for alerts. */
    val AMOLED: Map<String, String> =
        mapOf(
            "mode" to "dark",
            "accent" to "#ffffff",
            // A window's title bar, and the round badge behind monochrome app icons.
            "selection" to "#1c1c1c",
            // Window frames (`border` in widgets).
            "muted" to "#333333",
            "background" to "#000000",
            "dark_background" to "#000000",
            "darker_background" to "#000000",
            // Panels and tiles (`surface` in widgets).
            "lighter_background" to "#111111",
            "foreground" to "#ffffff",
            // Secondary text (`muted` in widgets).
            "dark_foreground" to "#8a8a8a",
            "light_foreground" to "#ececec",
            "bright_foreground" to "#ffffff",
            // Nothing's red, for alerts and errors; the other colors are greys, so terminals stay black and white.
            "red" to "#d71921",
            "yellow" to "#cecece",
            "orange" to "#b9b9b9",
            "green" to "#b6b6b6",
            "cyan" to "#b0b0b0",
            "blue" to "#8d8d8d",
            "magenta" to "#9b9b9b",
            "purple" to "#9b9b9b",
            "brown" to "#5c5c5c",
            "bright_red" to "#ff4d57",
            "bright_yellow" to "#e6e6e6",
            "bright_green" to "#d0d0d0",
            "bright_cyan" to "#c8c8c8",
            "bright_blue" to "#a8a8a8",
            "bright_magenta" to "#b4b4b4",
        )

    /** Every theme by name, the default first. */
    val ALL: Map<String, Map<String, String>> = linkedMapOf("amoled" to AMOLED, "copland" to Copland.COLORS)

    const val DEFAULT = "amoled"

    /** What settings › style shows each theme as. */
    val LABELS = mapOf("amoled" to "amoled", "copland" to "copland os")

    /** [name]'s colors, or the default theme's for a name that isn't one. */
    fun colors(name: String?): Map<String, String> = ALL[name?.trim()?.lowercase()] ?: ALL.getValue(DEFAULT)

    /** [name] if it's a theme, else the default. */
    fun known(name: String?): String = name?.trim()?.lowercase()?.takeIf { it in ALL } ?: DEFAULT
}
