package com.haowu.buxingtu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** 设置里的「关于 / 检查更新」，以及自动检查发现新版本时弹出的对话框，共用一个。 */
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
