package com.haowu.buxingtu.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haowu.buxingtu.preset.Cams
import com.haowu.buxingtu.preset.ResolvedCam
import kotlinx.coroutines.launch

private val Sheet = Color(0xFF161616)

/** 相机架：按分类排好的相机图标网格，点一台就换上并收起。 */
@Composable
fun CamPicker(vm: CameraViewModel, visible: Boolean, onDismiss: () -> Unit) {
    BackHandler(visible, onDismiss)
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            PickerSheet(vm, onDismiss)
        }
    }
}

@Composable
private fun PickerSheet(vm: CameraViewModel, onDismiss: () -> Unit) {
    val sections = remember(vm.cams) {
        Cams.categories.map { c -> c to vm.cams.filter { it.cam.category == c.id } }.filter { it.second.isNotEmpty() }
    }
    // 网格里每个分类标题所在的位置，点分类标签时滚过去
    val headerIndex = remember(sections) {
        var i = 0
        sections.map { (_, list) -> i.also { i += 1 + list.size } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { headerIndex.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0) } }

    LaunchedEffect(Unit) {
        val s = sections.indexOfFirst { (_, list) -> vm.cam in list }
        if (s >= 0) grid.scrollToItem(headerIndex[s])
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(Sheet)
            .clickable(remember { MutableInteractionSource() }, indication = null) {} // 挡住点击，别穿到遮罩
            .navigationBarsPadding()
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF444444)))
        }

        // 当前相机
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CamIcon(vm.cam.cam, Modifier.width(76.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(vm.cam.cam.name, color = Color.White, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(vm.cam.cam.desc, color = Color(0xFF9A9A9A), fontSize = 12.sp, maxLines = 1)
                if (vm.filter.lut != null) {
                    LabeledSlider("强度", vm.intensityOf(vm.filter), onChange = vm::setIntensity)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sections.forEachIndexed { i, (c, list) ->
                val active = i == current
                Text(
                    "${c.name} ${list.size}",
                    color = if (active) Color.White else Color(0xFF8A8A8A),
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (active) Color(0xFF2C2C2C) else Color.Transparent)
                        .clickable { scope.launch { grid.animateScrollToItem(headerIndex[i]) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            sections.forEach { (c, list) ->
                item(key = "h_${c.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(c.name, color = Color(0xFF777777), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                }
                items(list, key = { it.cam.id }) { rc ->
                    CamCell(rc, rc == vm.cam) {
                        vm.selectCam(rc)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun CamCell(rc: ResolvedCam, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.15f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) Color(0xFF2E2620) else Color(0xFF1F1F1F))
                .border(1.5.dp, if (selected) Accent else Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CamIcon(rc.cam, Modifier.fillMaxWidth())
            if (rc.cam.leak > 0f) {
                // 随机漏光的相机标个小光点
                Box(Modifier.align(Alignment.TopEnd).size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFFF9A3C)))
            }
        }
        Text(
            rc.cam.name, fontSize = 11.sp, maxLines = 1, fontFamily = Mono,
            color = if (selected) Accent else Color(0xFFCCCCCC),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
