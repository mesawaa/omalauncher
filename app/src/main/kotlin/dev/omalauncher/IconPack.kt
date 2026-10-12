package dev.omalauncher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * An icon pack app in the format Nova, Lawnchair and most launchers read: an appfilter
 * (res/xml/appfilter.xml or assets/appfilter.xml) that maps activities to the pack's drawables,
 * and optionally a back, mask and front for apps it has no icon of, scaled by its `scale`.
 * Reads the pack's resources directly; call [load] off the main thread before the rest.
 */
class IconPack(
    private val context: Context,
    val packageName: String,
) {
    private lateinit var res: Resources

    /** "package/full.Activity" -> drawable name. */
    private val icons = HashMap<String, String>()

    /** Packages the pack has some icon for, for activities it doesn't name exactly. */
    private val byPackage = HashMap<String, String>()
    private val backs = ArrayList<String>()
    private var mask: String? = null
    private var front: String? = null
    private var scale = 1f

    /** Reads the appfilter; false if the pack can't be read or has none. */
    fun load(): Boolean =
        runCatching {
            res = context.packageManager.getResourcesForApplication(packageName)
            val parser = appfilter() ?: return false
            parser.use { p ->
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType != XmlPullParser.START_TAG) continue
                    when (p.name) {
                        "item" -> {
                            val component = p.getAttributeValue(null, "component")?.let(::componentKey) ?: continue
                            val drawable = p.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() } ?: continue
                            icons.putIfAbsent(component, drawable)
                            byPackage.putIfAbsent(component.substringBefore('/'), drawable)
                        }
                        "iconback" -> for (i in 0 until p.attributeCount) if (p.getAttributeName(i).startsWith("img")) backs += p.getAttributeValue(i)
                        "iconmask" -> mask = p.getAttributeValue(null, "img1")
                        "iconupon" -> front = p.getAttributeValue(null, "img1")
                        "scale" -> scale = p.getAttributeValue(null, "factor")?.toFloatOrNull()?.coerceIn(0.1f, 2f) ?: 1f
                    }
                }
            }
            true
        }.onFailure { Log.w(TAG, "can't read the icon pack $packageName", it) }.getOrDefault(false)

    /** The appfilter as resource XML, or from the pack's assets. */
    @SuppressLint("DiscouragedApi")
    private fun appfilter(): Parser? {
        val id = res.getIdentifier("appfilter", "xml", packageName)
        if (id != 0) return Parser(res.getXml(id), null)
        val stream = runCatching { res.assets.open("appfilter.xml") }.getOrNull() ?: return null
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, "utf-8")
        return Parser(parser, stream)
    }

    /** The pack's icon for [component], or for another activity of its app; null if it has none. */
    fun icon(
        component: ComponentName,
        densityDpi: Int,
    ): Drawable? {
        val name = icons["${component.packageName}/${component.className}"] ?: byPackage[component.packageName] ?: return null
        return drawable(name, densityDpi)
    }

    /**
     * An app the pack has no icon of, dressed like the pack's own: [icon] scaled onto one of its backs,
     * cut by its mask and under its front, as launchers do. Null if the pack has no back to put it on.
     */
    fun dress(
        component: ComponentName,
        icon: Drawable,
        size: Int,
        densityDpi: Int,
    ): Bitmap? {
        if (backs.isEmpty()) return null
        val back = drawable(backs[Math.floorMod(component.hashCode(), backs.size)], densityDpi) ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        back.setBounds(0, 0, size, size)
        back.draw(canvas)
        val inner = (size * scale).toInt()
        val inset = (size - inner) / 2
        icon.setBounds(inset, inset, inset + inner, inset + inner)
        icon.draw(canvas)
        mask?.let { drawable(it, densityDpi) }?.let { m ->
            // The mask's opaque parts cut the icon away.
            val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            m.setBounds(0, 0, size, size)
            m.draw(Canvas(layer))
            canvas.drawBitmap(layer, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            layer.recycle()
        }
        front?.let { drawable(it, densityDpi) }?.let { f ->
            f.setBounds(0, 0, size, size)
            f.draw(canvas)
        }
        return out
    }

    @SuppressLint("DiscouragedApi")
    private fun drawable(
        name: String,
        densityDpi: Int,
    ): Drawable? {
        val id = res.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } ?: res.getIdentifier(name, "mipmap", packageName)
        if (id == 0) return null
        return runCatching { res.getDrawableForDensity(id, densityDpi, null) }.getOrNull()
    }

    /** An XmlPullParser with the stream it reads, if any, closed after. */
    private class Parser(
        val parser: XmlPullParser,
        val stream: java.io.InputStream?,
    ) {
        fun <T> use(block: (XmlPullParser) -> T): T =
            try {
                block(parser)
            } finally {
                (parser as? android.content.res.XmlResourceParser)?.close()
                stream?.close()
            }
    }

    companion object {
        private const val TAG = "icon-pack"

        /**
         * The intents icon packs answer, by launcher. Most declare several; an app counts once.
         * The manifest lists them under <queries>, so Android lets the launcher see the packs.
         */
        val ACTIONS =
            listOf(
                "org.adw.launcher.THEMES",
                "com.novalauncher.THEME",
                "com.gau.go.launcherex.theme",
                "org.adw.launcher.icons.ACTION_PICK_ICON",
                "com.anddoes.launcher.THEME",
                "com.teslacoilsw.launcher.THEME",
            )

        /** Installed icon packs as (package, name), by name. */
        fun installed(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val found = LinkedHashMap<String, String>()
            for (action in ACTIONS) {
                val hits = runCatching { pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
                for (hit in hits) {
                    val pkg = hit.activityInfo.packageName
                    if (pkg !in found) found[pkg] = runCatching { hit.activityInfo.applicationInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
                }
            }
            return found.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
        }

        /** "ComponentInfo{pkg/.Main}" -> "pkg/pkg.Main"; null when it isn't one. */
        fun componentKey(raw: String): String? {
            val inner = raw.trim().removePrefix("ComponentInfo{").removeSuffix("}")
            val slash = inner.indexOf('/')
            if (slash <= 0 || slash == inner.length - 1) return null
            val pkg = inner.substring(0, slash)
            val cls = inner.substring(slash + 1)
            return "$pkg/${if (cls.startsWith(".")) pkg + cls else cls}"
        }
    }
}
