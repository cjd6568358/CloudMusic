# 高级音效 capstone 专项 · 施工单（2026-11-14，§61.117）

> 复核报告与 §61.98/98b/99 反复挂账的「高级音效 capstone 专项」按 T5 分级拆解。
> 数天级；每级独立可验收，**不捆绑发布**。证据基线全部来自既有一手实证。

## 0. 证据基线（已实证，不再重复求证）

| 证据 | 结论 | 来源 |
|---|---|---|
| 竞品 AudioEffects 101 native 方法全量解码 | **零公开 PCM 入口**——引擎与竞品内核紧耦合，A 路线（native 挂接）不可行 | `12 §61.98b` baksmali 实证 |
| `LoudnessEvaluator`（init/push/getReplayGain） | 引擎内**现成的 ReplayGain 计算实现**，可接回（跨层链路待建） | `12 §61.99` |
| 系统 audiofx（Equalizer + BassBoost + Virtualizer） | B2 侧真机挂载 PASS，effectId 1~5 档位；§61.113 起 UI 0~5 档循环 | `12 §61.93` / `§61.113` |
| ReplayGain 标签通路 | 真机 5/5（ID3 TXXX 通道，0.471 精确命中）；VorbisComment 等其它容器未验 | `12 §61.111` |
| B2 PCM 插入点 | `FfmpegAudioDecoder` → `DecoderAudioRenderer(AudioSink)`——AudioSink 可注入自定义实现，是自研 DSP 的合法插入位 | Media3 官方架构 + §61.88 缓存层同型推理 |

## 1. T5-1 系统 audiofx 完整化（1~2 天，低风险，先做）

- **分频段 EQ UI**：`Equalizer.numberOfBands`（MIUI 设备实测 5 段）逐段 slider →
  `setBandLevel(b, milliBel)`；替代现在「effectId 全频段线性抬升」的粗糙档位；
- **预设**：平直/流行/摇滚/古典/人声（band level 表）+ 自定义；
- **持久化**：AppSettings 增 `audiofx` 段（enabled / bands / preset）→ DataStore JSON
  codec 扩展（字段缺省回落默认，向后兼容）→ 引擎 attach 时按持久值恢复；
- **验收**：真机听感 + 分频段变更即时生效（audioSessionId 不断重挂）+ 杀进程恢复持久值。

## 2. T5-2 ReplayGain 扫描落库（2~3 天，中风险）

- **链路**：扫描阶段（`ScanCoordinator` METADATA 段）解码头部 → `LoudnessEvaluator.push`
  → `getReplayGain` → Room 增 gain 字段（DB 迁移）→ 播放时 `ReplayGainController`
  读库优先、容器标签兜底；
- **依赖**：Room schema 迁移 + `TrackSink` 扩展 + 扫描时长预算（逐曲解码音频数据，
  大曲库耗时要诚实标注——先做「仅 LOCAL + 用户显式开启」的 opt-in 扫描）；
- **验收**：带标签样本走库值、无标签样本走 LoudnessEvaluator 计算值、无扫描样本走原音量；
- **挂账来源**：`§61.105 ④`（"不宣称接回已完成"）与本单合并收口。

## 3. T5-3 竞品高级音效对齐（空间音频 / NCAE 音质修复）（3~5 天，高风险，最后做）

- **不可行路径（已被 §61.98b 判死）**：直接挂竞品 AudioEffects JNI；
- **可行路径**：B2 自定义 AudioSink（`DefaultAudioSink` 包装或替换）截取 PCM →
  自研/开源 DSP 链（声场展宽 / 响度补偿 / 均衡后处理）→ 回写；
- **风险**：AudioSink 替换影响所有格式的时延与漏洞面；需要 A/B 真机听感验收与
  `UnexpectedDiscontinuityException` 类时间戳回归排查（§61.111④ 教训）；
- **验收**：开关切换 A/B 对比 + 全矩阵 22 格回归不破 + 长稳 20min 不回归。

## 4. 次序与当前状态

1. **T5-1 先行**（本单即开工许可；UI 已有 0~5 档雏形，升级为分频段）；
2. T5-2 随曲库 DB 批次；
3. T5-3 独立立项，需再次拍板（AudioSink 替换是全输出生面变更）。

> AI生成
