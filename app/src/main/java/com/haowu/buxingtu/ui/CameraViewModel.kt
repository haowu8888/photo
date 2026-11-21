package com.haowu.buxingtu.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.haowu.buxingtu.camera.PhotoProcessor
import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.filter.Filter
import com.haowu.buxingtu.filter.Filters
import com.haowu.buxingtu.frame.FrameStyle
import com.haowu.buxingtu.gl.Beauty
import com.haowu.buxingtu.update.Updater
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.io.File

enum class AspectRatio(val label: String, val ratio: Float) {
    R3_2("3:2", 1.5f), R4_3("4:3", 4f / 3f), R1_1("1:1", 1f), R16_9("16:9", 16f / 9f);

    fun next() = entries[(ordinal + 1) % entries.size]
}

enum class FlashMode(val label: String, val camerax: Int) {
    OFF("关", ImageCapture.FLASH_MODE_OFF),
    AUTO("自动", ImageCapture.FLASH_MODE_AUTO),
    ON("开", ImageCapture.FLASH_MODE_ON);

    fun next() = entries[(ordinal + 1) % entries.size]
}

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("camera", Context.MODE_PRIVATE)

    val catalog = Filters.load(app)
    val processor = PhotoProcessor(app, catalog.lutSize)

    var filter by mutableStateOf(catalog.filters.firstOrNull { it.id == prefs.getString("filter", "cc") } ?: catalog.filters[0])
        private set
    private val intensities = mutableStateMapOf<String, Float>()
    var ratio by mutableStateOf(AspectRatio.entries.getOrElse(prefs.getInt("ratio", 0)) { AspectRatio.R3_2 })
        private set
    var flash by mutableStateOf(FlashMode.entries.getOrElse(prefs.getInt("flash", 0)) { FlashMode.OFF })
        private set
    var timer by mutableIntStateOf(prefs.getInt("timer", 0))
        private set
    var grid by mutableStateOf(prefs.getBoolean("grid", false))
        private set
    var frame by mutableStateOf(FrameStyle.byId(prefs.getString("frame", null)))
        private set
    /** 当前边框是滤镜自动套上的（如拍立得滤镜），切走滤镜时恢复成 [userFrame] */
    private var autoFrame = false
    private var userFrame = frame

    var beauty by mutableStateOf(
        Beauty(
            prefs.getFloat("smooth", 0f), prefs.getFloat("whiten", 0f), prefs.getFloat("rosy", 0f),
            prefs.getFloat("slim", 0f), prefs.getFloat("eyes", 0f),
        )
    )
        private set
    var effect by mutableStateOf(Effect.byName(prefs.getString("effect", null)))
        private set
    /** 双重曝光：已拍第一张、等第二张 */
    var doubleArmed by mutableStateOf(false)
        private set
    /** 双重曝光第一张的缩小版，取景器叠加用 */
    var doubleOverlay by mutableStateOf<Bitmap?>(null)
        private set
    var front by mutableStateOf(false)
    /** 切滤镜时跟随滤镜默认值，用户可以单独开关 */
    var dateStamp by mutableStateOf(filter.dateStamp)

    var lastThumb by mutableStateOf<Bitmap?>(null)
        private set
    var lastUri by mutableStateOf<Uri?>(null)
        private set
    var processing by mutableIntStateOf(0)
        private set
    /** 上一张照片的拍摄参数，水印边框预览用 */
    var lastParams by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    private var resolutionShown = false
    /** 音量键等外部触发快门，每次 +1 */
    var shutterRequests by mutableIntStateOf(0)
        private set

    /** 自动更新的界面状态 */
    data class UpdateUi(
        val checking: Boolean = false,
        val release: Updater.Release? = null,
        val upToDate: Boolean = false,
        /** 下载进度 0..1，null = 没在下载 */
        val progress: Float? = null,
        /** 下载好、等安装的包 */
        val apk: File? = null,
        val error: String? = null,
    )

    var update by mutableStateOf(UpdateUi())
        private set
    var showUpdate by mutableStateOf(false)

    /** manual = 用户点的「检查更新」；自动检查每 6 小时最多一次，没新版本时不打扰 */
    fun checkUpdate(manual: Boolean) {
        if (!Updater.enabled || update.checking || update.progress != null) return
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("updateChecked", 0) < 6 * 3600_000L) return
        update = update.copy(checking = true, error = null, upToDate = false)
        viewModelScope.launch {
            update = runCatching { Updater.check() }.fold(
                onSuccess = { r ->
                    prefs.edit { putLong("updateChecked", now) }
                    if (r != null && !manual) showUpdate = true
                    UpdateUi(release = r, upToDate = r == null)
                },
                onFailure = { UpdateUi(error = it.message) },
            )
        }
    }

    fun downloadUpdate() {
        val r = update.release ?: return
        if (update.progress != null) return
        update = update.copy(progress = 0f, error = null)
        viewModelScope.launch {
            runCatching {
                Updater.download(getApplication(), r) { done, total ->
                    if (total > 0) {
                        val p = done.toFloat() / total
                        if (p - (update.progress ?: 0f) > 0.01f || done == total) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post { update = update.copy(progress = p) }
                        }
                    }
                }
            }.onSuccess { apk ->
                update = update.copy(progress = null, apk = apk)
                installUpdate()
            }.onFailure {
                update = update.copy(progress = null, error = it.message)
            }
        }
    }

    /** 已下载就交给系统安装；还没允许安装未知应用时先跳到系统设置，回来再点一次。 */
    fun installUpdate() {
        val apk = update.apk ?: return
        val app = getApplication<Application>()
        if (Updater.canInstall(app)) Updater.install(app, apk) else Updater.openInstallPermission(app)
    }

    init {
        filter.frame?.let {
            frame = FrameStyle.byId(it)
            autoFrame = true
        }
        prefs.getString("intensities", null)?.split(';')?.forEach { kv ->
            val (k, v) = kv.split('=').takeIf { it.size == 2 } ?: return@forEach
            v.toFloatOrNull()?.let { intensities[k] = it }
        }
    }

    fun intensityOf(f: Filter): Float = intensities[f.id] ?: 1f

    fun select(f: Filter) {
        if (autoFrame) {
            frame = userFrame
            autoFrame = false
        }
        f.frame?.let {
            frame = FrameStyle.byId(it)
            autoFrame = true
        }
        filter = f
        dateStamp = f.dateStamp
        prefs.edit { putString("filter", f.id) }
    }

    fun updateBeauty(value: Beauty) {
        beauty = value
        prefs.edit {
            putFloat("smooth", value.smooth)
            putFloat("whiten", value.whiten)
            putFloat("rosy", value.rosy)
            putFloat("slim", value.slim)
            putFloat("eyes", value.eyes)
        }
    }

    fun selectEffect(e: Effect) {
        if (effect == Effect.DOUBLE && e != Effect.DOUBLE) cancelDouble()
        effect = e
        prefs.edit { putString("effect", e.name) }
    }

    fun cancelDouble() {
        if (doubleArmed) processor.cancelDouble()
        doubleArmed = false
        doubleOverlay = null
    }

    /** 用户手动选的边框：记住，并取消「滤镜自动边框」状态。 */
    fun selectFrame(style: FrameStyle) {
        frame = style
        userFrame = style
        autoFrame = false
        prefs.edit { putString("frame", style.id) }
    }

    /** 竖屏取景时的画幅（长边/短边）：边框强制的优先。 */
    val previewRatio: Float get() = frame.forcedRatio(landscape = false) ?: ratio.ratio

    /** 左右滑切换到相邻滤镜。 */
    fun step(delta: Int) {
        val list = catalog.filters
        val i = list.indexOf(filter)
        select(list[((i + delta) % list.size + list.size) % list.size])
    }

    fun setIntensity(value: Float) {
        intensities[filter.id] = value
        prefs.edit { putString("intensities", intensities.entries.joinToString(";") { "${it.key}=${it.value}" }) }
    }

    fun cycleRatio() {
        ratio = ratio.next()
        prefs.edit { putInt("ratio", ratio.ordinal) }
    }

    fun cycleFlash() {
        flash = flash.next()
        prefs.edit { putInt("flash", flash.ordinal) }
    }

    fun cycleTimer() {
        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
        prefs.edit { putInt("timer", timer) }
    }

    fun toggleGrid() {
        grid = !grid
        prefs.edit { putBoolean("grid", grid) }
    }

    fun requestShutter() {
        shutterRequests++
    }

    fun process(jpeg: ByteArray, rotation: Int) {
        processing++
        val stage = when {
            effect != Effect.DOUBLE -> PhotoProcessor.DoubleStage.NONE
            doubleArmed -> PhotoProcessor.DoubleStage.SECOND
            else -> PhotoProcessor.DoubleStage.FIRST
        }
        // 处理线程按顺序执行，第一张没处理完就按第二次快门也没问题
        doubleArmed = stage == PhotoProcessor.DoubleStage.FIRST
        if (stage == PhotoProcessor.DoubleStage.SECOND) doubleOverlay = null
        val job = PhotoProcessor.Job(
            jpeg = jpeg,
            rotation = rotation,
            mirror = front,
            ratio = ratio.ratio,
            filter = filter,
            intensity = if (filter.lut == null) 0f else intensityOf(filter),
            dateStamp = dateStamp,
            frame = frame,
            beauty = beauty,
            effect = effect,
            double = stage,
        )
        processor.submit(job) { result ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                processing--
                result.onSuccess {
                    if (it.overlay != null) {
                        if (doubleArmed) doubleOverlay = it.overlay // 期间被取消就不再显示
                        else it.overlay.recycle()
                    }
                    if (it.uri != null && (!resolutionShown || it.downscaled)) {
                        // 第一张和降分辨率时提示一下成片像素，方便确认画质
                        resolutionShown = true
                        val mp = it.pixels / 10_000
                        message = if (it.downscaled) "内存不足，已降为 $mp 万像素保存" else "已保存 · $mp 万像素"
                    }
                    it.thumbnail?.let { t -> lastThumb = t }
                    it.uri?.let { u -> lastUri = u }
                    it.params?.let { p -> lastParams = p }
                }.onFailure {
                    if (stage == PhotoProcessor.DoubleStage.FIRST) doubleArmed = false
                    message = "保存失败：${it.message}"
                }
            }
        }
    }

    override fun onCleared() {
        processor.release()
    }
}
