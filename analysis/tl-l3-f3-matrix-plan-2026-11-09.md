# F3 施工单：逐格式 × REMOTE / LOCAL 双路径真机矩阵（2026-11-09 第七十六批 / `12 §61.49`）

> **触发**：用户「继续」→ 按 `docs/17 §8.3` 的 todo 顺序，F2-b + F2-c 已于
> `12 §61.48` 完成，当前主断点推进到 **F3**（**该触发说明为开工时快照**；
> 16:19 用户纠偏后的现行口径与最终结果见 **§0**，本批已由 `12 §61.49` 执行完毕）。
> **本轮性质**：前置实证 + 样本能力盘点 + 通道施工 + 真机矩阵；**不伪造全格式结论**。

---

## 0. 修订：用户 16:19 纠偏「为什么要编码器？」（**现行口径**）

用户指出样本本就在 OpenList 上：`http://192.168.1.1:15244/strm_test_probe`。
实测 `/strm_test_probe` 下 **92 个 `.strm` 文件**指向
`/ecloud_self/我的音乐/` 的真实音频直链，该目录 134 个条目含
**`ape 18 / mp3 58 / flac 22 / wma 9 / wav 3`**，外部直链支持 `Range`（206）。
**本机不需要任何音频编码器**；§1 的「无编码器 = 硬阻塞」与 §2 的「自造 FLAC 编码器」
是纠偏前的**当时快照**，已被本节取代，保留仅为决策时间线可追溯。

现行样本集（7 种）：`mp3 / wma / flac / wav / ape`（OpenList 真实，大文件取前
4 MiB 片段）+ `ogg`（竞品真实 Vorbis）+ `aiff`（无现成样本，标准库合成）。
**NO_SAMPLE 由 9 种收窄为 7 种**：`m4a / aac / opus / wv / tak / dsf / dff`。
OpenList 凭据只在进程内使用，不写入仓库。

**最终执行结果见 `analysis/l3-f3-matrix-report.md`**：14 格 = 11 PASS / 3 FAIL /
7 种格式 NO_SAMPLE；**14/14 格 `extension` 与真实格式一致**（F2-b 双路径复核）。
F3 整体仍判 **PARTIAL**。§3~§6 的通道设计、判据、交付与边界继续有效，其中 §3 的
REMOTE 通道已按实测补入「默认网络为 MOBILE → 临时 `wifiOnly=false`」前置。

---

## 1. 前置实证（本机 / 设备，2026-11-09 16:02~16:12 实跑；**当时快照，见 §0**）

| 项 | 实测 | 结论 |
|---|---|---|
| PC `ffmpeg` | PATH 无；`C:/Users/Administrator/AppData/Local/ms-playwright/ffmpeg-1013/ffmpeg-win64.exe` 为 `n7.0.1-playwright-build-1013`，配置 `--disable-everything`，`-encoders` / `-formats` 音频项 **0** | 只有 VP9 / PNG，**不能生成音频样本** |
| Python 音频库 | `imageio_ffmpeg / soundfile / numpy / pydub / av / scipy` **全部未安装** | 只能用标准库 |
| 外网 | `pypi.org` / `files.pythonhosted.org` / `github.com` / `ghfast.top` / `maven.google.com` 各 15s 探测均 `000`（超时）；`pip download imageio-ffmpeg` 挂起 3m37s 后终止 | **无法补齐编码器/依赖**（本轮硬阻塞） |
| 竞品 APK 音频素材 | 9 个 `.mp3`（`assets/`，最大 29,902 B）、1 个 `res/raw/d.ogg`（6,401 B，魔数含 `\x01vorbis` → **Vorbis**）、1 个 `res/raw/x.m4a`（**0 B，不可用**）；`analysis/decoded/` 已解出同样文件 | 真实样本只有 **mp3 / ogg(vorbis)** 两类 |
| 140 个 `res/raw` 无扩展名文件魔数扫描 | 仅 `OggS` 1 / `ID3` 6 / `RIFF`(webp) 2，无 `fLaC` / `ftyp` / `MAC ` / `wvpk` / `DSD ` / `FORM` | 没有隐藏的其它格式样本 |
| 设备 `sqlite3` | `which sqlite3` → 无 | 不能靠 `run-as` 直接灌 Room URL 夹具 |
| 设备端点 | `10.0.0.2:39853` `device`（`37827` offline）；`/sdcard/Music/cloudmusic_g01.mp3` 37,824 B 在位 | 真机可用 |

**结论**：F3 的「14 种候选格式全覆盖」在本轮**无法完成**，原因是本机无音频编码器且外网不可达。
按「不伪造」原则，本批只跑**可无损生成 / 已确证真实**的样本，其余格如实标 `NO_SAMPLE`
并写明解除条件；矩阵整体判 **PARTIAL**，不写「全格式已支持」。

## 2. 可达样本集（本批）

