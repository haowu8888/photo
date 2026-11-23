package com.haowu.buxingtu.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.haowu.buxingtu.preset.Body
import com.haowu.buxingtu.preset.Cam

/** 相机图标：按机身类型用几何图形画，颜色取自 [Cam]。画布 4:3。 */
@Composable
fun CamIcon(cam: Cam, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(4f / 3f)) { drawCam(cam) }
}

private fun Color.light(f: Float) = lerp(this, Color.White, f)
private fun Color.dark(f: Float) = lerp(this, Color.Black, f)

private val Glass = Color(0xFF0B0F16)

private fun DrawScope.drawCam(cam: Cam) {
    val body = Color(cam.color)
    val trim = Color(cam.trim)
    val accent = Color(cam.accent)
    val w = size.width
    val h = size.height
    // 地面阴影
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(w * 0.14f, h * 0.86f), Size(w * 0.72f, h * 0.1f))
    when (cam.body) {
        Body.COMPACT -> {
            panel(0.72f, 0.15f, 0.86f, 0.24f, accent, 0.03f)
            panel(0.06f, 0.22f, 0.94f, 0.86f, body, 0.12f)
            flash(0.12f, 0.30f, 0.30f, 0.41f)
            window(0.36f, 0.30f, 0.46f, 0.40f)
            lens(0.62f, 0.60f, 0.21f, trim)
            drawCircle(accent, h * 0.03f, Offset(w * 0.17f, h * 0.74f))
        }
        Body.RANGEFINDER -> {
            panel(0.13f, 0.15f, 0.25f, 0.25f, trim, 0.02f)
            panel(0.70f, 0.15f, 0.83f, 0.25f, trim, 0.02f)
            panel(0.05f, 0.23f, 0.95f, 0.85f, trim, 0.08f)
            panel(0.05f, 0.44f, 0.95f, 0.80f, body, 0.0f, outline = false)
            window(0.12f, 0.29f, 0.24f, 0.38f)
            panel(0.32f, 0.29f, 0.44f, 0.38f, Color(0xFFE9E7DF), 0.02f)
            window(0.72f, 0.29f, 0.86f, 0.38f)
            lens(0.5f, 0.62f, 0.23f, trim, barrel = true)
            drawCircle(accent, h * 0.035f, Offset(w * 0.84f, h * 0.52f))
        }
        Body.SLR -> {
            val prism = Path().apply {
                moveTo(w * 0.32f, h * 0.38f); lineTo(w * 0.40f, h * 0.13f)
                lineTo(w * 0.60f, h * 0.13f); lineTo(w * 0.68f, h * 0.38f); close()
            }
            drawPath(prism, Brush.verticalGradient(listOf(trim.light(0.25f), trim.dark(0.1f)), h * 0.13f, h * 0.38f))
            panel(0.76f, 0.29f, 0.87f, 0.37f, accent, 0.02f)
            panel(0.06f, 0.35f, 0.94f, 0.86f, trim, 0.06f)
            panel(0.06f, 0.46f, 0.94f, 0.82f, body, 0.0f, outline = false)
            panel(0.06f, 0.40f, 0.20f, 0.86f, body.dark(0.25f), 0.05f, outline = false)
            lens(0.5f, 0.64f, 0.26f, trim.dark(0.5f), barrel = true)
        }
        Body.INSTANT -> {
            panel(0.10f, 0.08f, 0.90f, 0.92f, body, 0.14f)
            panel(0.10f, 0.64f, 0.90f, 0.92f, body.dark(0.12f), 0.0f, outline = false)
            val stripes = listOf(0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5)
            stripes.forEachIndexed { i, c ->
                val x = w * (0.45f + i * 0.022f)
                drawRect(Color(c), Offset(x, h * 0.64f), Size(w * 0.022f, h * 0.28f))
            }
            flash(0.16f, 0.14f, 0.36f, 0.27f)
            window(0.68f, 0.14f, 0.83f, 0.27f)
            lens(0.5f, 0.42f, 0.19f, trim)
            drawCircle(accent, h * 0.05f, Offset(w * 0.2f, h * 0.48f))
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(w * 0.2f, h * 0.86f), Size(w * 0.6f, h * 0.018f))
        }
        Body.DISPOSABLE -> {
            panel(0.78f, 0.12f, 0.92f, 0.22f, Color(0xFF2A2A2A), 0.01f)
            for (i in 0..4) {
                val x = w * (0.80f + i * 0.025f)
                drawLine(Color(0xFF555555), Offset(x, h * 0.13f), Offset(x, h * 0.21f), w * 0.006f)
            }
            panel(0.06f, 0.20f, 0.94f, 0.86f, body, 0.05f)
            drawRect(accent, Offset(w * 0.06f, h * 0.64f), Size(w * 0.88f, h * 0.09f))
            drawRect(trim, Offset(w * 0.06f, h * 0.74f), Size(w * 0.88f, h * 0.025f))
            flash(0.10f, 0.27f, 0.36f, 0.43f)
            panel(0.46f, 0.27f, 0.72f, 0.62f, Color(0xFF242424), 0.03f, outline = false)
            lens(0.59f, 0.45f, 0.12f, Color(0xFF3A3A3A))
            window(0.78f, 0.28f, 0.90f, 0.38f)
        }
        Body.CCD -> {
            panel(0.18f, 0.20f, 0.30f, 0.27f, accent, 0.02f)
            panel(0.06f, 0.25f, 0.94f, 0.80f, body, 0.14f)
            panel(0.56f, 0.30f, 0.88f, 0.66f, body.dark(0.18f), 0.08f, outline = false)
            lens(0.72f, 0.48f, 0.15f, trim)
            flash(0.12f, 0.33f, 0.32f, 0.42f)
            drawRect(accent, Offset(w * 0.12f, h * 0.68f), Size(w * 0.30f, h * 0.022f))
        }
        Body.DV -> {
            panel(0.36f, 0.12f, 0.82f, 0.20f, trim.dark(0.2f), 0.04f)
            panel(0.40f, 0.12f, 0.44f, 0.28f, trim.dark(0.2f), 0.01f, outline = false)
            panel(0.74f, 0.12f, 0.78f, 0.28f, trim.dark(0.2f), 0.01f, outline = false)
            panel(0.26f, 0.24f, 0.94f, 0.80f, body, 0.10f)
            panel(0.04f, 0.34f, 0.32f, 0.70f, trim, 0.05f)
            drawOval(Brush.radialGradient(listOf(Color(0xFF34405A), Glass), Offset(w * 0.07f, h * 0.48f), h * 0.2f),
                Offset(w * 0.02f, h * 0.33f), Size(w * 0.1f, h * 0.38f))
            panel(0.50f, 0.33f, 0.88f, 0.68f, Color(0xFF1B1D21), 0.04f)
            panel(0.53f, 0.37f, 0.85f, 0.64f, accent.copy(alpha = 0.35f).compositeOver(Color(0xFF263040)), 0.03f, outline = false)
            drawCircle(accent, h * 0.035f, Offset(w * 0.33f, h * 0.30f))
        }
        Body.TOY -> {
            panel(0.70f, 0.16f, 0.84f, 0.24f, accent, 0.04f)
            panel(0.08f, 0.22f, 0.92f, 0.86f, body, 0.28f)
            flash(0.14f, 0.32f, 0.30f, 0.42f)
            drawCircle(accent, h * 0.30f, Offset(w * 0.52f, h * 0.56f))
            lens(0.52f, 0.56f, 0.26f, trim)
        }
        Body.TLR -> {
            panel(0.74f, 0.58f, 0.81f, 0.72f, trim, 0.02f)
            panel(0.26f, 0.04f, 0.74f, 0.94f, body, 0.06f)
            lens(0.5f, 0.30f, 0.15f, trim)
            panel(0.30f, 0.47f, 0.70f, 0.52f, trim, 0.01f)
            lens(0.5f, 0.71f, 0.18f, trim)
        }
    }
}

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a))
}

