package dev.watchbridge.theme

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.io.File

data class ThemeSpec(
    val id: String,
    val name: String,
    val dark: Boolean,
    val primary: Color,
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val accent: Color,
    val cornerRadius: Int,
    val fontScale: Float
)

private fun color(s: String) = Color(android.graphics.Color.parseColor(s))

fun parseTheme(json: String): ThemeSpec {
    val o = JSONObject(json)
    val c = o.getJSONObject("colors")
    return ThemeSpec(
        id = o.getString("id"),
        name = o.getString("name"),
        dark = o.optBoolean("dark", true),
        primary = color(c.getString("primary")),
        background = color(c.getString("background")),
        surface = color(c.getString("surface")),
        onBackground = color(c.getString("onBackground")),
        accent = color(c.getString("accent")),
        cornerRadius = o.optInt("cornerRadius", 16),
        fontScale = o.optDouble("fontScale", 1.0).toFloat()
    )
}

/** Tema bawaan ada di assets/themes, tema buatanmu disimpan di filesDir/themes. */
class ThemeRepository(private val ctx: Context) {
    private val userDir = File(ctx.filesDir, "themes").also { it.mkdirs() }
    private val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var selectedId: String
        get() = prefs.getString("theme", "midnight") ?: "midnight"
        set(v) { prefs.edit().putString("theme", v).apply() }

    fun loadAll(): List<ThemeSpec> {
        val built = (ctx.assets.list("themes") ?: emptyArray()).mapNotNull { f ->
            runCatching { parseTheme(ctx.assets.open("themes/$f").bufferedReader().use { it.readText() }) }.getOrNull()
        }
        val user = (userDir.listFiles() ?: emptyArray()).mapNotNull { f ->
            runCatching { parseTheme(f.readText()) }.getOrNull()
        }
        return (user + built).distinctBy { it.id }
    }

    fun import(uri: Uri): Result<ThemeSpec> = runCatching {
        val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        val spec = parseTheme(text) // gagal di sini kalau JSON tidak valid
        val safe = spec.id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        File(userDir, "$safe.json").writeText(text)
        spec
    }
}

@Composable
fun WatchTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val base = if (spec.dark) darkColorScheme() else lightColorScheme()
    val onPrimary = if (spec.primary.luminance() > 0.5f) Color.Black else Color.White
    val scheme = base.copy(
        primary = spec.primary,
        onPrimary = onPrimary,
        secondary = spec.accent,
        background = spec.background,
        onBackground = spec.onBackground,
        surface = spec.surface,
        onSurface = spec.onBackground,
        surfaceVariant = spec.surface,
        onSurfaceVariant = spec.onBackground,
        secondaryContainer = spec.primary.copy(alpha = 0.25f),
        onSecondaryContainer = spec.onBackground
    )
    val r = spec.cornerRadius
    val shapes = Shapes(
        small = RoundedCornerShape((r / 2).dp),
        medium = RoundedCornerShape(r.dp),
        large = RoundedCornerShape((r * 3 / 2).dp)
    )
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * spec.fontScale)) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}
