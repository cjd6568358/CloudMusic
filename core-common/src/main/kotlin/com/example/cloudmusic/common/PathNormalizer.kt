package com.example.cloudmusic.common

/**
 * 路径规范化纯函数（docs/03-01 §8）。
 *
 * 实现期契约修正 C-01：03-01 §6 把 normalizePath() 约定在 :datasource-api 的 PathRule，
 * 但 §3.4 的 TrackId.of 位于 :core-model 且必须自己规范化（T10），
 * 若反向依赖 :datasource-api 会破坏 03-01 §2 的依赖方向。
 * 因此把「纯规范化」下沉到 :core-common，:datasource-api 的 PathRule.normalizePath()
 * 委托至此，保持单一真相源；沙箱二次校验仍留在 PathRule。
 */
object PathNormalizer {

    /**
     * 统一 `/` 分隔、折叠重复斜杠、消解 `.` 与 `..`、结尾不加斜杠。
     * 当 `..` 试图越出根（栈空仍要回退）时抛 [CloudMusicError.DsNotSupport]（T12）。
     */
    fun normalize(path: String): String {
        val input = path.replace('\\', '/')
        val absolute = input.startsWith('/')
        val segments = ArrayDeque<String>()
        for (raw in input.split('/')) {
            when {
                raw.isEmpty() || raw == "." -> Unit
                raw == ".." -> {
                    if (segments.isEmpty() || segments.last() == "..") {
                        throw CloudMusicError.DsNotSupport("路径越界: $path")
                    }
                    segments.removeLast()
                }
                else -> segments.addLast(raw)
            }
        }
        val joined = segments.joinToString("/")
        return if (absolute) "/$joined" else joined
    }

    /** 判断 path 是否仍位于 root 内（03-01 §8 的二次校验由 PathRule.sandbox 调用）。 */
    fun isWithinRoot(root: String, path: String): Boolean {
        val r = normalize(root).trimEnd('/')
        val p = normalize(path)
        return r.isEmpty() || p == r || p.startsWith("$r/")
    }
}
