package dev.omalauncher.style

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.agentInvocation
import dev.omalauncher.linux.run
import dev.omalauncher.ui.Corners
import dev.omalauncher.ui.Fonts
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How the launcher looks: Copland OS ([Copland]) unless `~/.config/omalauncher/style.toml`
 * says otherwise. That file sets any of the colors widgets use by name (`accent = "#3fb8e0"`)
 * and the launcher's `font`; saving it restyles the launcher, widgets and new terminals at once.
 * Also keeps the home screen options (icons, corners, ...) and the guest's terminal and shell configs.
 */
class StyleStore(
    private val context: Context,
    private val proot: Proot,
    /** Whether Linux is installed and running: terminals get their colors only then. */
    private val isLinux: () -> Boolean,
) {
    /** The same file Omarchy themes were saved in, so the home screen options carry over. */
    private val prefs = context.getSharedPreferences("themes", Context.MODE_PRIVATE)

    /** style.toml on the Android side; it lives in Linux's home, so it exists only with Linux. */
    val file get() = File(proot.launcherConfig, FILE_NAME)

    /** What style.toml sets, as last read; empty without one. */
    var overrides by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The font style.toml asks for, if it found nothing by that name; shown in settings › style. */
    var missingFont by mutableStateOf<String?>(null)
        private set

    /** Every color by name, as now in use. */
    @Volatile private var colors: Map<String, String> = Colors.resolve(Copland.COLORS)

    /** The home screen options; before the first frame, so it doesn't jump. */
    fun loadOptions() {
        LauncherStyle.monochromeIcons = prefs.getBoolean(MONO_ICONS, false)
        // Bare icons on the wallpaper, like stock launchers, unless boxes were picked.
        LauncherStyle.bareAppIcons = prefs.getBoolean(BARE_APP_ICONS, true)
        LauncherStyle.recentApps = prefs.getBoolean(RECENT_APPS, true)
        LauncherStyle.corners = Corners.of(prefs.getString(CORNERS, null))
    }

    fun setMonochromeIcons(on: Boolean) {
        LauncherStyle.monochromeIcons = on
        prefs.edit().putBoolean(MONO_ICONS, on).apply()
    }

    fun setBareAppIcons(on: Boolean) {
        LauncherStyle.bareAppIcons = on
        prefs.edit().putBoolean(BARE_APP_ICONS, on).apply()
    }

    fun setRecentApps(on: Boolean) {
        LauncherStyle.recentApps = on
        prefs.edit().putBoolean(RECENT_APPS, on).apply()
    }

    fun setCorners(corners: Corners) {
        LauncherStyle.corners = corners
        prefs.edit().putString(CORNERS, corners.id).apply()
    }

    /** Follows style.toml from now on, including when it's created or deleted. Call once. */
    fun start(scope: CoroutineScope) {
        scope.launch { watchFile(file).collect { apply(Colors.parse(it)) } }
    }

    private suspend fun apply(raw: Map<String, String>) {
        val resolved = Colors.resolve(Copland.COLORS + raw.filterKeys { it !in NOT_COLORS })
        val spec = raw["font"]?.takeIf { it.isNotBlank() } ?: Copland.FONT
        val font = withContext(Dispatchers.IO) { Fonts.load(spec, ::guestFile) }
        missingFont = spec.takeIf { font == null }
        val recolor = resolved != colors
        colors = resolved
        overrides = raw
        Palette.apply(resolved, font ?: FontFamily.Monospace)
        if (isLinux()) {
            withContext(Dispatchers.IO) {
                writeTerminalColors()
                if (recolor) recolorTerminals()
            }
        }
    }

    /** Linux just came up: its terminals get the colors (a fresh install has no file for them yet). */
    suspend fun linuxReady() = withContext(Dispatchers.IO) { writeTerminalColors() }

    private fun guestFile(path: String) = proot.hostFile(if (path.startsWith("~/")) Proot.GUEST_HOME + "/" + path.removePrefix("~/") else path)

    /** foot's colors, included by foot.ini: the palette in the shape Omarchy's foot template has. */
    private fun writeTerminalColors() {
        val c = terminalColors(colors)
        fun hex(key: String) = c[key]?.removePrefix("#").orEmpty()
        val ini =
            buildString {
                append("# The launcher writes this from its colors (style.toml); edit those instead.\n")
                append("[colors-dark]\n")
                append("foreground=${hex("foreground")}\n")
                append("background=${hex("background")}\n")
                append("selection-foreground=${hex("selection_foreground")}\n")
                append("selection-background=${hex("selection_background")}\n")
                append("cursor=${hex("background")} ${hex("bright_foreground")}\n")
                REGULAR.forEachIndexed { i, key -> append("regular$i=${hex(key)}\n") }
                BRIGHT.forEachIndexed { i, key -> append("bright$i=${hex(key)}\n") }
            }
        val out = guestFile(COLORS_PATH)
        if (out.isFile && runCatching { out.readText() }.getOrNull() == ini) return
        runCatching {
            out.parentFile?.mkdirs()
            out.writeText(ini)
        }.onFailure { Log.w(TAG, "can't write the terminal colors", it) }
    }

    /** Running terminals take the new colors at once, through OSC sequences, as omarchy-theme-set-foot does. Blocking. */
    private fun recolorTerminals() {
        val c = terminalColors(colors)
        val osc =
            buildString {
                fun emit(
                    code: String,
                    key: String,
                ) = c[key]?.let { append("\u001b]$code;$it\u0007") }
                emit("10", "foreground")
                emit("11", "background")
                emit("12", "cursor")
                emit("17", "selection_background")
                emit("19", "selection_foreground")
                (REGULAR + BRIGHT).forEachIndexed { i, key -> c[key]?.let { append("\u001b]4;$i;$it\u0007") } }
            }
        runCatching {
            proot.hostFile("/tmp/omalauncher-osc").writeText(osc)
            proot.run(
                "for p in \$(pgrep -x foot); do for c in \$(pgrep -P \$p); do " +
                    "t=\$(readlink /proc/\$c/fd/1); case \$t in /dev/pts/*) cat /tmp/omalauncher-osc > \$t;; esac; done; done; true",
            )
        }.onFailure { Log.w(TAG, "can't recolor the terminals", it) }
    }

    /** The guest's terminal and shell configs, and style.toml itself to start from; see [Policy.Seeded]. */
    fun managed(): List<Managed> {
        val home = proot.home
        val dark = colors["mode"] != "light"
        val accent = colors["accent"] ?: Copland.COLORS.getValue("accent")
        val files =
            mutableListOf(
                Managed.text(File(home, ".config/foot/foot.ini"), Policy.Seeded) { FOOT_INI },
                Managed.text(File(home, ".bashrc"), Policy.Seeded) { BASHRC },
                Managed.text(File(proot.rootfs, "usr/local/bin/btop"), Policy.Owned, executable = true) { BTOP },
                Managed.text(File(proot.launcherConfig, FILE_NAME), Policy.Seeded) { STYLE_TOML },
            )
        for (gtk in listOf("gtk-3.0", "gtk-4.0")) {
            files +=
                Managed.text(File(home, ".config/$gtk/settings.ini"), Policy.Seeded) {
                    "[Settings]\ngtk-application-prefer-dark-theme=${if (dark) 1 else 0}\ngtk-theme-name=Adwaita${if (dark) "-dark" else ""}\n"
                }
            files +=
                Managed.text(File(home, ".config/$gtk/gtk.css"), Policy.Seeded) {
                    "@define-color accent_color $accent;\n@define-color accent_bg_color $accent;\n@define-color theme_selected_bg_color $accent;\n"
                }
        }
        return files
    }

    /**
     * Once, after the update that dropped Omarchy's themes: deletes their download (about 70 MB), and
     * moves the user's own themes and backgrounds into Linux's home, where Linux saw them, rather than
     * deleting them. Without Linux those two wait for the next start that has it. Blocking.
     */
    fun removeOmarchy() {
        if (prefs.getBoolean(OMARCHY_REMOVED, false)) return
        val files = context.filesDir
        runCatching {
            for (name in listOf("omarchy", ".omarchy.download", ".theme-install")) LauncherData.deleteTree(File(files, name))
            File(context.cacheDir, "theme-catalog.html").delete()
            LauncherData.deleteTree(File(context.cacheDir, "theme-catalog"))
        }.onFailure { Log.w(TAG, "can't delete Omarchy's themes", it) }
        val moved = listOf("themes" to ".config/omarchy/themes", "backgrounds" to ".config/omarchy/backgrounds").map { (name, guest) -> moveIntoLinux(File(files, name), guest) }
        if (moved.all { it }) {
            prefs.edit().remove("theme").remove("background").remove("rendered_by").remove("borders").putBoolean(OMARCHY_REMOVED, true).apply()
        }
    }

    /** Moves [from] to [guest] in Linux's home; true once [from] is gone. */
    private fun moveIntoLinux(
        from: File,
        guest: String,
    ): Boolean {
        if (!from.exists()) return true
        if (from.list().isNullOrEmpty()) return from.delete()
        if (!proot.home.isDirectory) return false
        val to = File(proot.home, guest)
        // The empty folder proot bound it over is in the way; one with files in it isn't ours to touch.
        if (to.isDirectory && to.list().isNullOrEmpty()) to.delete()
        to.parentFile?.mkdirs()
        val ok = !to.exists() && from.renameTo(to)
        Log.i(TAG, "moved $from -> $to: $ok")
        return ok
    }

    companion object {
        const val FILE_NAME = "style.toml"

        /** Guest path of the colors foot.ini includes. */
        private const val COLORS_PATH = "~/.local/state/omalauncher/foot-colors.ini"

        /** style.toml keys that aren't colors. */
        private val NOT_COLORS = setOf("font")

        private const val MONO_ICONS = "mono_icons"
        private const val BARE_APP_ICONS = "bare_app_icons"
        private const val RECENT_APPS = "recent_apps"
        private const val CORNERS = "corners"
        private const val OMARCHY_REMOVED = "omarchy_removed"
        private const val TAG = "style"

        /** The palette's names for foot's 16 colors, as Omarchy's foot template maps them. */
        private val REGULAR = listOf("background", "red", "green", "yellow", "blue", "magenta", "cyan", "foreground")
        private val BRIGHT = listOf("muted", "bright_red", "bright_green", "bright_yellow", "bright_blue", "bright_magenta", "bright_cyan", "bright_foreground")

        /** Selections in the accent, as inverted text: a shade off the background is barely visible on a phone. */
        fun terminalColors(c: Map<String, String>): Map<String, String> {
            val accent = c["accent"] ?: c["blue"] ?: return c
            val background = c["background"] ?: return c
            return c + mapOf("selection_background" to accent, "selection_foreground" to background)
        }

        /** An agent session about style.toml: it reads the file and the widget docs, then asks what to change. */
        fun editCommand(agentBin: String): String {
            val prompt =
                "~/.config/omalauncher/$FILE_NAME sets how my home-screen launcher looks: its colors by name (the same names my " +
                    "widgets use, like accent, foreground, muted and border) and its font. Saving it restyles the launcher, the " +
                    "widgets that use those names and new terminals at once. Read it, and the Colors and Fonts parts of " +
                    "~/.config/omalauncher/widgets/README.md, then ask me what to change."
            return "cd ~/.config/omalauncher && ${agentInvocation(agentBin, prompt)}; exec bash"
        }

        /** Where to start: every color Copland OS sets, and the font, each ready to change. */
        val STYLE_TOML =
            buildString {
                append("# How the launcher looks. Save this file and the launcher, widgets that use these color\n")
                append("# names and new terminals follow at once. Delete a line to get the built-in value back.\n")
                append("# Copland OS Enterprise, the OS on Lain's Navi.\n\n")
                append("# The launcher's font: mono, sans, serif, an Android font (sans-serif-condensed),\n")
                append("# a font file's name in ~/.local/share/fonts or /usr/share/fonts, or its path.\n")
                append("font = \"${Copland.FONT}\"\n\n")
                for ((key, value) in Copland.COLORS) append("$key = \"$value\"\n")
            }

        /**
         * foot's config, sized for a phone: colors from [COLORS_PATH], the font from [TerminalFont],
         * which follows the screen and Android's font size. Nerd glyphs come via fontconfig fallback.
         */
        val FOOT_INI =
            """
            [main]
            include=$COLORS_PATH
            term=xterm-256color
            include=${TerminalFont.PATH}
            pad=8x8
            workers=0
            # Copy on select: on a phone a selection is a deliberate long press, and Android shows what was copied.
            selection-target=both

            [scrollback]
            lines=10000
            multiplier=7.0

            [cursor]
            style=block
            blink=no

            [key-bindings]
            clipboard-copy=Control+Insert Control+Shift+c XF86Copy
            primary-paste=none
            clipboard-paste=Shift+Insert Control+Shift+v XF86Paste
            """.trimIndent() + "\n"

        /**
         * btop's full layout needs 80 columns, more than a terminal on a phone in
         * portrait has at a readable size, and btop then draws nothing: it opens
         * with less instead. Its first preset, cpu and processes, needs 60; under
         * that only the process list fits (44), from a copy of the config.
         * /usr/local/bin precedes /usr/bin.
         */
        val BTOP =
            """
            #!/bin/sh
            # omalauncher: btop with fewer boxes in a narrow terminal (see StyleStore.kt)
            [ ${'$'}# -eq 0 ] || exec /usr/bin/btop "${'$'}@"
            cols=${'$'}(stty size 2>/dev/null | cut -d' ' -f2)
            cols=${'$'}{cols:-80}
            [ "${'$'}cols" -ge 80 ] && exec /usr/bin/btop
            [ "${'$'}cols" -ge 60 ] && exec /usr/bin/btop -p 1
            conf=${'$'}{XDG_CONFIG_HOME:-${'$'}HOME/.config}/btop/btop.conf
            narrow=/tmp/omalauncher-btop-narrow.conf
            { grep -v -e '^shown_boxes' -e '^presets' "${'$'}conf" 2>/dev/null; echo 'shown_boxes = "proc"'; echo 'presets = "proc:0:default"'; } > "${'$'}narrow"
            exec /usr/bin/btop -c "${'$'}narrow"
            """.trimIndent() + "\n"

        /**
         * A shell that stands on its own: history, completion and a few aliases, then starship's
         * prompt if it's installed, else Navi's in plain bash.
         */
        val BASHRC =
            """
            # Written by the launcher; yours to change (it's only updated while you haven't).
            export EDITOR=micro
            [[ ${'$'}- != *i* ]] && return

            HISTSIZE=32768
            HISTFILESIZE=32768
            HISTCONTROL=ignoreboth
            shopt -s histappend checkwinsize
            [[ -r /usr/share/bash-completion/bash_completion ]] && source /usr/share/bash-completion/bash_completion

            alias ls='ls --color=auto'
            alias ll='ls -lah'
            alias grep='grep --color=auto'

            if command -v starship >/dev/null; then
              eval "${'$'}(starship init bash)"
            else
              PS1='\[\e[38;2;36;106;150m\]⌈\[\e[3;38;2;115;205;212m\]navi\[\e[0;38;2;36;106;150m\]⌋\[\e[0m\] \[\e[38;2;168;228;234m\]\W\[\e[0m\] \[\e[1;38;2;63;184;224m\]▸\[\e[0m\] '
            fi
            command -v zoxide >/dev/null && eval "${'$'}(zoxide init bash)"
            [[ -f ~/.bashrc.local ]] && source ~/.bashrc.local
            """.trimIndent() + "\n"
    }
}
