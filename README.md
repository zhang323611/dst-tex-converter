# 饥荒手机版纹理转换工具（DST Tex Converter）

一个 Android 应用，用于把饥荒（Don't Starve / DST）PC 版模组纹理批量转换为**手机版兼容格式**。

## 功能

- **DXT1/3/5 `.tex` → ASTC 8×8 `.tex`**：把 PC 版模组纹理转成手机版 GPU 支持的 ASTC 压缩格式（体积约为 RGBA 的 1/16）
- **PNG → ASTC `.tex`**：新纹理转手机版格式
- **ASTC `.tex` → PNG**：反解预览
- **批量转换**：支持选中文件夹递归扫描、zip 压缩包内部纹理批量转换
- **原地替换 + 备份**：转换后直接替换原文件，原文件自动备份为 `.bak`
- **格式互转设置**：可选转换方向（自动 / PNG→TEX / TEX→PNG / TEX→ASTC）、ASTC 块大小、压缩质量

## 背景：手机版纹理格式

饥荒手游（DST mobile）的 `.tex` 使用 **ASTC 8×8（2bpp）** 压缩，KTEX 头部的 `compression` 字段值为 `24`；而 PC 版使用 DXT1/3/5（compression 值 0/1/2）。本工具负责在两者之间转换。

- ASTC 块大小可选：4x4(8bpp) ~ 12x12(0.89bpp)，默认 **8x8（手机版标准）**
- 饥荒 `.tex` 为垂直翻转存储，工具已自动处理翻转

## 构建

### 依赖

- JDK 17
- Android SDK：build-tools `34.0.0`、platform `android-34`
- Android NDK：r26+（含 aarch64 clang）

### 步骤

```bash
# 设置 SDK / NDK 路径（如未自动探测到）
export ANDROID_SDK_ROOT=~/Android/Sdk
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/26.3.11579264

bash build_apk.sh
```

产物：`dst_tex_converter.apk`

## 使用

1. 安装 APK（需允许「未知来源」）
2. 首次启动授予存储权限（Android 11+ 需在系统设置开启「所有文件访问」）
3. 进入模组目录，长按文件进入多选
4. 勾选文件/文件夹，点「转换选中」
5. 完成后原文件被替换为 ASTC 版本，原文件备份为同名 `.bak`

## 目录结构

```
├── app/                    # Android 应用（纯 Java）
│   ├── AndroidManifest.xml
│   └── src/com/dsttex/TexConverter.java
├── native/                 # JNI 桥接与 DXT 解压
│   ├── jni_astcenc.cpp
│   ├── jni_tex2png.cpp
│   ├── tex2png.cpp
│   └── stb_image_write.h
├── third_party/            # 第三方依赖
│   ├── astcenc/            # ARM astc-encoder（ASTC 压缩，Apache-2.0）
│   └── squish/             # squish（DXT 压缩，MIT）
├── build_apk.sh            # 构建脚本
├── LICENSE
├── NOTICE
└── README.md
```

## 第三方组件

| 组件 | 用途 | 许可 |
|------|------|------|
| [astc-encoder](https://github.com/ARM-software/astc-encoder) | ASTC 压缩/解压 | Apache-2.0 |
| [squish](https://sourceforge.net/projects/libsquish/) | DXT1/3/5 解压 | MIT |
| [stb_image_write](https://github.com/nothings/stb) | PNG 写入 | public domain / MIT |

详见 [NOTICE](NOTICE) 与各组件自带 LICENSE 文件。
