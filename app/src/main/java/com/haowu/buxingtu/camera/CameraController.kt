package com.haowu.buxingtu.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Display
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.haowu.buxingtu.face.FaceTracker
import java.util.concurrent.TimeUnit

/** CameraX 绑定：4:3 预览（约 1440×1080）进 [CameraGlView]，4:3 最高分辨率拍照（含高像素档）。 */
class CameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val glView: CameraGlView,
    private val faceTracker: FaceTracker,
) {
    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set

    private val preview = Preview.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
        )
        .build()

    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(95)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                // 默认会排除「拍得慢的高像素尺寸」（如 5000 万），系统相机常用的就是这些，显式允许
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
        )
        .build()

    /** 人脸跟踪用的低分辨率分析流，和预览同为 4:3 才能直接换算坐标 */
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        .build()
        .also { it.setAnalyzer(faceTracker.executor, faceTracker) }

    init {
        glView.onContextRecreated = { rebindPreview() }
    }

    fun bind(front: Boolean, onBound: (Camera) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                p.unbindAll()
                preview.setSurfaceProvider(glView.surfaceProvider)
                // 少数设备不支持三路同时输出，退回不带人脸跟踪
                val cam = runCatching { p.bindToLifecycle(owner, selector, preview, imageCapture, analysis) }
                    .getOrElse {
                        Log.w(TAG, "不支持预览+拍照+分析同时绑定，瘦脸大眼只在成片生效", it)
                        p.unbindAll()
                        p.bindToLifecycle(owner, selector, preview, imageCapture)
                    }
                camera = cam
                onBound(cam)
            } catch (e: Exception) {
                Log.e(TAG, "绑定相机失败", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun hasCamera(front: Boolean): Boolean {
        val p = provider ?: return !front
        return runCatching {
            p.hasCamera(if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA)
        }.getOrDefault(false)
    }

    private fun rebindPreview() {
        preview.setSurfaceProvider(null)
        preview.setSurfaceProvider(glView.surfaceProvider)
    }

    fun setFlash(mode: Int) {
        imageCapture.flashMode = mode
    }

    /** 手机实际朝向（横拍时成片也是横的）。 */
    fun setTargetRotation(rotation: Int) {
        imageCapture.targetRotation = rotation
    }

    /** fx, fy：点在「摆正后整帧」里的归一化坐标。 */
    fun focus(display: Display, fx: Float, fy: Float) {
        val cam = camera ?: return
        val point = DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, 1f, 1f).createPoint(fx, fy)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun zoomBy(scale: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        cam.cameraControl.setZoomRatio((state.zoomRatio * scale).coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    fun takePicture(onCaptured: (jpeg: ByteArray, rotation: Int) -> Unit, onError: (Exception) -> Unit) {
        imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val bytes = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } finally {
                    image.close()
                }
                onCaptured(bytes, rotation)
            }

            override fun onError(exception: ImageCaptureException) = onError(exception)
        })
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
