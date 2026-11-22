package com.haowu.buxingtu.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 传感器全像素模式（Android 12+ ULTRA_HIGH_RESOLUTION_SENSOR，如 5000 万 / 2 亿像素）拍单张。
 * CameraX 1.4 不会设 SENSOR_PIXEL_MODE，这些尺寸它用不了，所以拍的时候临时绕开 CameraX 直接走 Camera2：
 * 小 YUV 流在默认像素模式下跑 3A → 一张 MAXIMUM_RESOLUTION 的 JPEG → 关相机。
 * [capture] 是阻塞的，在后台线程调用，调用前 CameraX 必须已经释放相机。
 */
@RequiresApi(Build.VERSION_CODES.S)
class FullResCapture(context: Context, private val cameraId: String, private val size: Size) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val closed = CountDownLatch(1)

    /** 返回 JPEG 和成片需要顺时针旋转的角度（同 CameraX 的 rotationDegrees）。 */
    @SuppressLint("MissingPermission")
    fun capture(jpegOrientation: Int): Pair<ByteArray, Int> {
        val thread = HandlerThread("full-res").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { handler.post(it) }
        val chars = manager.getCameraCharacteristics(cameraId)
        val previewSize = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width <= 1280 && abs(it.width * 3 - it.height * 4) < it.height / 25 }
            .maxByOrNull { it.width } ?: Size(640, 480)
        val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        val still = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        preview.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            device = open(handler)
            session = createSession(device, preview, still, executor)
            converge3A(device, session, preview, chars, handler)
            val jpeg = takeStill(device, session, still, jpegOrientation, chars, handler)
            val rotation = runCatching { ExifInterface(ByteArrayInputStream(jpeg)).rotationDegrees }.getOrDefault(0)
            return jpeg to rotation
        } finally {
            runCatching { session?.close() }
            device?.close()
            // 等相机真正关掉再交还 CameraX，否则它重开时会撞上「相机正在使用」
            if (device != null) closed.await(2, TimeUnit.SECONDS)
            preview.close()
            still.close()
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(handler: Handler): CameraDevice {
        val opened = CountDownLatch(1)
        var result: CameraDevice? = null
        var error = 0
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                result = d
                opened.countDown()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                opened.countDown()
            }

            override fun onError(d: CameraDevice, e: Int) {
                error = e
                d.close()
                opened.countDown()
            }

            override fun onClosed(d: CameraDevice) = closed.countDown()
        }, handler)
        check(opened.await(3, TimeUnit.SECONDS)) { "打开相机超时" }
        return result ?: error("打开相机失败（$error）")
    }

    private fun createSession(device: CameraDevice, preview: ImageReader, still: ImageReader, executor: Executor): CameraCaptureSession {
        val done = CountDownLatch(1)
        var result: CameraCaptureSession? = null
        val outputs = listOf(
            OutputConfiguration(preview.surface),
            OutputConfiguration(still.surface).apply {
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            },
        )
        device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    result = s
                    done.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) = done.countDown()
            }))
        check(done.await(3, TimeUnit.SECONDS)) { "配置相机超时" }
        return result ?: error("这台手机不支持全像素输出")
    }

    /** 默认像素模式的预览流跑一会儿，等自动曝光和连续对焦稳定（最多约 2 秒）。 */
    private fun converge3A(device: CameraDevice, session: CameraCaptureSession, preview: ImageReader,
                           chars: CameraCharacteristics, handler: Handler) {
        val done = CountDownLatch(1)
        var frames = 0
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(preview.surface)
            if (hasContinuousAf(chars)) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build()
        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                frames++
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                val afOk = af == null || af != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN && af != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                if (frames >= 5 && aeOk && afOk) done.countDown()
            }
        }, handler)
        done.await(2, TimeUnit.SECONDS)
    }

    private fun takeStill(device: CameraDevice, session: CameraCaptureSession, still: ImageReader, jpegOrientation: Int,
                          chars: CameraCharacteristics, handler: Handler): ByteArray {
        val done = CountDownLatch(1)
        var bytes: ByteArray? = null
        var failure: String? = null
        still.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                val buf = image.planes[0].buffer
                bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            }
            done.countDown()
        }, handler)
        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(still.surface)
            set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            if (hasContinuousAf(chars)) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build()
        session.capture(request, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                failure = "全像素拍照失败（${f.reason}）"
                done.countDown()
            }
        }, handler)
        // 2 亿像素的 JPEG 编码可能要好几秒
        check(done.await(10, TimeUnit.SECONDS)) { "全像素拍照超时" }
        failure?.let { error(it) }
        return bytes ?: error("全像素拍照没有拿到图像")
    }

    private fun hasContinuousAf(chars: CameraCharacteristics) =
        chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true

    companion object {
        /** 全像素模式下最大的 JPEG 尺寸；手机没开放给第三方（多数如此）就是 null。 */
        fun maxSize(chars: CameraCharacteristics): Size? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return null
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR !in caps) return null
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION) ?: return null
            return map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        }
    }
}
