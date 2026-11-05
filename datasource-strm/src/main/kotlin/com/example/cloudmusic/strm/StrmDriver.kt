package com.example.cloudmusic.strm

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.datasource.AbstractDriver
import com.example.cloudmusic.datasource.Driver
import com.example.cloudmusic.datasource.DriverConfig
import com.example.cloudmusic.datasource.DriverCtx
import com.example.cloudmusic.datasource.DriverRegistry
import com.example.cloudmusic.datasource.PathRule
import com.example.cloudmusic.datasource.readTextOrThrow
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import java.util.concurrent.ConcurrentHashMap

/**
 * docs/03-03 §2.1 · 委托型驱动：不自己做文件 IO / 网络，一律委托底层 Driver。
 * V0.1 只读；解析结果不缓存（noCache=true，直链会过期）。
 */
class StrmDriver(
    storage: Storage,
    addition: String,
    private val registry: DriverRegistry,
) : AbstractDriver(storage, addition) {

    override val config = DriverConfig(name = "Strm", localSort = true, noCache = true)

    private var delegate: Driver? = null
    private var paths = StrmPaths(emptyMap(), false)
    private val seenUrls = ConcurrentHashMap.newKeySet<String>()

    override suspend fun doInit(ctx: DriverCtx) {
        val id = str("delegate_storage_id")
        if (id.isEmpty()) throw CloudMusicError.DsInvalidConfig("strm 未配置 delegate_storage_id")
        delegate = registry.get(id)
            ?: throw CloudMusicError.DsInvalidConfig("strm 引用的底层数据源不存在: $id")
        paths = parsePaths(str("paths"))
        ctx.setErrDetail("strm 挂载已就绪: ${paths.groups.size} 个来源")
    }

    override fun doDrop() {
        delegate = null
        seenUrls.clear()
    }

    override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        val d = requireDelegate()
        val roots = if (paths.autoFlatten && isRoot(dir.path)) {
            paths.allDestinations.map {
                Obj(name = it.substringAfterLast('/'), isDir = true, path = it)
            }
        } else {
            listOf(dir)
        }
        return roots.flatMap { root -> d.list(root, refresh).flatMap { expand(it, d) } }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun get(path: String): Obj? {
        val d = requireDelegate()
        val obj = d.get(PathRule.normalizePath(path)) ?: return null
        return expand(obj, d).firstOrNull()
    }

    override suspend fun link(file: Obj, args: LinkArgs): Link {
        val d = requireDelegate()
        val text = d.readTextOrThrow(file)
        return when (val result = parseStrm(text)) {
            is StrmParseResult.Url -> Link(url = result.url, contentLength = -1L)
            is StrmParseResult.Unsupported -> throw CloudMusicError.LinkBadScheme(result.scheme)
            StrmParseResult.Empty -> throw CloudMusicError.DsNotSupport("strm 内容为空: ${file.path}")
        }
    }

    /** 解析结果通过 Obj.priv 交给 03-04：Unsupported / Empty 标状态但不删条目。 */
    private suspend fun expand(obj: Obj, d: Driver): List<Obj> {
        if (obj.isDir || !obj.name.endsWith(".strm", ignoreCase = true)) return listOf(obj)
        val text = d.readTextOrThrow(obj)
        return when (val result = parseStrm(text)) {
            is StrmParseResult.Url ->
                // B06：同一 URL 在同一 StrmDriver 内去重，保留首次出现的路径
                if (seenUrls.add(result.url)) listOf(obj.copy(priv = "strm:url")) else emptyList()
            is StrmParseResult.Unsupported ->
                listOf(obj.copy(priv = "strm:unsupported:${result.scheme}"))
            StrmParseResult.Empty -> listOf(obj.copy(priv = "strm:empty"))
        }
    }

    private fun requireDelegate(): Driver {
        ensureReady()
        return delegate ?: error("strm 委托驱动丢失: ${storage.id}")
    }

    private fun isRoot(path: String): Boolean =
        path.isBlank() || path == "/" || path == "."
}
