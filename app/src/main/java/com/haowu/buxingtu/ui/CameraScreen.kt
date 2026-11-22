package com.haowu.buxingtu.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaActionSound
import android.util.Size
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FilterFrames
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.platform.LocalViewConfiguration
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
import com.haowu.buxingtu.filter.Filter
import com.haowu.buxingtu.gl.TexMapping
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

val Accent = Color(0xFFFF7A2F)
/** 滤镜条和美颜滑杆区域同高，切换时取景框不跳 */
private val PANEL_HEIGHT = 144.dp
private const val EFFECT_COLUMNS = 6
private val Panel = Color(0xFF111111)
private val Mono = FontFamily.Monospace

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
    val flashAlpha = remember { Animatable(0f) }

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
        }
    }
    LaunchedEffect(vm.flash, vm.front) { controller.setFlash(if (vm.front) FlashMode.OFF.camerax else vm.flash.camerax) }

    // 滤镜参数同步到 GL 线程
    val intensity = vm.intensityOf(vm.filter)
    LaunchedEffect(vm.filter, intensity) {
        glView.filter = vm.filter
        glView.intensity = if (vm.filter.lut == null) 0f else intensity
        glView.refresh()
    }
    LaunchedEffect(vm.filter) { showName++ }
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

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        TopBar(vm)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // 以照片宽 = 1 为单位算边框留白，整体（照片 + 边框）按比例塞进可用区域
            val frame = vm.frame
            val ratio = vm.previewRatio
            val m = frame.margins(1f, ratio)
            val outerW = 1f + m.left + m.right
            val outerH = ratio + m.top + m.bottom
            BoxWithConstraints(Modifier.aspectRatio(outerW / outerH)) {
                val unit = constraints.maxWidth / outerW
                val density = LocalDensity.current
                if (frame != FrameStyle.NONE) {
                    val info = PhotoProcessor.frameInfo(vm.filter, vm.lastParams, null)
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
                    FilterNameOverlay(vm.filter, showName)
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
        }

        BottomPanel(
            vm = vm,
            onShutter = ::capture,
            onFlip = { if (controller.hasCamera(!vm.front)) vm.front = !vm.front },
            onGallery = {
                vm.lastUri?.let { uri ->
                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(intent) }
                }
            },
        )
    }
}

@Composable
private fun TopBar(vm: CameraViewModel) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val flashIcon = when (vm.flash) {
            FlashMode.OFF -> Icons.Outlined.FlashOff
            FlashMode.AUTO -> Icons.Outlined.FlashAuto
            FlashMode.ON -> Icons.Outlined.FlashOn
        }
        TopButton(icon = flashIcon, enabled = !vm.front, active = vm.flash != FlashMode.OFF) { vm.cycleFlash() }
        val forced = vm.frame.forcedRatio(landscape = false)
        TopButton(text = forced?.let(::ratioLabel) ?: vm.ratio.label, enabled = forced == null) { vm.cycleRatio() }
        FrameButton(vm)
        TopButton(
            icon = when (vm.timer) { 3 -> Icons.Outlined.Timer3; 10 -> Icons.Outlined.Timer10; else -> Icons.Outlined.Timer },
            active = vm.timer > 0,
        ) { vm.cycleTimer() }
        TopButton(icon = if (vm.grid) Icons.Outlined.GridOn else Icons.Outlined.GridOff, active = vm.grid) { vm.toggleGrid() }
        TopButton(text = "DATE", active = vm.dateStamp) { vm.dateStamp = !vm.dateStamp }
        Box {
            TopButton(icon = Icons.Outlined.Settings) { vm.showUpdate = true }
            // 有新版本时设置按钮上亮个点
            if (vm.update.release != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp).size(6.dp).clip(CircleShape).background(Accent))
            }
        }
    }
}

private fun ratioLabel(r: Float): String = when {
    abs(r - 1f) < 0.01f -> "1:1"
    abs(r - 1.5f) < 0.01f -> "3:2"
    else -> "MINI"
}

@Composable
private fun FrameButton(vm: CameraViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TopButton(icon = Icons.Outlined.FilterFrames, active = vm.frame != FrameStyle.NONE) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Color(0xFF1C1C1C))) {
            FrameStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = {
                        Text(style.label, color = if (style == vm.frame) Accent else Color.White, fontSize = 14.sp)
                    },
                    onClick = {
                        vm.selectFrame(style)
                        open = false
                    },
                )
            }
        }
    }
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
        else -> Color.White
    }
    Box(
        Modifier
            .size(width = 46.dp, height = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
        if (text != null) Text(text, color = color, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
        drawCircle(Accent, radius = 34.dp.toPx() * scale.value, center = point,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
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
private fun BoxScope.FilterNameOverlay(filter: Filter, trigger: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger <= 1) return@LaunchedEffect // 启动时不弹
        visible = true
        delay(900)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(filter.code, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 34.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)))
            Text(filter.name, color = Color.White, fontSize = 15.sp,
                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)))
        }
    }
}

