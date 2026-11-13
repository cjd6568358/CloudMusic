# FFmpeg 工具链与 APK 构建快照（`docs/12 §61.103`）

- 工具：`tools/b2_ffmpeg_toolchain_audit.py`（纯 stdlib / 只读）
- 判定：**PASS**（10/10 项）

| 项 | 实测 |
|---|---|
| FFmpeg 源码 | 6.0 / 10234012 B / 8117 entries |
| 静态库 arm64 | {'libavcodec.a': 12452932, 'libavutil.a': 3849284, 'libswresample.a': 572606} |
| 静态库 v7a | {'libavcodec.a': 8484818, 'libavutil.a': 3063012, 'libswresample.a': 397994} |
| 启用 decoder | 28 个（含 ape/wmav2/wavpack/pcm_s16be） |
| APK | 41845775 B / BUILD SUCCESSFUL / 149 tasks |
| libffmpegJNI.so | 7794496 B / 4 个目标符号实扫命中 |
| 装机 | BLOCKED: INSTALL_FAILED_UPDATE_INCOMPATIBLE (签名不匹配) |
| 真机复测 | NOT RUN |

| # | 检查项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | 两 ABI 静态库三件齐备 | PASS | arm64-v8a / armeabi-v7a 各 avcodec+avutil+swresample |
| 2 | 四个目标 decoder 在 libffmpegJNI.so 中实测存在 | PASS | llvm-nm 实扫；共 30 个 ff_*_decoder |
| 3 | APK 首次构建成功 | PASS | 41,845,775 B / 149 tasks |
| 4 | 装机阻塞如实登记 | PASS | 旧 APK 与本机 debug keystore 签名不匹配 |
| 5 | 真机复测 NOT RUN | PASS | 未强行卸载 / 未改设备现场 |
| 6 | Windows 宿主适配登记（VERSION 遮蔽） | PASS | FFmpeg VERSION 遮蔽 C++ <version>，本地构建树重命名为 VERSION.txt |
| 7 | §61.103 正式批次在位 | PASS | docs/12 |
| 8 | 全量回写锚点齐备 | PASS | 12 / 09 / 16 / 17 / backlog |
| 9 | 反伪证：符号存在不写成可播 | PASS | 历史禁语命中=['真机可播'] |
| 10 | 历史 F3 矩阵零改写 | PASS | 复测未执行，l3-f3-matrix.json 与 8 份 logcat 未触碰 |

## 诚实边界

- 静态库与源码目录被 .gitignore 排除，跨检出不可复跑本编译；本快照只记录本机实测，不代表 CI 可复现。
- decoder 符号存在 ≠ ffmpegHasDecoder 运行时为真 ≠ 真机可播；真机 F3 复测 NOT RUN。
- 装机因签名不匹配被拒，未擅自卸载旧 app（会清设备私有数据）。
- 历史正文与证据 JSON 零改写；未连 adb 注入、未改 Manifest / CMake。

> AI生成
