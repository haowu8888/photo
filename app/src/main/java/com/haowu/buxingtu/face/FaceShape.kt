package com.haowu.buxingtu.face

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.hypot

/**
 * 一张脸里瘦脸大眼要用的关键点，坐标是「摆正后整帧」的归一化坐标（x / 帧宽，y / 帧高），未镜像。
 * 长度（半径、脸宽）以帧高为单位。
 */
class FaceShape(
    /** 帧宽 / 帧高 */
    val frameAspect: Float,
    /** 两只眼睛：x, y, 半径 */
    val eyes: FloatArray,
    /** 四个脸颊控制点：x, y 各 4 个 */
    val cheeks: FloatArray,
    /** 收脸的目标点（鼻底） */
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    /** ML Kit 跟踪 ID，用来把相邻帧里的同一张脸对上做平滑 */
    val trackingId: Int? = null,
) {
    /** 和上一帧做指数平滑，减少预览里关键点抖动。 */
    fun smoothWith(prev: FaceShape?, alpha: Float = 0.55f): FaceShape {
        if (prev == null || hypot(prev.centerX - centerX, prev.centerY - centerY) > width * 0.5f) return this
        fun mix(a: Float, b: Float) = b + (a - b) * alpha
        return FaceShape(
            frameAspect,
            FloatArray(eyes.size) { mix(eyes[it], prev.eyes[it]) },
            FloatArray(cheeks.size) { mix(cheeks[it], prev.cheeks[it]) },
            mix(centerX, prev.centerX),
            mix(centerY, prev.centerY),
            mix(width, prev.width),
            trackingId,
        )
    }

    /**
     * 换算成 shader 用的输出坐标（宽高比空间：x ∈ [0, 输出宽高比]，y ∈ [0, 1]）。
     * cropX / cropY 是输出占整帧的比例（[com.haowu.buxingtu.gl.TexMapping.centerCrop]），mirror 为前摄镜像。
     */
    fun toUniforms(cropX: Float, cropY: Float, mirror: Boolean, slim: Float, eyeAmount: Float): FaceUniforms {
        val outAspect = frameAspect * cropX / cropY
        fun ox(fx: Float): Float = (0.5f + ((if (mirror) 1f - fx else fx) - 0.5f) / cropX) * outAspect
        fun oy(fy: Float): Float = 0.5f + (fy - 0.5f) / cropY
        fun len(l: Float): Float = l / cropY

        val eyeOut = FloatArray(6)
        for (i in 0 until 2) {
            eyeOut[i * 3] = ox(eyes[i * 3])
            eyeOut[i * 3 + 1] = oy(eyes[i * 3 + 1])
            eyeOut[i * 3 + 2] = len(eyes[i * 3 + 2])
        }
        val cx = ox(centerX)
        val cy = oy(centerY)
        val faceW = len(width)
        // 沿双眼连线方向水平往里收（朝鼻子收会把下巴往上提、捏尖，嘴也会被挤窄）
        var axX = eyeOut[3] - eyeOut[0]
        var axY = eyeOut[4] - eyeOut[1]
        val axLen = hypot(axX, axY).coerceAtLeast(1e-6f)
        axX /= axLen
        axY /= axLen
        val mag = faceW * SLIM_MAX * slim
        val slimOut = FloatArray(16)
        for (i in 0 until 4) {
            val px = ox(cheeks[i * 2])
            val py = oy(cheeks[i * 2 + 1])
            val inward = if ((cx - px) * axX + (cy - py) * axY >= 0f) 1f else -1f
            slimOut[i * 4] = px
            slimOut[i * 4 + 1] = py
            slimOut[i * 4 + 2] = axX * inward * mag
            slimOut[i * 4 + 3] = axY * inward * mag
        }
        return FaceUniforms(eyeOut, slimOut, faceW * SLIM_RADIUS, eyeAmount * EYE_MAX)
    }

    companion object {
        /** 瘦脸最大位移、影响半径（脸宽的比例），大眼最大放大量 */
        const val SLIM_MAX = 0.09f
        const val SLIM_RADIUS = 0.38f
        const val EYE_MAX = 0.28f

        /**
         * ML Kit 人脸关键点 → 瘦脸大眼控制点。用关键点模式（轮廓模式只给一张脸），多人合照每张脸都能处理。
         * 下颌控制点按五官比例推：以双眼连线为横轴、眼心到鼻底为竖轴，脸歪着也跟着转。
         */
        fun from(face: Face, frameW: Int, frameH: Int): FaceShape? {
            val le = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
            val re = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position ?: return null
            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
            val w = frameW.toFloat()
            val h = frameH.toFloat()
            val iod = hypot(re.x - le.x, re.y - le.y)
            if (iod < 4f) return null
            // 横轴：从图像左边的眼睛指向右边的眼睛；竖轴：眼心 → 鼻底
            val (lx, ly, rx, ry) = if (le.x <= re.x) listOf(le.x, le.y, re.x, re.y) else listOf(re.x, re.y, le.x, le.y)
            val ax = (rx - lx) / iod
            val ay = (ry - ly) / iod
            val ex = (lx + rx) / 2
            val ey = (ly + ry) / 2
            var dx = nose.x - ex
            var dy = nose.y - ey
            val dl = hypot(dx, dy).coerceAtLeast(1f)
            dx /= dl
            dy /= dl
            fun pt(down: Float, side: Float) = floatArrayOf(
                (nose.x + dx * down * iod + ax * side * iod) / w,
                (nose.y + dy * down * iod + ay * side * iod) / h,
            )
            // 控制点落在下颌线上（鼻底往下 0.3 / 0.85 倍眼距，往两侧 1.0 / 0.85 倍）
            val cheeks = pt(0.3f, -1.0f) + pt(0.85f, -0.85f) + pt(0.3f, 1.0f) + pt(0.85f, 0.85f)
            val eyeR = iod * EYE_RADIUS / h
            return FaceShape(
                frameAspect = w / h,
                eyes = floatArrayOf(lx / w, ly / h, eyeR, rx / w, ry / h, eyeR),
                cheeks = cheeks,
                centerX = nose.x / w,
                centerY = nose.y / h,
                // 脸宽只按眼距算：ML Kit 的人脸框宽窄因人、因角度变化很大（常到眼距 3 倍），会让瘦脸力度忽大忽小
                width = iod * FACE_WIDTH_IOD / h,
                trackingId = face.trackingId,
            )
        }

        /** 脸宽 = 眼距 × 这个比例 */
        const val FACE_WIDTH_IOD = 2.3f
        /** 大眼影响半径（两眼间距的比例） */
        const val EYE_RADIUS = 0.48f
        /** shader 里最多同时处理的人脸数，和 film.frag 的 MAX_FACES 一致 */
        const val MAX_FACES = 4
    }
}

