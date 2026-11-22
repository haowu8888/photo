package com.haowu.buxingtu.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * CameraX 绑定：4:3 预览（约 1440×1080）进 [CameraGlView]，4:3 拍照（默认最高分辨率，含高像素档；可指定尺寸）。
 * 全像素模式 CameraX 用不了，拍那一张时由 [FullResCapture] 临时接管相机。
 */
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

    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var rotation = Surface.ROTATION_0
    /** 指定的拍照尺寸，null = 最高 */
    private var captureSize: Size? = null
    private var imageCapture = buildImageCapture()
    private var front = false
    private var onBound: ((Camera) -> Unit)? = null
    /** 全像素拍照进行中（相机暂时交给 Camera2） */
    private var fullResBusy = false

    private fun buildImageCapture() = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setJpegQuality(95)
        .setFlashMode(flashMode)
        .setTargetRotation(rotation)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    captureSize?.let { ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER) }
                        ?: ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                )
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

    /** size：指定拍照尺寸，null = 最高。 */
    fun bind(front: Boolean, size: Size?, onBound: (Camera) -> Unit) {
        this.front = front
        this.onBound = onBound
        if (size != captureSize) {
            captureSize = size
            imageCapture = buildImageCapture()
        }
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
        flashMode = mode
        imageCapture.flashMode = mode
    }

    /** 手机实际朝向（横拍时成片也是横的）。 */
    fun setTargetRotation(rotation: Int) {
        this.rotation = rotation
        imageCapture.targetRotation = rotation
    }

    /** 当前相机可选的拍照尺寸（4:3，大到小）、实际在用的尺寸、全像素模式尺寸（没有为 null）。 */
    data class CaptureOptions(val sizes: List<Size>, val current: Size?, val fullRes: Size?)

    @OptIn(ExperimentalCamera2Interop::class)
    fun captureOptions(): CaptureOptions? {
        val cam = camera ?: return null
        return runCatching {
            val chars = context.getSystemService(CameraManager::class.java)
                .getCameraCharacteristics(Camera2CameraInfo.from(cam.cameraInfo).cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val all = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
                map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
            val sizes = all
                .filter { abs(it.width * 3 - it.height * 4) < it.height / 25 && it.area >= 2_000_000 }
                .distinct()
                .sortedByDescending { it.area }
                .take(4)
            val full = FullResCapture.maxSize(chars)?.takeIf { it.area > (sizes.firstOrNull()?.area ?: 0L) * 1.2 }
            CaptureOptions(sizes, imageCapture.resolutionInfo?.resolution, full)
        }.onFailure { Log.w(TAG, "读取拍照尺寸失败", it) }.getOrNull()
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

    /** fullRes：用全像素模式拍（真走了全像素会先回调 onFullRes）；前摄、开闪光灯或变焦时自动退回普通拍照。 */
    fun takePicture(
        fullRes: Boolean,
        onFullRes: () -> Unit = {},
        onCaptured: (jpeg: ByteArray, rotation: Int) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        if (fullResBusy) return onError(IllegalStateException("上一张还没拍完"))
        val cam = camera
        val zoom = cam?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        if (fullRes && cam != null && !front && flashMode == ImageCapture.FLASH_MODE_OFF && abs(zoom - 1f) < 0.05f &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            val size = captureOptions()?.fullRes
            if (size != null) {
                onFullRes()
                return takeFullRes(cam, size, onCaptured, onError)
            }
        }
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

    @OptIn(ExperimentalCamera2Interop::class)
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun takeFullRes(cam: Camera, size: Size, onCaptured: (ByteArray, Int) -> Unit, onError: (Exception) -> Unit) {
        val p = provider ?: return onError(IllegalStateException("相机还没准备好"))
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        // 后摄：传感器方向减去手机转角，和 CameraX 的 rotationDegrees 算法一致
        val orientation = (sensor - rotation * 90 + 360) % 360
        val capture = FullResCapture(context, info.cameraId, size)
        val main = Handler(Looper.getMainLooper())
        fullResBusy = true

        var started = false
        val state = cam.cameraInfo.cameraState
        lateinit var observer: androidx.lifecycle.Observer<CameraState>
        fun start() {
            if (started) return
            started = true
            state.removeObserver(observer)
            thread(name = "full-res-capture") {
                val result = runCatching { capture.capture(orientation) }
                main.post {
                    fullResBusy = false
                    onBound?.let { bind(front, captureSize, it) }
                    result.fold({ (jpeg, rot) -> onCaptured(jpeg, rot) }, { onError(it as? Exception ?: RuntimeException(it)) })
                }
            }
        }
        observer = androidx.lifecycle.Observer { if (it.type == CameraState.Type.CLOSED) start() }
        // CameraX 释放相机是异步的，等它关掉再用 Camera2 打开（最多等 1.5 秒）
        p.unbindAll()
        state.observe(owner, observer)
        main.postDelayed(::start, 1500)
    }

    companion object {
        private val Size.area get() = width.toLong() * height
        private const val TAG = "CameraController"
    }
}
