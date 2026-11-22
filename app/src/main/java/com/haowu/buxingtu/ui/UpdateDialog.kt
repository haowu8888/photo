package com.haowu.buxingtu.ui

import android.util.Size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haowu.buxingtu.BuildConfig
import com.haowu.buxingtu.update.Updater

/** 设置（拍照像素 + 关于 / 检查更新），以及自动检查发现新版本时弹出的对话框，共用一个。 */
@Composable
fun UpdateDialog(vm: CameraViewModel, onDismiss: () -> Unit) {
    val u = vm.update
    AlertDialog(
        onDismissRequest = { if (u.progress == null) onDismiss() },
        containerColor = Color(0xFF1C1C1C),
        titleContentColor = Color.White,
        textContentColor = Color(0xFFCCCCCC),
        title = { Text("睡图 ${BuildConfig.VERSION_NAME}") },
        text = {
            Column {
                CaptureSizeSection(vm)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color(0xFF333333))
                val r = u.release
                when {
                    !Updater.enabled -> Text("这是本地构建的版本，不检查更新。从网盘安装的版本会自动检查。")
                    u.checking -> Text("正在检查更新…")
                    u.progress != null -> {
                        Text("正在下载 ${r?.version}…")
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { u.progress }, color = Accent, modifier = Modifier.fillMaxWidth())
                    }
                    u.apk != null -> Text("新版本已下载。如果系统要求，先允许「睡图」安装应用，再点「安装」。")
                    r != null -> Text("发现新版本 ${r.version}（${"%.1f".format(r.size / 1048576f)} MB）")
                    u.upToDate -> Text("已经是最新版本")
                    else -> Text("点「检查更新」看看网盘里有没有新版本")
                }
                u.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            val r = u.release
            when {
                !Updater.enabled || u.checking || u.progress != null -> {}
                u.apk != null -> TextButton(onClick = vm::installUpdate) { Text("安装", color = Accent) }
                r != null -> TextButton(onClick = vm::downloadUpdate) { Text("更新", color = Accent) }
                else -> TextButton(onClick = { vm.checkUpdate(manual = true) }) { Text("检查更新", color = Accent) }
            }
        },
        dismissButton = {
            if (u.progress == null) TextButton(onClick = onDismiss) { Text(if (u.release != null) "以后再说" else "关闭", color = Color(0xFFAAAAAA)) }
        },
    )
}

private fun Size.megaLabel() = "${width.toLong() * height / 10_000} 万"

/** 拍照像素：手机给第三方应用的 4:3 尺寸，加上全像素模式（手机开放了才有）。 */
@Composable
private fun CaptureSizeSection(vm: CameraViewModel) {
    val o = vm.captureOptions ?: return
    Text("拍照像素${if (vm.front) "（前摄）" else ""}", color = Color.White, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    // 第一项（最高）对应默认值 ""，换手机或系统升级后自动跟着变
    val items = o.sizes.mapIndexed { i, s -> (if (i == 0) "" else "${s.width}x${s.height}") to "${s.megaLabel()}　${s.width}×${s.height}" } +
        listOfNotNull(o.fullRes?.let { FULL_RES to "${it.megaLabel()}　全像素（实验）" })
    val selected = vm.sizeChoice.takeIf { c -> items.any { it.first == c } } ?: ""
    items.forEach { (choice, label) ->
        Text(
            (if (choice == selected) "● " else "○ ") + label,
            color = if (choice == selected) Accent else Color(0xFFCCCCCC),
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().clickable { vm.selectSize(choice) }.padding(vertical = 6.dp),
        )
    }
    Spacer(Modifier.height(4.dp))
    val note = when {
        selected == FULL_RES -> "全像素每张多花 1~3 秒；开闪光灯、变焦时自动用普通模式。"
        o.fullRes == null && !vm.front -> "这台手机没有把全像素模式开放给第三方应用，系统相机的高像素模式这里用不了。"
        else -> null
    }
    o.current?.let { Text("当前：${it.megaLabel()}（${it.width}×${it.height}）", fontSize = 12.sp, color = Color(0xFF999999)) }
    note?.let { Text(it, fontSize = 12.sp, color = Color(0xFF999999)) }
}