@Composable
private fun BottomPanel(vm: CameraViewModel, onShutter: () -> Unit, onFlip: () -> Unit, onGallery: () -> Unit) {
    val filters = vm.catalog.filters
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val selectedIndex = filters.indexOf(vm.filter)

    LaunchedEffect(selectedIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == selectedIndex && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
            listState.animateScrollToItem(maxOf(0, selectedIndex - 2))
        }
    }

    // 0 滤镜 / 1 美颜 / 2 特效
    var mode by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth().background(Panel).padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeTab("滤镜", mode == 0) { mode = 0 }
            ModeTab("美颜", mode == 1, dot = vm.beauty.enabled) { mode = 1 }
            ModeTab("特效", mode == 2, dot = vm.effect != Effect.NONE) { mode = 2 }
            Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 14.dp).background(Color(0xFF444444)))
            // 分组标签：点了滚到该组第一个
            if (mode == 0) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    vm.catalog.groups.forEach { g ->
                        val active = vm.filter.group == g.id
                        Text(
                            g.name,
                            color = if (active) Color.White else Color(0xFF888888),
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .clickable {
                                    val i = filters.indexOfFirst { it.group == g.id }
                                    if (i >= 0) scope.launch { listState.animateScrollToItem(maxOf(0, i - 1)) }
                                }
                                .padding(vertical = 6.dp)
                        )
                    }
                }
            } else if (mode == 1) {
                // 一键美颜：套用预设；已经是预设时再点就关掉
                val auto = vm.beauty == Beauty.AUTO
                Text(
                    "一键美颜", color = if (auto) Accent else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (auto) Accent.copy(alpha = 0.18f) else Color(0xFF2A2A2A))
                        .clickable { vm.updateBeauty(if (auto) Beauty.OFF else Beauty.AUTO) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                if (vm.beauty.enabled) {
                    Text(
                        "重置", color = Color(0xFF888888), fontSize = 13.sp,
                        modifier = Modifier.clickable { vm.updateBeauty(Beauty.OFF) }.padding(vertical = 6.dp, horizontal = 10.dp)
                    )
                }
            }
        }

        if (mode == 2) {
            EffectPanel(vm)
        } else if (mode == 1) {
            Column(Modifier.fillMaxWidth().height(PANEL_HEIGHT).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
                val b = vm.beauty
                LabeledSlider("磨皮", b.smooth, compact = true) { vm.updateBeauty(b.copy(smooth = it)) }
                LabeledSlider("美白", b.whiten, compact = true) { vm.updateBeauty(b.copy(whiten = it)) }
                LabeledSlider("红润", b.rosy, compact = true) { vm.updateBeauty(b.copy(rosy = it)) }
                LabeledSlider("瘦脸", b.slim, compact = true) { vm.updateBeauty(b.copy(slim = it)) }
                LabeledSlider("大眼", b.eyes, compact = true) { vm.updateBeauty(b.copy(eyes = it)) }
            }
        } else Column(Modifier.height(PANEL_HEIGHT), verticalArrangement = Arrangement.Center) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                itemsIndexed(filters, key = { _, f -> f.id }) { _, f ->
                    FilterItem(f, selected = f == vm.filter) { vm.select(f) }
                }
            }

            Box(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                if (vm.filter.lut != null) LabeledSlider("强度", vm.intensityOf(vm.filter), onChange = vm::setIntensity)
            }
        }

        Row(
            Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryButton(vm.lastThumb?.asImageBitmap(), vm.processing > 0, onGallery)
            ShutterButton(onShutter)
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF222222)).clickable(onClick = onFlip),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Cameraswitch, "切换镜头", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun EffectPanel(vm: CameraViewModel) {
    Column(
        Modifier.fillMaxWidth().height(PANEL_HEIGHT).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Effect.entries.chunked(EFFECT_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { e ->
                    val selected = e == vm.effect
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Accent.copy(alpha = 0.18f) else Color(0xFF1E1E1E))
                            .border(1.5.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { vm.selectEffect(e) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e.label, color = if (selected) Accent else Color(0xFFDDDDDD), fontSize = 12.sp, maxLines = 1)
                    }
                }
                repeat(EFFECT_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, active: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp)) {
        Text(label, color = if (active) Accent else Color(0xFFBBBBBB), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (dot) Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp).size(5.dp).clip(CircleShape).background(Accent))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, compact: Boolean = false, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(if (compact) 28.dp else 36.dp)) {
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

@Composable
private fun FilterItem(f: Filter, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb = remember(f.id) {
        runCatching { context.assets.open(f.thumb).use { BitmapFactory.decodeStream(it).asImageBitmap() } }.getOrNull()
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(62.dp).clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(2.dp, if (selected) Accent else Color.Transparent), RoundedCornerShape(8.dp))
                .background(Color(0xFF222222)),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Text(
                f.code, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 6f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp)
            )
        }
        Text(
            f.name, fontSize = 11.sp, maxLines = 1,
            color = if (selected) Accent else Color(0xFFBBBBBB),
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun GalleryButton(thumb: ImageBitmap?, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF222222))
            .clickable(enabled = thumb != null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        thumb?.let { Image(it, "最近一张", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (busy) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}
