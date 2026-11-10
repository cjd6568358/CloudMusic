# F4 · Java→native 参数槽透传的一手二进制复核（12 §61.56）

> **2026-11-09 第八十三批** · 设备仍离线 → 纯离线 / 只读、
> 零生产代码改动、零设备操作、零新增依赖。
> 承接 §61.54（native 分支语义）与 §61.55（两个 task 的行为差异）。
> 本批把 §61.52 的 kind 槽假设**拆成两个独立环节**，并攻下前一个。

---

## 1. 为什么要拆分

§61.52 的假设其实混了两件事：

- **环节 A**：`UrlDataRequest.kt:96` 传的 `requestId` 是否**原样**落到 native
  `OnNetSourceDataDone(int kind, ...)` 的 `w1`（kind 槽）？
- **环节 B**：由此产生的错误「预加载完成」通知，是否**就是** fMP4 REMOTE
  卡点（无 codec、`Start id=3 offset=327895` 重复）的原因？

此前 A 只有 KDoc 与 §59 的文字推断，B 完全没有真机证据。§61.54/§61.55 证明了
native 侧收到不同 kind 会做什么，但没证明 Java 传的值怎么到那里。本批补 A。

## 2. JNI 调用约定与一手指令证据

ARM64 JNI：`x0=JNIEnv*`、`x1=jobject`、`x2=long j(native_ptr)`、
**`x3=long j2`（Java 侧 `j1` 请求标识）**、`x4+` 其余参数。

`SourceDataRequest.java:72-79`：
`OnDataRequestEnd(long j, String str)` → `OnNativeDataRequestEnd(native_ptr, j, str)`，
**j 原样透传，不做任何变换**。

JNI 导出 `Java_com_netease_cloudmusic_sdk_NMCommonCache_SourceDataRequest_OnNativeDataRequestEnd`
@ `0x3495c`（size 360）：

```
0x34988  cbz   x2, ...            ; native_ptr 判空
0x34990  mov   x23, x2            ; x23 = native_ptr
0x349a4  mov   x21, x3            ; x21 = j2（请求标识）
...
0x34a54  mov   x0, x23            ; x0 = native_ptr
0x34a58  mov   x1, x21            ; x1 = j2，原样
0x34a60  bl    #0x36c70           ; trampoline
```

trampoline `0x36c70`：

```
0x36c80  mov   x19, x0
0x36c90  mov   x21, x1            ; 再存一次
0x36ca4  mov   x1, x21            ; 再原样放回 x1
0x36ca8  mov   x2, x20            ; std::string
0x36cac  ldr   x8, [x8, #0x20]    ; vtable + 0x20
0x36cb0  blr   x8                 ; 虚调用
```

**整条链上 `x1` 从未被改写或重映射**；native 侧 `OnNetSourceDataDone @ 0xbee8`
正是 `0xbf08 cmp w1, #2` 用这个 `w1` 做分支。

## 3. 强交叉验证：数据包路径完全同构

`OnNativeDataRequestData @ 0x347f4`（size 360，数据包路径）：

```
0x34834  mov   x21, x3            ; j2
0x34838  mov   x22, x2            ; native_ptr
...
0x34900  mov   x0, x22            ; x0 = native_ptr
0x34904  mov   x1, x21            ; x1 = j2，原样
0x34914  bl    #0x36f68           ; 平行 trampoline
```

两条路径的 `(x0, x1) = (native_ptr, j2)` **完全同构**，只是 trampoline 地址与
后续参数不同。关键在于：**数据包路径的 `x1` 已被 `docs/12 §59.3 E9` 真机校准为
`kind`** —— `NATIVE_DATA_KIND_NORMAL = 1L` 时 32768 B 正常送达解码器，
`kind=2` 时尾段 push 数据丢失 → `Send Packet Invalid data error`。
既然同一个 `j2` 槽在数据包路径就是 kind，End 路径自然也是 kind 槽。

`SourceDataRequest.java` 的 KDoc 亦记录：「竞品 `nmcache/l.Start(JJJ)` 把 Start 的
j1（请求标识）原样带回三个回调首参」「`j1==2 → PreloadDoneTaskOnWriteThread`」。

## 4. 结论分层

| 环节 | 状态 | 依据 |
|---|---|---|
| **A**：`requestId` 原样落到 kind 槽，`id==2` 即 `kind==2` | **已由二进制证据 + §59.3 真机校准双重确认** | JNI 导出 / trampoline 参数槽无变换 + 数据包路径同构 + KDoc + `cmp w1,#2` |
| **B**：错误完成通知 → fMP4 REMOTE 卡点 | **仍待验证（高置信假设）** | 只有日志时序相关性（`onEnd id=2` → 1~2 ms 后重发同区间），无 A/B 裁决 |

**F4 仍判 BLOCKED**。 环节 A 被证实不等于整条因果链被证实：§59.2 的 **exp6
是已知反例**（两条路径读侧均正常收尾）、**竞品同样传 requestId 却可用**、
`ape×remote`（`onEnd=[1]`）与 `aiff`（`openSource 2`）仍不解释。
本批只是把「假设里最容易被质疑的一环」变成字节级事实。

**边界声明**：虚表槽 `vtable+0x20` 的具体目标类型由运行时决定，静态反汇编
证明的是「参数槽透传无变换」；结合 §59.3 数据包路径的真机校准与 KDoc 记录
构成高置信证据，但**不是 100% 的 C++ 类型证明**。

## 5. 对 A/B 的意义（不变）

环节 A 成立使 §61.53 的 A/B 判据更清晰：把首参恒传
`NATIVE_DONE_KIND_FLUSH = 1L` 后，若 fMP4 REMOTE 出现
`Open codec for aac` + `Write start play nErr:0`，则环节 B 成立；若仍
`Start id=3 offset=327895` 重复且无 codec，则环节 B 证伪、需另找机理。
`1L` 是 §59.3 已真机校准的正常值（§61.55），变体风险已显著降低。

设备重新上线后给新端点：

```
python tools/l3_f4_kind_slot_ab.py --serial <ip:port> --apply
```

脚本 `try/finally` 无条件恢复 P0 基线并重编译重装。之后仍需 F3 14 格全量复跑
+ 自动进曲 3 首（无 `-103`、无释放后回调），后者如实 **NOT RUN**。

## 6. 交付与诚实边界

- `tools/l3_f4_jni_arg_verify.py` → `analysis/l3-f4-jni-arg.json`
  （**17 项 checks 全 PASS**）；`tools/l3_f4_jni_arg_audit.py` →
  `analysis/l3-f4-jni-arg-audit.json`；`check_source_contracts.py §61.56` 守护。
- **零生产代码改动**：`UrlDataRequest.kt` `git diff` 为空、`so` sha256 与
  `assets/ENGINE-FINGERPRINT` 一致、`engine_compat_test` 不回填、舱不冻结。
- **零设备操作**：不连 adb、不改设备设置；不连 OpenList、不写凭据。
- 不写「根因已证实」「F4 已解决」「m4a 已支持」「全格式已支持」；
  两个 m4a 仍是人工截取/重排的 fMP4 片段；F3 矩阵 14 格未改写；
  A3 仍 **G09 PARTIAL**。`§61.38`~`§61.56` 十九批改动均未提交、未 push，
  `HEAD` 仍 `8c9d79b1`。

> AI生成