| 格式 | 来源 | 生成方式 | 结构可信度 |
|---|---|---|---|
| `mp3` | 设备 `/sdcard/Music/cloudmusic_g01.mp3`（37,824 B，A3 G01 同一 12s 正弦音夹具） | `adb pull` 原样取回 | 真机历史已验证可播 |
| `ogg` | 竞品 `analysis/decoded/res/raw/d.ogg`（6,401 B，Vorbis） | 原样复制 | 竞品自带真实音频 |
| `wav` | 纯标准库 `wave` 生成 12s / 48kHz / mono / s16le 440Hz 正弦 | `tools/l3_f3_fixture_gen.py` | 容器极简、逐字段可校验 |
| `aiff` | 纯标准库 `struct` 生成 FORM/AIFF COMM+SSND（s16be PCM） | 同上 | 容器极简、逐字段可校验 |
| `flac` | 纯标准库手写最小 FLAC 编码器（`fLaC` + STREAMINFO + verbatim subframe + CRC8/CRC16） | 同上 | 需注明「自造编码器，未经第三方解码器校验」 |

**NO_SAMPLE（9 项，硬阻塞）**：`m4a` / `aac` / `opus` / `wma` / `ape` / `wv` /
`tak` / `dsf` / `dff`（`alac` 属 `EXTRA_AUDIO_EXT` 非收录集，同样无样本）。
解除条件：用户提供任一可用 `ffmpeg`（或上述格式的真实样本文件），或恢复外网以便获取
编码器；届时复用本批脚本即可补齐矩阵，**无需改代码**。

## 3. 双路径注入通道

- **LOCAL**：沿用 `12 §61.47` 的 `ACTION_PLAY` + `EXTRA_LOCAL_PATH`，样本经
  `run-as` 写入 app 私有 `files/music/f3_<ext>.<ext>`，零权限。
- **REMOTE（本批新增，验收期调试入口）**：`ACTION_PLAY` + 可选
  `EXTRA_SOURCE_URL = "com.example.cloudmusic.extra.SOURCE_URL"`；
  `PlaybackRuntime.playUrl(url)` 构造 `SourceRef(storageId="f3-remote-fixture",
  url=http://127.0.0.1:8765/…)`，`RegistryLinkProvider.link()` 在该 storageId
  **无 driver** 时按 `ref.url` 产出 `Link(url=…)` → `engineSourceOf()` 产出
  `EngineSource(url=…, localPath=null)` → `createDataRequestHandler()` 建
  `UrlDataRequest`。PC 侧 `python -m http.server 8765` + `adb reverse tcp:8765 tcp:8765`
  （沿用 A3 G01 回环口径；Manifest 已有 `usesCleartextTraffic="true"`）。
  不新增 Manifest action / 权限 / 模块 / Gradle 依赖；无 extra 时 `runtime.play()`
  原行为零回归，**随 `12 §61.33` 一并收口**。

## 4. 判据（每格）

沿用 A3 G01：`prepare` → `resourceInfo extension=<真实扩展名>` → `Open codec for
<codec>` / `Open input file success` → `Write start play nErr:0` → `mDuration` /
`onProgress` → 无 `SourceCallback.onError` / `FATAL EXCEPTION` / ANR / 非 0 `nErr`。

| verdict | 含义 |
|---|---|
| `PASS` | 上述链完整、零崩溃 |
| `PARTIAL` | 进了解码链但有缺口（如无 `Write start play`、时长异常、seek 未验） |
| `FAIL` | 明确 `onError` / `openSource != 0` / 崩溃 / 无解码 |
| `NO_SAMPLE` | 无可用样本（§2 的 9 项），**不算 FAIL、不算 PASS** |

`seek` / `pause→play` / `ENDED` / 完整时长 ±0.5s 属 G01 全量判据；本批夹具多为
提示音级短音频，**只记录观测到什么，不把缺失项补成 PASS**。

## 5. 交付与守护

- `tools/l3_f3_fixture_gen.py` → `analysis/l3-f3-fixtures/` + 清单 JSON。
- `tools/l3_f3_matrix_run.py` → `analysis/l3-f3-matrix.json` + 每格原始 logcat。
- `tools/l3_f3_matrix_audit.py` → 校验 JSON（防伪造 `PASS`、防把 `NO_SAMPLE`
  写成 `PASS`、防基线漂移）+ `check_source_contracts.py §61.49` 守护。
- 全量回写 `docs/12 §61.49` / `09 §2·§7` / `16 §5.3` / `17 §8.3·§8.9` /
  `analysis/task-backlog` / 当日日志 / `MEMORY.md`。**不新增 `docs/` 文件**
  → `09 §3` 计数仍 31。

## 6. 本批边界

- P0 native 舱（so / `ENGINE-FINGERPRINT` / `openLocalFd=-1`）零改动；
  只扩展自研 Java/Kotlin 调试入口。
- 真机命令一律带 `-s 10.0.0.2:39853`；不 `pm grant`、不改设备设置、
  不碰公共媒体目录（除读取既有夹具）。
- A3 仍 G09 PARTIAL → 舱不冻结、`engine_compat_test` 不回填。
- **F3 完成后仍需 F4（m4a EOF 2s）→ F5（ABI 回填 / 冻结舱）→ 回 P2→P3→P4。**

> AI生成
