package com.haowu.buxingtu.ui

import android.content.Intent
import android.media.MediaActionSound
import android.util.Size
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timer10
import androidx.compose.material.icons.outlined.Timer3
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.haowu.buxingtu.camera.CameraController
import com.haowu.buxingtu.camera.CameraGlView
import com.haowu.buxingtu.camera.DateStamp
import com.haowu.buxingtu.camera.PhotoProcessor
import com.haowu.buxingtu.effect.Effect
import com.haowu.buxingtu.face.FaceTracker
import com.haowu.buxingtu.frame.FrameStyle
import com.haowu.buxingtu.gl.Beauty
import com.haowu.buxingtu.gl.TexMapping
import com.haowu.buxingtu.preset.ResolvedCam
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

val Accent = Color(0xFFFF7A2F)
internal val Mono = FontFamily.Monospace
private const val EFFECT_COLUMNS = 6
internal val ScreenBg = Color(0xFF101010)
private val KeyBg = Color(0xFF2A2A2A)
private val Dim = Color(0xFF8A8A8A)

/** 取景器下方弹出的托盘 */
private enum class Tray { NONE, FRAME, LENS, BEAUTY, EV }

@Composable
fun CameraScreen(vm: CameraViewModel, onController: (CameraController) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val glView = remember { CameraGlView(context, vm.catalog.lutSize) }
    val faceTracker = remember { FaceTracker { glView.faces = it } }
    val controller = remember { CameraController(context, lifecycleOwner, glView, faceTracker).also(onController) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    var zoomText by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showName by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var tray by remember { mutableStateOf(Tray.NONE) }
    var picker by remember { mutableStateOf(false) }
    val flashAlpha = remember { Animatable(0f) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { vm.importPhotos(it) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sound.release()
            faceTracker.close()
        }
    }

    LaunchedEffect(vm.front, vm.sizeChoice) {
        val size = vm.sizeChoice.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { Size(it[0], it[1]) }
        controller.bind(vm.front, size) { cam ->
            cam.cameraInfo.zoomState.observe(lifecycleOwner) { z -> zoomText = "%.1fx".format(z.zoomRatio) }
            vm.captureOptions = controller.captureOptions()
            vm.exposure = controller.exposure()
            controller.setExposure(vm.ev)
        }
    }
    LaunchedEffect(vm.flash, vm.front) { controller.setFlash(if (vm.front) FlashMode.OFF.camerax else vm.flash.camerax) }
    LaunchedEffect(vm.ev) { controller.setExposure(vm.ev) }

    // 滤镜参数同步到 GL 线程
    val filter = vm.renderFilter
    val intensity = vm.intensityOf(vm.filter)
    LaunchedEffect(filter, intensity) {
        glView.filter = filter
        glView.intensity = if (filter.lut == null) 0f else intensity
        glView.refresh()
    }
    LaunchedEffect(vm.cam) { showName++ }
    LaunchedEffect(vm.effect) {
        glView.effect = vm.effect
        glView.refresh()
    }
    LaunchedEffect(vm.doubleOverlay) { glView.setOverlay(vm.doubleOverlay) }
    LaunchedEffect(vm.beauty) {
        glView.beauty = vm.beauty
        faceTracker.enabled = vm.beauty.faceEnabled
        glView.refresh()
    }

    vm.message?.let { msg ->
        LaunchedEffect(msg) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    fun capture() {
        if (countdown > 0) return
        scope.launch {
            for (i in vm.timer downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            sound.play(MediaActionSound.SHUTTER_CLICK)
            launch {
                flashAlpha.snapTo(0.85f)
                flashAlpha.animateTo(0f, tween(260))
            }
            controller.takePicture(
                fullRes = vm.sizeChoice == FULL_RES,
                onFullRes = { vm.message = "全像素拍摄中，请拿稳…" },
                onCaptured = { jpeg, rotation -> vm.process(jpeg, rotation) },
                onError = { vm.message = "拍照失败：${it.message}" },
            )
        }
    }

    LaunchedEffect(vm.shutterRequests) { if (vm.shutterRequests > 0) capture() }
    LaunchedEffect(Unit) { vm.checkUpdate(manual = false) }
    if (vm.showUpdate) UpdateDialog(vm) { vm.showUpdate = false }

    Box(Modifier.fillMaxSize().background(ScreenBg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                vm,
                onFlip = { if (controller.hasCamera(!vm.front)) vm.front = !vm.front },
                onImport = { importer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                // 以照片宽 = 1 为单位算边框留白，整体（照片 + 边框）按比例塞进可用区域
                val frame = vm.frame
                val ratio = vm.previewRatio
                val m = frame.margins(1f, ratio)
                val outerW = 1f + m.left + m.right
                val outerH = ratio + m.top + m.bottom
                BoxWithConstraints(Modifier.aspectRatio(outerW / outerH).clip(RoundedCornerShape(4.dp))) {
                    val unit = constraints.maxWidth / outerW
                    val density = LocalDensity.current
                    if (frame != FrameStyle.NONE) {
                        val info = PhotoProcessor.frameInfo(vm.cam.cam.name, vm.lastParams, null)
                        Canvas(Modifier.fillMaxSize().clipToBounds()) {
                            val img = android.graphics.RectF(m.left * unit, m.top * unit, (m.left + 1f) * unit, (m.top + ratio) * unit)
                            drawIntoCanvas { frame.draw(it.nativeCanvas, size.width, size.height, img, info) }
                        }
                    }
                    var vfSize by remember { mutableStateOf(IntSize.Zero) }
                    Box(
                        Modifier
                            .offset { IntOffset((m.left * unit).roundToInt(), (m.top * unit).roundToInt()) }
                            .size(with(density) { unit.toDp() }, with(density) { (ratio * unit).toDp() })
                            .onSizeChanged { vfSize = it }
                            .pointerInput(vm.front) {
                                val slop = viewConfiguration.touchSlop
                                val swipe = 56.dp.toPx()
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    var pan = Offset.Zero
                                    var zooming = false
                                    var moved = false
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } >= 2) {
                                            zooming = true
                                            val z = event.calculateZoom()
                                            if (z != 1f) controller.zoomBy(z)
                                        } else if (!zooming) {
                                            pan += event.calculatePan()
                                            if (pan.getDistance() > slop) moved = true
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    if (zooming) return@awaitEachGesture
                                    if (!moved) {
                                        if (tray != Tray.NONE) {
                                            tray = Tray.NONE
                                            return@awaitEachGesture
                                        }
                                        focusPoint = down.position
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        // 预览帧是竖着的 3:4，取景框是居中裁切出来的
                                        val (cx, cy) = TexMapping.centerCrop(3, 4, w / h)
                                        val fx = 0.5f + (down.position.x / w - 0.5f) * cx
                                        val fy = 0.5f + (down.position.y / h - 0.5f) * cy
                                        view.display?.let { controller.focus(it, fx, fy) }
                                    } else if (abs(pan.x) > swipe && abs(pan.x) > abs(pan.y)) {
                                        vm.step(if (pan.x < 0) 1 else -1)
                                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    }
                                }
                            }
                    ) {
                        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
                        if (vm.grid) GridOverlay()
                        if (vm.dateStamp) DateStampOverlay(vfSize)
                        FocusRing(focusPoint) { focusPoint = null }
                        CamNameOverlay(vm.cam, showName)
                        if (vm.effect == Effect.DOUBLE) DoubleExposureHint(vm)
                        zoomText?.takeIf { it != "1.0x" }?.let {
                            Text(
                                it, color = Color.White, fontFamily = Mono, fontSize = 12.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                                    .background(Color(0x66000000), RoundedCornerShape(10.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        if (countdown > 0) {
                            Text(
                                "$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
                    }
                }

                // 托盘浮在取景区底部，不挤压取景器（外层是 Column，要显式用非作用域版本）
                androidx.compose.animation.AnimatedVisibility(
                    tray != Tray.NONE,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    var last by remember { mutableStateOf(tray) }
                    if (tray != Tray.NONE) last = tray
                    TrayPanel(vm, last)
                }
            }

            BodyPanel(
                vm = vm,
                tray = tray,
                onTray = { tray = if (tray == it) Tray.NONE else it },
                onShutter = ::capture,
                onPicker = { tray = Tray.NONE; picker = true },
                onGallery = {
                    vm.lastUri?.let { uri ->
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(intent) }
                    }
                },
            )
        }

        CamPicker(vm, picker) { picker = false }
    }
}

@Composable
private fun TopBar(vm: CameraViewModel, onFlip: () -> Unit, onImport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TopButton(icon = Icons.Outlined.Settings) { vm.showUpdate = true }
            // 有新版本时设置按钮上亮个点
            if (vm.update.release != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(Accent))
            }
        }
        val flashIcon = when (vm.flash) {
            FlashMode.OFF -> Icons.Outlined.FlashOff
            FlashMode.AUTO -> Icons.Outlined.FlashAuto
            FlashMode.ON -> Icons.Outlined.FlashOn
        }
        TopButton(icon = flashIcon, enabled = !vm.front, active = vm.flash != FlashMode.OFF) { vm.cycleFlash() }
        TopButton(
            icon = when (vm.timer) { 3 -> Icons.Outlined.Timer3; 10 -> Icons.Outlined.Timer10; else -> Icons.Outlined.Timer },
            active = vm.timer > 0,
        ) { vm.cycleTimer() }
        val forced = vm.frame.forcedRatio(landscape = false)
        TopButton(text = forced?.let(::ratioLabel) ?: vm.ratio.label, enabled = forced == null) { vm.cycleRatio() }
        TopButton(icon = if (vm.grid) Icons.Outlined.GridOn else Icons.Outlined.GridOff, active = vm.grid) { vm.toggleGrid() }
        TopButton(icon = Icons.Outlined.AddPhotoAlternate, onClick = onImport)
        TopButton(icon = Icons.Outlined.Cameraswitch, onClick = onFlip)
    }
}

private fun ratioLabel(r: Float): String = when {
    abs(r - 1f) < 0.01f -> "1:1"
    abs(r - 1.5f) < 0.01f -> "3:2"
    else -> "MINI"
}

@Composable
private fun TopButton(
    icon: ImageVector? = null,
    text: String? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> Color(0xFF555555)
        active -> Accent
        else -> Color(0xFFE6E6E6)
    }
    Box(
        Modifier
            .size(width = 44.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        if (text != null) Text(text, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** 机身面板：铭牌 → 一排实体按键 → 相册 / 快门 / 换相机。 */
@Composable
private fun BodyPanel(
    vm: CameraViewModel,
    tray: Tray,
    onTray: (Tray) -> Unit,
    onShutter: () -> Unit,
    onPicker: () -> Unit,
    onGallery: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        LeatherBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp)) {
            // 铭牌
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onPicker),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(vm.cam.cam.name, color = Color(0xFFF2F2F2), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "  ${vm.cam.cam.desc}", color = Dim, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HardKey("DATE", led = vm.dateStamp, modifier = Modifier.weight(1f)) { vm.dateStamp = !vm.dateStamp }
                HardKey("边框", led = vm.frame != FrameStyle.NONE, open = tray == Tray.FRAME, modifier = Modifier.weight(1f)) { onTray(Tray.FRAME) }
                HardKey("配件", led = vm.effect != Effect.NONE, open = tray == Tray.LENS, modifier = Modifier.weight(1f)) { onTray(Tray.LENS) }
                HardKey("美颜", led = vm.beauty.enabled, open = tray == Tray.BEAUTY, modifier = Modifier.weight(1f)) { onTray(Tray.BEAUTY) }
                HardKey(evLabel(vm), led = vm.ev != 0, open = tray == Tray.EV, enabled = vm.exposure != null, modifier = Modifier.weight(1f)) { onTray(Tray.EV) }
            }
            Row(
                Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 30.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryButton(vm.lastThumb?.asImageBitmap(), vm.processing > 0, onGallery)
                ShutterButton(Color(vm.cam.cam.accent), onShutter)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), Color(0xFF242424))))
                            .border(1.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
                            .clickable(onClick = onPicker)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CamIcon(vm.cam.cam, Modifier.fillMaxSize())
                    }
                    Text("换相机", color = Dim, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

private fun evLabel(vm: CameraViewModel): String {
    val step = vm.exposure?.step ?: return "±EV"
    if (vm.ev == 0) return "±EV"
    return "%+.1f".format(vm.ev * step)
}

/** 机身蒙皮：深灰渐变 + 细密噪点，像皮革纹。 */
@Composable
private fun LeatherBackground(modifier: Modifier) {
    val dots = remember {
        val r = Random(7)
        List(2400) { Offset(r.nextFloat(), r.nextFloat()) }
    }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF262626), Color(0xFF1A1A1A), Color(0xFF141414))))
        val pts = dots.map { Offset(it.x * size.width, it.y * size.height) }
        drawPoints(pts.subList(0, 1200), PointMode.Points, Color.White.copy(alpha = 0.035f), strokeWidth = 2.5f)
        drawPoints(pts.subList(1200, pts.size), PointMode.Points, Color.Black.copy(alpha = 0.25f), strokeWidth = 2.5f)
        // 顶部一条金属饰线
        drawLine(Brush.horizontalGradient(listOf(Color(0x00FFFFFF), Color(0x55FFFFFF), Color(0x00FFFFFF))),
            Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.5f)
    }
}

