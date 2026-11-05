package com.example.cloudmusic.scanner

/**
 * docs/03-04 §6.4 · 封面优先级：内嵌标签 → 同目录 cover/folder/front → 占位。
 * 图片压缩与 cacheDir 落盘属 Android 侧适配，本模块只裁决引用（B05：>5MB 回落）。
 */
interface CoverResolver {
    fun resolve(candidate: Candidate, embedded: ByteArray?): String?
}

class PriorityCoverResolver(
    private val directoryCover: (Candidate) -> String? = { null },
    private val placeholder: String? = null,
) : CoverResolver {
    override fun resolve(candidate: Candidate, embedded: ByteArray?): String? {
        if (embedded != null && embedded.isNotEmpty() && embedded.size <= MAX_EMBEDDED) {
            return "embedded:${candidate.obj.path}"
        }
        return directoryCover(candidate) ?: placeholder
    }

    companion object {
        const val MAX_EMBEDDED = 5 * 1024 * 1024
        val COVER_NAMES = listOf("cover.jpg", "cover.png", "folder.jpg", "front.jpg")

        /** 供适配层复用：大小写不敏感地按 §6.4 顺序挑第一个。 */
        fun pickDirectoryCover(names: Collection<String>): String? {
            val lowered = names.map { it.lowercase() }
            return COVER_NAMES.firstOrNull { it in lowered }
                ?.let { wanted -> names.firstOrNull { it.lowercase() == wanted } }
        }
    }
}
