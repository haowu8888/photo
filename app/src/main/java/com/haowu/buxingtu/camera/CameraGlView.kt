package com.haowu.buxingtu.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.face.FaceBatch
import com.haowu.buxingtu.face.FaceShape
import com.haowu.buxingtu.filter.Filter
import com.haowu.buxingtu.filter.Filters
import com.haowu.buxingtu.gl.Beauty
import com.haowu.buxingtu.gl.FilmRenderer
import com.haowu.buxingtu.gl.TexMapping
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 实时取景：CameraX 的预览帧进 SurfaceTexture，用胶片 shader 画到屏幕。
 * SurfaceTexture 用「分离模式」创建，CameraX 要 Surface 时不必等 GL 线程就绪，
 * 第一次绘制时再挂到 GL 上下文。
 */
@SuppressLint("ViewConstructor")
class CameraGlView(context: Context, private val lutSize: Int) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile var filter: Filter = Filters.NONE
    @Volatile var intensity: Float = 1f
    @Volatile var beauty: Beauty = Beauty.OFF
    /** FaceTracker 最新检测到的脸（整帧归一化坐标，未镜像） */
    @Volatile var faces: List<FaceShape> = emptyList()
    @Volatile var effect: Effect = Effect.NONE
    /** 双重曝光第一张的纹理（GL 线程） */
    private var overlayTexture = 0

    /** GL 上下文丢失重建后回调（主线程），需要让 CameraX 重新要一次 Surface。 */
    var onContextRecreated: (() -> Unit)? = null

    private class Stream(val st: SurfaceTexture, val width: Int, val height: Int) {
        @Volatile var info: SurfaceRequest.TransformationInfo? = null
        var texture = 0 // 仅 GL 线程读写
    }

    @Volatile private var stream: Stream? = null
    private var renderer: FilmRenderer? = null
    private val stMatrix = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    private var frame = 0
    private var contexts = 0

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    val surfaceProvider = Preview.SurfaceProvider { request -> provide(request) }

    private fun provide(request: SurfaceRequest) {
        val size = request.resolution
        val st = SurfaceTexture(false)
        st.setDefaultBufferSize(size.width, size.height)
        val s = Stream(st, size.width, size.height)
        st.setOnFrameAvailableListener { requestRender() }
        val surface = Surface(st)
        val main = ContextCompat.getMainExecutor(context)
        request.setTransformationInfoListener(main) { s.info = it }
        request.provideSurface(surface, main) {
            surface.release()
            queueEvent {
                if (stream === s) stream = null
                if (s.texture != 0) {
                    runCatching { st.detachFromGLContext() }
                    glDeleteTextures(1, intArrayOf(s.texture), 0)
                }
                st.release()
            }
        }
        stream = s
    }

    /** 设置/清除双重曝光的叠加图（第一张成片的缩小版，输出方向）。 */
    fun setOverlay(bitmap: Bitmap?) = queueEvent {
        if (overlayTexture != 0) {
            glDeleteTextures(1, intArrayOf(overlayTexture), 0)
            overlayTexture = 0
        }
        if (bitmap != null) {
            val ids = IntArray(1)
            glGenTextures(1, ids, 0)
            glBindTexture(GL_TEXTURE_2D, ids[0])
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
            overlayTexture = ids[0]
        }
        requestRender()
    }

    /** 让当前帧按新参数重画一次（切滤镜、拖强度时用，不必等下一帧相机画面）。 */
    fun refresh() = requestRender()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer = FilmRenderer(context, oes = true, lutSize)
        overlayTexture = 0
        if (contexts++ > 0) {
            // 旧上下文里的纹理都失效了，旧 SurfaceTexture 也挂在旧上下文上，只能让 CameraX 重新给一个
            stream = null
            post { onContextRecreated?.invoke() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val s = stream ?: return
        val r = renderer ?: return
        try {
            if (s.texture == 0) {
                s.texture = newOesTexture()
                s.st.attachToGLContext(s.texture)
            }
            s.st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage 失败", e)
            return
        }
        s.st.getTransformMatrix(stMatrix)

        val info = s.info ?: return
        val rot = info.rotationDegrees
        val frameW = if (rot % 180 == 0) s.width else s.height
        val frameH = if (rot % 180 == 0) s.height else s.width
        val (cropX, cropY) = TexMapping.centerCrop(frameW, frameH, viewW.toFloat() / viewH)
        // 相机直连的 SurfaceTexture，系统已把传感器方向（前摄还有镜像）写进变换矩阵，
        // 只剩界面方向要补；否则要自己转、自己镜像（与 PreviewView 的 TextureView 逻辑一致）
        val remaining = if (info.hasCameraTransform()) (360 - surfaceRotationDegrees(info.targetRotation)) % 360 else rot
        val mirror = info.isMirroring && !info.hasCameraTransform()
        val corners = TexMapping.corners(remaining, mirror, cropX, cropY, TexMapping.surfaceTexture(stMatrix))

        val b = beauty
        val faceUniforms = if (b.faceEnabled) FaceBatch.of(faces, cropX, cropY, info.isMirroring, b.slim, b.eyes) else null
        r.render(s.texture, corners, viewW, viewH, filter, intensity, frame++, targetFramebuffer = 0, flipY = false,
            beauty = b, face = faceUniforms, effect = effect, overlayTexture = overlayTexture)
    }

    private fun newOesTexture(): Int {
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        // SurfaceTexture.attachToGLContext 会自己绑定，这里先解绑
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return ids[0]
    }

    companion object {
        private const val TAG = "CameraGlView"

        private fun surfaceRotationDegrees(rotation: Int) = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