/** 实体按键：上方指示灯 + 文字，按下时稍微缩一下。 */
@Composable
private fun HardKey(
    label: String,
    led: Boolean,
    modifier: Modifier = Modifier,
    open: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "key")
    Column(
        modifier
            .scale(scale)
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(if (open) listOf(Color(0xFF3C3C3C), Color(0xFF2E2E2E)) else listOf(Color(0xFF333333), KeyBg, Color(0xFF222222))))
            .border(1.dp, if (open) Accent.copy(alpha = 0.7f) else Color(0xFF3E3E3E), RoundedCornerShape(10.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 12.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (led) Accent else Color(0xFF151515))
        )
        Text(
            label, color = if (enabled) Color(0xFFE0E0E0) else Color(0xFF666666),
            fontFamily = if (label.all { it.code < 128 || it == '±' }) Mono else null,
            fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun TrayPanel(vm: CameraViewModel, tray: Tray) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xE6161616))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        when (tray) {
            Tray.FRAME -> {
                TrayTitle("边框")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameStyle.entries.forEach { style -> Chip(style.label, style == vm.frame) { vm.selectFrame(style) } }
                }
            }
            Tray.LENS -> {
                TrayTitle("镜头配件")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Effect.entries.chunked(EFFECT_COLUMNS).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { e ->
                                Chip(e.label, e == vm.effect, Modifier.weight(1f)) { vm.selectEffect(e) }
                            }
                            repeat(EFFECT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Tray.BEAUTY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrayTitle("美颜", Modifier.weight(1f))
                    // 一键美颜：套用预设；已经是预设时再点就关掉
                    val auto = vm.beauty == Beauty.AUTO
                    Chip("一键美颜", auto) { vm.updateBeauty(if (auto) Beauty.OFF else Beauty.AUTO) }
                    if (vm.beauty.enabled) {
                        Text(
                            "重置", color = Dim, fontSize = 13.sp,
                            modifier = Modifier.clickable { vm.updateBeauty(Beauty.OFF) }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
                val b = vm.beauty
                LabeledSlider("磨皮", b.smooth) { vm.updateBeauty(b.copy(smooth = it)) }
                LabeledSlider("美白", b.whiten) { vm.updateBeauty(b.copy(whiten = it)) }
                LabeledSlider("红润", b.rosy) { vm.updateBeauty(b.copy(rosy = it)) }
                LabeledSlider("瘦脸", b.slim) { vm.updateBeauty(b.copy(slim = it)) }
                LabeledSlider("大眼", b.eyes) { vm.updateBeauty(b.copy(eyes = it)) }
            }
            Tray.EV -> {
                val e = vm.exposure ?: return@Column
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrayTitle("曝光补偿", Modifier.weight(1f))
                    Text("%+.1f EV".format(vm.ev * e.step), color = Color.White, fontFamily = Mono, fontSize = 13.sp)
                    if (vm.ev != 0) {
                        Text(
                            "归零", color = Dim, fontSize = 13.sp,
                            modifier = Modifier.clickable { vm.ev = 0 }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
                EvDial(vm.ev, e) { vm.ev = it }
            }
            Tray.NONE -> {}
        }
    }
}

@Composable
private fun TrayTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Dim, fontSize = 12.sp, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color(0xFF242424))
            .border(1.dp, if (selected) Accent else Color(0xFF333333), RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Accent else Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1)
    }
}

