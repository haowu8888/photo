package com.haowu.buxingtu.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 人脸关键点（整帧归一化坐标）→ shader 输出坐标的换算。 */
class FaceShapeTest {
    // 竖拍 3:4 整帧，左眼在 (0.4, 0.4)，右眼在 (0.6, 0.4)
    private val shape = FaceShape(
        frameAspect = 0.75f,
        eyes = floatArrayOf(0.4f, 0.4f, 0.05f, 0.6f, 0.4f, 0.05f),
        cheeks = floatArrayOf(0.35f, 0.55f, 0.37f, 0.62f, 0.65f, 0.55f, 0.63f, 0.62f),
        centerX = 0.5f,
        centerY = 0.5f,
        width = 0.3f,
    )

    @Test
    fun noCropNoMirrorKeepsPositions() {
        val u = shape.toUniforms(1f, 1f, mirror = false, slim = 1f, eyeAmount = 1f)
        assertEquals(0.4f * 0.75f, u.eyes[0], 1e-5f) // x 换到宽高比空间要乘输出宽高比
        assertEquals(0.4f, u.eyes[1], 1e-5f)
        assertEquals(0.05f, u.eyes[2], 1e-5f)
        assertEquals(FaceShape.EYE_MAX, u.eyeAmount, 1e-5f)
    }

    @Test
    fun mirrorSwapsLeftAndRight() {
        val u = shape.toUniforms(1f, 1f, mirror = true, slim = 1f, eyeAmount = 1f)
        assertEquals(0.6f * 0.75f, u.eyes[0], 1e-5f)
        assertEquals(0.4f * 0.75f, u.eyes[3], 1e-5f)
    }

    @Test
    fun cropScalesAroundCenter() {
        // 3:4 帧裁成 2:3：宽保留 8/9，高全保留
        val cropX = (2f / 3f) / 0.75f
        val u = shape.toUniforms(cropX, 1f, mirror = false, slim = 0f, eyeAmount = 0f)
        val outAspect = 2f / 3f
        assertEquals((0.5f + (0.4f - 0.5f) / cropX) * outAspect, u.eyes[0], 1e-5f)
        assertEquals(0.4f, u.eyes[1], 1e-5f)
    }

    @Test
    fun slimPullsCheeksTowardCenter() {
        val u = shape.toUniforms(1f, 1f, mirror = false, slim = 1f, eyeAmount = 0f)
        assertTrue("左脸颊往右收", u.slim[2] > 0f)
        assertTrue("右脸颊往左收", u.slim[10] < 0f)
        assertEquals(0.3f * FaceShape.SLIM_RADIUS, u.slimRadius, 1e-5f)
    }
}
