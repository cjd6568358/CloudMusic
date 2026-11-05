package com.example.cloudmusic.playlist

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

/** docs/03-03 §3.1 / §3.6 · 委托型驱动，读文本与取流都走底层 Driver。 */
class PlaylistDriver(
    storage: Storage,
    addition: String,
    private val registry: DriverRegistry,
) : AbstractDriver(storage, addition), PlaylistSource {

    override val config = DriverConfig(name = "Playlist", localSort = true, noCache = true)

    private var delegate: Driver? = null
    private var rootPath = ""

    override suspend fun doInit(ctx: DriverCtx) {
        val id = str("delegate_storage_id")
        if (id.isEmpty()) throw CloudMusicError.DsInvalidConfig("playlist 未配置 delegate_storage_id")
        delegate = registry.get(id)
            ?: throw CloudMusicError.DsInvalidConfig("playlist 引用的底层数据源不存在: $id")
        rootPath = strCompat("root_folder_path", "root_path")
        ctx.setErrDetail("playlist 挂载已就绪")
    }

    override fun doDrop() {
        delegate = null
    }

    override suspend fun readText(path: String): String? {
        val d = delegate ?: return null
        return runCatching {
            d.readTextOrThrow(Obj(name = path.substringAfterLast('/'), path = path))
        }.getOrNull()
    }

    override suspend fun find(path: String): Obj? = delegate?.get(path)

    override suspend fun list(dir: Obj, refresh: Boolean): List<Obj> {
        ensureReady()
        val d = delegate ?: error("playlist 委托驱动丢失: ${storage.id}")
        return d.list(dir, refresh).flatMap { obj ->
            if (obj.isDir || !isPlaylist(obj.name)) listOf(obj)
            else expandPlaylist(obj, this, rootPath)
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun get(path: String): Obj? {
        ensureReady()
        val obj = delegate?.get(PathRule.normalizePath(path)) ?: return null
        return if (isPlaylist(obj.name)) expandPlaylist(obj, this, rootPath).firstOrNull() else obj
    }

    /** §3.6：URL 条目直推、Unsupported 明确报错、普通条目委托底层（本地→LOCAL / WebDAV→PROXIED）。 */
    override suspend fun link(file: Obj, args: LinkArgs): Link {
        ensureReady()
        file.playlistDirectUrl()?.let { return Link(url = it, contentLength = -1L) }
        file.playlistRejection()?.let { throw CloudMusicError.DsNotSupport(it) }
        val d = delegate ?: error("playlist 委托驱动丢失: ${storage.id}")
        return d.link(file, args)
    }

    companion object {
        fun isPlaylist(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in setOf("m3u", "m3u8", "pls")
    }
}
