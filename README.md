# 睡图

睡图是一款自用的 Android 胶片相机，取景和相机切换体验参考 Dazz Cam。拍照前可以切换复古相机预设，在取景器里实时看到调色和特效。也可以从系统相册选图，用当前预设处理后保存为新照片。

## 安装与使用

在 [APK 下载页](https://cloudreve.haowu.fun/s/7Zip) 选择 `Shuitu-*.apk`。设备需运行 Android 8.0 或以上版本，使用 ARM 架构，并支持相机和 OpenGL ES 3.0。

1. 打开睡图并授予相机权限。Android 8 和 9 上还需授予存储权限，用于保存成片。
2. 左右滑动取景器，或点「换相机」挑选预设。通过相机架里的滑杆调整滤镜强度。
3. 按快门拍摄，或用顶部的导入按钮从相册选图。成片以 JPEG 格式存入 `DCIM/睡图`。

## 相机功能

- **胶片预设**支持胶片、拍立得、数码、日常、黑白和趣味风格。不同预设可以搭配颗粒、暗角、光晕、漏光、日期戳和边框。
- **实时取景**支持前后摄切换、触点对焦、双指变焦、曝光补偿、闪光灯、定时快门和多种画幅。预览与成片共用滤镜渲染流程。
- **特效和美颜**支持鱼眼、万花筒、星芒、双重曝光等特效。磨皮、美白、红润、瘦脸和大眼可单独调节，人脸轮廓在设备端检测。
- **相册处理**支持一次最多导入 9 张照片，沿用原始比例和方向。打开日期戳时，有 EXIF 拍摄时间的照片会用该时间。
- **拍摄分辨率**可按相机选择拍照尺寸。全像素模式适用于 Android 12 及以上支持该模式的后置相机，需关闭闪光灯并保持约 1× 变焦。

## 从源码构建

准备 JDK 17 和 Android SDK Platform 35。用 Android Studio 打开仓库根目录同步 Gradle，或配置 `ANDROID_HOME` 后使用仓库自带的 Gradle Wrapper。

```bash
git clone https://github.com/haowu8888/photo.git
cd photo
bash ./gradlew :app:assembleDebug
```

Windows 在仓库根目录执行 `.\gradlew.bat :app:assembleDebug`。产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

运行 `bash ./gradlew :app:testDebugUnitTest`（Windows 用 `.\gradlew.bat :app:testDebugUnitTest`）可执行边框、人脸坐标和界面截图测试。拍照和实时预览需在真机验证。

## 滤镜与渲染

界面使用 Kotlin 和 Jetpack Compose，相机由 CameraX 驱动。预览使用 OpenGL ES 3.0 实时应用 3D LUT 和特效，拍摄后由离屏渲染生成成片。支持全像素模式的设备通过 Camera2 获取该模式下的 JPEG。

- 调色参数和胶片效果定义在配方文件中。修改后安装 Python 的 NumPy 和 Pillow，运行 `python tools/lutgen/lutgen.py` 重新生成 LUT、缩略图和索引。[滤镜配方](tools/lutgen/filters.json)
- 着色器实现相机预览和成片使用的渲染效果。[Shader](app/src/main/assets/shaders/)
- 设计文档记录滤镜体系、渲染方案和开发过程。[设计文档](docs/DESIGN.md)
