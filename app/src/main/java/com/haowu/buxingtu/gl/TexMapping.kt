package com.haowu.buxingtu.gl

/**
 * 输出画面 → 输入纹理坐标 的映射，预览和拍照共用同一套逻辑，保证所见即所得。
 *
 * 输出坐标 u：左上原点、y 向下，已经是「摆正」后的方向。
 * 依次做：居中裁切 → 镜像（前摄）→ 反向旋转回缓冲区方向 → 转成具体纹理坐标。
 */
object TexMapping {

    /** 输出画面占「摆正后整帧」的比例（居中裁切），返回 (cropX, cropY)。 */
    fun centerCrop(frameW: Int, frameH: Int, outAspect: Float): Pair<Float, Float> {
        val frameAspect = frameW.toFloat() / frameH
        return if (outAspect > frameAspect) 1f to frameAspect / outAspect
        else outAspect / frameAspect to 1f
    }

    /**
     * @param rotation 缓冲区顺时针转多少度才是正的（CameraX 的 rotationDegrees）
     * @param toTex 缓冲区归一化坐标（左上原点、y 向下）→ 纹理坐标
     * @return 8 个 float：左上、左下、右上、右下 的纹理坐标
     */
    fun corners(
        rotation: Int,
        mirror: Boolean,
        cropX: Float,
        cropY: Float,
        toTex: (Float, Float) -> Pair<Float, Float>,
    ): FloatArray {
        val out = FloatArray(8)
        val us = floatArrayOf(0f, 0f, 1f, 1f)
        val vs = floatArrayOf(0f, 1f, 0f, 1f)
        for (i in 0 until 4) {
            var cx = 0.5f + (us[i] - 0.5f) * cropX
            val cy = 0.5f + (vs[i] - 0.5f) * cropY
            if (mirror) cx = 1f - cx
            val (bx, by) = when (((rotation % 360) + 360) % 360) {
                90 -> cy to 1f - cx
                180 -> 1f - cx to 1f - cy
                270 -> 1f - cy to cx
                else -> cx to cy
            }
            val (s, t) = toTex(bx, by)
            out[i * 2] = s
            out[i * 2 + 1] = t
        }
        return out
    }

    /** SurfaceTexture：先换成 GL 习惯的左下原点，再乘它给的变换矩阵。 */
    fun surfaceTexture(m: FloatArray): (Float, Float) -> Pair<Float, Float> = { bx, by ->
        val s = bx
        val t = 1f - by
        (m[0] * s + m[4] * t + m[12]) to (m[1] * s + m[5] * t + m[13])
    }

    /** GLUtils.texImage2D 上传的 Bitmap：第 0 行在 t=0，直接用。 */
    val bitmap: (Float, Float) -> Pair<Float, Float> = { bx, by -> bx to by }
}
