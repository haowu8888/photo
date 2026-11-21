package com.haowu.buxingtu.frame

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt
import kotlin.random.Random

/** 水印边框里显示的文字。 */
data class FrameInfo(
    val title: String,
    val subtitle: String,
    val params: String,
    val device: String,
) {
    companion object {
        /** 从原始 JPEG 的 EXIF 读焦距、光圈、快门、ISO。 */
        fun params(exif: ExifInterface?): String? {
            exif ?: return null
            val parts = mutableListOf<String>()
            val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            when {
                focal35 > 0 -> parts += "${focal35}mm"
                focal > 0 -> parts += "%.1fmm".format(focal)
            }
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { parts += "f/%.1f".format(it).replace(".0", "") }
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                parts += if (it >= 1) "%.1fs".format(it).replace(".0", "") else "1/${(1 / it).roundToInt()}s"
            }
            exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { parts += "ISO$it" }
            return parts.joinToString("  ").ifEmpty { null }
        }
    }
}

/**
 * 照片边框。只负责留白尺寸和装饰（底色、齿孔、文字），照片本身由调用方画进 imageRect。
 * 预览（Compose 里拿 nativeCanvas）和成片用同一套 [margins] / [draw]，所以所见即所得。
 */
enum class FrameStyle(val id: String, val label: String) {
    NONE("none", "无边框"),
    POLAROID("polaroid", "拍立得"),
    INSTAX("instax", "拍立得 mini"),
    WHITE("white", "白边"),
    INFO("info", "参数水印"),
    FILM("film", "胶片底片");

    /** 边框要求的画幅（长边/短边）；null 表示跟随用户选的画幅。 */
    fun forcedRatio(landscape: Boolean): Float? = when (this) {
        POLAROID -> 1f
        INSTAX -> if (landscape) 99f / 62f else 62f / 46f // 宽幅 / mini 相纸的画面尺寸
        FILM -> 1.5f
        else -> null
    }

    /** 图片 w×h 时四周的留白（left, top, right, bottom，像素）。 */
    fun margins(w: Float, h: Float): RectF {
        val short = minOf(w, h)
        val long = maxOf(w, h)
        return when (this) {
            NONE -> RectF()
            // 宝丽来 600：画面 79×79，相纸 88×107
            POLAROID -> RectF(0.057f * w, 0.07f * w, 0.057f * w, 0.285f * w)
            // Instax mini：画面 46×62，相纸 54×86；宽幅：画面 99×62，相纸 108×86
            INSTAX -> if (h >= w) RectF(0.087f * w, 0.148f * w, 0.087f * w, 0.374f * w)
                      else RectF(0.045f * w, 0.069f * w, 0.045f * w, 0.174f * w)
            WHITE -> RectF(0.045f * short, 0.045f * short, 0.045f * short, 0.045f * short)
            INFO -> RectF(0f, 0f, 0f, 0.12f * short)
            FILM -> if (w >= h) RectF(0.03f * long, 0.17f * long, 0.03f * long, 0.17f * long)
                    else RectF(0.17f * long, 0.03f * long, 0.17f * long, 0.03f * long)
        }
    }

    /** 画底色和装饰。outW×outH 是带边框的整张尺寸，image 是照片所在区域。 */
    fun draw(canvas: Canvas, outW: Float, outH: Float, image: RectF, info: FrameInfo) {
        when (this) {
            NONE -> {}
            POLAROID, INSTAX -> drawPaper(canvas, outW, outH, image)
            WHITE -> fill(canvas, outW, outH, Color.WHITE)
            INFO -> drawInfo(canvas, outW, outH, image, info)
            FILM -> drawFilm(canvas, outW, outH, image, info)
        }
    }

