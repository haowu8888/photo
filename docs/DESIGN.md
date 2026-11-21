# 睡图 · 胶片相机 App 设计方案

> 一个 Android 相机 App：打开就是取景器，拍之前选好滤镜，所见即所得。风格对标醒图的相机滤镜、Dazz Cam、NOMO，主打富士、松下/徕卡、柯达、理光这类胶片感。

## 1. 核心体验

- 打开 App 直接进相机，底部横滑切换滤镜，取景画面**实时**显示效果。
- 每个滤镜有「强度」滑杆（0–100%），以及颗粒、暗角、日期戳等开关。
- 拍完的照片带滤镜保存到系统相册 `DCIM/<App名>`，可选同时保留原图。
- 第二阶段：从相册导入照片套滤镜（醒图式后期）。

## 2. 滤镜体系

滤镜按「相机/胶卷品牌风格」分组。**界面上不写品牌商标**（Fujifilm、Kodak 这些都是注册商标），用代号加描述，比如「CC 经典正片」。

| 分组 | 滤镜（代号） | 风格要点 |
|---|---|---|
| **富士风** | PV 标准正片 | 中性、色彩准确（对标 PROVIA） |
| | VV 鲜艳正片 | 高饱和、蓝绿浓（对标 Velvia） |
| | AS 柔和人像 | 肤色柔、对比低（对标 ASTIA） |
| | CC 经典正片 | 低饱和、暗部硬、偏青（对标 Classic Chrome） |
| | CN 经典负片 | 青绿暗部、品红高光、硬对比（对标 Classic Neg） |
| | NN 怀旧负片 | 琥珀高光、暗部浓郁，像冲印照片（对标 Nostalgic Neg） |
| | ET 电影 | 低饱和、暗部细节多（对标 Eterna） |
| | BB 漂白 | 低饱和、高对比（对标 Eterna Bleach Bypass） |
| | RA 写实 | 色彩准确、对比偏硬（对标 Reala Ace） |
| | AC 黑白 | 细腻黑白加颗粒（对标 ACROS，另有红/黄/绿滤镜变体） |
| **松下/徕卡风** | LN 经典新调 | 偏青、低饱和、复古（对标 L.ClassicNeo） |
| | LM 徕黑白 | 层次丰富的黑白，另有 D（高对比）和 S（柔）两个变体（对标 L.Monochrome） |
| | CD 电影平调 | 宽动态、平调（对标 Cinelike D） |
| **柯达风** | P4 人像负片 | 暖肤色、柔高光（Portra 400 风） |
| | G2 金色 | 暖黄、怀旧日常（Gold 200 风） |
| | E1 风光 | 高饱和、细腻（Ektar 100 风） |
| | TX 街头黑白 | 粗颗粒、高对比（Tri-X 风） |
| **理光风** | GP 正片 | 浓郁、高饱和（GR Positive Film 风） |
| | GN 负片 | 偏青、低反差 |
| | GH 高对比黑白 | 硬调颗粒黑白 |
| **复古数码** | CCD 千禧 | 偏冷、高光溢出、轻微锐化，带橙色日期戳 |
| | DV 胶片机 | 低分辨率质感、色偏 |
| **拍立得** | IP 一次成像 | 褪色、偏青绿暗部、相纸白边 |
| **氛围** | 日系清新 / 过期胶卷 / 漏光 / 青橙电影 | 醒图里最常用的几类 |

首版先做 **12 个**：PV、VV、CC、CN、NN、ET、AC、LN、LM、P4、G2、CCD。其余放后续版本。

## 3. 滤镜参数模型

每个滤镜是一份配方 JSON，渲染管线按顺序执行：

```
原图 → 3D LUT（色彩/色调）→ 光晕(halation) → 暗角 → 颗粒 → 边框/日期戳 → 输出
```

```json
{
  "id": "cc",
  "name": "CC 经典正片",
  "group": "fuji",
  "lut": "luts/cc.png",
  "intensity": 1.0,
  "grain": { "amount": 0.25, "size": 1.2, "roughness": 0.5 },
  "vignette": { "amount": 0.2, "radius": 0.75 },
  "halation": { "amount": 0.0, "threshold": 0.85, "tint": "#ff5a2a" },
  "frame": null,
  "dateStamp": false
}
```

### LUT 怎么来

- **不用网上来路不明的现成 LUT**（版权不清、质量参差不齐）。写一个 Python 生成器 `tools/lutgen/`，用参数（色调曲线、通道混合、HSL 分区调整、高光/阴影分离着色、饱和度）把配方生成 **33³ LUT**，存成 PNG 图集。可复现、能调，也没有版权问题。
- 生成器同时输出**样张对比图**（同一组测试照片套全部滤镜），在电脑上先把颜色调好再进 App。
- App 支持导入 `.cube` 文件，用户自己的 LUT 也能用（比如松下 LUMIX Lab 导出的 LUT）。

## 4. 技术方案

**原生 Kotlin + Jetpack Compose + CameraX + OpenGL ES 3.0。** 实时预览要每帧跑 GPU shader，原生做最稳。Flutter/RN 也要回到原生写 GL，没有省事。

