package com.example.cloudmusic.datasource

import com.example.cloudmusic.model.Addition
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs
import com.example.cloudmusic.model.Obj
import com.example.cloudmusic.model.Storage
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * 数据源驱动契约（docs/03-01 §4）。
 * 核心不变量：新增一种挂载协议 = 新增一个 :datasource-xxx + 一条 DriverRegistry.register，
 * 不改 L2 / L4 / L5 任何一行代码（PRD §4 原则 2）。
 * 每个驱动只实现这 5 个方法；V0.1 对音源只读，写操作一律不实现（03-01 §13）。
 */
interface Driver {
    val config: DriverConfig

    /** 校验配置 / 登录 / 恢复会话。失败抛 CloudMusicError；已初始化时由 DriverLifecycle 先 drop。 */
    suspend fun init(ctx: DriverCtx)

    /** 释放连接、关闭客户端。必须容忍「init 失败后被调用」，且可重复调用（B02）。 */
    fun drop()

    /** 列出 dir 的直接子项（不递归，递归由 03-04 ScanCoordinator 负责）。 */
    suspend fun list(dir: Obj, refresh: Boolean = false): List<Obj>

    /** 按驱动内相对路径取单文件；不存在返回 null（不是抛异常，B04）。 */
    suspend fun get(path: String): Obj?

    /** ★统一交付点：把 Obj 变成可播放的 Link。 */
    suspend fun link(file: Obj, args: LinkArgs = LinkArgs()): Link
}

data class DriverConfig(
    val name: String,
    /** 一切走代理 */
    val onlyProxy: Boolean = false,
    /** 不返回直链 */
    val noLinkUrl: Boolean = false,
    /** 禁止缓存 list/link 结果（strm 直链会过期） */
    val noCache: Boolean = false,
    /** 由驱动本地排序（不依赖服务端顺序） */
    val localSort: Boolean = true,
    /**
     * 驱动级「投屏优先代理」默认（**默认 false**，直链优先决策）。
     * 投屏生效值 = 用户 forceWebdavProxy **或** 本值；为 true 时跳过 directUrl() 探测直接代理
     * （03-08 §3.2、10 §2 R6）。
     */
    val preferProxy: Boolean = false,
) {
    fun mustProxy(): Boolean = onlyProxy || noLinkUrl
}

/** 极简请求级可变引用（对齐 OpenList-zig Ctx.err_detail 的值语义；不跨请求复用）。 */
class MutableRef<T>(@Volatile var value: T? = null)

/**
 * 请求级上下文（对齐 OpenList-zig driver.Ctx）。
 * errDetail 是**请求级**对象，禁止跨请求复用（03-01 §10）。
 */
data class DriverCtx(
    /** 结构化日志前缀，如 "[webdav:nas]" */
    val tag: String = "",
    /** "scan" | "play" | "test" */
    val caller: String = "",
    val errDetail: MutableRef<String> = MutableRef(),
    val coroutineContext: CoroutineContext = Dispatchers.IO,
) {
    fun setErrDetail(msg: String) {
        if (msg.isNotEmpty()) errDetail.value = msg
    }
}

/** 注册表只存 factory；Addition.raw 由各驱动 parse() 自行解释（03-01 §4.1）。 */
typealias DriverFactory = (Storage, Addition) -> Driver