/** 带上下渐变和细描边的圆角块，坐标是画布的比例。 */
private fun DrawScope.panel(l: Float, t: Float, r: Float, b: Float, color: Color, corner: Float, outline: Boolean = true) {
    val w = size.width
    val h = size.height
    val tl = Offset(w * l, h * t)
    val sz = Size(w * (r - l), h * (b - t))
    val radius = CornerRadius(h * corner)
    drawRoundRect(Brush.verticalGradient(listOf(color.light(0.14f), color, color.dark(0.14f)), tl.y, tl.y + sz.height), tl, sz, radius)
    if (outline) drawRoundRect(Color.Black.copy(alpha = 0.35f), tl, sz, radius, style = Stroke(w * 0.008f))
}

private fun DrawScope.window(l: Float, t: Float, r: Float, b: Float) {
    panel(l, t, r, b, Color(0xFF1A2230), 0.02f)
    val w = size.width
    val h = size.height
    drawLine(Color.White.copy(alpha = 0.35f), Offset(w * (l + (r - l) * 0.2f), h * (t + (b - t) * 0.3f)),
        Offset(w * (l + (r - l) * 0.45f), h * (t + (b - t) * 0.3f)), h * 0.012f)
}

private fun DrawScope.flash(l: Float, t: Float, r: Float, b: Float) {
    panel(l, t, r, b, Color(0xFFE9ECEF), 0.02f)
    val w = size.width
    val h = size.height
    for (i in 1..3) {
        val y = h * (t + (b - t) * i / 4f)
        drawLine(Color(0xFFB0B6BD), Offset(w * (l + 0.01f), y), Offset(w * (r - 0.01f), y), h * 0.008f)
    }
}

/** 镜头：外圈饰环 → 镜筒 → 玻璃（深色径向渐变 + 紫色镀膜反光 + 高光点）。半径按画布高度算。 */
private fun DrawScope.lens(cx: Float, cy: Float, r: Float, ring: Color, barrel: Boolean = false) {
    val c = Offset(size.width * cx, cy * size.height)
    val rad = r * size.height
    if (barrel) drawCircle(Brush.radialGradient(listOf(ring.light(0.2f), ring.dark(0.3f)), c, rad * 1.1f), rad * 1.08f, c)
    drawCircle(Brush.linearGradient(listOf(ring.light(0.45f), ring.dark(0.35f)), c - Offset(rad, rad), c + Offset(rad, rad)), rad, c)
    drawCircle(Color(0xFF15171A), rad * 0.8f, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF2C3A55), Glass), c - Offset(rad * 0.15f, rad * 0.15f), rad * 0.62f), rad * 0.62f, c)
    drawArc(Color(0xFF8E6BD8).copy(alpha = 0.45f), 200f, 70f, false, c - Offset(rad * 0.45f, rad * 0.45f), Size(rad * 0.9f, rad * 0.9f),
        style = Stroke(rad * 0.08f))
    drawCircle(Color.White.copy(alpha = 0.7f), rad * 0.1f, c - Offset(rad * 0.25f, rad * 0.25f))
}
