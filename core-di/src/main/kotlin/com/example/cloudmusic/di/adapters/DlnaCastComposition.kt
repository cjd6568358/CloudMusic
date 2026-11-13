package com.example.cloudmusic.di.adapters

import com.example.cloudmusic.output.dlna.CastUriResolver
import com.example.cloudmusic.output.dlna.DlnaCastCoordinator
import com.example.cloudmusic.output.dlna.DmrDevice
import com.example.cloudmusic.output.dlna.DmrDiscoverPort
import com.example.cloudmusic.output.dlna.DescriptionFetcher
import com.example.cloudmusic.output.dlna.DescriptionParser
import com.example.cloudmusic.output.dlna.DeviceSource
import com.example.cloudmusic.output.dlna.SoapTransport
import com.example.cloudmusic.output.dlna.SsdpScanner
import com.example.cloudmusic.output.dlna.CastProxyPort
import com.example.cloudmusic.output.router.OutputRouter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton

/**
 * §61.110：DLNA 第一个生产调用点的组合层。
 *
 * 复用既有件（全部已 @Provides）：[SsdpScanner]（Datagram 多播 + MulticastLock）、
 * [DescriptionFetcher]（OkHttp 拉 XML）、[DescriptionParser]（纯 JVM 解析）、
 * [CastUriResolver]（决策表）、[SoapTransport]（OkHttp SOAP）、[CastProxyPort]
 * （PrePublishedCastProxy）。本文件只补两件「接线」：
 * ① [DmrDiscoverPort] 的生产实现（扫描 → 拉 XML → 解析 → 去重）；
 * ② [DlnaCastCoordinator] 的 @Provides（hint 写 [OutputRouter.routeHint]）。
 *
 * 诚实边界：03-08 §10.1 的本机侧静音 / §10.2 断连回落 / §10.3 切歌保持 /
 * PositionPoller 属 DLNA 会话引擎（触碰真机播放态），**挂账不施工**；
 * 真机验证 NOT RUN（设备离线），判据只有 JVM 单测 + 编译级。
 */
@Module
@InstallIn(SingletonComponent::class)
object DlnaCastComposition {

    @Provides
    @Singleton
    fun provideDmrDiscoverPort(
        scanner: SsdpScanner,
        fetcher: DescriptionFetcher,
    ): DmrDiscoverPort = object : DmrDiscoverPort {

        /** SSDP 响应按 USN 去重后逐个拉描述；解析失败的单台跳过（不因一台脏数据失败整轮）。 */
        override suspend fun discover(timeoutMs: Int): List<DmrDevice> =
            withContext(Dispatchers.IO) {
                val responses = runCatching { scanner.discover(timeoutMs) }.getOrElse {
                    return@withContext emptyList()
                }
                val seen = HashSet<String>()
                val devices = ArrayList<DmrDevice>()
                for (resp in responses) {
                    if (!seen.add(resp.usn)) continue
                    val xml = runCatching { fetcher.get(resp.location) }.getOrNull() ?: continue
                    val device = runCatching {
                        DescriptionParser.parse(xml, resp.location, DeviceSource.SSDP)
                    }.getOrNull() ?: continue
                    devices.add(device)
                }
                devices
            }
    }

    @Provides
    @Singleton
    fun provideDlnaCastCoordinator(
        discoverPort: DmrDiscoverPort,
        resolver: CastUriResolver,
        soap: SoapTransport,
        proxy: CastProxyPort,
        output: OutputRouter,
    ): DlnaCastCoordinator = DlnaCastCoordinator(
        discoverPort = discoverPort,
        resolver = resolver,
        soap = soap,
        proxy = proxy,
        // 03-08 §14 #9：输出状态唯一写入方是 OutputRouter（协调器只提供文案）。
        onHint = output::updateRouteHint,
    )
}
