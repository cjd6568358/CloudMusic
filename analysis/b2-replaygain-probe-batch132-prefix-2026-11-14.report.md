# §61.107 ReplayGain 真机两格对照（2026-11-13）

- device_run = true，端点 `192.168.1.15:39497`，每格等待 8.0s，判据 **3/5 PASS**。
- 期望 TRACK 音量 = `10^(-6.54/20)` ≈ `0.4710`。

| 格 | 样本 | READY | PLAYING | replaygain 日志 | gain | volume |
|---|---|---|---|---|---|---|
| tagged | f3_mp3_rg.mp3 | True | True | True | none | 1.0 |
| plain | f3_mp3.mp3 | True | True | True | none | 1.0 |

## 一手日志

- **tagged**：`11-14 00:49:23.782 I/CM-Media3(27710): replaygain(tracks) tags=7 gain=none volume=1.000 mode=TRACK diag==Lavf57.83.100;=官方回答片段;=官方回答（翻自 O.WEN）;=Dollyy;=01;=1;=163 key(Don't modify):L64FU3W4YxX3ZFTmbZ+8/TMEUPuhU9L4c74O3lmoa4WcdIsvVqjuMJVYHH1qPmat9U+X16UbQ2xkHSoPLgmuR7438AGhpaEQ45jGKqd4SbsMv5+u1J2RXV64fhRgdkNRK+SSqd3YUFHAPuGdkgYVbIiHGbdcjrvIfDxQR13rmhWFCcO9aHi2Nhgdf+2vdpfufBPOVpmIXlyAtOnEqGpIrzMqf2085MJY0AXAN/wByRgS6azO/ZoahiFil7BrgL4kz5wPYM9pw7GjVzFf7ONwC6I19G2`
- **plain**：`11-14 00:49:36.172 I/CM-Media3(27711): replaygain(tracks) tags=7 gain=none volume=1.000 mode=TRACK diag==Lavf57.83.100;=官方回答片段;=官方回答（翻自 O.WEN）;=Dollyy;=01;=1;=163 key(Don't modify):L64FU3W4YxX3ZFTmbZ+8/TMEUPuhU9L4c74O3lmoa4WcdIsvVqjuMJVYHH1qPmat9U+X16UbQ2xkHSoPLgmuR7438AGhpaEQ45jGKqd4SbsMv5+u1J2RXV64fhRgdkNRK+SSqd3YUFHAPuGdkgYVbIiHGbdcjrvIfDxQR13rmhWFCcO9aHi2Nhgdf+2vdpfufBPOVpmIXlyAtOnEqGpIrzMqf2085MJY0AXAN/wByRgS6azO/ZoahiFil7BrgL4kz5wPYM9pw7GjVzFf7ONwC6I19G2`

## 判据明细

| # | 判据 | 结果 | 证据 |
|---|---|---|---|
| 1 | 两格均 READY(duration>0) + PLAYING | PASS | tagged=True/True plain=True/True |
| 2 | 带标签格出现 replaygain 日志且增益非 none | FAIL | ["11-14 00:49:23.782 I/CM-Media3(27710): replaygain(tracks) tags=7 gain=none volume=1.000 mode=TRACK diag==Lavf57.83.100;=官方回答片段;=官方回答（翻自 O.WEN）;=Dollyy;=01;=1;=163 key(Don't modify):L64FU3W4YxX3ZFTmb |
| 3 | 带标签格音量 ≈ 0.4710（10^(-6.54/20)） | FAIL | volume=1.0 |
| 4 | 无标签对照格保持 volume=1.000（不误施增益） | PASS | gain=none volume=1.0 |
| 5 | 两格均无播放错误回调 | PASS | tagged=0 plain=0 |

## 诚实边界

只验证含 ID3 TXXX REPLAYGAIN_* 标签的 MP3 LOCAL 通道；不验证其它容器 / REMOTE / 扫描式 ReplayGain，不宣称全格式生效；未改 Manifest / CMake / 生产构建配置，未做破坏性注入。
