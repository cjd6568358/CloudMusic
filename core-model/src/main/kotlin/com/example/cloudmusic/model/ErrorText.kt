package com.example.cloudmusic.model

/**
 * §61.275（V0.4-02 离线可见性 / PRD §7.5 #2）：扫描 / 数据源错误的**人话**映射。
 *
 * 来源：`docs/07` 错误码表 + `StorageDto.errorMsg` 的写入格式 `"CODE: detail"`
 * （`core-di` 的 `storageErrorStates`）。**错误码不进用户文案**——用户只需要知道
 * 「哪一类问题、能不能自己解决、去哪儿解决」；码值留给日志（`CM-Scan`）。
 *
 * 纯 JVM、零依赖：UI 与测试共用同一份映射，避免各页各写一套。
 */
object ErrorText {

    /**
     * `"E_DS_UNREACHABLE: connect timeout"` → `"连接失败：网络或设备不可达（connect timeout）"`。
     * 裸码（无 detail）也能处理；未知码给通用文案并保留 detail，不丢诊断信息。
     */
    fun human(stored: String): String {
        val raw = stored.trim()
        if (raw.isEmpty()) return ""
        val code = raw.substringBefore(':').trim()
        val detail = raw.substringAfter(':', "").trim()
        val label = when (code) {
            "E_DS_AUTH_FAILED" -> "认证失败：请检查用户名与密码"
            "E_DS_UNREACHABLE" -> "连接失败：网络或设备不可达"
            "E_DS_STORAGE_MISSING" -> "来源已不存在（可能被移除），请重新添加"
            "E_DS_NOT_SUPPORT" -> "有文件无法识别（格式不支持或目录层级过深）"
            "E_DS_IO" -> "读取失败：文件或磁盘异常"
            // §61.323（C-2）：此前未登记也未映射，落 else 的「扫描失败」——
            // 与真实原因（源被移除 / 歌单导入失败）无关，用户无法据此处理。
            "E_SCAN_ORPHAN_SOURCE" -> "数据源已移除，其下曲目已标记为暂不可用"
            "E_SCAN_IMPORT" -> "歌单导入失败：请检查文件格式与编码"
            // 播放侧两码的 message 本身已是人话（lastError 直接取 .message 展示），
            // 这里补齐仅为「全部登记码都有映射」的一贯性，正常不会走到。
            "E_SKIP_GUARD" -> "连续多首播放失败，已暂停自动跳曲"
            "E_LINK_TIMEOUT" -> "音源解析超时，请检查音源是否可达"
            else -> "扫描失败"
        }
        return if (detail.isBlank()) label else "$label（$detail）"
    }
}
