package com.example.cloudmusic.enginelegacy

import com.netease.cloudmusic.sdk.NMCommonCache.SourceDataRequest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * 12 §54（第四十五批 · `12 §50.7 #1` 阶段 A/B）· 取流句柄的生产承接。
 *
 * 继承竞品原包的 [SourceDataRequest]（**包名不可改，JNI 符号硬绑定**），把 native 回调
 * `Start(requestId, offset, length)` 接到纯 JVM 的 [RangeFetcher]。类型与契约均有实证：
 * 竞品 `DataSourcePlatformImpl$d` → `nmcache/l` → `SourceDataRequest`；
 * `libNMCommonCache.so` 的 `InitPtr` / 3 个 `OnNativeDataRequest*` 符号齐全，
 * `NMCommonCache` 在 `EngineNativeLoader` 加载链第 2 位，构造时 `InitPtr()` 可解析。
 *
 * **协议（smali 实证，见 `analysis/data-request-handler-evidence-2026-11-07.md §2.5`）**：
 * - `Start` 必须**立即返回 0**（异步启动），不能阻塞 native 回调线程；
 * - `j1` = 请求标识，`j2` = offset，`j3` = length（`end = j2 + j3`）；
 * - 回传槽的首参语义已于 **12 §59** 按 exp6 原始日志 + capstone 校准（见 [sink] 注释）：
 *   `OnDataRequestData` 首参是 kind 槽（==2 被当作完成标记、跳过内存写），数据包恒传 1。
 *
 * **诚实边界**：本类让 native 拿到取流对象并完成 HTTP Range 回传（阶段 A/B 判据），
 * 阶段 C 的数据通路在 kind 校准后待真机复验。**编译通过 ≠ 出声**，A3 仍 `DEEP RUN`。
 */
class UrlDataRequest(
    private val url: String,
    private val opener: RangeOpener = HttpUrlRangeOpener(),
    private val executor: Executor = DISPATCHER,
) : SourceDataRequest() {

    @Volatile
    private var cancelled = false

    /** 12 §56：只包一层日志，不改变 [opener] 的行为（异常仍由 `RangeFetcher.fetch` 转成 onEnd）。 */
    private val tracedOpener = RangeOpener { target, offset, length ->
        EngineDiag.i("open url=$target Range=${RangeFetcher.rangeHeader(offset, length)}")
        val response = opener.open(target, offset, length)
        EngineDiag.i("open result total=${response.totalLength} contentLength=${response.contentLength}")
        response
    }

    /**
     * 把纯逻辑的回传槽映射到 native 包装方法。
     *
     * **12 §59（第四十九批 · 真机 exp6 原始日志 + capstone 实证校准）**——三个回调的
     * 首个 long 在 native 侧是 `NMBufferSourceImp` 的 `int kind/status` 槽，不是 requestId：
     * - `OnDataRequestBegin(kind, totalLength)` → `OnNetSourceDataStart(int, long)`：
     *   第 1 参被 native 完全忽略，第 2 参 = 资源总长（写 `+0x80` 水位，仅 normal mode）。
     *   保留传 requestId（无害且利于日志对账）。
     * - `OnDataRequestData(kind, bytes, offset, count)` → `OnNetSourceData(int, …)`：
     *   `kind==2`（"末包/完成标记"）会**跳过 `NMMemoryCache::Write`**，只投递
     *   `OnWriteNetData` 异步任务；`kind!=2` 才真正写入内存缓存。
     * - `OnDataRequestEnd(kind, error)` → `OnNetSourceDataDone(int, String)`：
     *   `kind==2` → `PreloadDoneTaskOnWriteThread`（完成通知），否则 → `OnFlushNetData`
     *   任务；exp6 里两条路径都观测到读侧正常收尾。
     *
     * exp6 事故还原：native 第二轮 `Start id=2` 后我们原样回传 requestId=2 → kind=2 →
     * 内存写被跳过；而 Memory Mode（`cache_local=false`，构造清 `+0xb8`）下
     * `OnWriteNetData` 任务一律 `Failed Not Support`（GOT+lambda 异步调用，
     * `.text` 无直接 bl，见 `analysis/route1_disasm.py`）→ 数据无处可写，
     * 读侧 `Read((1-2) Finish Async:5056` 拿到空数据 → `Send Packet Invalid data error`
     * → 解码器 EOS。竞品同样把 requestId 传进 kind 槽，但它活在 normal mode
     * （`cache_local=true`），被拒的只有内存快路径、vfile 落盘由 OnWriteNetData 兜底。
     * 我们 Memory Mode 下没有兜底，故数据包 kind 恒传
     * [NATIVE_DATA_KIND_NORMAL]（exp6 实证：kind=1 的 5 次 push 全部落内存缓存，
     * 32768B 成功送达解码器）。
     */
    private val sink = object : RangeSink {
        override fun onBegin(requestId: Long, totalLength: Long) {
            EngineDiag.i("onBegin id=$requestId total=$totalLength")
            OnDataRequestBegin(requestId, totalLength)
        }

        override fun onData(requestId: Long, bytes: ByteArray, offset: Long, count: Long) {
            EngineDiag.i("onData id=$requestId offset=$offset count=$count")
            OnDataRequestData(NATIVE_DATA_KIND_NORMAL, bytes, offset, count)
        }

        override fun onEnd(requestId: Long, error: String?) {
            // §61.26（G01 真机 m4a 实证）：成功 EOF **延迟 2s** 回传，且分片检查
            // [cancelled]。原因：引擎读循环对「push→读 vfile→onEnd」是紧耦合重试——
            // vfile 落盘（OnWriteNetData 异步任务）需要 ~50ms，而立即 onEnd 会让
            // 「Start→push→onEnd→ReadFromFileNoLock 0→重试」以 ~5ms/轮烧完重试预算
            // （-103，moov-at-end 的 m4a 首当其冲）。E16 的 30s 延迟本质是同一掩盖，
            // 但 30s 在自动进曲下会形成释放后回调（use-after-free）——2s + 取消检查
            // 两全：vfile 有充足落盘窗口，release/Stop 后不再触碰 native。
            // 错误路径立即回传（G07 快速跳曲依赖）。
            if (error == null) {
                repeat(20) {
                    if (cancelled) return
                    try { Thread.sleep(100) } catch (_: InterruptedException) { return }
                }
            }
            if (cancelled) return
            EngineDiag.i("onEnd id=$requestId error=$error (delayed 2s or immediate)")
            OnDataRequestEnd(requestId, error ?: "")
        }
    }

    /** native 回调：立即返回 0，取流在后台线程执行。 */
    override fun Start(j1: Long, j2: Long, j3: Long): Long {
        EngineDiag.i("Start id=$j1 offset=$j2 length=$j3 url=$url")
        cancelled = false
        executor.execute {
            RangeFetcher.fetch(url, j1, j2, j3, tracedOpener, sink) { cancelled }
        }
        return 0L
    }

    override fun Stop() {
        EngineDiag.i("Stop")
        cancelled = true
    }

    /** 由 `UrlDataProvider.close()` 调用，释放 native 侧的 C++ 对象。 */
    fun release() {
        EngineDiag.i("release")
        cancelled = true
        deletePtr()
    }

    private companion object {
        /** 12 §59：`OnNetSourceData` 的 kind=普通数据包（==2 会被 native 当完成标记跳过内存写）。 */
        const val NATIVE_DATA_KIND_NORMAL = 1L

        val DISPATCHER: Executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "cloudmusic-range").apply { isDaemon = true }
        }
    }
}
