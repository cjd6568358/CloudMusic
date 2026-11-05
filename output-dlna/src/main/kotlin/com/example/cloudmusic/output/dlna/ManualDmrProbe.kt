package com.example.cloudmusic.output.dlna

/** §4.2：GET 描述 URL 的端口；返回 200 的 body，其余返回 null。 */
fun interface DescriptionFetcher {
    suspend fun get(url: String): String?
}

/** docs/03-08 §4.2 主动指定 DMR（A7）。候选路径依次探测，失败给出明确原因、不静默。 */
object ManualDmrProbe {

    /** `descriptionUrl` 非空时优先且仅用它；否则按 §4.2 的候选顺序。 */
    fun candidateUrls(spec: ManualDmrSpec): List<String> {
        spec.descriptionUrl?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val port = spec.port ?: 8200
        return buildList {
            add("http://${spec.host}:$port/description.xml")
            add("http://${spec.host}:$port/devicedesc.xml")
            add("http://${spec.host}:$port/rootDesc.xml")
            add("http://${spec.host}:8200/rootDesc.xml")
            add("http://${spec.host}:49152/description.xml")
        }.distinct()
    }

    suspend fun probe(
        spec: ManualDmrSpec,
        fetcher: DescriptionFetcher,
        now: Long = System.currentTimeMillis(),
    ): DmrDevice {
        val tried = mutableListOf<String>()
        for (url in candidateUrls(spec)) {
            tried.add(url)
            val body = fetcher.get(url) ?: continue
            if (!body.contains("<device", ignoreCase = true)) continue
            return DescriptionParser.parse(body, url, DeviceSource.MANUAL, now)
        }
        val reason = if (tried.size == 1 && spec.descriptionUrl != null) {
            "手动指定描述地址不可达或非 MediaRenderer：${tried.first()}"
        } else {
            "候选描述路径均不可达（${tried.size} 个），请检查 IP / 端口 / 设备是否为 MediaRenderer"
        }
        throw DlnaManualUnreachable(reason)
    }
}
