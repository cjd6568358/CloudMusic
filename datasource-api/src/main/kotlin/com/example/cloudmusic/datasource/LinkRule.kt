package com.example.cloudmusic.datasource

import com.example.cloudmusic.model.Link

/**
 * Link 合法字段组合（docs/03-01 §3.2 的对照表）与安全日志描述。
 *
 * Link.init 已强制三态互斥与 http/https scheme；本对象提供可读的分类结果，
 * 供 03-06 DataSourceAdapter / 03-08 CastUriResolver 在日志与断言里复用，
 * 避免各处自行判断字段组合造成分派口径漂移。
 */
object LinkRule {

    enum class Scenario {
        /** URL 直连：strm 直链 / 播放列表 URL 条目 / WebDAV 302 后直链。
         *  三者在 Link 层不可区分也不必区分 —— kind 是唯一分派依据。 */
        URL_DIRECT,
        /** 本地音乐 */
        LOCAL,
        /** WebDAV 鉴权流 / SFTP / FTP / NFS（url 可为空或原 URL） */
        PROXIED_STREAM,
        /** strm 虚拟目录文件内容 */
        VIRTUAL_TEXT,
    }

    fun classify(link: Link): Scenario = when (link.kind) {
        Link.Kind.TEXT -> Scenario.VIRTUAL_TEXT
        Link.Kind.LOCAL -> Scenario.LOCAL
        Link.Kind.URL -> Scenario.URL_DIRECT
        Link.Kind.PROXIED -> Scenario.PROXIED_STREAM
    }

    /**
     * 结构化日志描述。
     * 07 §8：账号密码、私钥、带签名的完整 URL、蓝牙设备地址一律不入日志，
     * 因此这里**只输出 kind 与 headers 数量**，绝不输出 url / header 值 / text。
     */
    fun safeDescription(link: Link): String =
        "[link] kind=${link.kind} scenario=${classify(link)} headers=${link.headers.size} " +
            "reference=${link.requireReference} length=${link.contentLength}"
}
