package com.example.cloudmusic.model

/** docs/03-01 §3.1：数据源层模型（对齐 OpenList-zig model.zig）。 */

data class HashInfo(val hashName: String, val hashValue: String)

data class Header(val name: String, val value: String)

data class Obj(
    val name: String,
    val size: Long = 0,
    val isDir: Boolean = false,
    /** Unix 秒；毫秒制驱动须 /1000（B13）。 */
    val modified: Long = 0,
    val created: Long = 0,
    /** 不透明 token，link 时原样回传。 */
    val sign: String = "",
    val thumb: String = "",
    val hashInfo: HashInfo? = null,
    /** 驱动内相对根的路径（规范化后，见 03-01 §8）。 */
    val path: String = "",
    /** 驱动私有载荷（如 itvsh 的真实服务端路径）。 */
    val priv: String? = null,
) {
    val type: ObjectType get() = ObjectType.of(name, isDir)
}

data class File(
    val name: String,
    val size: Long = 0,
    val isDir: Boolean = false,
    val modified: Long = 0,
    val created: Long = 0,
    val sign: String = "",
    val thumb: String = "",
    val hashInfo: HashInfo? = null,
) {
    companion object {
        fun from(o: Obj) = File(
            o.name, o.size, o.isDir, o.modified, o.created, o.sign, o.thumb, o.hashInfo,
        )
    }
}
