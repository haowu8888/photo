package com.haowu.buxingtu.face

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 关键点模式：每张脸都给双眼、鼻底（轮廓模式只给最显著的一张脸，合照只能瘦一个人）。 */
private fun landmarkDetector(accurate: Boolean): FaceDetector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(if (accurate) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE else FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.08f)
        .apply { if (!accurate) enableTracking() }
        .build()
)

/**
 * 预览用的实时人脸跟踪：接 CameraX ImageAnalysis 的低分辨率帧。
 * [enabled] 为 false 时直接丢帧，不跑检测（没开瘦脸大眼时不耗电）。
 */
class FaceTracker(private val onFaces: (List<FaceShape>) -> Unit) : ImageAnalysis.Analyzer {
    val executor = Executors.newSingleThreadExecutor { Thread(it, "face-tracker") }
    private val detector = landmarkDetector(accurate = false)
    @Volatile var enabled = false
    private var last: List<FaceShape> = emptyList()
    private var misses = 0

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (!enabled || media == null) {
            if (!enabled && last.isNotEmpty()) publish(emptyList())
            image.close()
            return
        }
        val rot = image.imageInfo.rotationDegrees
        val w = if (rot % 180 == 0) image.width else image.height
        val h = if (rot % 180 == 0) image.height else image.width
        detector.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener(executor) { faces ->
                val shapes = faces.mapNotNull { FaceShape.from(it, w, h) }
                if (shapes.isNotEmpty()) {
                    misses = 0
                    // 按跟踪 ID 找上一帧的同一张脸做平滑
                    publish(shapes.map { s -> s.smoothWith(last.firstOrNull { it.trackingId != null && it.trackingId == s.trackingId }) })
                } else if (++misses > 4) {
                    publish(emptyList()) // 连续几帧没脸再清掉，避免偶尔漏检时画面跳
                }
            }
            .addOnCompleteListener(executor) { image.close() }
    }

    private fun publish(shapes: List<FaceShape>) {
        last = shapes
        onFaces(shapes)
    }

    fun close() {
        detector.close()
        executor.shutdown()
    }
}

/** 成片用：在全分辨率照片上同步检测一次（在后台线程调用）。 */
object FaceDetectorSync {
    private val detector by lazy { landmarkDetector(accurate = true) }

    /** @param rotation 照片需要顺时针转多少度才是正的 */
    fun detect(bitmap: Bitmap, rotation: Int): List<FaceShape> {
        // 缩到长边 1600 检测：合照里的小脸也能检到，坐标是归一化的
        val scale = 1600f / maxOf(bitmap.width, bitmap.height)
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else bitmap
        try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(small, rotation)))
            val w = if (rotation % 180 == 0) small.width else small.height
            val h = if (rotation % 180 == 0) small.height else small.width
            return faces.mapNotNull { FaceShape.from(it, w, h) }
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }
}
