# F3 逐格式 × REMOTE / LOCAL 双路径真机矩阵报告（2026-11-09 第七十六批 / `12 §61.49`）

> 端点 `10.0.0.2:39853`（Redmi K60 `23013RK75C` / Android 13 / arm64-v8a），
> 真机命令全部带 `-s`；不 `pm grant`、不改设备系统设置。
> 机器证据：`analysis/l3-f3-fixtures.json`（样本）、`analysis/l3-f3-matrix.json`（14 格结果）、
> `analysis/l3-f3-logs/*.txt`（每格全量 logcat）、`analysis/l3-f3-audit.json`（审计）。
> 可复跑：`tools/l3_f3_fixture_gen.py` → `tools/l3_f3_matrix_run.py` → `tools/l3_f3_matrix_audit.py`。

---

## 1. 样本来源（用户纠偏：不需要编码器）

用户 16:19 指出「为什么要编码器？从 openlist strm 驱动里拿
`http://192.168.1.1:15244/strm_test_probe`」。实测：

- `/strm_test_probe` 下有 **92 个 `.strm` 文件**，内容指向
  `/d/ecloud_self/我的音乐/<曲目>.<ext>?sign=…` 的真实音频直链；
- 音乐目录 134 个条目，格式分布 **`ape 18 / mp3 58 / flac 22 / wma 9 / wav 3`**；
- 直链为外部云盘签名 URL，支持 `Range`（实测 `206`），无需任何本地编码器。

本批取每种格式体积最小的一首：小文件完整下载，`flac / wav / ape` 因原文件
20MB / 45MB / 228MB 取**前 4 MiB 片段**（WAV 片段已修正 RIFF / data 长度使容器自洽）；
`ogg` 用竞品 `res/raw/d.ogg`（真实 Vorbis）；`aiff` 无现成样本，用标准库合成极简
FORM/AIFF PCM。凭据只在进程内使用，**未写入仓库任何文件**，清单不含 token / 签名 URL。

| 格式 | 大小 | 来源 | 完整性 |
|---|---|---|---|
| mp3 | 1,129,442 B | OpenList 真实 | 完整 |
| wma | 722,095 B | OpenList 真实 | 完整 |
| ogg | 6,401 B | 竞品 APK 真实 Vorbis | 完整 |
| flac | 4,194,304 B | OpenList 真实（原 20,458,409 B） | **前 4 MiB 片段** |
| wav | 4,194,304 B | OpenList 真实（原 45,537,204 B，已修长度字段） | **前 4 MiB 片段** |
| ape | 4,194,304 B | OpenList 真实（原 228,210,015 B） | **前 4 MiB 片段** |
| aiff | 1,146,934 B | 纯标准库合成 | 完整但未第三方校验 |

**仍无样本（7 种，NO_SAMPLE）**：`m4a / aac / opus / wv / tak / dsf / dff`。
OpenList 音乐库没有这些格式，矩阵里**一格未跑、不计 PASS 也不计 FAIL**。

## 2. 双路径通道

| 路径 | 注入 | 取流证据 |
|---|---|---|
| LOCAL | `ACTION_PLAY` + `EXTRA_LOCAL_PATH`（§61.47 零权限通道），样本经 `adb push /data/local/tmp/` → `cat \| run-as cat` 写入 app 私有 `files/music/` | `getLocalPath` → `CreateLocalSource: XorLocalSource::Open` → `BufferSource open ret=0` |
| REMOTE | **本批新增** `ACTION_PLAY` + `EXTRA_SOURCE_URL`，`PlaybackRuntime.playUrl()` 构造 `SourceRef(url=…)` → `RegistryLinkProvider` 无 driver 走 `ref.url` 直推 `Link(url=…)` → `EngineSource(url=…)`；PC `http.server 8765` + `adb reverse` | `getUrl` → `createDataRequestHandler` → `Start id=…` Range 取流 |

**测试前置（如实登记）**：设备默认网络实测为 `MOBILE[NR]`（WiFi 仅用于无线 adb），
而 `AppSettings` 默认 `network.wifiOnly=true`，`PlayerController.blockedByNetworkPolicy()`
会拒绝 URL 源 —— 这是产品闸门真实行为，非缺陷。为跑 REMOTE 矩阵，按用户在设置页可做
的等价操作，临时写入 app 私有 `files/datastore/cloudmusic_settings.pb`
（`{"network":{"wifiOnly":false}}`，显式 JSON codec 字段缺失回落默认值）；
**跑完删除该文件恢复默认**，设备系统设置零变更。

## 3. 矩阵结果（14 格）