/** 曝光刻度尺：每档一根刻度，整数 EV 长刻度，左右拖或点。 */
@Composable
private fun EvDial(value: Int, e: CameraController.Exposure, onChange: (Int) -> Unit) {
    val view = LocalView.current
    val count = e.max - e.min
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(e) {
                fun pick(x: Float) {
                    val i = (e.min + (x / size.width * count).roundToInt()).coerceIn(e.min, e.max)
                    if (i != value) {
                        onChange(i)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { if (it.pressed) pick(it.position.x); it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        if (count <= 0) return@Canvas
        val perEv = (1f / e.step).roundToInt().coerceAtLeast(1)
        for (i in e.min..e.max) {
            val x = (i - e.min) / count.toFloat() * size.width
            val major = i % perEv == 0
            val len = if (major) size.height * 0.55f else size.height * 0.3f
            drawLine(if (i == 0) Accent else Color(0xFF888888), Offset(x, size.height * 0.15f), Offset(x, size.height * 0.15f + len),
                if (major) 2.5f else 1.5f)
        }
        val x = (value - e.min) / count.toFloat() * size.width
        drawCircle(Color.White, 6.dp.toPx(), Offset(x, size.height * 0.85f))
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1f)
            drawLine(c, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
        }
    }
}

/** 和成片 [DateStamp] 同比例的预览叠加层。 */
@Composable
private fun BoxScope.DateStampOverlay(vf: IntSize) {
    if (vf == IntSize.Zero) return
    val density = LocalDensity.current
    val short = minOf(vf.width, vf.height).toFloat()
    with(density) {
        Text(
            DateStamp.text(),
            style = TextStyle(
                color = Color(DateStamp.COLOR),
                fontFamily = Mono,
                fontWeight = FontWeight.Bold,
                fontSize = (short * DateStamp.TEXT_SIZE).toSp(),
                shadow = Shadow(Color(DateStamp.GLOW), blurRadius = short * DateStamp.TEXT_SIZE * 0.3f),
            ),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = (short * DateStamp.MARGIN).toDp(), bottom = (short * DateStamp.MARGIN * 0.75f).toDp())
        )
    }
}

@Composable
private fun FocusRing(point: Offset?, onDone: () -> Unit) {
    if (point == null) return
    val scale = remember(point) { Animatable(1.4f) }
    LaunchedEffect(point) {
        scale.animateTo(1f, tween(180))
        delay(900)
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Accent, radius = 34.dp.toPx() * scale.value, center = point, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun BoxScope.DoubleExposureHint(vm: CameraViewModel) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 10.dp)
            .background(Color(0x99000000), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (vm.doubleArmed) "双重曝光 · 再拍一张叠加" else "双重曝光 · 先拍第一张",
            color = Color.White, fontSize = 12.sp,
        )
        if (vm.doubleArmed) {
            Text(
                "重拍", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp).clickable { vm.cancelDouble() }
            )
        }
    }
}

