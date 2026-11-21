package com.haowu.buxingtu.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * 3D LUT 纹理缓存，每个 GL 上下文一份。
 * LUT PNG 是 宽 N × 高 N*N，按行读出来正好是 3D 纹理要的 [b][g][r] 顺序。
 */
class LutTextures(private val context: Context) {
    private val textures = HashMap<String, Int>()
    private var identity = 0

    /** path 为 null 时返回一个 2³ 的恒等 LUT（原图）。 */
    fun get(path: String?): Int {
        if (path == null) {
            if (identity == 0) identity = upload(identityPixels(), 2)
            return identity
        }
        return textures.getOrPut(path) {
            val (pixels, size) = decode(context, path)
            upload(pixels, size)
        }
    }

    fun release() {
        val ids = (textures.values + identity).filter { it != 0 }.toIntArray()
        if (ids.isNotEmpty()) glDeleteTextures(ids.size, ids, 0)
        textures.clear()
        identity = 0
    }

    private fun upload(pixels: ByteBuffer, size: Int): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_3D, ids[0])
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, size, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        return ids[0]
    }

    private fun identityPixels(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            buf.put((r * 255).toByte()).put((g * 255).toByte()).put((b * 255).toByte()).put(-1)
        }
        buf.flip()
        return buf
    }

    companion object {
        /** 解码后的像素在两个 GL 上下文之间共享，避免重复解码 PNG。 */
        private val decoded = ConcurrentHashMap<String, Pair<ByteBuffer, Int>>()

        private fun decode(context: Context, path: String): Pair<ByteBuffer, Int> =
            decoded.getOrPut(path) {
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPremultiplied = false
                    inScaled = false
                }
                val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
                    ?: error("无法读取 LUT: $path")
                val size = bmp.width
                check(bmp.height == size * size) { "LUT 尺寸不对: ${bmp.width}x${bmp.height}" }
                val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
                bmp.copyPixelsToBuffer(buf)
                bmp.recycle()
                buf.flip()
                buf to size
            }.let { (buf, size) -> buf.duplicate() to size }
    }
}
