package dev.omalauncher.ui

import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonts by name, for the launcher's own text (style.toml's `font`) and widgets' `font:`:
 * - the generic families: `mono`, `sans`, `serif`, `cursive`, `system`, and `icon` (the bundled Nerd glyphs)
 * - Android's own families by name: `sans-serif-condensed`, `serif-monospace`, `casual`, ...
 * - a font file by its name, without the extension: `JetBrainsMono-Regular`, `ndot57`. Looked for in
 *   Linux's font folders (~/.local/share/fonts, ~/.fonts, /usr/share/fonts, ...), then in the phone's.
 *   Case, spaces, dashes and underscores don't matter, and a prefix will do: `jetbrains mono` finds
 *   JetBrainsMono-Regular.ttf, preferring the regular style.
 * - a font file's path in Linux: `~/fonts/Mine.otf`, `/usr/share/fonts/TTF/x.ttf`.
 */
object Fonts {
    /** Where fonts are looked for by name, as guest paths; earlier folders win. */
    val GUEST_DIRS = listOf("~/.local/share/fonts", "~/.fonts", "/usr/local/share/fonts", "/usr/share/fonts")

    private val EXTENSIONS = setOf("ttf", "otf", "ttc")

    /** The family for a generic or Android system name, without reading any file; null if [name] is neither. */
    fun builtIn(name: String): FontFamily? =
        when (name.trim().lowercase()) {
            "", "default", "system" -> FontFamily.Default
            "mono", "monospace" -> FontFamily.Monospace
            "sans", "sans-serif" -> FontFamily.SansSerif
            "serif" -> FontFamily.Serif
            "cursive" -> FontFamily.Cursive
            "icon", "icons", "nerd" -> NerdSymbols
            else -> systemFamily(name.trim())
        }

    /** Android's own families (fonts.xml): an unknown name gives back the default typeface itself. */
    private fun systemFamily(name: String): FontFamily? {
        if (name.contains('/') || name.contains('.')) return null
        val typeface = runCatching { Typeface.create(name, Typeface.NORMAL) }.getOrNull() ?: return null
        return if (typeface === Typeface.DEFAULT) null else FontFamily(typeface)
    }

    /** Loaded families by spec; a miss is kept for [MISS_MS], so a font installed meanwhile shows up. */
    private val loaded = ConcurrentHashMap<String, Pair<FontFamily?, Long>>()

    /** What [load] found for [spec] so far, without touching files: null if it hasn't run or found nothing. */
    fun cached(spec: String): FontFamily? = builtIn(spec) ?: loaded[spec]?.first

    /**
     * The family [spec] names (see [Fonts]), reading fonts as needed: call off the main thread.
     * [guestFile] maps a guest path (`~/x`, `/usr/x`) to the file on the Android side. Null if
     * there's no such font.
     */
    fun load(
        spec: String,
        guestFile: (String) -> File,
    ): FontFamily? {
        builtIn(spec)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        loaded[spec]?.let { (family, at) -> if (family != null || now - at < MISS_MS) return family }
        val file = if (isPath(spec)) guestFile(spec).takeIf { it.isFile } else find(spec, guestFile)
        val family =
            file?.let { f ->
                runCatching { FontFamily(Typeface.createFromFile(f)) }
                    .onFailure { Log.w(TAG, "can't read the font $f", it) }
                    .getOrNull()
            }
        loaded[spec] = family to now
        return family
    }

    private fun isPath(spec: String) = spec.startsWith("/") || spec.startsWith("~/") || spec.substringAfterLast('.').lowercase() in EXTENSIONS

    /** Font files by their name without the extension, normalized; rebuilt at most every [MISS_MS]. */
    @Volatile private var index: Pair<List<Pair<String, File>>, Long>? = null

    private fun find(
        name: String,
        guestFile: (String) -> File,
    ): File? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        val now = SystemClock.elapsedRealtime()
        val files = index?.takeIf { now - it.second < MISS_MS }?.first ?: scan(guestFile).also { index = it to now }
        files.firstOrNull { it.first == key }?.let { return it.second }
        // A family name finds its regular style: "jetbrains mono" → JetBrainsMono-Regular.
        val prefixed = files.filter { it.first.startsWith(key) }
        return (prefixed.firstOrNull { "regular" in it.first.removePrefix(key) } ?: prefixed.minByOrNull { it.first.length })?.second
    }

    /** Linux's font folders first (the user put those there), then the phone's own fonts. */
    private fun scan(guestFile: (String) -> File): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (dir in GUEST_DIRS) {
            val host = runCatching { guestFile(dir) }.getOrNull() ?: continue
            host
                .walkTopDown()
                .maxDepth(6)
                .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
                .forEach { out += normalize(it.nameWithoutExtension) to it }
        }
        runCatching { SystemFonts.getAvailableFonts() }.getOrNull()?.forEach { font ->
            font.file?.let { out += normalize(it.nameWithoutExtension) to it }
        }
        return out
    }

    private fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    private const val MISS_MS = 15_000L
    private const val TAG = "fonts"
}
