# F3 8 FAIL 纯离线归因（`docs/12 §61.101`）

- 工具：`tools/b2_f3_fail_attribution.py`（纯 stdlib / 只读 / 不连 adb）
- 判定：**PASS**（10/10 项）；`device_run=false`
- 矩阵现状：{'PASS': 14, 'FAIL': 8}

## 逐格一手证据

| 格式 | 路径 | 错误类型 | 官方 Extractor 数 | 自定义工厂命中 | prepare |
|---|---|---|---|---|---|
| aiff | local | UnrecognizedInputFormatException | 21 | 0 | Y |
| aiff | remote | UnrecognizedInputFormatException | 21 | 0 | Y |
| wma | local | UnrecognizedInputFormatException | 21 | 0 | Y |
| wma | remote | UnrecognizedInputFormatException | 21 | 0 | Y |
| ape | local | UnrecognizedInputFormatException | 21 | 0 | Y |
| ape | remote | UnrecognizedInputFormatException | 21 | 0 | Y |
| wv | local | UnrecognizedInputFormatException | 21 | 0 | Y |
| wv | remote | UnrecognizedInputFormatException | 21 | 0 | Y |

## 判定项

| # | 检查项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | F3 矩阵 FAIL 格数为 8 | PASS | 实读 8 格 |
| 2 | 8 格全部为 REMOTE/LOCAL 成对 | PASS | 4 格式 × 2 路径 |
| 3 | 8/8 一手日志含 UnrecognizedInputFormatException | PASS | 非崩溃 / 非解码器 / 非取流错误 |
| 4 | 8/8 可用 Extractor 清单仅官方默认 | PASS | 清单实扫，Ape/Asf 均不在内 |
| 5 | 自定义 ExtractorsFactory 日志 0 命中 | PASS | CM-Factory/CM-Ape/CM-Asf 全 0 → 跑批时自定义工厂未被调用 |
| 6 | 8/8 均记录 media3_error 判据 | PASS | 矩阵 errors.media3_error=True |
| 7 | 8/8 均已完成 prepare | PASS | 失败点在 prepare 之后的容器嗅探，不在注入通道 |
| 8 | ape/wma 已有自研 Extractor（待复测） | PASS | §61.94/§61.97 已入库，F3 跑批时点早于其提交 |
| 9 | aiff/wv 仍无自研 Extractor | PASS | 容器缺口 ③④，属待实现，非回归 |
| 10 | 结论不写成播放器/解码器缺陷 | PASS | UnrecognizedInputFormatException = 容器嗅探未识别，与解码器正交 |

## 归因结论

8/8 = UnrecognizedInputFormatException（容器嗅探未识别）；可用 Extractor 仅官方默认清单，自定义工厂 0 命中。F3 跑批时点 2026-11-12 07:21~07:24 早于 §61.94/§61.97 Extractor 提交（11-13 01:04/01:54）与 1485ce0f 的 mimeType 修正。ape/wma 已具备解析条件但真机复测 NOT RUN；aiff/wv 容器 Extractor 仍待实现。结论：容器解析缺口，不是解码器缺陷、不是取流缺陷。

## 诚实边界

- 本工具不重跑 F3、不连接设备、不改写 l3-f3-matrix.json 与 22 份 logcat。
- 真机复测 NOT RUN：端点 10.0.0.2:37077 adb 握手失败、5555 offline，需设备端确认无线调试授权或提供新配对端点 + 配对码。
- 不得把「归因明确」写成「8 格已修复 / 格式已支持」。
- 零生产代码改动、零新增依赖、不动解码器。

> AI生成
