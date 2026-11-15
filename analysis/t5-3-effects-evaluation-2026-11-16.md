# T5-3 竞品高级音效对齐 · 评估拍板材料（2026-11-16）

> 承接 `analysis/audio-effects-capstone-plan-2026-11-14.md` §3 的「T5-3 独立立项，
> 需再次拍板」。本材料汇总既有一手实证，给出三条路径的可行性/工作量/风险对照
> 与拍板选项。**本批零代码改动**。

## 一、证据基线（全部一手，来源标注）

| # | 证据 | 来源 |
|---|---|---|
| E1 | 竞品音效引擎 = libneaudioeffects.so（1.88MB，302 符号）+ libneaudioeffect_core.so（1.73MB）；**Java JNI 仅 14 个 set*Params**，101 个 native 方法为**纯参数面**，处理器由竞品播放内核**内部调用**（RegisterNatives 动态注册，静态不可见） | §61.98 / §61.98b（dynsym + baksmali 全量解码） |
| E2 | 效果族清单：ParametricEQ / GraphEQ / BassTreble / MultiBandCompressor×3 / MBStereoField / Delay / ChannelDelay / Chorus / Reverb / SpatialAudio / DynamicEQ / ALC / Loudness / FIR / HeadphoneImpulse / Timeline | §61.98b smali 全量 |
| E3 | **零公开 PCM 入口**——NCAudioEffectsProcessor 为 push 型 PCM 引擎但 ABI 未导出；`EffectorCheck.wavFirEnable([BII)Z` 是唯一疑似 byte[] 入口（语义未证） | §61.98b / §61.99 |
| E4 | `LoudnessEvaluator`（init(III)/push([BI Z)/getReplayGain(I)F）**有公开 PCM 入口**——已接回并真机验证（§61.150：29 日志 / 21 首落库 / spec 跨会话一致） | §61.99 / §61.122-124 / §61.150 |
| E5 | **audiofx 基础件已真机 PASS**：EQ（5 段）/BassBoost/Virtualizer 挂 audioSessionId，effectId 档位 + 分频段 + 持久化 + 换曲自动恢复 | §61.93 / §61.118（T5-1 ✓）/ §61.150 |
| E6 | B2 PCM 链插入点：`FfmpegAudioDecoder → DecoderAudioRenderer(AudioSink)`；Media3 1.11.1 官方 API = `DefaultAudioSink.Builder.setAudioProcessors(vararg AudioProcessor)`（官方支持的 PCM 处理链插入，无需整体替换 AudioSink） | Media3 官方架构 + §61.88 同型推理 |
| E7 | ReplayGain 全链真机闭环（库值优先/标签兜底/扫描计算） | §61.150 一手 |

## 二、三条路径对照

### 路径 A：不接回，维持现状（audiofx 基础件）

- **现状即此**：基础 EQ/重低音/环绕（audiofx）+ ReplayGain 音量补偿已覆盖
  「常规听感增强」需求面；竞品的**高级**效果（SpatialAudio 空间音频 / NCAE
  音质修复 / HeadphoneImpulse）不提供。
- 工作量 0；风险 0；用户感知差异 = 高级效果缺失（个人使用可接受与否 = 用户判断）。

### 路径 B：Media3 `AudioProcessor` 桥 + 自研/开源 DSP 链（**推荐，若拍板做**）

- **插入点为官方 API**（E6）：自定义 `BaseAudioProcessor` 子类挂入
  `DefaultAudioSink.Builder.setAudioProcessors`——不整体替换 AudioSink，
  输出面变更比施工单预估的「包装或替换」更收敛（官方支持的扩展点，
  Media3 自身升版兼容面由库维护）。
- 效果对齐 = **自研/开源 DSP 实现同类语义**（竞品效果本体不可复用，E3）：
  | 竞品效果族 | 开源/自研等价 | 复杂度 |
  |---|---|---|
  | MBStereoField / SpatialAudio | mid/side 声场展宽、crossfeed（bs2b 同型） | 低~中 |
  | BassTreble / ParametricEQ / GraphEQ | biquad 级联（audiofx EQ 已覆盖大半） | 低 |
  | Reverb / Chorus / Delay | Freeverb 类 / 反馈延迟线 | 中 |
  | MultiBandCompressor / ALC / DynamicEQ | 多带压缩 + AGC | 中~高 |
  | HeadphoneImpulse | 卷积冲激响应（FFT 卷积） | 高 |
- 工作量：AudioProcessor 桥 + 逐效果实现 + A/B 听感验收 ≈ **与施工单 3~5 天
  同量级**（官方 API 降低结构风险，DSP 实现工作量不变）。
- 风险：全输出生面变更（22 格回归 + 20min 长稳 + §61.111④ 时间戳教训照验）；
  增加音频延迟（处理器缓冲）；每效果独立开关（默认全关，零行为变化基线）。

### 路径 C：capstone 接回竞品 NCAudioEffectsProcessor（照搬舱路线）

- 前置 = 反汇编 libneaudioeffects.so 钉死 NCAudioEffectsProcessor 的 **PCM ABI**
  （缓冲格式 s16/f32 / interleaved、init 参数链、in-place 语义）——E3 已证
  **Java 层零入口**，全在 native 内部；RegisterNatives 动态注册表需运行时 dump
  或深度 capstone——**不可预估工期的深水区**。
- 收益 = 效果语义 1:1（含 NCAE 音质修复的私有算法）；代价 = 依赖竞品 so
  （C5 范围内原样保留，可 statically link 调用）+ 高不确定性。
- **与路径 B 不互斥**：AudioProcessor 桥（B）是 C 的接入面——C 可视为 B 的
  「DSP 换成竞品处理器」升级，桥先建、源后换。

## 三、拍板选项

1. **维持 A**（现状）：基础听感已覆盖，高级效果不做——零工作量。
2. **批 B（推荐）**：先建 AudioProcessor 桥 + 1~2 个低风险效果（声场展宽 /
   BassTreble 补强）真机 A/B 验收，效果族按需扩展——3~5 天量级。
3. **先 C 前置调研**：只做 NCAudioEffectsProcessor PCM ABI 反汇编取证
   （数天、不可预估），取证后再决定 B/C——**调研本身不改播放链，零回归面**。
4. A+B+C 混合：桥先建（B），DSP 先用自研占位，C 取证后按结果换芯。

> 推荐：**选项 2**（桥 + 低风险效果先行）或 **选项 3**（先取证再拍板）。
> 选项 2 的桥实现是任何后续路径的公共前置，不产生废功。

## 四、验收基线（路径 B/C 通用，照施工单原口径）

22 格全矩阵回归不破 + 20min 长稳不回归 + A/B 听感对比 + §61.111④ 类时间戳
回归排查（UnexpectedDiscontinuityException）+ 每效果独立开关默认全关。

> AI生成

## 五、选项 C 前置取证结果（2026-11-16 追补，analysis/t5-3-ncabi-forensics-2026-11-16.md）

capstone 反汇编 libneaudioeffects.so（arm64）：**Create @0x49db0 导出可调**、
对象 44,368 B、**vtable @0x1c3358 共 24 槽**（全内部函数）、效果链子对象定偏移
（this+0xa8 / this+0x170 等）——**库已随 T5-2 在进程内加载**（LoudnessEvaluator
JNI 同库），C 路径无「库加载」障碍；剩余工作量收敛为「24 槽语义映射的数天级
capstone」（锚点齐：Create/ctor/vtable/子对象偏移族）。选项 4（混合）的成本
不确定性显著降低。

> AI生成
