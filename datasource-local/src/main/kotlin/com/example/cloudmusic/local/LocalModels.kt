package com.example.cloudmusic.local

import com.example.cloudmusic.common.PathNormalizer
import com.example.cloudmusic.datasource.firstStr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * docs/03-02 §3 `LocalDriver` 的模型与端口（第十九批 / T-A3）。
 *
 * **纯 JVM**：SAF / MediaStore 的 Android 框架访问全部隔离在 [LocalDirectorySource] 后面，
 * 生产实现落在 `:core-di/local`（与第十六批端口适配同型）。这样新增本地音源不需要
 * Android SDK 就能编译 + 跑单测，`-Pcloudmusic.noAndroid` 逃生开关不受影响。
 */
enum class LocalChannel { SAF, MEDIA_STORE }

/** 本地数据源的根：`treeUri` 非空时优先 SAF，否则用 `rootPath` 走 MediaStore 兜底。 */
data class LocalRoot(
    val treeUri: String? = null,
    val rootPath: String = "",
    val treeDocumentId: String? = null,
) {
    val useSaf: Boolean get() = !treeUri.isNullOrBlank()

    /** 路径沙箱的根（03-02 §3.3 / 03-01 §8）；没有配置路径时退化为 `/`。 */
    fun sandboxRoot(): String = PathNormalizer.normalize(rootPath).ifEmpty { "/" }

    companion object {
        fun from(addition: LocalAddition): LocalRoot = LocalRoot(
            treeUri = addition.treeUri,
            rootPath = addition.rootFolderPath,
            treeDocumentId = addition.treeUri?.let(LocalPathMapping::treeDocumentId),
        )
    }
}

/** 两通道统一的目录项；`uri` 是 link 时用的定位符（content:// 或绝对文件路径）。 */
data class LocalEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    /** Unix 秒；毫秒制来源须 /1000（03-01 B13）。 */
    val modified: Long = 0,
    val uri: String? = null,
    val symlink: Boolean = false,
)

/**
 * 03-02 §3.2 的双通道端口。`list` / `find` 的 `absolutePath` 是**已通过沙箱校验**的绝对路径；
 * 适配层负责把它映射到 SAF documentId 或 MediaStore 的 RELATIVE_PATH，并把结果转回
 * [LocalEntry]，上层永远只看得到统一的 `Obj`（§3.2「不得让上层感知差异」）。
 */
interface LocalDirectorySource {
    val channel: LocalChannel

    suspend fun list(root: LocalRoot, absolutePath: String): List<LocalEntry>

    suspend fun find(root: LocalRoot, absolutePath: String): LocalEntry?

    /** SAF 授权被系统回收时返回 false，`LocalDriver.doInit` 抛 DsInvalidConfig，不静默降级。 */
    fun permissionValid(root: LocalRoot): Boolean = true
}

/** 03-02 §3.1 Addition schema；键名兼容 Go / Zig 两套（03-02 §2）。 */
data class LocalAddition(
    val rootFolderPath: String = "",
    val treeUri: String? = null,
    /** 默认 **true**（对齐 `local.zig:34`）。 */
    val showHidden: Boolean = true,
    val followSymlinks: Boolean = false,
    val ignoreDirs: List<String> = listOf("Android", ".thumbnails"),
    val minDurationMs: Long = 10_000L,
) {
    companion object {
        val DEFAULTS = LocalAddition()

        fun from(json: JsonObject): LocalAddition = LocalAddition(
            rootFolderPath = json.firstStr("root_folder_path", "root_path"),
            treeUri = json.firstStr("tree_uri").takeIf { it.isNotEmpty() },
            showHidden = json["show_hidden"]?.jsonPrimitive?.booleanOrNull ?: true,
            followSymlinks = json["follow_symlinks"]?.jsonPrimitive?.booleanOrNull ?: false,
            ignoreDirs = runCatching {
                json["ignore_dirs"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.content.takeIf(String::isNotBlank)
                }
            }.getOrNull() ?: DEFAULTS.ignoreDirs,
            minDurationMs = json["min_duration_ms"]?.jsonPrimitive?.longOrNull ?: 10_000L,
        )

        fun parse(raw: String): LocalAddition = runCatching {
            from(kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject)
        }.getOrDefault(DEFAULTS)
    }
}