@Composable
private fun BoxScope.CamNameOverlay(rc: ResolvedCam, trigger: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger <= 1) return@LaunchedEffect // 启动时不弹
        visible = true
        delay(1000)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
        val shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CamIcon(rc.cam, Modifier.width(88.dp))
            Text(rc.cam.name, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 28.sp,
                style = TextStyle(shadow = shadow))
            Text(rc.cam.desc, color = Color.White, fontSize = 13.sp, style = TextStyle(shadow = shadow))
        }
    }
}

@Composable
internal fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
        Text(label, color = Color(0xFFAAAAAA), fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color(0xFF333333),
            ),
        )
        Text("${(value * 100).toInt()}", color = Color.White, fontFamily = Mono, fontSize = 12.sp,
            textAlign = TextAlign.End, modifier = Modifier.width(28.dp))
    }
}

/** 金属快门：外圈银色环 + 内圈按钮（带一圈相机点缀色），按下缩一下。 */
@Composable
private fun ShutterButton(accent: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutter")
    Box(
        Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color(0xFFE8E8E8), Color(0xFF8C8C8C), Color(0xFFDADADA), Color(0xFF7A7A7A), Color(0xFFE8E8E8))))
            .padding(5.dp)
            .clip(CircleShape)
            .background(Color(0xFF1A1A1A))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .scale(scale)
                .size(64.dp)
                .border(2.dp, accent.copy(alpha = 0.85f), CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFAFAFA), Color(0xFFCFCFCF), Color(0xFF9E9E9E))))
        )
    }
}

@Composable
private fun GalleryButton(thumb: ImageBitmap?, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF222222))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
            .clickable(enabled = thumb != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { Image(it, "最近一张", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (busy) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}
