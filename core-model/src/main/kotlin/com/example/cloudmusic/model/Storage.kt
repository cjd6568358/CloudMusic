package com.example.cloudmusic.model

/** docs/03-01 §3.3：持久化模型。 */

/**
 * 驱动私有配置，保持 JSON 原文字符串（不做多态反序列化）：
 * 对齐 OpenList Storage.addition 语义，避免驱动配置变更时迁移整张表；由各驱动自己 parse()。
 * 决策 ④：凭据明文存 DataStore，不加密（个人自用作品）。
 */
data class Addition(val raw: String = "{}")

data class Storage(
    /** 稳定 UUID，新增时生成，永不变；TrackId 只依赖它，不依赖 host / 凭据。 */
    val id: String,
    /** 虚拟挂载点，如 "/nas"。不参与 ID 计算（03-01 §7）。 */
    val mountPath: String,
    /** 注册表 key。 */
    val driverType: String,
    val addition: Addition,
    val remark: String = "",
    val disabled: Boolean = false,
    /** init 失败时记录，UI 显示「暂不可用」。 */
    val errorMsg: String = "",
    val modified: Long = 0,
) {
    val isWork: Boolean get() = !disabled && errorMsg.isEmpty()
}
