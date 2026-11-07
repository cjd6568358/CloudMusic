package com.example.cloudmusic.model

/**
 * ★全架构枢纽：交付形态四态互斥（docs/03-01 §3.2，对齐 OpenList-zig model.zig:116）。
 *
 * - URL     : url 非空，内核 / DMR 可直连
 * - LOCAL   : localPath 非空，本机文件，内核 / SAF 直接读
 * - PROXIED : proxy=true，必须由 nanohttpd 本地代理转发（03-08）
 * - TEXT    : text 非空，内存虚拟文件内容（strm 虚拟目录）
 *
 * [kind] 是唯一分派依据：03-06 DataSourceAdapter 与 03-08 CastUriResolver 都只读它，
 * 不判断具体协议类型（新增协议不改 L2/L4/L5 一行代码）。
 */
data class Link(
    val url: String = "",
    val headers: List<Header> = emptyList(),
    val localPath: String? = null,
    val localSize: Long = 0,
    val proxy: Boolean = false,
    val text: String? = null,
    val contentLength: Long = 0,
    /** 需持有底层句柄（对齐 Go SyncClosers）。 */
    val requireReference: Boolean = false,
    /**
     * **C-07（2026-11-06，`12 §25`）**：PROXIED 态专用**回源 URL**，只供本地代理每请求回源使用。
     * 不参与 [kind] 判定、不推给 DMR、禁止写入日志（可能含签名参数；凭据仍在 [headers] 里）。
     * 默认 null、放在构造参数末尾，URL / LOCAL / TEXT 恒为 null，既有调用与 14 方法门面零改动。
     *
     * 背景：`WebDavDriver.link()` 的 PROXIED 分支原先只给鉴权 [headers]、不给 URL，
     * 导致 `DriverStreamFactory` 生产实现无法回源（纯 JVM 测试用 fake 未暴露）。
     */
    val proxySourceUrl: String? = null,
) {
    enum class Kind { URL, LOCAL, PROXIED, TEXT }

    val kind: Kind get() = when {
        !text.isNullOrEmpty() -> Kind.TEXT
        localPath != null -> Kind.LOCAL
        proxy || url.isEmpty() -> Kind.PROXIED
        else -> Kind.URL
    }

    init {
        require(!(url.isNotEmpty() && localPath != null)) { "url 与 localPath 互斥" }
        require(!(!text.isNullOrEmpty() && !proxy)) { "text 必须伴随 proxy=true" }
        require(localPath == null || localSize >= 0) { "localSize 不得为负" }
        require(!url.isNotEmpty() || url.startsWith("http://") || url.startsWith("https://")) {
            "V0.1 直链只接受 http/https（PRD §5.2）"
        }
        if (requireReference) {
            require(kind == Kind.PROXIED || kind == Kind.LOCAL) {
                "持有句柄的场景仅出现在 PROXIED/LOCAL"
            }
        }
    }
}

data class LinkArgs(
    val method: String = "GET",
    val headers: List<Header> = emptyList(),
    /** true：先发一次请求取 302 Location 再返回（WebDAV）。 */
    val redirect: Boolean = false,
)
