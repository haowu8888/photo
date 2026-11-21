package com.haowu.buxingtu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.haowu.buxingtu.camera.CameraController
import com.haowu.buxingtu.ui.Accent
import com.haowu.buxingtu.ui.CameraScreen
import com.haowu.buxingtu.ui.CameraViewModel

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()
    private var controller: CameraController? = null
    private lateinit var orientation: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 界面锁竖屏，但成片方向跟着手机实际朝向走
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val rotation = when (degrees) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                controller?.setTargetRotation(rotation)
            }
        }

        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()

        setContent {
            var granted by remember { mutableStateOf(permissions.all(::isGranted)) }
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                granted = permissions.all(::isGranted)
            }
            LaunchedEffect(Unit) { if (!granted) launcher.launch(permissions) }

            if (granted) {
                CameraScreen(vm) { controller = it }
            } else {
                PermissionScreen { launcher.launch(permissions) }
            }
        }
    }

    private fun isGranted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onPause() {
        orientation.disable()
        super.onPause()
    }

    /** 音量键当快门。 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controller != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event.repeatCount == 0) vm.requestShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("睡图需要相机权限才能拍照", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Text("授权相机")
        }
    }
}
