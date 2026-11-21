package com.haowu.buxingtu.gl

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import com.haowu.buxingtu.face.FaceBatch
import com.haowu.buxingtu.face.FaceShape

/** 美颜参数，均为 0..1。 */
data class Beauty(
    val smooth: Float = 0f,
    val whiten: Float = 0f,
    val rosy: Float = 0f,
    val slim: Float = 0f,
    val eyes: Float = 0f,
) {
    val enabled: Boolean get() = smooth > 0f || whiten > 0f || rosy > 0f || faceEnabled
    /** 瘦脸大眼要人脸检测，没开时不跑 */
    val faceEnabled: Boolean get() = slim > 0f || eyes > 0f

    companion object {
        val OFF = Beauty()
        /** 一键美颜：自然不夸张的一组值 */
        val AUTO = Beauty(smooth = 0.6f, whiten = 0.35f, rosy = 0.3f, slim = 0.3f, eyes = 0.3f)
    }
}

/** 一次绘制要用的滤镜参数。 */
data class FilmParams(
    val lutTexture: Int,
    val lutSize: Int,
    val intensity: Float,
    val vignette: Float,
    val grain: Float,
    val seed: Int,
    /** GlowPass 输出；halo 为 0 时不采样 */
    val glowTexture: Int = 0,
    val halo: Float = 0f,
    val haloTint: FloatArray = WHITE,
    val leak: Float = 0f,
    val leakX: Float = 0f,
    val leakY: Float = 0f,
    val beauty: Beauty = Beauty.OFF,
    /** 瘦脸大眼；null = 没检测到脸或没开 */
    val face: FaceBatch? = null,
    /** [com.haowu.buxingtu.effect.Effect.code] */
    val effect: Int = 0,
    /** 双重曝光预览：第一张的纹理和叠加强度 */
    val overlayTexture: Int = 0,
    val overlayAmount: Float = 0f,
) {
    companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f)
    }
}

/**
 * 胶片 shader 程序。必须在持有 GL 上下文的线程上创建和使用。
 * @param oes 输入是相机的外部纹理（预览）还是普通 2D 纹理（拍照）
 */
class FilmProgram(context: Context, private val oes: Boolean) {
    private val program = Gl.program(context, "film.vert", "film.frag", oes)
    private val quad = Quad()
    private val u = listOf(
        "uInput", "uLut", "uLutSize", "uIntensity", "uVignette", "uGrain", "uGrainGrid", "uSeed", "uAspect",
        "uGlow", "uHalo", "uHaloTint", "uLeak", "uLeakPos",
        "uSmooth", "uWhiten", "uRosy", "uBeautyRadius", "uOutSize", "uTexOrigin", "uTexU", "uTexV",
        "uFaceCount", "uEyes", "uSlimPts", "uSlimR", "uEyeAmt", "uEffect", "uOverlay", "uOverlayAmt",
    ).associateWith { glGetUniformLocation(program, it) }

    /**
     * 画满当前视口。
     * @param texCorners 输入纹理坐标，顺序 左上、左下、右上、右下（见 [TexMapping]）
     * @param flipY 为 true 时输出左上角落在帧缓冲第 0 行（glReadPixels 直接得到正向图）
     */
    fun draw(inputTexture: Int, texCorners: FloatArray, outW: Int, outH: Int, p: FilmParams, flipY: Boolean) {
        glUseProgram(program)

        glActiveTexture(GL_TEXTURE0)
        glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D, inputTexture)
        glUniform1i(u.getValue("uInput"), 0)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_3D, p.lutTexture)
        glUniform1i(u.getValue("uLut"), 1)
        glActiveTexture(GL_TEXTURE2)
        glBindTexture(GL_TEXTURE_2D, p.glowTexture)
        glUniform1i(u.getValue("uGlow"), 2)
        glActiveTexture(GL_TEXTURE3)
        glBindTexture(GL_TEXTURE_2D, p.overlayTexture)
        glUniform1i(u.getValue("uOverlay"), 3)

        val cells = GRAIN_CELLS_SHORT_SIDE / minOf(outW, outH).toFloat()
        glUniform1f(u.getValue("uLutSize"), p.lutSize.toFloat())
        glUniform1f(u.getValue("uIntensity"), p.intensity)
        glUniform1f(u.getValue("uVignette"), p.vignette)
        glUniform1f(u.getValue("uGrain"), p.grain)
        glUniform2f(u.getValue("uGrainGrid"), outW * cells, outH * cells)
        glUniform1ui(u.getValue("uSeed"), p.seed)
        glUniform1f(u.getValue("uAspect"), outW.toFloat() / outH)
        glUniform1f(u.getValue("uHalo"), if (p.glowTexture != 0) p.halo else 0f)
        glUniform3fv(u.getValue("uHaloTint"), 1, p.haloTint, 0)
        glUniform1f(u.getValue("uLeak"), p.leak)
        glUniform2f(u.getValue("uLeakPos"), p.leakX, p.leakY)
        glUniform1f(u.getValue("uSmooth"), p.beauty.smooth)
        glUniform1f(u.getValue("uWhiten"), p.beauty.whiten)
        glUniform1f(u.getValue("uRosy"), p.beauty.rosy)
        glUniform1f(u.getValue("uBeautyRadius"), minOf(outW, outH) * BEAUTY_RADIUS)
        glUniform2f(u.getValue("uOutSize"), outW.toFloat(), outH.toFloat())
        // 四角顺序 左上、左下、右上、右下：原点 = 左上，u 轴 = 右上 - 左上，v 轴 = 左下 - 左上
        glUniform2f(u.getValue("uTexOrigin"), texCorners[0], texCorners[1])
        glUniform2f(u.getValue("uTexU"), texCorners[4] - texCorners[0], texCorners[5] - texCorners[1])
        glUniform2f(u.getValue("uTexV"), texCorners[2] - texCorners[0], texCorners[3] - texCorners[1])
        glUniform1i(u.getValue("uEffect"), p.effect)
        glUniform1f(u.getValue("uOverlayAmt"), if (p.overlayTexture != 0) p.overlayAmount else 0f)
        val face = p.face
        glUniform1i(u.getValue("uFaceCount"), face?.count ?: 0)
        if (face != null) {
            glUniform3fv(u.getValue("uEyes"), FaceShape.MAX_FACES * 2, face.eyes, 0)
            glUniform4fv(u.getValue("uSlimPts"), FaceShape.MAX_FACES * 4, face.slim, 0)
            glUniform1fv(u.getValue("uSlimR"), FaceShape.MAX_FACES, face.slimRadius, 0)
            glUniform1f(u.getValue("uEyeAmt"), face.eyeAmount)
        }

        quad.draw(program, texCorners, flipY)
        glActiveTexture(GL_TEXTURE0)
    }

    fun release() = glDeleteProgram(program)

    companion object {
        /** 短边方向的颗粒格子数，成片短边 3000px 时每颗约 2px。 */
        const val GRAIN_CELLS_SHORT_SIDE = 1600f

        /** 磨皮半径占短边的比例：成片短边 3000px 时约 18px，预览 1080px 时约 6.5px。 */
        const val BEAUTY_RADIUS = 0.006f
    }
}