| 格式 | LOCAL | REMOTE | codec | 备注 |
|---|---|---|---|---|
| mp3 | **PASS** | **PASS** | `mp3` | 完整真实音频 |
| wma | **PASS** | **PASS** | `wmav2` | 完整真实音频 |
| flac | **PASS** | **PASS** | `flac` | 前 4 MiB 片段，双路径均进解码链 |
| wav | **PASS** | **PASS** | `pcm_s16le` | 前 4 MiB 片段（长度已修正） |
| ape | **PASS** | **FAIL** | `ape` / — | 见 §4 归因 |
| ogg | **PASS** | **PASS** | `vorbis` | 竞品真实 Vorbis |
| aiff | **FAIL** | **FAIL** | — | 见 §4 归因 |

**11 PASS / 3 FAIL / 7 种格式 NO_SAMPLE**（`analysis/l3-f3-matrix.json`）。
每格 PASS 判据：`prepare` → `Open codec for <codec>` → `Write start play nErr:0`，
且无 `SourceCallback.onError` / `FATAL EXCEPTION` / ANR / `openSource != 0`。

### ★ F2-b 核心验收

**14 格 `resourceInfo extension=` 全部等于真实扩展名（14/14）**，LOCAL 源不再是
`audio/mpeg`，REMOTE 无后缀兜底也走同一 `AudioFormats.extOfSource` 真相源。
这是 §61.48 修正后在**双路径 + 7 种格式**上的真机复核，未出现 MIME 字符串回潮。

## 4. FAIL 归因（不掩盖、不误判为播放器缺陷）

**`ape × REMOTE`**：日志 `Start id=1 offset=0 length=1048576` →
`Start id=2 offset=4194272 length=32` → `onEnd id=1 … (delayed 2s)`，未见
`Open codec`。APE 的头 / seek table 需要文件尾部元数据，4 MiB 截断片段缺尾部 →
native 在 REMOTE Range 取流上反复读尾并延迟回 EOF。**同一片段在 LOCAL 路径 PASS**
（`XorLocalSource` 直读 fd 容错更好）→ 归因**样本截断 + REMOTE 取流**，
不是「播放器不支持 ape」；需完整 228 MB 样本复核。该现象与 **F4 要根治的
m4a EOF 2s 延迟掩盖同源**，已登记为 F4 输入。

**`aiff × LOCAL / REMOTE`**：两格均 `openSource 2` / `Play status 9`，
native demuxer 未打开。样本是**自造极简 AIFF 容器且未经第三方解码器校验**，
因此**无法区分「样本不可解」与「libffmpeg 未编入 aiff demuxer/decoder」**，
不判播放器支持或不支持。需真实 `.aiff` 样本复核。`extension=aiff` 仍正确，
说明失败发生在扩展名之后的 demuxer 阶段。

## 5. 诚实边界（F3 判 **PARTIAL**）

- 7 种格式无真实样本 → 矩阵不完整，**不得写「全格式已支持」**。
- `flac / wav / ape` 为截断片段：只验证 demuxer 识别 + codec 打开 + 播放链，
  **不验证完整时长 / seek / ENDED**；`mDuration_raw` 是 native 原始值，
  单位未在 ABI 契约钉死，不换算、不据此声称时长达标。
- `aiff` 为合成容器，结论不可外推到真实 AIFF。
- 本批**未逐格注入 seek / pause→play**，未跑自动进曲连续性；这些属 F4 / A3 G01 全量判据。
- `EXTRA_SOURCE_URL` + `playUrl()` 是验收期调试入口，零新增 Manifest action /
  权限 / 模块 / Gradle 依赖，无 extra 时 `runtime.play()` 零回归，随 `12 §61.33` 收口。
- P0 native 舱（so 打包集 / `ENGINE-FINGERPRINT` / `openLocalFd=-1`）零改动；
  A3 仍 **G09 PARTIAL** → 舱不冻结、`engine_compat_test` 不回填。

## 6. 下一步

1. **F4**：根治 m4a EOF 2s 延迟掩盖（本批 `ape×remote` 的 `Start id=2 … delayed 2s`
   提供了同源代码路径证据），连续 3 首无 `-103`、无释放后回调。
2. 补齐矩阵：用户提供 `m4a / aac / opus / wv / tak / dsf / dff` 真实样本或完整
   `ape` / 真实 `aiff` 后，**复用本批脚本即可复跑**，无需改代码。
3. **F5**：A3 全绿后 ABI 回填 / 冻结舱。完成后回 `docs/17 §4` 的 P2→P3→P4。

> AI生成
