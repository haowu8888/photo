package com.haowu.buxingtu.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.mlkit.common.sdkinternal.MlKitContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haowu.buxingtu.preset.Cams
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 在 Robolectric 里渲染界面，输出 PNG 供人工检查（相机预览本身是黑的）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        val dir = File(System.getProperty("uiOut") ?: "build/ui-previews").apply { mkdirs() }
        rule.waitForIdle()
        // captureToImage 在 Robolectric 里等不到重绘，直接把 decorView 画到 Bitmap 上
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun icons() {
        rule.setContent {
            Column(Modifier.background(ScreenBg).padding(8.dp)) {
                Cams.all.chunked(6).forEach { row ->
                    Row {
                        row.forEach { cam ->
                            Column(Modifier.width(64.dp).padding(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                CamIcon(cam, Modifier.fillMaxWidth())
                                Text(cam.name, color = Color.White, fontSize = 8.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        save("icons")
    }

    @Test
    fun picker() {
        val vm = CameraViewModel(RuntimeEnvironment.getApplication())
        rule.setContent { Box(Modifier.fillMaxSize().background(ScreenBg)) { CamPicker(vm, true) {} } }
        rule.waitForIdle()
        save("picker")
    }

    @Test
    fun screen() {
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication())
        val vm = CameraViewModel(RuntimeEnvironment.getApplication())
        rule.setContent { CameraScreen(vm) {} }
        rule.waitForIdle()
        save("screen")
    }
}