    companion object {
        fun byId(id: String?): FrameStyle = entries.firstOrNull { it.id == id } ?: NONE

        private const val PAPER = 0xFFF4F1EA.toInt()
        private const val FILM_BASE = 0xFF15110E.toInt()
        private const val FILM_HOLE = 0xFFEDE8DE.toInt()
        private const val FILM_TEXT = 0xFFE89A3C.toInt()
        private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        /** 只填 w×h 区域。不能用 Canvas.drawColor：在取景器里它会无视控件边界涂满整个窗口。 */
        private fun fill(canvas: Canvas, w: Float, h: Float, color: Int) {
            canvas.drawRect(0f, 0f, w, h, Paint().apply { this.color = color })
        }

        /** 相纸纹理：固定种子的 64×64 噪点图平铺，便宜且每次一样。 */
        private val paperNoise: Bitmap by lazy {
            val rnd = Random(42)
            val px = IntArray(64 * 64) {
                val a = rnd.nextInt(0, 14)
                Color.argb(a, 90, 80, 60)
            }
            Bitmap.createBitmap(px, 64, 64, Bitmap.Config.ARGB_8888)
        }

        private fun drawPaper(canvas: Canvas, w: Float, h: Float, image: RectF) {
            fill(canvas, w, h, PAPER)
            val noise = Paint().apply { shader = BitmapShader(paperNoise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
            canvas.drawRect(0f, 0f, w, h, noise)
            // 画面四周一圈很淡的压痕
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, minOf(w, h) * 0.002f)
                color = 0x33000000
            }
            canvas.drawRect(image.left - edge.strokeWidth / 2, image.top - edge.strokeWidth / 2,
                image.right + edge.strokeWidth / 2, image.bottom + edge.strokeWidth / 2, edge)
        }

        private fun drawInfo(canvas: Canvas, w: Float, h: Float, image: RectF, info: FrameInfo) {
            fill(canvas, w, h, Color.WHITE)
            val barTop = image.bottom
            val bar = h - barTop
            val pad = bar * 0.42f
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF111111.toInt()
                textSize = bar * 0.22f
                typeface = Typeface.DEFAULT_BOLD
            }
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF8A8A8A.toInt()
                textSize = bar * 0.16f
            }
            val line1 = barTop + bar * 0.47f
            val line2 = barTop + bar * 0.75f
            canvas.drawText(info.title, pad, line1, title)
            canvas.drawText(info.subtitle, pad, line2, sub)

            title.textAlign = Paint.Align.RIGHT
            sub.textAlign = Paint.Align.RIGHT
            canvas.drawText(info.params, w - pad, line1, title)
            canvas.drawText(info.device, w - pad, line2, sub)

            // 右侧文字前面：竖线 + 紫色小月亮（App 图标）
            val rightWidth = maxOf(title.measureText(info.params), sub.measureText(info.device))
            val divX = w - pad - rightWidth - bar * 0.25f
            val leftEnd = pad + maxOf(title.measureText(info.title), sub.measureText(info.subtitle))
            if (divX - bar * 0.5f > leftEnd) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDDDDDD.toInt(); strokeWidth = maxOf(1f, bar * 0.012f) }
                canvas.drawLine(divX, barTop + bar * 0.28f, divX, barTop + bar * 0.78f, p)
                p.color = 0xFF470FD5.toInt()
                val cx = divX - bar * 0.28f
                val cy = barTop + bar * 0.53f
                val r = bar * 0.12f
                canvas.drawCircle(cx, cy, r, p)
                p.color = Color.WHITE
                canvas.drawCircle(cx - r * 0.45f, cy - r * 0.35f, r * 0.8f, p)
            }
        }

        private fun drawFilm(canvas: Canvas, w: Float, h: Float, image: RectF, info: FrameInfo) {
            fill(canvas, w, h, FILM_BASE)
            canvas.save()
            // 统一按横向底片画，竖拍时整体转 90°
            val lw: Float
            val lh: Float
            if (w >= h) {
                lw = w; lh = h
            } else {
                canvas.translate(w, 0f)
                canvas.rotate(90f)
                lw = h; lh = w
            }
            val long = maxOf(image.width(), image.height())
            val band = (lh - minOf(image.width(), image.height())) / 2
            val holeW = 0.055f * long
            val holeH = 0.0775f * long
            val pitch = 0.132f * long
            val radius = 0.012f * long
            val holeCenter = band * 0.38f
            val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILM_HOLE }
            val count = (lw / pitch).toInt() + 1
            val start = (lw - (count - 1) * pitch) / 2
            for (i in 0 until count) {
                val cx = start + i * pitch
                for (cy in floatArrayOf(holeCenter, lh - holeCenter)) {
                    canvas.drawRoundRect(cx - holeW / 2, cy - holeH / 2, cx + holeW / 2, cy + holeH / 2, radius, radius, hole)
                }
            }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = FILM_TEXT
                typeface = mono
                textSize = 0.03f * long
            }
            val topLine = band * 0.9f
            val bottomLine = lh - band * 0.9f + text.textSize * 0.8f
            canvas.drawText("SHUITU 400", lw * 0.06f, topLine, text)
            canvas.drawText("▶ 12", lw * 0.55f, topLine, text)
            canvas.drawText("12A", lw * 0.06f, bottomLine, text)
            canvas.drawText(info.title.substringAfter("· ").substringBefore(" "), lw * 0.3f, bottomLine, text)
            canvas.drawText("▶ 13", lw * 0.8f, bottomLine, text)
            canvas.restore()
        }
    }
}
