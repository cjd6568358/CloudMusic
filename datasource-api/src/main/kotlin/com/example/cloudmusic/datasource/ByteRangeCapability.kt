package com.example.cloudmusic.datasource

import com.example.cloudmusic.model.Obj

/**
 * 可选能力（docs/03-01 §5.1 / 03-04 §3）。
 * 扫描时只读文件头部做标签解析，避免整文件下载；未实现时阶段 B 跳过元数据补齐，
 * 保留阶段 A 的文件名骨架（E_SCAN_RANGE_FAILED）。
 */
interface ByteRangeCapability {
    /** 返回 [offset, offset+length) 的字节；失败返回 null，由扫描器降级处理。 */
    suspend fun readBytes(file: Obj, offset: Long, length: Int): ByteArray?
}
