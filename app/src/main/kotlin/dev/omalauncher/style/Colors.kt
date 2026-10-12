package dev.omalauncher.style

/**
 * Colors by name, as style.toml writes them (`accent = "#3fb8e0"`): parses them and
 * fills in the derived names widgets use, in the cascade Omarchy's colors.toml has.
 */
object Colors {
    private val LINE = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*"([^"]*)"""")

    /** `key = "value"` lines; anything else (comments, sections, bare values) is skipped. */
    fun parse(text: String): Map<String, String> =
        text.lineSequence().mapNotNull { LINE.find(it)?.destructured?.let { (k, v) -> k to v } }.toMap()

    fun resolve(raw: Map<String, String>): Map<String, String> {
        val c = raw.toMutableMap()
        fun alias(
            key: String,
            from: String,
        ) {
            if (c[key].isNullOrEmpty()) c[from]?.let { c[key] = it }
        }
        fun default(
            key: String,
            value: () -> String?,
        ) {
            if (c[key].isNullOrEmpty()) value()?.let { c[key] = it }
        }

        default("background") { c["color0"] }
        default("foreground") { c["color7"] }
        // Palettes that only define ANSI colorN.
        listOf(
            "red" to 1, "green" to 2, "yellow" to 3, "blue" to 4, "magenta" to 5, "cyan" to 6,
            "bright_red" to 9, "bright_green" to 10, "bright_yellow" to 11, "bright_blue" to 12,
            "bright_magenta" to 13, "bright_cyan" to 14,
        ).forEach { (key, n) -> alias(key, "color$n") }
        alias("magenta", "purple")
        alias("bright_magenta", "bright_purple")

        default("light_foreground") { c["color7"] ?: c["foreground"] }
        default("bright_foreground") { c["color15"] ?: c["foreground"] }
        default("cursor") { c["bright_foreground"] ?: c["foreground"] ?: "#ffffff" }
        default("lighter_background") { c["color0"] ?: c["background"] }
        default("dark_foreground") { c["color8"] ?: c["foreground"] }
        default("muted") { c["color8"] ?: c["dark_foreground"] }
        default("selection") { c["selection_background"] ?: c["color8"] ?: c["color0"] ?: c["background"] }
        default("selection_background") { c["selection"] }
        default("selection_foreground") { c["bright_foreground"] }
        default("brown") { c["orange"]?.let { mix(it, "#000000", 0.5) } }
        default("dark_background") { c["background"]?.let { mix(it, "#000000", 0.25) } }
        default("darker_background") { c["background"]?.let { mix(it, "#000000", 0.5) } }
        for (name in listOf("red", "yellow", "green", "cyan", "blue", "magenta")) {
            default("bright_$name") { c[name]?.let { mix(it, "#ffffff", 0.2) } }
        }
        alias("purple", "magenta")
        alias("bright_purple", "bright_magenta")
        listOf(
            "background", "red", "green", "yellow", "blue", "magenta", "cyan", "foreground",
            "muted", "bright_red", "bright_green", "bright_yellow", "bright_blue", "bright_magenta",
            "bright_cyan", "bright_foreground",
        ).forEachIndexed { n, key -> alias("color$n", key) }
        default("mode") { if (luminance(c["background"] ?: "#000000") > 0.5) "light" else "dark" }
        return c
    }

    fun mix(
        a: String,
        b: String,
        amount: Double,
    ): String {
        val x = rgb(a)
        val y = rgb(b)
        val out = IntArray(3) { i -> (x[i] * (1 - amount) + y[i] * amount + 0.5).toInt() }
        return "#%02x%02x%02x".format(out[0], out[1], out[2])
    }

    /** `#rrggbb` (or `#aarrggbb`, alpha ignored) as red, green and blue. */
    fun rgb(hex: String): IntArray {
        val h = hex.removePrefix("#").let { if (it.length == 8) it.substring(2) else it }
        return IntArray(3) { i -> h.substring(i * 2, i * 2 + 2).toInt(16) }
    }

    fun luminance(hex: String): Double = rgb(hex).let { (0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2]) / 255 }
}
