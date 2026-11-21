package com.example.cloudmusic

/**
 * §61.211（G09 拍板 A 落地，`analysis/g09-preresearch-2026-11-18.md` §4）：MIUI
 * 自启动引导入口的纯 JVM 判定核。
 *
 * G09 预研结论（§61.32 / §61.161 一手）：前台 crash 恢复走 Activity 重建路径、
 * 后台恢复受 MIUI fg service restriction 限制——进程重建本身不在应用代码可控范围，
 * 应用侧唯一可行动点 = **检测 MIUI + 引导用户手动开启自启动白名单**（PRD §9
 * 不申请忽略电池优化，走引导口径）。Redmi / POCO 设备的
 * `ro.product.manufacturer` 同为 `Xiaomi`，单字符串判定即可覆盖。
 */
object MiuiAutostartGuide {
    /** MIUI 系设备（Xiaomi / Redmi / POCO）的 manufacturer 值。 */
    const val MIUI_MANUFACTURER = "Xiaomi"

    /**
     * 引导文案（设置页行下方说明，来源 = 预研报告 §4 草案）。
     *
     * §61.275（V0.4-02 文案人话化）：去「重新拉起 / 电池优化豁免 / 保活」三个系统术语，
     * 改为用户能照做的描述；「不申请省电白名单」的口径保留（PRD §9 硬边界不变）。
     */
    const val GUIDE_TEXT =
        "小米系统为省电，可能在你切到其它应用后停止播放。遇到这种情况，请打开系统" +
            "「设置 → 应用管理 → 云音乐 → 自启动」。本应用不会申请省电白名单，" +
            "是否允许后台运行由你决定。"

    /** 仅 MIUI 系设备显示引导行；判定失败按非 MIUI 处理（隐藏）。 */
    fun shouldShow(manufacturer: String?): Boolean =
        manufacturer?.trim()?.equals(MIUI_MANUFACTURER, ignoreCase = true) == true
}
