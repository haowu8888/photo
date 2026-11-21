# 睡图

Android 胶片滤镜相机（Kotlin + Compose + CameraX + OpenGL ES 3.0）。设计见 `docs/DESIGN.md`。

## 结构

- `tools/lutgen/filters.json` — 滤镜配方唯一来源（46 个，10 组；调色参数 + 颗粒/暗角/光晕/漏光/日期戳/默认边框）
- `tools/lutgen/lutgen.py` — 配方 → `app/src/main/assets/filters/`（`luts/*.png` 33³ LUT、`thumbs/*.jpg`、`index.json`）；`--preview 照片目录 输出目录` 出样张对比图
- `app/src/main/assets/shaders/` — `film.frag`（特效/瘦脸大眼改采样坐标 → 美颜 → LUT → 光晕/漏光/星芒/双重曝光叠图 → 波普 → 暗角 → 颗粒；美颜 = 肤色掩码 + 16 采样双边滤波磨皮、对数曲线美白、红润，半径按短边 0.6%）、`glow_prefilter.frag` + `blur.frag`（光晕：高光缩到短边 240 再两轮高斯）；头部由 `gl/Gl.kt` 拼接，OES / 2D 两种输入
- `gl/FilmRenderer.kt` — 一个 GL 上下文里的完整渲染（GlowPass + FilmProgram + LUT 缓存），预览和成片各一个
- `frame/FrameStyle.kt` — 边框（拍立得/mini/白边/参数水印/胶片底片）：留白尺寸 + Canvas 装饰，预览（Compose nativeCanvas）和成片共用
- `gl/TexMapping.kt` — 输出坐标 → 输入纹理坐标（裁切、前摄镜像、旋转），预览和成片共用，保证所见即所得
- `camera/CameraGlView.kt` — GLSurfaceView 实时取景（分离模式 SurfaceTexture）
- `face/` — ML Kit 人脸轮廓（内置模型，不依赖 GMS）：`FaceTracker` 接 ImageAnalysis 640×480 实时跟踪，`FaceDetectorSync` 成片时在全分辨率上重检；`FaceShape.toUniforms` 换算到输出坐标（裁切、前摄镜像）
- `effect/Effect.kt` — 特效（鱼眼/镜像/万花筒/波普/色散/故障/星芒/双重曝光），code 对应 shader 的 uEffect；双重曝光第一张留在 PhotoProcessor 内存里，第二张用 SCREEN 混合合成
- `camera/PhotoProcessor.kt` — 全分辨率 JPEG → 离屏 EGL 跑同一套渲染 → 日期戳 → 边框 → EXIF → `DCIM/睡图`
- `camera/CameraController.kt` — CameraX 绑定、对焦、变焦、拍照
- `ui/CameraScreen.kt`、`ui/CameraViewModel.kt` — 界面与状态（SharedPreferences 记住滤镜、强度、画幅等）

## 改滤镜

改 `filters.json` → `python3 tools/lutgen/lutgen.py` → 重新打包。LUT PNG 布局：宽 N、高 N²，像素 (x=r, y=b·N+g)，直接 `glTexImage3D` 上传。

### 校准（真机对比图）

- `tools/lutgen/calibration.json` 列数据集（同一 RAW 不同风格的 JPG）和「滤镜 ← 风格」对应；`calibrate.py` 从「基准风格 → 目标风格」像素对拟合 LUT（带平滑 + 手调配方作先验），写到 `tools/lutgen/calibrated/<id>.png`；`filters.json` 里 `"calibrated": true` 的滤镜由 lutgen 直接用它。
- 数据：富士用 friimaind/film-simulations-comparator（MIT，3 场景 × 全部模拟，基准 Provia）；索尼用官网 Creative Look 对比合成图（scene7 图床，基准 ST；官网页面被 Akamai 拦，图床可直接下载）。图片缓存在 `~/.cache/shuitu-calib`，不进仓库。
- 输出留一场景交叉验证的 ΔE（不加滤镜 / 手调 / 校准）。理光、柯达、胶卷、大疆等没找到同场景对比数据，仍是手调。
- 依赖只有 numpy/Pillow（共轭梯度手写，没用 scipy：apt 的 scipy 与 numpy 2 不兼容）。

## 构建

容器里：JDK 17（apt）+ `~/android-sdk`（cmdline-tools，platform 35）。`local.properties` 写 `sdk.dir`。

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
```

发布签名在 `keys/`（`release.jks` + `keystore.properties`，已 gitignore，用户要求放项目里防误删），宿主机 `D:\AI\keys\buxingtu\` 另有备份。缺了 release 会回落到 debug 签名，手机上无法覆盖升级。包名保持 `com.haowu.buxingtu`（改名「睡图」时没改，保证能覆盖升级）。版本号：`-PversionCode=N -PversionName=x.y.z`。

## 验证

容器里没有 KVM，跑不了模拟器。
- shader：无头 Chromium 的 WebGL2 跑同一套 shader（含 GlowPass 的 JS 移植），和 `lutgen.apply_effects` / `apply_beauty` 逐像素对比。
- 单元测试 `./gradlew :app:testDebugUnitTest`：边框（Robolectric 原生图形，渲染到 `app/build/frame-previews/`）、人脸坐标换算。
- 人脸检测本身（ML Kit）只能真机验证。
- 相机相关的行为只能在真机上验证。
