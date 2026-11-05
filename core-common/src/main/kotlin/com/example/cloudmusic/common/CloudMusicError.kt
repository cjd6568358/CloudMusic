package com.example.cloudmusic.common

/**
 * 全项目错误基类（docs/03-01 §6、docs/07-错误码总表.md §1）。
 *
 * 刻意不使用 sealed class：各 module 需要扩展自己的错误码子类
 * （E_SCAN_* → :scanner，E_ENGINE_* → :engine-*，E_OUT_* → :output-*，E_DB_* → :core-storage）。
 * 新增错误码必须先在 [ErrorCatalog] 登记，再编码（07 §8）。
 */
open class CloudMusicError(
    message: String,
    val code: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    // ---- E_DS_* 数据源（03-01 §6） --------------------------------------
    class DsUnknownDriver(t: String) : CloudMusicError("未知数据源类型: $t", "E_DS_UNKNOWN_DRIVER")
    class DsInvalidConfig(d: String) : CloudMusicError(d, "E_DS_INVALID_CONFIG")
    class DsAuthFailed(d: String) : CloudMusicError(d, "E_DS_AUTH_FAILED")
    class DsTimeout(d: String) : CloudMusicError(d, "E_DS_TIMEOUT")
    class DsNotFound(p: String) : CloudMusicError("文件不存在: $p", "E_DS_NOT_FOUND")
    class DsNotSupport(d: String) : CloudMusicError(d, "E_DS_NOT_SUPPORT")
    class DsEncoding(d: String) : CloudMusicError(d, "E_DS_ENCODING")
    class DsUnreachable(d: String) : CloudMusicError(d, "E_DS_UNREACHABLE")
    class DsStorageMissing(id: String) : CloudMusicError("数据源不存在: $id", "E_DS_STORAGE_MISSING")

    /** 其他上游错误：code 形如 E_DS_UPSTREAM_500，由 07 §2 的前缀规则收纳。 */
    class DsUpstream(code2: String, d: String) : CloudMusicError(d, "E_DS_UPSTREAM_$code2")

    // ---- E_LINK_* strm / 直链（03-01 §6） -------------------------------
    /** u 为带签名的完整 URL，按 07 §8 禁止写入日志，故不保存在消息里。 */
    class LinkExpired(u: String) : CloudMusicError("直链已失效", "E_LINK_EXPIRED")
    class LinkBadScheme(s: String) : CloudMusicError("不支持的 strm scheme: $s", "E_LINK_BAD_SCHEME")
}

/** 统一返回类型（03-01 §6）：异常规范为 [CloudMusicError]。 */
typealias CmResult<T> = Result<T>

/**
 * 上游消息透传（03-01 §6 / 07 §8）：优先使用 [errDetail]，
 * 禁止把 IOException 原样抛给 UI、禁止显示笼统「网络错误」。
 */
fun mapDriverError(error: Throwable, errDetail: String? = null): String {
    val upstream = errDetail?.takeIf { it.isNotBlank() }
    if (upstream != null) return upstream
    val message = error.message
    if (!message.isNullOrBlank() && message != "网络错误") return message
    val code = (error as? CloudMusicError)?.code
    return if (code != null) "操作失败（$code）" else "操作失败"
}
