package com.haowu.buxingtu.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import kotlin.math.roundToInt

/**
 * 光晕/柔光用的模糊高光：原图高光 → 短边 [SHORT_SIDE] 的小缓冲 → 两轮横竖高斯模糊。
 * 缓冲尺寸只跟输出宽高比有关，所以预览和成片的光晕大小比例一致。
 * 渲染会改动帧缓冲绑定和视口，调用方画最终画面前要自己恢复。
 */
class GlowPass(context: Context, private val oes: Boolean) {
    private val prefilter = Gl.program(context, "film.vert", "glow_prefilter.frag", oes)
    private val blur = Gl.program(context, "film.vert", "blur.frag")
    private val quad = Quad()
    private var a: Pair<Int, Int>? = null
    private var b: Pair<Int, Int>? = null
    private var width = 0
    private var height = 0

    /** @return 模糊后的高光纹理（按输出 uv 采样） */
    fun render(inputTexture: Int, texCorners: FloatArray, outW: Int, outH: Int, threshold: Float, radius: Float): Int {
        ensureTargets(outW, outH)
        val (texA, fbA) = a!!
        val (texB, fbB) = b!!
        glViewport(0, 0, width, height)

        glBindFramebuffer(GL_FRAMEBUFFER, fbA)
        glUseProgram(prefilter)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D, inputTexture)
        glUniform1i(glGetUniformLocation(prefilter, "uInput"), 0)
        glUniform1f(glGetUniformLocation(prefilter, "uThreshold"), threshold)
        quad.draw(prefilter, texCorners, flipY = true)

        glUseProgram(blur)
        glUniform1i(glGetUniformLocation(blur, "uInput"), 0)
        val step = glGetUniformLocation(blur, "uStep")
        repeat(2) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbB)
            glBindTexture(GL_TEXTURE_2D, texA)
            glUniform2f(step, radius / width, 0f)
            quad.draw(blur, Quad.UV, flipY = true)

            glBindFramebuffer(GL_FRAMEBUFFER, fbA)
            glBindTexture(GL_TEXTURE_2D, texB)
            glUniform2f(step, 0f, radius / height)
            quad.draw(blur, Quad.UV, flipY = true)
        }
        return texA
    }

    private fun ensureTargets(outW: Int, outH: Int) {
        val scale = SHORT_SIDE / minOf(outW, outH).toFloat()
        val w = maxOf(1, (outW * scale).roundToInt())
        val h = maxOf(1, (outH * scale).roundToInt())
        if (w == width && h == height && a != null) return
        releaseTargets()
        width = w
        height = h
        a = Gl.renderTarget(w, h)
        b = Gl.renderTarget(w, h)
    }

    private fun releaseTargets() {
        a?.let(Gl::deleteTarget)
        b?.let(Gl::deleteTarget)
        a = null
        b = null
    }

    fun release() {
        releaseTargets()
        glDeleteProgram(prefilter)
        glDeleteProgram(blur)
    }

    companion object {
        /** 和 tools/lutgen 的 GLOW_SHORT_SIDE 保持一致 */
        const val SHORT_SIDE = 240f
    }
}
