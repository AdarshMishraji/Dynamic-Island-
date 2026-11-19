package com.akira.island.island

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.akira.island.data.IslandFont
import com.akira.island.data.IslandSettings
import com.akira.island.ui.Inter
import java.io.File

/**
 * Font and weight of the island text from "Appearance > Font": Inter (default), system font or your own file.
 * The values are Compose state: change the font in settings and the island redraws by itself.
 */
object IslandType {
    var family: FontFamily by mutableStateOf(Inter)
        private set

    /** By how many steps (of 100) all island text is bolder or lighter */
    var shift: Int by mutableIntStateOf(0)
        private set

    var actionAccent: Color? by mutableStateOf(null)
        private set

    private var key: String? = null

    /** Apply settings. Called by the island service and the settings screen on every change; a repeat does nothing. */
    fun apply(context: Context, s: IslandSettings) {
        val k = "${s.islandFont}|${s.customFontFile}"
        if (k != key) {
            key = k
            family = when (s.islandFont) {
                IslandFont.INTER -> Inter
                // System font is the one chosen in the phone settings (many skins have their own)
                IslandFont.SYSTEM -> FontFamily.Default
                IslandFont.CUSTOM -> customFamily(context, s.customFontFile) ?: Inter
            }
        }
        shift = s.fontWeightShift.coerceIn(-1, 2)
        actionAccent = if (s.actionColorEnabled) Color(s.actionColor) else null
    }

    /** Folder for your own fonts: in the app's private folder, the chosen file is not needed after copying. */
    fun fontsDir(context: Context) = File(context.filesDir, "fonts")

    /** Whether the file can be read as a font: checked on selection so the island does not break. */
    fun isFont(file: File): Boolean = runCatching { Typeface.createFromFile(file) != null }.getOrDefault(false)

    private fun customFamily(context: Context, name: String): FontFamily? {
        if (name.isEmpty()) return null
        val f = File(fontsDir(context), name)
        if (!f.isFile || !isFont(f)) return null
        return runCatching { FontFamily(Font(f)) }.getOrNull()
    }
}

/** Weight with the setting's adjustment: "Bolder" turns regular text into medium, medium into semibold, and so on. */
val FontWeight.island: FontWeight
    get() {
        val shift = IslandType.shift
        return if (shift == 0) this else FontWeight((weight + shift * 100).coerceIn(100, 900))
    }

private fun TextStyle.islandText(family: FontFamily): TextStyle =
    copy(fontFamily = family, fontWeight = (fontWeight ?: FontWeight.Normal).island)

/** Material 3 styles for the island: the chosen font and weight. */
private fun islandTypography(family: FontFamily): Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.islandText(family),
        displayMedium = t.displayMedium.islandText(family),
        displaySmall = t.displaySmall.islandText(family),
        headlineLarge = t.headlineLarge.islandText(family),
        headlineMedium = t.headlineMedium.islandText(family),
        headlineSmall = t.headlineSmall.islandText(family),
        titleLarge = t.titleLarge.islandText(family),
        titleMedium = t.titleMedium.islandText(family),
        titleSmall = t.titleSmall.islandText(family),
        bodyLarge = t.bodyLarge.islandText(family),
        bodyMedium = t.bodyMedium.islandText(family),
        bodySmall = t.bodySmall.islandText(family),
        labelLarge = t.labelLarge.islandText(family),
        labelMedium = t.labelMedium.islandText(family),
        labelSmall = t.labelSmall.islandText(family),
    )
}

/** Island theme: dark, with its font. The island window and the sample island in settings are wrapped in it. */
@Composable
fun IslandTheme(content: @Composable () -> Unit) {
    val family = IslandType.family
    val shift = IslandType.shift
    val typography = remember(family, shift) { islandTypography(family) }
    MaterialTheme(colorScheme = darkColorScheme(), typography = typography, content = content)
}

/**
 * Text and icon size from "Appearance" ([percent], 100 is as designed). The density changes, not the space:
 * the width given from outside stays the same, and inside everything is drawn larger or smaller.
 */
@Composable
fun Scaled(percent: Int, content: @Composable () -> Unit) {
    if (percent == 100) {
        content()
        return
    }
    val d = LocalDensity.current
    val k = percent.coerceIn(70, 130) / 100f
    CompositionLocalProvider(LocalDensity provides Density(d.density * k, d.fontScale), content = content)
}

/**
 * "At the camera" island in landscape: everything inside is laid out as in portrait (width along the edge
 * with the camera) and rotated by [degrees] (-90 is camera on the left, 90 on the right). Compose recalculates touches and bounds for the touch
 * window with the rotation by itself.
 */
@Composable
fun Rotated(degrees: Int, content: @Composable () -> Unit) {
    if (degrees == 0) {
        content()
        return
    }
    val conf = LocalConfiguration.current
    // Screen width for the island is as in portrait: the expanded island and notifications do not stretch to the full landscape width
    val portrait = remember(conf) {
        Configuration(conf).apply {
            val w = screenWidthDp
            screenWidthDp = screenHeightDp
            screenHeightDp = w
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
    }
    CompositionLocalProvider(LocalConfiguration provides portrait) {
        Layout(content) { measurables, constraints ->
            // Along the camera edge it is the width of the "portrait" island, from the edge inward it is the height of its window
            val along = constraints.maxHeight
            val inward = constraints.maxWidth
            val placeables = measurables.map { it.measure(Constraints.fixed(along, inward)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEach { p ->
                    if (degrees < 0) {
                        p.placeWithLayer(0, along) {
                            rotationZ = -90f
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    } else {
                        p.placeWithLayer(inward, 0) {
                            rotationZ = 90f
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                }
            }
        }
    }
}
