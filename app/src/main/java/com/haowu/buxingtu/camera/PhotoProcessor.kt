package com.haowu.buxingtu.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30.*
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.face.FaceBatch
import com.haowu.buxingtu.face.FaceDetectorSync
import com.haowu.buxingtu.filter.Filter
import com.haowu.buxingtu.frame.FrameInfo
import com.haowu.buxingtu.frame.FrameStyle
import com.haowu.buxingtu.gl.Beauty
import com.haowu.buxingtu.gl.FilmRenderer
import com.haowu.buxingtu.gl.Gl
import com.haowu.buxingtu.gl.TexMapping
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 成片处理：全分辨率 JPEG → 离屏 GL 跑和预览同一套滤镜 → 日期戳 → 边框 → 带 EXIF 存进相册。
 * 所有工作在一个后台线程上排队，拍照不用等上一张处理完。
 */
class PhotoProcessor(private val context: Context, private val lutSize: Int) {

    data class Job(
        val jpeg: ByteArray,
        val rotation: Int,
        val mirror: Boolean,
        /** 画幅长边 / 短边，比如 3:2 = 1.5 */
        val ratio: Float,
        val filter: Filter,
        val intensity: Float,
        val dateStamp: Boolean,
        val frame: FrameStyle,
        val beauty: Beauty,
        val effect: Effect,
        val double: DoubleStage = DoubleStage.NONE,
        /** 水印边框标题里的相机名 */
        val camName: String = filter.name,
        /** 日期戳 / 水印用的时间：拍照为 null（现在），导入的照片用原拍摄时间 */
        val date: Date? = null,
    )

    /** 双重曝光：第一张只留在内存里，第二张拍完后叠上去再保存 */
    enum class DoubleStage { NONE, FIRST, SECOND }