/** 一张脸在 shader 里的瘦脸大眼参数（输出的宽高比空间）。 */
class FaceUniforms(
    /** 2 × (x, y, 半径) */
    val eyes: FloatArray,
    /** 4 × (x, y, 位移 dx, dy) */
    val slim: FloatArray,
    val slimRadius: Float,
    val eyeAmount: Float,
)

/** 多张脸打包成 shader 的数组 uniform（最多 [FaceShape.MAX_FACES] 张）。 */
class FaceBatch(faces: List<FaceUniforms>) {
    private val list = faces.take(FaceShape.MAX_FACES)
    val count = list.size
    val eyes = FloatArray(FaceShape.MAX_FACES * 6).also { a -> list.forEachIndexed { i, f -> f.eyes.copyInto(a, i * 6) } }
    val slim = FloatArray(FaceShape.MAX_FACES * 16).also { a -> list.forEachIndexed { i, f -> f.slim.copyInto(a, i * 16) } }
    val slimRadius = FloatArray(FaceShape.MAX_FACES).also { a -> list.forEachIndexed { i, f -> a[i] = f.slimRadius } }
    val eyeAmount = list.firstOrNull()?.eyeAmount ?: 0f

    companion object {
        /** 按脸的大小排序取前几张：合照里离镜头近的优先 */
        fun of(shapes: List<FaceShape>, cropX: Float, cropY: Float, mirror: Boolean, slim: Float, eyes: Float): FaceBatch? =
            shapes.sortedByDescending { it.width }
                .map { it.toUniforms(cropX, cropY, mirror, slim, eyes) }
                .takeIf { it.isNotEmpty() }
                ?.let(::FaceBatch)
    }
}
