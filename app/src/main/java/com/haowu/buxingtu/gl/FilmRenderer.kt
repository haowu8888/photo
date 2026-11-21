package com.haowu.buxingtu.gl

import android.content.Context
import android.opengl.GLES30.*
import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.face.FaceBatch
import com.haowu.buxingtu.filter.Filter

/**
 * 一个 GL 上下文里完整的滤镜渲染：光晕预处理 + 胶片 shader + LUT 缓存。
 * 预览（oes = true）和成片（oes = false）各持有一个，走完全相同的流程。
 */
class FilmRenderer(context: Context, oes: Boolean, private val lutSize: Int) {
    private val film = FilmProgram(context, oes)
    private val glow = GlowPass(context, oes)
    private val luts = LutTextures(context)

    /**
     * 把滤镜效果画到 [targetFramebuffer]（0 = 屏幕）的 outW×outH 视口上。
     * @param flipY 屏幕上为 false；离屏读回像素时为 true
     */
    fun render(
        inputTexture: Int,
        texCorners: FloatArray,
        outW: Int,
        outH: Int,
        filter: Filter,
        intensity: Float,
        seed: Int,
        targetFramebuffer: Int,
        flipY: Boolean,
        beauty: Beauty = Beauty.OFF,
        face: FaceBatch? = null,
        effect: Effect = Effect.NONE,
        overlayTexture: Int = 0,
    ) {
        val hal = filter.halation
        // 星芒和光晕共用模糊高光；开星芒时按星芒的参数取（更亮、更窄）
        val glowTexture = when {
            effect == Effect.STAR -> glow.render(inputTexture, texCorners, outW, outH, Effect.STAR_THRESHOLD, Effect.STAR_RADIUS)
            hal != null && intensity > 0f -> glow.render(inputTexture, texCorners, outW, outH, hal.threshold, hal.radius)
            else -> 0
        }

        glBindFramebuffer(GL_FRAMEBUFFER, targetFramebuffer)
        glViewport(0, 0, outW, outH)
        val params = FilmParams(
            lutTexture = luts.get(filter.lut),
            lutSize = if (filter.lut == null) 2 else lutSize,
            intensity = intensity,
            vignette = filter.vignette,
            grain = filter.grain,
            seed = seed,
            glowTexture = glowTexture,
            halo = hal?.amount ?: 0f,
            haloTint = hal?.tint ?: FilmParams.WHITE,
            leak = filter.leak?.amount ?: 0f,
            leakX = filter.leak?.x ?: 0f,
            leakY = filter.leak?.y ?: 0f,
            beauty = beauty,
            face = face,
            effect = effect.code,
            overlayTexture = overlayTexture,
            overlayAmount = Effect.DOUBLE_AMOUNT,
        )
        film.draw(inputTexture, texCorners, outW, outH, params, flipY)
    }

    fun release() {
        film.release()
        glow.release()
        luts.release()
    }
}
