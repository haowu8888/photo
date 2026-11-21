package com.haowu.buxingtu.camera

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 傻瓜相机式橙色日期戳，右下角。取景器叠加层用同样的比例参数。 */
object DateStamp {
    const val COLOR = 0xFFFF9A3C.toInt()
    const val GLOW = 0xCCFF5A1A.toInt()
    /** 字号、边距占短边的比例 */
    const val TEXT_SIZE = 0.042f
    const val MARGIN = 0.05f

    fun text(date: Date = Date()): String = SimpleDateFormat("''yy MM dd", Locale.US).format(date)

    fun draw(canvas: Canvas, width: Int, height: Int, date: Date = Date()) {
        val short = minOf(width, height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = short * TEXT_SIZE
            textAlign = Paint.Align.RIGHT
            setShadowLayer(textSize * 0.3f, 0f, 0f, GLOW)
        }
        val margin = short * MARGIN
        canvas.drawText(text(date), width - margin, height - margin, paint)
    }
}
