package com.example.cloudmusic.datasource

import com.example.cloudmusic.model.Obj

/**
 * 可选能力（docs/03-01 §5.1 / 03-02 §4.6）。
 * 由 03-08 CastUriResolver 用 `as?` 探测，判断 PROXIED 曲目能否把原始 URL 直推 DMR。
 * 未实现时回落到 nanohttpd 代理，**不改变 Link 三态与 Driver 的 5 个方法**。
 */
interface DirectLinkCapability {
    /** 返回可匿名访问的直链；null 表示不具备直连条件。含签名参数，禁止入日志（07 §8）。 */
    suspend fun directUrl(file: Obj): String?
}
