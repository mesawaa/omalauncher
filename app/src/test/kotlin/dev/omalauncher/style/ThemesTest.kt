package dev.omalauncher.style

import dev.omalauncher.IconPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemesTest {
    @Test fun namesFallBackToTheDefault() {
        assertEquals("copland", Themes.known(" Copland "))
        assertEquals("amoled", Themes.known("tokyo-night"))
        assertEquals("amoled", Themes.known(null))
        assertEquals(Copland.COLORS, Themes.colors("copland"))
    }

    @Test fun amoledIsBlackAndWhite() {
        val c = Colors.resolve(Themes.AMOLED)
        assertEquals("#000000", c["background"])
        assertEquals("#000000", c["color0"])
        assertEquals("#ffffff", c["accent"])
        assertEquals("#ffffff", c["color15"])
        assertEquals("dark", c["mode"])
    }

    @Test fun styleTomlPicksAThemeAndOverridesIt() {
        val raw = Colors.parse("# a comment\ntheme = \"copland\"\nfont = \"serif\"\naccent = \"#ff0000\"\n")
        assertEquals("copland", Themes.known(raw["theme"]))
        val c = Colors.resolve(Themes.colors(raw["theme"]) + raw.filterKeys { it != "theme" && it != "font" })
        assertEquals("#ff0000", c["accent"])
        assertEquals("#050517", c["background"])
    }

    @Test fun iconPackComponents() {
        assertEquals("com.a/com.a.Main", IconPack.componentKey("ComponentInfo{com.a/.Main}"))
        assertEquals("com.a/com.b.C", IconPack.componentKey(" ComponentInfo{com.a/com.b.C} "))
        assertNull(IconPack.componentKey("ComponentInfo{com.a}"))
        assertNull(IconPack.componentKey(":LAUNCHER_ACTION_APP_DRAWER"))
    }
}
