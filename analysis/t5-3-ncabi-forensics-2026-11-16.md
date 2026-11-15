# T5-3 选项 C 前置取证 · NCAudioEffectsProcessor ABI 反汇编（2026-11-16）

> 工具：`analysis/t53_neaudioeffects_disasm.py`（ELF64 dynsym + capstone，
> route1_disasm 同型，目标 = engine-legacy/src/main/jniLibs/arm64-v8a/
> libneaudioeffects.so，1,881,136B）。零代码改动（取证脚本 + 本报告）。

## 一、导出符号（dynsym 实测）

| 符号 | 地址 | size |
|---|---|---|
| `_ZN23NCAudioEffectsProcessor6CreateEv` | 0x49db0 | 48 |
| `_ZN23NCAudioEffectsProcessor26GetDefaultEffectChainOrderEv` | 0x49c30 | 4 |
| `_ZN23NCAudioEffectsProcessor27SetChannelDelayParamsSmoothERK24ChannelDelayEffectParams` | 0x4efbc | 16 |
| `Java_..._LoudnessEvaluator_{create,init,push,getReplayGain,reset,resetReplayGain,finalizer}` | 0xb0b28~0xb0d28 | — |

**关键事实：libneaudioeffects.so 已随 T5-2 在 App 进程内加载**
（ReplayGainController 的 LoudnessEvaluator JNI 即本库，§61.150 真机验证）——
C 路径**不存在「库加载」障碍**。

## 二、反汇编一手

1. **`Create()` @0x49db0**：`mov w0,#0xad50; movk w0,#1,lsl#16` → 分配
   **44,368 B 对象**（operator new @0x16700）→ `bl 0x49de0`（构造器）→ 返回。
2. **构造器 @0x49de0**：`adrp x10,#0x1c3000; add x10,x10,#0x358; str x10,[x0]`
   → **vtable @ 0x1c3358**（Itanium ABI，this+0）。
3. **vtable 24 槽**（0x4b384~0x4df44，全内部函数，无 dynsym 匹配）；
   抽槽 [4] @0x4cde4 = 参数应用方法（this+0x170 字节标志 + 逐效果链成员
   `blr` 调用，子对象位于 this+0xaa81 区 / `SetChannelDelayParamsSmooth`
   走 `this+0xa8` 子对象指针）→ 对象布局 = 效果链成员定偏移集合。
4. `SetChannelDelayParamsSmooth`：`ldr x0,[x0,#0xa8]` + cbz + 尾调 —— 子对象
   指针访问模式证实。

## 三、C 路径可行性结论（对照 §61.149 拍板材料）

- **技术可行证实**：Create 导出 + 库已加载 + vtable 可枚举（24 槽）+
  效果链子对象定偏移可访问。
- **剩余工作量（拍板决策输入）**：24 槽**语义映射**（逐槽反汇编 + 调用图，
  识别 Init/Configure/Process/Flush/Reset 与 PCM 格式 s16/f32、interleaved、
  in-place 语义）+ AudioProcessor 桥 + JNI glue——**数天级 capstone**，
  与 §61.98b「深水区不可预估工期」一致，但现在有具体锚点
  （Create @0x49db0 / ctor @0x49de0 / vtable @0x1c3358 / 子对象偏移族）。
- **风险**：vtable 槽位顺序随竞品 so 版本漂移（C5 原样保留的 so 不变则稳定）。

## 四、对拍板的影响

- 路径 C 的「不可预估」已收敛为「**可预估的数天级 capstone**」（锚点齐）；
- 路径 B（AudioProcessor 桥 + 自研 DSP）的桥实现仍是 C 的接入面
  （桥先建不废功）；
- 拍板材料（t5-3-effects-evaluation-2026-11-16.md）选项 4「混合」的
  成本不确定性显著降低。

> AI生成