/**
 * SAF documentId ↔ 驱动内路径的纯函数（03-02 §3.3 的「documentId 必须由 tree 派生」）。
 *
 * externalstorage provider 的两种形态都兼容：
 * - tree = `primary:Music` → 子项 `primary:Music/Jazz/a.mp3`
 * - tree = `primary:`（卷根）→ 子项 `primary:Music/Jazz/a.mp3`
 *
 * 任何不属于该 tree 的 documentId 返回 null，由适配层拒绝（第二道防线）。
 */
object LocalPathMapping {

    /** 从 `content://.../tree/<encoded docId>/document/...` 解出 treeDocumentId。 */
    fun treeDocumentId(treeUri: String): String? {
        val marker = "/tree/"
        val start = treeUri.indexOf(marker)
        if (start < 0) return null
        val encoded = treeUri.substring(start + marker.length).substringBefore('/')
        return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 绝对路径 → 相对 root 的路径（以 `/` 开头），供 `Obj.path` / TrackId 使用（不含 root 前缀）。 */
    fun relativePath(root: LocalRoot, absolutePath: String): String {
        val r = PathNormalizer.normalize(root.sandboxRoot()).trimEnd('/')
        val a = PathNormalizer.normalize(absolutePath)
        if (a == r) return "/"
        val rest = a.removePrefix("$r/").trimStart('/')
        return if (rest.isEmpty()) "/" else "/$rest"
    }

    /** 相对路径 → 沙箱内的绝对路径；根路径 `""` / `"/"` 都解析为 root 本身。 */
    fun absolutePath(root: LocalRoot, relativePath: String): String {
        val r = PathNormalizer.normalize(root.sandboxRoot()).trimEnd('/')
        val rel = PathNormalizer.normalize(relativePath).trimStart('/')
        return if (rel.isEmpty()) r else "$r/$rel"
    }

    private fun prefixOf(treeDocId: String): String = when {
        treeDocId.endsWith(":") || treeDocId.endsWith("/") -> treeDocId
        else -> "$treeDocId/"
    }

    fun safDocumentId(treeDocId: String, relativePath: String): String {
        val rel = relativePath.trim()
        if (rel.isBlank() || rel == "/") return treeDocId
        return prefixOf(treeDocId) + rel.trimStart('/')
    }

    /** 拒绝越界 documentId；返回相对路径（以 `/` 开头）或 null。 */
    fun safRelativeFromDocumentId(treeDocId: String, docId: String): String? {
        if (docId == treeDocId) return "/"
        val prefix = prefixOf(treeDocId)
        if (!docId.startsWith(prefix)) return null
        val rest = docId.removePrefix(prefix).trimStart('/')
        return if (rest.isEmpty()) "/" else "/$rest"
    }

    /** 03-02 §3.4：文件名含 `/` 时 MediaStore 通道过滤掉；扩展名不做限制（由 03-04 决定）。 */
    fun isValidEntryName(name: String): Boolean =
        name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != ".."
}

/**
 * MediaStore 是**平表**（只有 RELATIVE_PATH），没有目录行；这里按前缀聚出直接子目录，
 * 使它与 SAF 通道产出同构的 [LocalEntry] 列表（03-02 §3.2 双通道统一）。
 */
object LocalEntryGrouper {
    fun group(absolutePath: String, rows: List<LocalEntry>): List<LocalEntry> {
        val prefix = PathNormalizer.normalize(absolutePath).trimEnd('/') + "/"
        val dirs = LinkedHashMap<String, LocalEntry>()
        val files = mutableListOf<LocalEntry>()
        rows.forEach { entry ->
            val path = PathNormalizer.normalize(entry.path)
            if (!path.startsWith(prefix)) return@forEach
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val name = rest.substring(0, slash)
                dirs.putIfAbsent(name, LocalEntry(name = name, path = prefix + name, isDir = true))
            } else {
                files += entry
            }
        }
        return dirs.values.toList() + files
    }
}
