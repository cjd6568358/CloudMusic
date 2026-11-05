package com.example.cloudmusic.bridge

import com.example.cloudmusic.model.IDataSource
import com.example.cloudmusic.model.Link
import com.example.cloudmusic.model.LinkArgs

/**
 * docs/03-06 §7 **唯一入口**：只按 [Link.kind] 分派，不判断具体协议类型
 * （03-01 §5：新增协议不改 L2/L4/L5 一行代码）。
 *
 * 产出的 [IDataSource] 消费方是「本地代理每请求回源」与「扫描期 Range 读」，
 * **不在引擎主取流路径**（`13 §3` / `16 §3.2`）：URL / LOCAL 由引擎经
 * `EngineSource`（C-05）自取，PROXIED 由组合层 `proxy.publish` 转回环 URL。
 *
 * 端口全部带默认值：纯 JVM 分派（T01~T03）无需任何外部设施；
 * 实际取流时由 `:core-di` 注入 OkHttp / `ContentResolver` / 驱动实现。
 */
object DataSourceAdapter {

    fun from(
        link: Link,
        args: LinkArgs = LinkArgs(),
        http: RangeHttpStack = unavailableHttpStack(),
        fdOpener: FdOpener = JvmFileFdOpener(),
        driverStreams: DriverStreamFactory = unavailableDriverStreams(),
    ): IDataSource = when (link.kind) {
        Link.Kind.URL -> HttpDataSource(link, args, http)
        Link.Kind.LOCAL -> FileDataSource(link, fdOpener)
        Link.Kind.PROXIED -> DriverDataSource(link, driverStreams)
        Link.Kind.TEXT -> MemoryDataSource(link)
    }
}
