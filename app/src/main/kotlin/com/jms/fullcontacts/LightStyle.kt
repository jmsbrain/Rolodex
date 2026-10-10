package com.jms.fullcontacts

import android.graphics.fonts.SystemFonts
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** LightOS look: black, white, Akkurat, sizes scaled to a 600px-tall design. */
object LightStyle {
    val background = Color.Black
    val content = Color.White
    val secondary = Color(0xFFBBBBBB)

    /** Akkurat if the phone has it. */
    val font: FontFamily by lazy { loadAkkurat() ?: FontFamily.Default }

    private fun loadAkkurat(): FontFamily? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val fonts = SystemFonts.getAvailableFonts()
                .filter { it.file?.name?.startsWith("Akkurat", ignoreCase = true) == true }
                .mapNotNull { f ->
                    val file = f.file ?: return@mapNotNull null
                    Font(
                        file = file,
                        weight = FontWeight(f.style.weight),
                        style = if (f.style.slant != 0) FontStyle.Italic else FontStyle.Normal,
                    )
                }
            if (fonts.isEmpty()) null else FontFamily(fonts)
        } catch (e: Exception) {
            null
        }
    }
}

private const val BASELINE_PX = 600f

/** Text size vs. LightOS standard. */
const val TEXT_SCALE = 1.2f

/** Text size for the contact list. */
const val LIST_TEXT_SCALE = 1.3f

/** Rescales a style from [TEXT_SCALE] to [LIST_TEXT_SCALE]. */
fun TextStyle.forList(): TextStyle = copy(
    fontSize = fontSize * (LIST_TEXT_SCALE / TEXT_SCALE),
    lineHeight = lineHeight * (LIST_TEXT_SCALE / TEXT_SCALE),
)

/** Physical inches on this screen. */
@Composable
fun physicalInches(inches: Float): Dp {
    val ydpi = LocalContext.current.resources.displayMetrics.ydpi
    return with(LocalDensity.current) { (inches * ydpi).toDp() }
}

@Composable
fun designSp(px: Float): TextUnit {
    val h = LocalConfiguration.current.screenHeightDp.toFloat()
    return (px * TEXT_SCALE * h / BASELINE_PX).sp
}

@Composable
fun designDp(px: Float): Dp {
    val h = LocalConfiguration.current.screenHeightDp.toFloat()
    return (px * h / BASELINE_PX).dp
}

/** Light SDK type sizes. */
@Composable
fun headingStyle() = TextStyle(
    fontFamily = LightStyle.font, fontWeight = FontWeight.Normal,
    fontSize = designSp(38f), lineHeight = designSp(38f * 1.25f), color = LightStyle.content,
)

@Composable
fun copyStyle() = TextStyle(
    fontFamily = LightStyle.font, fontWeight = FontWeight.Normal,
    fontSize = designSp(30f), lineHeight = designSp(30f * 1.35f), color = LightStyle.content,
)

@Composable
fun paragraphStyle() = TextStyle(
    fontFamily = LightStyle.font, fontWeight = FontWeight.Normal,
    fontSize = designSp(24.5f), lineHeight = designSp(24.5f * 1.3f), color = LightStyle.content,
)

@Composable
fun labelStyle() = TextStyle(
    fontFamily = LightStyle.font, fontWeight = FontWeight.Normal,
    fontSize = designSp(16f), lineHeight = designSp(16f * 1.3f),
    letterSpacing = designSp(16f * 0.08f), color = LightStyle.secondary,
)

@Composable
fun buttonStyle() = TextStyle(
    fontFamily = LightStyle.font, fontWeight = FontWeight.Medium,
    fontSize = designSp(20f), lineHeight = designSp(20f * 1.2f),
    letterSpacing = designSp(20f * 0.15f), color = LightStyle.content,
)

/** Click with no ripple. */
@Composable
fun Modifier.flatClickable(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
