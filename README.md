# 饥荒手机版纹理转换工具（DST Tex Converter）

一个 Android 应用，用于把饥荒（Don't Starve / DST）PC 版模组纹理批量转换为**手机版兼容格式**。

## 功能

### 纹理转换
- **DXT1/3/5 `.tex` → ASTC 8×8 `.tex`**：把 PC 版模组纹理转成手机版 GPU 支持的 ASTC 压缩格式（体积约为 RGBA 的 1/16）
- **PNG → ASTC `.tex`**：新纹理转手机版格式
- **ASTC `.tex` → PNG**：反解预览
- **批量转换**：支持选中文件夹递归扫描、zip 压缩包内部纹理批量转换
- **嵌套压缩包**：压缩包内若还有压缩包，会逐层递归处理其中的纹理（可在设置中关闭）
- **原地替换 + 备份**：转换后**原子替换**原文件（先写临时文件再 rename，中途失败不会留下半截文件），原文件默认自动备份为 `.bak`（可在设置中关闭）
- **格式互转设置**：可选转换方向（自动 / PNG→TEX / TEX→PNG / TEX→ASTC）与压缩质量，设置项全部持久化
- **跳过提示**：已是目标格式（如已是 ASTC）或模式不符的文件会跳过，并在结果中给出计数，不会静默处理

### 预览与动画
- **`.tex` 图片预览**：直接查看纹理内容（自动处理垂直翻转存储）
- **`.dyn` 解密**：解密动画数据文件
- **动画预览**：解析 `anim.bin` / `build.bin`，播放动画、拖动帧条、切换动画、切换朝向与层序
- **动画编辑器（横屏）**：左侧元素列表、多层展开工具栏，支持拖动元素、换帧、缩放、旋转、层级、撤销，并可原地写回（原包备份为 `.bak`）

> ⚠️ **动画编辑器处于未完成状态**：目前编辑能力覆盖位置、换帧、缩放、旋转、层级与撤销；
> 增删帧、替换图集、多动画编辑等尚未实现。保存为原地字节改写，仅修改元素变换字段。

### 诊断
- **运行日志**：抽屉/菜单「查看日志」→ 独立日志页，可复制、分享、清空；同时落盘 `cacheDir/app.log`（超过 2MB 自动轮转）。
  转换链路全程记录，异常附带完整调用栈，便于定位失败原因。
- **调试模式**：设置中开启后，预览/编辑画布会叠加诊断信息（渲染统计、元素匹配数、图集尺寸）

## 背景：手机版纹理格式

饥荒手游（DST mobile）的 `.tex` 使用 **ASTC 8×8（2bpp）** 压缩，KTEX 头部的 `compression` 字段值为 `24`；而 PC 版使用 DXT1/3/5（compression 值 0/1/2）。本工具负责在两者之间转换。

- ASTC 块大小固定 **8x8（2bpp，手机版标准）**。KTEX 头部不携带块尺寸信息，写别的块尺寸会产出与本工具/游戏都不兼容的纹理，因此不再提供该选项
- 饥荒 `.tex` 为垂直翻转存储，工具已自动处理翻转

## 构建

### 依赖

- JDK 17
- Android SDK：`compileSdk 34`、build-tools `34.0.0`
- Android NDK：`27.3.13750724`（已在 `app/build.gradle` 固定，可用环境变量 `DSTTEX_NDK` 覆盖）
- CMake `3.22.1`（AGP 会经由 SDK 安装）

### 本地构建

```bash
# 可选：指定 SDK 路径
export ANDROID_SDK_ROOT=~/Android/Sdk

gradle :app:assembleRelease
# 或用 Android Studio 直接打开仓库根目录
```

产物：`app/build/outputs/apk/release/app-release.apk`

签名：若 `$HOME/.dsttex/dsttex.jks` 存在则用其签名（可用 `DSTTEX_KEYSTORE` / `DSTTEX_KS_PASS` /
`DSTTEX_KS_ALIAS` 覆盖），否则产出未签名包。密钥刻意放在仓库外并沿用同一个，
否则每次构建换签名会导致已装设备无法覆盖升级。

### 云端构建

推送到 `rebuild` 分支即触发 GitHub Actions（`.github/workflows/build-apk.yml`），
artifact 里可直接下载 `dst_tex_converter-apk`。

## 使用

1. 安装 APK（需允许「未知来源」）
2. 首次启动授予存储权限（Android 11+ 需在系统设置开启「所有文件访问」）
3. 进入模组目录，**长按**任意文件/文件夹进入多选
4. 多选模式下**单点**即可勾选/取消（容器行高亮）；或用工具栏「全选」，点「转换」
5. 完成后原文件被原子替换为 ASTC 版本，原文件备份为同名 `.bak`