| 环节 | 方案 |
|---|---|
| 预览 | CameraX `Preview` 接 `SurfaceTexture`，自己的 GL 渲染器做 OES 纹理 → LUT shader → 屏幕（约 1080p）。颗粒每帧换随机种子，看起来像真胶片在动 |
| 拍照 | `ImageCapture` 拿**全分辨率**帧，用离屏 EGL 跑**同一套 shader**。超过 GPU 最大纹理尺寸时分块处理。不用 CameraX 的 `CameraEffect` 处理拍照，因为它会把分辨率限制在 GL surface 尺寸，社区还反映会影响零延迟快门 |
| 保存 | JPEG（质量 95）+ 写回原始 EXIF（时间、GPS、机型）+ MediaStore，存到 `DCIM/<App名>` |
| 色彩 | 输入 sRGB，LUT 在 sRGB 空间采样（和生成器保持一致）。颗粒按亮度调制（中间调最明显）|
| 画幅 | 3:2（胶片）/ 4:3 / 1:1 / 16:9，取景框遮罩加裁切 |
| 系统要求 | minSdk 26（Android 8.0），targetSdk 用最新 |

### 工程结构（规划）

```
app/                    Android 工程（Kotlin）
  camera/               CameraX 绑定、拍照、保存
  gl/                   EGL、shader、LUT 纹理、离屏渲染
  filter/               配方解析、滤镜注册表
  ui/                   Compose：相机页、滤镜条、设置
  assets/filters/       配方 JSON + LUT PNG + 颗粒纹理 + 边框
tools/lutgen/           Python：配方 → LUT PNG + 样张对比图
docs/                   设计文档
```

## 5. 界面

```
┌──────────────────────────┐
│ ⚡  3:2  ⏱  ⚙          │  顶栏：闪光、画幅、定时、设置
│                          │
│                          │
│       实时取景画面         │  点按对焦/测光，上下滑调曝光
│                          │
│                          │
│        CC 经典正片        │  切换时短暂显示滤镜名
├──────────────────────────┤
│ 富士 松下 柯达 理光 复古   │  分组标签
│ [PV][VV][CC][CN][NN]...  │  滤镜缩略图（用实时画面小图渲染）
│ 强度 ━━━━━━●━━  颗粒 暗角 │
│   🖼        ◉        🔄   │  相册 / 快门 / 前后摄
└──────────────────────────┘
```

- 在取景画面上左右滑也能切滤镜（和醒图一样）。
- 快门带胶片过片音效和震动反馈，可以关。

## 6. 构建与分发

- 已核实：宿主机只有 Java 17，没装 Android SDK。计划在容器里装 JDK 17 + Android SDK cmdline-tools，用 Gradle 打 APK；后续可以照其他项目加一条 Flow 流水线，构建完传云盘。
- 自用或私下分发都没问题。如果要上架应用商店，需要再做一遍滤镜命名和商标审查。

## 7. 里程碑

| 阶段 | 内容 | 产出 |
|---|---|---|
| M0 | LUT 生成器 + 12 个首版配方 + 样张对比图 | 在电脑上看效果、调色 |
| M1 | 相机实时预览 + 滤镜切换 + 拍照保存 | 第一个可安装的 APK |
| M2 | 颗粒、光晕、暗角、强度滑杆、画幅、日期戳、拍立得边框 | 完整的胶片感 |
| M3 | 相册导入编辑、导入 `.cube`、收藏和排序滤镜、补齐剩余滤镜 | 醒图式后期 |
| M4（可选）| 带滤镜录视频 | |

## 8. 已定

- App 名「睡图」，包名 `com.haowu.buxingtu`，相册 `DCIM/睡图`。
- 首版只拍照，不录视频。
- 2026-11-21 完成 M0 + M1（另外提前做了 M2 里的颗粒、暗角、强度、画幅、日期戳），出了 0.1.0 APK。
- 2026-11-21 改名「睡图」（图标：醒图配色反过来，浅底紫色月亮）。0.2.0：光晕、漏光、5 种边框；滤镜扩到 46 个，分 10 组：热门（Dazz 式 CPM35 数码胶片、一次性相机、Huji 式漏光）、氛围（奶油/日系/韩系/蜜桃/森系/落日）、富士、索尼（FL/IN/SH/VV2）、理光（正片/负片/漂白/复古/高对比黑白）、大疆（航拍/清透/黑柔）、松下、柯达、胶卷（800T/Superia/C200/Agfa/Lomo Purple/HP5 风）、复古（CCD/拍立得/DV/港风）。实际配方以 `tools/lutgen/filters.json` 为准。
- 2026-11-21 0.3.0：富士 9 个、索尼 3 个滤镜用网上真机对比图校准（交叉验证 ΔE 约减半；索尼 SH 校准不如手调，保留手调）；美颜（磨皮/美白/红润/瘦脸/大眼）；8 个特效（鱼眼/镜像/万花筒/波普/色散/故障/星芒/双重曝光）；修复：拍立得滤镜边框改为临时套用、边框底色涂满整屏盖住顶栏。

## 参考

- 富士胶片模拟官方介绍：https://www.fujifilm-x.com/global/film-simulation/
- 松下 Photo Style / REAL TIME LUT：https://www.panasonic.com/global/consumer/lumix/lumix-color/real-time-lut.html
- CameraX 1.4 的 GL 效果（CameraEffect）：https://android-developers.googleblog.com/2024/12/whats-new-in-camerax-140-and-jetpack-compose-support.html
- CameraX 胶片管线讨论（分辨率限制）：https://groups.google.com/a/android.com/g/camerax-developers/c/kBIZX8scR_g
