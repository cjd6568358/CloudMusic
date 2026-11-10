# T-L L3 · aapt2 四步编译实证（2026-11-08 晚）

> 仓库外临时最小工程，9 个暂存 layout 原样参与编译；**未改任何生产文件**。
> 复跑：`python tools/l3_aapt2_probe.py --aapt2 <SDK>/build-tools/<ver>/aapt2.exe`
> `--android-jar <SDK>/platforms/android-<api>/android.jar`
> （或设 `ANDROID_HOME` 自动探测）。结果 JSON：`aapt2-probe-result.json`。

| 步骤 | values | 接入暂存资源 | compile rc | flat | link rc | 错误行 | id 缺失 | attr 缺失 | 其它 |
|---|---|---|---|---|---|---|---|---|---|
| step1_baseline | （无） | — | 0 | 9 | 1 | 20 | 11 | 9 | 0 |
| step2_with_ids | ids.xml | — | 0 | 10 | 1 | 20 | 0 | 18 | 2 |
| step3_with_attrs | ids.xml, attrs.xml | — | 0 | 11 | 1 | 20 | 0 | 0 | 20 |
| step4_with_staged_res | ids.xml, attrs.xml | 42 文件 | 0 | 53 | 0 | 0 | 0 | 0 | 0 |

**基线错误形态**（step1，节选）：
```
<workdir>\step1_baseline\res\layout\legacy_are.xml:2: error: resource id/miContainer (aka com.example.l3probe:id/miContainer) not found.
<workdir>\step1_baseline\res\layout\legacy_are.xml:4: error: resource id/miTopBackground (aka com.example.l3probe:id/miTopBackground) not found.
<workdir>\step1_baseline\res\layout\legacy_are.xml:5: error: resource id/miBottomBackground (aka com.example.l3probe:id/miBottomBackground) not found.
<workdir>\step1_baseline\res\layout\legacy_are.xml:6: error: resource id/miCover (aka com.example.l3probe:id/miCover) not found.
<workdir>\step1_baseline\res\layout\legacy_are.xml:7: error: resource id/miColorTBMask (aka com.example.l3probe:id/miColorTBMask) not found.
<workdir>\step1_baseline\res\layout\legacy_are.xml:8: error: resource id/miColorBTMask (aka com.example.l3probe:id/miColorBTMask) not found.
```

**step3 剩余错误形态**（节选，均为 layout 引用的资源定义缺失）：
```
<workdir>\step3_with_attrs\res\layout\legacy_are.xml:14: error: resource drawable/bn5 (aka com.example.l3probe:drawable/bn5) not found.
<workdir>\step3_with_attrs\res\layout\legacy_are.xml:16: error: resource drawable/g_k (aka com.example.l3probe:drawable/g_k) not found.
<workdir>\step3_with_attrs\res\layout\legacy_are.xml:17: error: resource drawable/g_o (aka com.example.l3probe:drawable/g_o) not found.
<workdir>\step3_with_attrs\res\layout\legacy_are.xml:18: error: resource drawable/g_a (aka com.example.l3probe:drawable/g_a) not found.
<workdir>\step3_with_attrs\res\layout\legacy_are.xml:19: error: resource drawable/gc6 (aka com.example.l3probe:drawable/gc6) not found.
```

**step4 接入的暂存资源**：42 个文件，5 个 values XML 重写定义名，目录分布：

```
drawable: 7
drawable-anydpi-v24: 1
drawable-hdpi: 1
drawable-ldpi: 1
drawable-mdpi: 2
drawable-xhdpi: 2
drawable-xxhdpi: 21
drawable-xxxhdpi: 1
font: 1
values: 3
values-h640dp: 1
values-h736dp: 1
```

**结论**：四步证明 aapt2 层面三类错误（101 个无定义 @id、33 个未声明 app:* attr、45 项 layout 资源引用）全部可由「竞品 attrs.xml + public.xml + 已暂存的 45 项素材」机械搬运彻底消除，9 个 layout 在仓库外临时工程中 aapt2 编译 / 链接全绿。唯一剩余阻塞是 20 个 com.netease.* 自定义 View 无实现：aapt2 不校验 Java 类，故这不属编译错误，而是运行时 LayoutInflater 的 ClassNotFoundException，须由阶段 C 重写；是否照搬播放页仍待拍板，本实证不构成接线。

> 注：aapt2 不校验自定义 View 类是否存在，故 step4 全绿
> **不等于**运行时可 inflate；20 个 com.netease.* 类仍须阶段 C 重写。
> step1~step3 的 `错误行` 均为 20，是 aapt2 单轮报错上限，不代表错误总量。
> step4 的去前缀动作仅发生在仓库外临时工程，`app/src/main/res/` 仍未建立。

> AI生成
