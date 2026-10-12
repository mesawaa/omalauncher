package dev.omalauncher.style

/**
 * The launcher's look: Copland OS Enterprise, the OS on Lain's Navi in Serial Experiments Lain.
 * Deep navy panels, teal window frames and pale cyan text, with fauux's pink and beige for alerts.
 *
 * The names are the ones widgets use (`accent`, `foreground`, `muted`, ...) and style.toml
 * overrides. Derived names (color0–color15, bright_*, ...) are filled in by [Colors.resolve].
 */
object Copland {
    val COLORS: Map<String, String> =
        mapOf(
            "mode" to "dark",
            // Highlight cyan: selections, active controls, headings.
            "accent" to "#3fb8e0",
            // A Navi window's title bar.
            "selection" to "#0d1f50",
            // Window frames. Omarchy calls the frame color `muted`, widgets call it `border`.
            "muted" to "#246a96",
            "background" to "#050517",
            "dark_background" to "#03030f",
            "darker_background" to "#020208",
            // Panels and tiles.
            "lighter_background" to "#070b26",
            "foreground" to "#a8e4ea",
            // Secondary text (`muted` in widgets).
            "dark_foreground" to "#5a7fae",
            "light_foreground" to "#c8f0f4",
            "bright_foreground" to "#e0fbff",
            // fauux's pink, for alerts and errors.
            "red" to "#d2738a",
            // fauux's beige.
            "yellow" to "#c1b492",
            "orange" to "#d89a7a",
            "green" to "#5fd4b4",
            "cyan" to "#73cdd4",
            "blue" to "#2f6fd6",
            "magenta" to "#8a7ad8",
            "purple" to "#8a7ad8",
            "brown" to "#3a3550",
            "bright_red" to "#e895a8",
            "bright_yellow" to "#dfd5bc",
            "bright_green" to "#8be8cf",
            "bright_cyan" to "#96d8dd",
            "bright_blue" to "#4f7ac5",
            "bright_magenta" to "#b3a6f0",
        )

    /** The launcher's text: Navi's panels are monospace. */
    const val FONT = "mono"
}
