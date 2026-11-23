# R1/R2 真机判读（2026-11-24，端点 192.168.1.11:37277）

**基线**：APK 用 `./gradlew :app:assembleDebug --offline` 重建；解 21 个 dex 拼串确认
`b2file:` / `MediaCacheKey` / `cacheAnchor` / `CacheWarmer-` / `cache warmer skipped` 全部在册
（非靠 APK 体积判断）；`adb install -r` 成功（lastUpdateTime=2026-11-24 00:51:27）。

**设备**：Redmi K60 / Android 13 / arm64；`input keyevent` 与 `input tap` 本窗可用
（旧「MIUI 禁 INJECT_EVENTS」口径本窗不成立）；全程未切用户网络；页面操作用输入注入完成。

## 判据与结论

| # | 判据 | 实测 | 判定 |
|---|---|---|---|
| 1 | cache key 换轨物理锚 | `prepare trackId=cd10ca2d...` → `cache key=b2file:cd10ca2d...` | ✅ |
| 2 | 分轨与整轨共享 key | 整轨 trackId=`cd10ca2d…`、分轨① `58995666…`、分轨② `9314aacd…`，三者 `cache key` **全为** `b2file:cd10ca2d…` | ✅ P0-1 核心 |
| 3 | key 与 trackId 解耦 | 日志同时打印 `cache key=b2file:… source=<trackId>`，两者不同 | ✅ |
| 4 | warmer 有效性 | 首次整轨 `cache warmer complete: b2file:cd10ca2d…`（00:57:06，约 32s） | ✅ |
| 5 | 二次播放跳过 warmer | 二次整轨（00:57:50）无 warmer 日志，BUFFERING→READY **0.083s**（3.7s→0.09s） | ✅ 首次命中 |
| 6 | 分轨连播跳过 | 分轨②（00:59:26）**未跳过**，warmer 于 01:00:05 再次 complete；READY 6.7s | ❌ 见下 |

## 发现（真机一手，需入账）

1. **P0-1 修复本身成立**：三个不同 trackId 映射到同一物理锚 key，旧实现此刻必然
   产生 3 个不相通 `b2track:` 条目；这是用户「大 CUE 仍卡」的结构性根因消除。
2. **分轨间 warmer 跳过未兑现**：`hasCompleteCache` 用 `ContentMetadata.getContentLength`
   + `isCached(key,0,length)`，首次 warmer 完成后写入的 exo_len 元数据实测为
   `0x122204ad = 304,219,309`，与文件实际音频数据长度 304,212,956B **一致**——
   2026-11-24 01:30 复核：**原判「contentLength 写错」是错的**，本机 APE 就是 304MB
   （APE 头 `audiodatalength` 实测 304,212,956）。warmer 之所以「跳过未兑现」，
   真因是 **SimpleCache 容量 256MiB < 文件 304MB**：预热写到一半就开始淘汰自己前面
   写下的 span，`hasCompleteCache` 永远判不完整；日志实证同一 key 两次
   `cache warmer complete` 后仍重跑，缓存长期贴顶（实测 218MB/256MiB）反复抖动。
   已在 §61.320 补容量闸门（`upstreamLen > cache.cacheSpace` 则放弃预热），
   真机日志：`cache warmer skipped: 304219309B > cache space 235440044B`。

## 边界

- 本窗只验证 R1 的 key 与 warmer；R2 队列批量化（规模性数字需 7838 首队列）未在设备侧量化。
- 缓存元数据写错问题**不在本批修**——R1 的 JVM 判据与 key 结构性修复已完成，
  该问题另立批次（建议：warmer 用 `DataSpec` 显式 length 或从 `ds.open()` 返回的
  已知长度写 ContentMetadata，而非依赖 chunked 响应推断），避免把新发现混进已收口批次。
- 证据：logcat 时间段 00:56:34 ~ 01:00:05，`CM-Media3` tag；截图/DB 副本为临时取证，已清理。
