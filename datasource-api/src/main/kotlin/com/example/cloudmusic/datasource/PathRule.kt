package com.example.cloudmusic.datasource

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.common.PathNormalizer

/**
 * 路径与编码规范（docs/03-01 §8）。
 *
 * 规范化纯函数下沉至 :core-common PathNormalizer（实现期修正 C-01，理由见其 KDoc），
 * 本对象保留契约中的公开 API 与沙箱二次校验职责。
 */
object PathRule {
    /** 统一 `/` 分隔、去除 `.` / `..`、折叠重复斜杠、结尾不加斜杠；越界抛 DsNotSupport。 */
    fun normalizePath(path: String): String = PathNormalizer.normalize(path)

    /**
     * 二次校验：任何外部传入路径先规范化，再确认仍位于 root 内（参考 local.zig:7-13）。
     * 越界抛 [CloudMusicError.DsNotSupport]，不静默吞掉。
     */
    fun sandbox(root: String, path: String): String {
        val normalized = normalizePath(path)
        requirePathWithin(root, normalized)
        return normalized
    }

    fun requirePathWithin(root: String, normalizedPath: String) {
        if (!PathNormalizer.isWithinRoot(root, normalizedPath)) {
            throw CloudMusicError.DsNotSupport("路径越出数据源根目录: $normalizedPath")
        }
    }

    /** 返回 root 内的相对路径（去掉 root 前缀），供 TrackId 使用（不含 host / 凭据）。 */
    fun relativeTo(root: String, path: String): String {
        val normalized = sandbox(root, path)
        val r = normalizePath(root).trimEnd('/')
        if (normalized == r) return ""
        return normalized.removePrefix("$r/")
    }
}
