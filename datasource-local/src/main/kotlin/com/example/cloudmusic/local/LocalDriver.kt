package com.example.cloudmusic.local

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.AbstractDriver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.PathRule
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage

/**
 * docs/03-02 §3 `LocalDriver`（第十九批 / T-A3）：本地音乐目录的只读驱动。
 *
 * 双通道（SAF 优先 / MediaStore 兜底）由 [LocalDirectorySource] 端口注入，
 * 本类只做：Addition 解析、init 校验、**路径沙箱二次校验**（§3.3）、过滤与本地排序、
 * 统一 `Obj` / `Link` 产出。不触碰 Android 框架，纯 JVM 可完整单测。
 *
 * `Obj.path` 是**相对 root** 的路径（不含本机绝对路径），保证 `TrackId` 稳定；
 * `Obj.priv` 携带 link 用的定位符（content:// 或绝对路径），不缓存 `Link` 对象（03-06 §16）。
 */
class LocalDriver(
    storage: Storage,
    additionJson: String,
    private val saf: LocalDirectorySource? = null,
    private val mediaStore: LocalDirectorySource? = null,
) : AbstractDriver(storage, additionJson) {

    override val config = DriverConfig(name = "Local", localSort = true)

    private val parsed = LocalAddition.from(addition)
    private val root: LocalRoot = LocalRoot.from(parsed)

    private val source: LocalDirectorySource?
        get() = if (root.useSaf) saf else mediaStore

    override suspend fun doInit(ctx: DriverCtx) {
        if (!root.useSaf && parsed.rootFolderPath.isBlank()) {
            throw CloudMusicError.DsInvalidConfig("未配置音乐目录")
        }
        if (root.useSaf && root.treeDocumentId == null) {
            throw CloudMusicError.DsInvalidConfig("SAF treeUri 无法解析出 documentId")
        }
        // 授权被系统回收 → 明确报错引导重新授权，不静默降级（§3.4）
        if (root.useSaf && saf != null && !saf.permissionValid(root)) {
            throw CloudMusicError.DsInvalidConfig("SAF 目录授权已失效，请重新选择目录")
        }
        ctx.setErrDetail("本地目录已就绪: ${parsed.rootFolderPath.ifBlank { parsed.treeUri.orEmpty() }}")
    }

    /** 幂等：端口本身无状态连接，drop 只清初始化标志（B02 / T18）。 */
    override fun doDrop() = Unit

    override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        ensureReady()
        val absolute = sandboxAbsolute(dir.path)
        val raw = source?.list(root, absolute).orEmpty()
        return raw.asSequence()
            .filter { LocalPathMapping.isValidEntryName(it.name) }
            .filter { parsed.followSymlinks || !it.symlink }
            .filter { parsed.showHidden || !it.name.startsWith(".") }
            .filter { !it.isDir || it.name !in parsed.ignoreDirs }
            .sortedBy { it.name.lowercase() }
            .map { it.toObj() }
            .toList()
    }

    override suspend fun get(path: String): Obj? {
        ensureReady()
        val absolute = sandboxAbsolute(path)
        return source?.find(root, absolute)?.toObj()
    }

    override suspend fun link(file: Obj, args: LinkArgs): Link {
        ensureReady()
        val absolute = sandboxAbsolute(file.path)
        val ref = file.priv?.takeIf { it.isNotBlank() } ?: absolute
        return Link(
            localPath = ref,
            localSize = file.size,
            contentLength = file.size,
        )
    }

    /**
     * §3.3：外部 path → 拼到 root → `PathRule.sandbox` 规范化并二次校验越界。
     * SAF 场景的 documentId 派生校验由适配层完成，这里是字符串级 belt-and-braces。
     */
    private fun sandboxAbsolute(path: String): String {
        val absolute = LocalPathMapping.absolutePath(root, path)
        return PathRule.sandbox(root.sandboxRoot(), absolute)
    }

    private fun LocalEntry.toObj(): Obj = Obj(
        name = name,
        size = size,
        isDir = isDir,
        modified = modified,
        path = LocalPathMapping.relativePath(root, path),
        priv = uri,
    )
}