预览与编辑：

- **点按 `.tex`** → 打开预览页（双指缩放、拖动、双击复位）
- **点按 `.zip`** → 选择「预览动画 / 编辑动画」
  - 动画预览页：拖动帧条、切换朝向/层序、翻转 UV，可直接进编辑器
  - 编辑器：横屏，元素列表 + 拖动/缩放/旋转/层级/换帧/撤销，保存写回原包
- **点按 `.dyn`** → 解密并列出条目，可导出 zip 或重新加密

界面：Material 主题，跟随系统深色模式；设置、日志、预览、动画均为独立页面；
文件列表用 RecyclerView（长目录滚动不再逐行 stat）。

## 目录结构

```
├── app/
│   ├── build.gradle                    # 模块配置（AGP 8.5.2 / compileSdk 34 / minSdk 24）
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                        # native 层（CMake）
│       │   ├── CMakeLists.txt          # libastcenc.so + libtex2png.so
│       │   ├── jni_astcenc.cpp         # JNI 桥：com.dsttex.core.TexCodec#nativeAstcenc
│       │   ├── jni_tex2png.cpp         # JNI 桥：com.dsttex.core.TexCodec#nativeTex2png
│       │   ├── tex2png.cpp             # KTEX 解析 + DXT1/3/5 解压（带边界校验，返回码上报）
│       │   └── stb_image_write.h
│       ├── java/com/dsttex/
│       │   ├── MainActivity.java       # 文件浏览 / 多选 / 批量转换 / 搜索 / 书签 / .dyn
│       │   ├── SettingsActivity.java   # 转换设置（改动即时生效并持久化）
│       │   ├── LogActivity.java        # 运行日志（复制/分享/清空）
│       │   ├── PreviewActivity.java    # 纹理预览（可缩放，独立页面）
│       │   ├── AnimPreviewActivity.java# 动画预览（独立页面）
│       │   ├── AnimEditor.java         # 横屏动画编辑器
│       │   ├── core/                   # 纯逻辑，不碰 UI
│       │   │   ├── TexCodec.java       # KTEX/.dyn/原生调用/原子写/文件工具
│       │   │   ├── Converter.java      # 转换流水线（文件级 + zip 级 + 嵌套）
│       │   │   ├── AnimFormat.java     # build.bin / anim.bin 解析（全量长度校验）
│       │   │   ├── AnimLoader.java     # 从压缩包载入动画资产
│       │   │   ├── AnimWriter.java     # 原地改写 anim.bin + 重打包
│       │   │   ├── AnimRenderer.java   # 动画渲染（逐三角形仿射贴图）
│       │   │   ├── AppLog.java         # 日志（环形缓冲 + 落盘轮转）
│       │   │   └── Settings.java       # 设置存储
│       │   └── ui/
│       │       ├── FileEntry.java      # 列表条目（browse 时一次性读元数据）
│       │       ├── FileAdapter.java    # RecyclerView + ViewHolder
│       │       ├── ZoomImageView.java  # 双指缩放/拖动预览
│       │       ├── AnimRendererView.java
│       │       └── AnimEditorView.java # 网格/选中/拖动/缩放/撤销
│       └── res/                        # 资源层
│           ├── values/                 # strings / colors / dimens / themes
│           ├── values-night/           # 深色模式配色
│           ├── drawable/               # vector 图标 + 徽标/选中背景
│           ├── layout/                 # 11 个布局（ViewBinding）
│           └── menu/                   # 主界面 / 多选 / 抽屉 / 日志 / 编辑器
├── third_party/                        # 第三方依赖
│   ├── astcenc/                        # ARM astc-encoder（Apache-2.0）
│   └── squish/                         # squish（MIT）
├── .github/workflows/build-apk.yml     # CI：JDK17 + SDK34 + NDK27 → 签名 APK
├── build.gradle / settings.gradle / gradle.properties
├── LICENSE
├── NOTICE
├── README.md
└── REBUILD.md                          # 改造记录与回归清单
```

## 第三方组件

| 组件 | 用途 | 许可 |
|------|------|------|
| [astc-encoder](https://github.com/ARM-software/astc-encoder) | ASTC 压缩/解压 | Apache-2.0 |
| [squish](https://sourceforge.net/projects/libsquish/) | DXT1/3/5 解压 | MIT |
| [stb_image_write](https://github.com/nothings/stb) | PNG 写入 | public domain / MIT |

详见 [NOTICE](NOTICE) 与各组件自带 LICENSE 文件。
