# rebuild 分支改造说明（v1.5，针对 v1.4 的代码审查结论）

本次改造目标：先把"会毁用户文件 / 会崩 / 静默出错"的问题止血，不动架构。
逐条对应审查报告（`代码审查报告.md`）中的编号。

## 已修复

### P0

| # | 问题 | 修法 |
|---|---|---|
| 1 | ASTC 块大小选项是假功能（header 与数据块尺寸不一致 → 废纹理） | 收敛为唯一值 `ASTC_BLOCK = "8x8"`，设置里只显示说明；`writeAstcHeader` / `packKtexLevels` / astcenc 参数三处常量一致 |
| 2 | `tex2png.cpp` 用 `exit()` 杀进程、无任何边界校验 | 全部改为返回码（`ERR_*`）；校验 KTEX 头、尺寸上限、mipmap 头偏移、按格式算出的必需字节数；`readFile` 不再 `exit`，分配失败返回 `ERR_TOO_LARGE` |
| 3 | 写盘非原子，中途失败把原文件写成半截 | 新增 `writeFileAtomic()`（同目录 `.tmp` → fsync → rename）+ `backupOnce()`；`texToAstc` / `pngToTex` / zip 替换 / `AnimEditor.save` 全部改走它 |
| 4 | 提示"备份 .bak"但默认不备份 | `autoBackup` 默认改为 **true**；完成对话框按实际设置输出文案 |
| 5 | 编辑器"导入"后保存写的是 cacheDir 副本，原文件不变 | 新增 `sAnimSrc`：浏览器打开=原 zip；SAF 导入尽力解析真实路径，解析不出则**明确另存到下载目录**并提示路径 |
| 6 | 撤销栈只存数值不存元素，会把旧值写到别的元素 | 撤销条目改为 `Undo{el, a..z, buildFrame}`，撤销时自动切回被改元素；拖动也纳入撤销 |
| 7 | zip 内 TEX→PNG 内容变了但扩展名没变（假 tex） | `convertTexByMode` 返回 `TexConvOut{data, changed, name}`，改名 `.tex`→`.png`，并新增 `uniqueName()` 防撞名 |

### P1

- **跳过 ≠ 失败**：新增 `SkipException`，批量结果分列 `成功 / 跳过 / 失败`（原先把跳过当失败并打调用栈）。
- **递归收集筛选**：`collectTexRecursive` 只收 `.tex/.png/.zip`，`.bak` 一律跳过（之前勾一个模组文件夹会刷出大量 `FAIL xxx.bak`）。
- **设置全量持久化**：`convertMode` / `quality` 之前只改内存，现与 `autoBackup/nestedZip/debugMode/threadCount` 一起落盘。
- **TEX→PNG 模式遇 ASTC 必失败**：新增 `texBytesToPng()`，comp=24 走 astcenc，其余走 tex2png。
- **帧滑条 max 不随动画切换更新**：`SeekBar` 提为字段 + `syncFrameBar()`，切动画/跳帧/撤销后同步。
- **播放时每帧重建左侧元素列表**：`setFrame` 仅在非播放状态重建，停止播放时补一次。
- **`save()` 在 UI 线程解压重压整包（大模组必 ANR）**：改后台线程 + `patchAnimBytes`/`repackZip` 拆分 + 原子替换。
- **进度框异常时永久卡住**：`ProgressDialog` 改可取消、`try/catch(Throwable)/finally` 保证 dismiss；新增取消按钮语义（`shutdownNow`）。
- **结果集无截断**：结果框最多列 40 条，其余指向日志。
- **进度回调刷屏**：`postProgress()` 节流 120ms。
- **`openDyn` 导出覆盖同名 zip**：改为 `xxx_decoded.zip`（重名递增）；`ZipFile` 加 finally 关闭。
- **`MANAGE_APP_ALL_FILES_ACCESS` 无 try/catch**：失败退化到应用详情页。
- **临时 zip 放 cacheDir**：改为原文件同目录（不受 cache 配额限制 + rename 同分区原子 + 省一次整包拷贝）。
- **`ok == 0` 也重写整个 zip**：无改动时保持原包不动。
- **日志无上限**：`dsh_tex.log` 超过 2MB 自动轮转清理。

### 构建/清单

- `minSdk` 21 → **24**（与 `aarch64-linux-android24-clang++`、`d8 --min-api 24` 对齐，否则 API 21–23 加载 .so 必失败）。
- 清单加 `android:extractNativeLibs="true"`（targetSdk≥23 时默认 false，要求 .so 未压缩+页对齐，与当前打包方式冲突）与 `largeHeap="true"`。
- native 编译加 `-Wl,-s`；`aapt2 link` 显式传 `--min-sdk-version/--target-sdk-version`；NDK 探测失败给明确错误而非静默退出。
- 版本 1.4 → **1.5**（versionCode 6）。

## 尚未处理（审查报告里的 P2/P3）

- `parseBuild/parseAnim` 缺长度校验（伪造文件可 OOM/AIOOBE）
- 大目录列表元数据缓存（`getView` 每行多次 stat + `new SimpleDateFormat`）
- 首页 UI 线程递归搜索
- 动画预览对话框 `weight=1` 画布高度可能为 0（需真机确认）
- 每级 mip 的 PNG 中转 → 改 TGA；zip 内 `.tex` 用 `NO_COMPRESSION`；mip 用 `-fast`
- `AnimRenderer` 去掉 `LAYER_TYPE_SOFTWARE` 的可行性验证
- 拆包（`TexConverter.java` 仍 2500+ 行）+ 引入 JUnit（格式解析最适合单测）
- zip 条目权限位/extra field 保真

## 验证状态

- Java：`javalang` 语法解析通过（3 个源文件）；**未做编译验证**（本机无 JDK/Android SDK）。
- 原生：**未编译**（本机无 NDK）。
- 请在构建环境执行 `bash build_apk.sh`，重点回归：
  1. 单张 DXT→ASTC 转换后预览、游戏内加载；
  2. 勾选整目录批量（确认 SKIP 与 FAIL 分列、进度框可取消）；
  3. zip 批量 + 嵌套 zip；`TEX→PNG` 模式下 zip 内条目是否变成 `.png`；
  4. 动画编辑器：拖动→撤销、切动画后拖帧条、保存后原包是否被改写且生成 `.bak`；
  5. 中断测试（转换中强杀进程）后原文件是否完好。
