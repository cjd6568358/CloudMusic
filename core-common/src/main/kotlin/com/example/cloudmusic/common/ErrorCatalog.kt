package com.example.cloudmusic.common

/**
 * 错误码唯一真相源（docs/07-错误码总表.md）。
 *
 * 07 §8：「新增错误码必须先在本表登记再编码；命名遵循前缀分层；禁止跨层复用码值」。
 * 本表只登记码值与处置策略，各域的 [CloudMusicError] 子类仍由所属 module 扩展。
 */
object ErrorCatalog {

    enum class Domain { DS, LINK, SCAN, ENGINE, OUT, DB }

    data class Entry(
        val code: String,
        val domain: Domain,
        val handling: String,
        /** 07 §8：仅这三类属于致命错误（构建期阻断 / 阻止启动）。 */
        val fatal: Boolean = false,
        /** 是否需要优先取 DriverCtx.errDetail 的上游消息。 */
        val passthrough: Boolean = false,
    )

    private fun e(code: String, domain: Domain, handling: String, fatal: Boolean = false, passthrough: Boolean = false) =
        Entry(code, domain, handling, fatal, passthrough)

    /** 07 §2~§7 的全部具名码。 */
    val ALL: Map<String, Entry> = listOf(
        // ---- E_DS_* ----
        e("E_DS_UNKNOWN_DRIVER", Domain.DS, "抛错，UI 提示不支持的数据源类型"),
        e("E_DS_INVALID_CONFIG", Domain.DS, "标暂不可用，引导重新配置", passthrough = true),
        e("E_DS_AUTH_FAILED", Domain.DS, "展示 WWW-Authenticate 摘要", passthrough = true),
        e("E_DS_TIMEOUT", Domain.DS, "标记不可用，可重试", passthrough = true),
        e("E_DS_NOT_FOUND", Domain.DS, "get 返回 null；条目标不可用但不删除", passthrough = true),
        e("E_DS_NOT_SUPPORT", Domain.DS, "明确原因，可筛选查看", passthrough = true),
        e("E_DS_ENCODING", Domain.DS, "跳过该文件，不中断整轮扫描", passthrough = true),
        e("E_DS_UNREACHABLE", Domain.DS, "Track.availability=UNREACHABLE，恢复后置 OK", passthrough = true),
        e("E_DS_STORAGE_MISSING", Domain.DS, "标 UNREACHABLE，不崩溃"),
        // ---- E_LINK_* ----
        e("E_LINK_EXPIRED", Domain.LINK, "标 LINK_EXPIRED，自动跳下一首，不删条目"),
        e("E_LINK_BAD_SCHEME", Domain.LINK, "不执行、不联网，标 UNSUPPORTED_SCHEME"),
        // ---- E_SCAN_* ----
        e("E_SCAN_TAG_PARSE", Domain.SCAN, "降级文件名兜底，不中断扫描"),
        e("E_SCAN_PROBE_MISMATCH", Domain.SCAN, "按真实格式解析；仍失败则跳过并汇总"),
        e("E_SCAN_RANGE_FAILED", Domain.SCAN, "阶段 B 跳过，保留阶段 A 骨架"),
        e("E_SCAN_STORAGE_UNREACHABLE", Domain.SCAN, "该 Storage 标不可用，其他继续"),
        e("E_SCAN_CANCELLED", Domain.SCAN, "正常结束，返回已完成进度"),
        e("E_SCAN_ENCODING", Domain.SCAN, "回落 GBK；再失败跳过并计入失败汇总"),
        // ---- E_ENGINE_* ----
        e("E_ENGINE_DECODE", Domain.ENGINE, "提示格式/文件损坏，自动跳下一首"),
        e("E_ENGINE_ABI_MISSING", Domain.ENGINE, "构建期阻断，不进运行时", fatal = true),
        e("E_ENGINE_NATIVE_CRASH", Domain.ENGINE, "START_STICKY 重启服务 → 从游标恢复队列并提示"),
        // ---- E_OUT_* / DLNA ----
        e("E_OUT_SSDP_FAILED", Domain.OUT, "提示改用手动指定 DMR"),
        e("E_OUT_MANUAL_UNREACHABLE", Domain.OUT, "明确原因（超时/端口/非 DMR）"),
        e("E_OUT_DESC_PARSE", Domain.OUT, "提示设备不是 MediaRenderer"),
        e("E_OUT_DMR_LOST", Domain.OUT, "回落本机播放"),
        e("E_OUT_DIRECT_FAILED", Domain.OUT, "回落 nanohttpd 代理（最多 1 次/曲目）"),
        e("E_OUT_PROXY_RANGE", Domain.OUT, "回落整段 200，记录兼容日志"),
        e("E_OUT_PROXY_PORT", Domain.OUT, "换随机端口重试一次"),
        // ---- E_OUT_* / 媒体会话 ----
        e("E_OUT_FOCUS_REQUEST_FAILED", Domain.OUT, "记 failure_log，仍允许播放"),
        e("E_OUT_SESSION_INIT", Domain.OUT, "降级为仅前台服务 + 无锁屏控制"),
        e("E_OUT_METADATA", Domain.OUT, "回落占位图，其余元数据照常发布"),
        e("E_OUT_LEGACY_ABI", Domain.OUT, "阻断启动并提示", fatal = true),
        // ---- E_OUT_* / 蓝牙 ----
        e("E_OUT_BT_PERMISSION", Domain.OUT, "降级为只监听 AUDIO_BECOMING_NOISY"),
        e("E_OUT_BT_ADAPTER", Domain.OUT, "不注册观察者，UI 不显示蓝牙区"),
        e("E_OUT_BT_STATE", Domain.OUT, "记日志、忽略，不改输出状态"),
        // ---- E_DB_* ----
        e("E_DB_MIGRATION", Domain.DB, "阻止启动并提示；禁止 fallbackToDestructiveMigration()", fatal = true),
        e("E_DB_FULL", Domain.DB, "停止扫描，已入库数据保留"),
        e("E_DB_CONSTRAINT", Domain.DB, "追加路径长度后缀重试；仍失败记日志并跳过"),
    ).associateBy { it.code }

    /** 07 §2 / §6.1 的通配码：E_DS_UPSTREAM_*、E_OUT_SOAP_*。 */
    private val PREFIX_RULES = listOf(
        "E_DS_UPSTREAM_" to Domain.DS,
        "E_OUT_SOAP_" to Domain.OUT,
    )

    fun lookup(code: String): Entry? {
        ALL[code]?.let { return it }
        val domain = PREFIX_RULES.firstOrNull { code.startsWith(it.first) }?.second ?: return null
        return Entry(code, domain, "透出上游 errorDescription，不显示笼统失败", passthrough = true)
    }

    fun isRegistered(code: String): Boolean = lookup(code) != null

    fun isFatal(code: String): Boolean = lookup(code)?.fatal == true

    /** 供各 module 在构造 CloudMusicError 时自检，避免未登记码值流入运行时。 */
    fun requireRegistered(code: String) {
        require(isRegistered(code)) { "错误码 $code 未登记在 07-错误码总表.md，禁止使用" }
    }
}
