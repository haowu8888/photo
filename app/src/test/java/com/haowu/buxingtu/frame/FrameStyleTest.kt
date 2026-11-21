package com.haowu.buxingtu.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** 用真实的 Android Canvas（Robolectric 原生图形）渲染每种边框，输出 PNG 供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class FrameStyleTest {
    private val info = FrameInfo("睡图 · CC 经典正片", "2026.11.21  14:30", "26mm  f/1.8  1/120s  ISO100", "Xiaomi 14")

    private fun photo(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(60, 120, 170), Color.rgb(230, 150, 90), Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    private fun render(style: FrameStyle, w: Int, h: Int): Bitmap {
        val m = style.margins(w.toFloat(), h.toFloat())
        val left = m.left.roundToInt()
        val top = m.top.roundToInt()
        val ow = w + left + m.right.roundToInt()
        val oh = h + top + m.bottom.roundToInt()
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        style.draw(c, ow.toFloat(), oh.toFloat(), RectF(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat()), info)
        c.drawBitmap(photo(w, h), left.toFloat(), top.toFloat(), null)
        return out
    }

    @Test
    fun renderAll() {
        val dir = File(System.getProperty("frameOut") ?: "build/frame-previews").apply { mkdirs() }
        for (style in FrameStyle.entries.filter { it != FrameStyle.NONE }) {
            for ((tag, landscape) in listOf("portrait" to false, "landscape" to true)) {
                val ratio = style.forcedRatio(landscape) ?: 1.5f
                val long = 1200
                val short = (long / ratio).roundToInt()
                val (w, h) = if (landscape) long to short else short to long
                val out = render(style, w, h)
                File(dir, "${style.id}_$tag.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    @Test
    fun polaroidIsSquareWithThickBottom() {
        val m = FrameStyle.POLAROID.margins(790f, 790f)
        assertEquals(45f, m.left, 1f)
        assertEquals(225f, m.bottom, 1f)
    }
}