    /**
     * params 是这张照片的拍摄参数，水印边框的预览会沿用。
     * 双重曝光第一张：uri 为 null，overlay 是给取景器叠加用的缩小版。
     */
    data class Result(
        val uri: Uri?,
        val thumbnail: Bitmap?,
        val params: String?,
        val overlay: Bitmap? = null,
        /** 成片像素数（不含边框），用来提示用户 */
        val pixels: Long = 0,
        /** 内存不够时降了分辨率 */
        val downscaled: Boolean = false,
    )

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "photo-processor") }
    private var egl: Egl? = null
    private var renderer: FilmRenderer? = null
    private var maxSize = 4096
    /** 双重曝光第一张的成片（处理线程独占） */
    private var pendingFirst: Bitmap? = null

    fun submit(job: Job, onDone: (kotlin.Result<Result>) -> Unit) {
        executor.execute {
            onDone(runCatching {
                try {
                    process(job)
                } catch (e: Throwable) {
                    // 高像素原图在内存/显存紧张的手机上可能放不下（Java OOM、GL_OUT_OF_MEMORY、帧缓冲建不出来），降一档重试
                    if (e !is OutOfMemoryError && e !is IllegalStateException) throw e
                    System.gc()
                    process(job, minSample = 2).copy(downscaled = true)
                }
            })
        }
    }

    /** 放弃已拍的双重曝光第一张。 */
    fun cancelDouble() = executor.execute {
        pendingFirst?.recycle()
        pendingFirst = null
    }

    fun release() {
        executor.execute {
            pendingFirst?.recycle()
            renderer?.release()
            egl?.release()
            egl = null
        }
        executor.shutdown()
    }

    private fun process(job: Job, minSample: Int = 1): Result {
        ensureGl()
        val src = decode(job.jpeg, minSample)
        val rotated = job.rotation % 180 != 0
        val frameW = if (rotated) src.height else src.width
        val frameH = if (rotated) src.width else src.height
        val landscape = frameW >= frameH
        val ratio = job.frame.forcedRatio(landscape) ?: job.ratio
        val aspect = if (landscape) ratio else 1f / ratio
        val (cropX, cropY) = TexMapping.centerCrop(frameW, frameH, aspect)
        val outW = (frameW * cropX).roundToInt()
        val outH = (frameH * cropY).roundToInt()

        // 瘦脸大眼：在全分辨率原图上重新检测，比预览里的关键点准
        val face = if (job.beauty.faceEnabled) {
            runCatching { FaceDetectorSync.detect(src, job.rotation) }.getOrNull()
                ?.let { FaceBatch.of(it, cropX, cropY, job.mirror, job.beauty.slim, job.beauty.eyes) }
        } else null

        // 失败时（多半是高像素图显存不够）要把已分配的纹理、帧缓冲都释放掉，降档重试才有机会成功
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        var target: Pair<Int, Int>? = null
        var out: Bitmap
        try {
            glBindTexture(GL_TEXTURE_2D, ids[0])
            setLinearClamp()
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, src, 0)
            src.recycle()
            target = Gl.renderTarget(outW, outH)

            val corners = TexMapping.corners(job.rotation, job.mirror, cropX, cropY, TexMapping.bitmap)
            renderer!!.render(ids[0], corners, outW, outH, job.filter, job.intensity, Random.nextInt(),
                targetFramebuffer = target.second, flipY = true, beauty = job.beauty, face = face, effect = job.effect)

            glDeleteTextures(1, ids, 0) // 输入纹理用完先释放，给输出腾显存
            ids[0] = 0
            out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            try {
                readPixelsInStrips(out)
                val err = glGetError()
                if (err == GL_OUT_OF_MEMORY) throw OutOfMemoryError("显存不足")
                check(err == GL_NO_ERROR) { "GL 处理出错：$err" }
            } catch (e: Throwable) {
                out.recycle()
                throw e
            }
        } finally {
            if (ids[0] != 0) glDeleteTextures(1, ids, 0)
            if (!src.isRecycled) src.recycle()
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            target?.let(Gl::deleteTarget)
        }
        val exif = runCatching { ExifInterface(ByteArrayInputStream(job.jpeg)) }.getOrNull()
        val params = FrameInfo.params(exif)

        when (job.double) {
            DoubleStage.FIRST -> {
                pendingFirst?.recycle()
                pendingFirst = out
                val s = 1440f / maxOf(outW, outH)
                val overlay = Bitmap.createScaledBitmap(out, (outW * s).roundToInt(), (outH * s).roundToInt(), true)
                return Result(null, null, params, overlay, pixels = outW.toLong() * outH)
            }
            DoubleStage.SECOND -> pendingFirst?.let { first ->
                // 屏幕混合，和取景器里 shader 的叠加公式一致：1 - (1 - 第二张)(1 - 第一张 × 强度)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
                    alpha = (Effect.DOUBLE_AMOUNT * 255).roundToInt()
                }
                Canvas(out).drawBitmap(first, null, Rect(0, 0, outW, outH), paint)
                first.recycle()
                pendingFirst = null
            }
            DoubleStage.NONE -> {}
        }
        if (job.dateStamp) DateStamp.draw(Canvas(out), outW, outH, job.date ?: Date())
        if (job.frame != FrameStyle.NONE) out = addFrame(out, job, params, exif)

        val uri = save(out, job.jpeg)
        val thumbH = 240
        val thumb = Bitmap.createScaledBitmap(out, (thumbH * out.width / out.height.toFloat()).roundToInt(), thumbH, true)
        out.recycle()
        return Result(uri, thumb, params, pixels = outW.toLong() * outH)
    }

    /**
     * 分条读回帧缓冲写进 Bitmap，不再额外分配一整张图大小的缓冲（5000 万像素时省 200MB）。
     * GL 给的是 RGBA 字节，Bitmap.setPixels 要 ARGB 整数，逐像素换 R/B。
     */
    private fun readPixelsInStrips(out: Bitmap) {
        val w = out.width
        val stripRows = maxOf(1, (4 shl 20) / (w * 4)) // 每条约 4MB
        val buf = ByteBuffer.allocateDirect(w * stripRows * 4).order(ByteOrder.LITTLE_ENDIAN)
        val ints = IntArray(w * stripRows)
        glPixelStorei(GL_PACK_ALIGNMENT, 4)
        var y = 0
        while (y < out.height) {
            val rows = minOf(stripRows, out.height - y)
            buf.clear()
            glReadPixels(0, y, w, rows, GL_RGBA, GL_UNSIGNED_BYTE, buf)
            buf.asIntBuffer().get(ints, 0, w * rows)
            for (i in 0 until w * rows) {
                val p = ints[i] // 小端读出是 0xAABBGGRR
                ints[i] = (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p ushr 16) and 0xFF)
            }
            out.setPixels(ints, 0, w, 0, y, w, rows)
            y += rows
        }
    }

    private fun addFrame(image: Bitmap, job: Job, params: String?, exif: ExifInterface?): Bitmap {
        val m = job.frame.margins(image.width.toFloat(), image.height.toFloat())
        val left = m.left.roundToInt()
        val top = m.top.roundToInt()
        val w = image.width + left + m.right.roundToInt()
        val h = image.height + top + m.bottom.roundToInt()
        val framed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(framed)
        val rect = android.graphics.RectF(left.toFloat(), top.toFloat(), (left + image.width).toFloat(), (top + image.height).toFloat())
        val info = frameInfo(job.camName, params, exif?.getAttribute(ExifInterface.TAG_MODEL), job.date ?: Date())
        job.frame.draw(canvas, w.toFloat(), h.toFloat(), rect, info)
        canvas.drawBitmap(image, left.toFloat(), top.toFloat(), null)
        image.recycle()
        return framed
    }

    /** 解码 JPEG；超过 GPU 最大纹理尺寸时按 2 的倍数缩小。 */
    private fun decode(jpeg: ByteArray, minSample: Int = 1): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = minSample
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: error("照片解码失败")
    }

    private fun save(bitmap: Bitmap, original: ByteArray): Uri {
        val name = "ST_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
        val tmp = File(context.cacheDir, name)
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            copyExif(original, tmp, bitmap.width, bitmap.height)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) insertMediaStore(tmp, name)
            else saveLegacy(tmp, name)
        } finally {
            tmp.delete()
        }
    }

    private fun insertMediaStore(file: File, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入相册")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(file: File, name: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM)
        dir.mkdirs()
        val dest = File(dir, name)
        file.copyTo(dest, overwrite = true)
        var uri: Uri? = null
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg")) { _, u ->
            uri = u
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return uri ?: Uri.fromFile(dest)
    }

    private fun copyExif(original: ByteArray, target: File, width: Int, height: Int) {
        val src = runCatching { ExifInterface(ByteArrayInputStream(original)) }.getOrNull()
        val dst = ExifInterface(target)
        if (src != null) {
            for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "睡图")
        dst.saveAttributes()
    }

    private fun setLinearClamp() {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    private fun ensureGl() {
        if (egl != null) return
        egl = Egl()
        renderer = FilmRenderer(context, oes = false, lutSize)
        val v = IntArray(2)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, v, 0)
        val maxTex = v[0]
        glGetIntegerv(GL_MAX_VIEWPORT_DIMS, v, 0)
        maxSize = minOf(maxTex, v[0], v[1])
    }

    /** 1×1 pbuffer 的 ES 3.0 上下文，只用来离屏渲染到 FBO。 */
    private class Egl {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val context: EGLContext
        val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize 失败" }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "没有可用的 EGL 配置" }
            context = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext 失败" }
            surface = EGL14.eglCreatePbufferSurface(
                display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent 失败" }
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    companion object {
        const val ALBUM = "睡图"

        /** 水印边框文字；预览时 params 用上一张照片的，没有就显示占位。 */
        fun frameInfo(camName: String, params: String?, model: String?, date: Date = Date()): FrameInfo = FrameInfo(
            title = "睡图 · $camName",
            subtitle = SimpleDateFormat("yyyy.MM.dd  HH:mm", Locale.US).format(date),
            params = params ?: "f/--  1/--s  ISO--",
            device = model?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}",
        )
        private const val JPEG_QUALITY = 95
        private const val EGL_OPENGL_ES3_BIT_KHR = 0x40

        private val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
