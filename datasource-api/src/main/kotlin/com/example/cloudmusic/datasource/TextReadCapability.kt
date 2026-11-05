package com.example.cloudmusic.datasource

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Obj

/**
 * 实现期契约回补 C-02（2026-11-05）。
 *
 * 03-03 的委托型驱动（StrmDriver / PlaylistDriver）需要读取 `.strm` / 播放列表文本，
 * 但 03-01 §4 的 Driver 只有 init/drop/list/get/link 五个方法，没有文本读取能力。
 * 按 §5.1 的「可选能力 + as? 探测」模式补一个能力接口，不改动 Driver 基本契约、
 * 不破坏 Link 三态；未实现的底层驱动按降级路径报错而非崩溃。
 * 扫描器做 03-04 §5 内联展开时也可直接复用。
 */
interface TextReadCapability {
    /** 读取小文本文件的全部内容；编码回落（UTF-8 → GBK）由实现负责。 */
    suspend fun readText(file: Obj): String
}

suspend fun Driver.readTextOrThrow(file: Obj): String =
    (this as? TextReadCapability)?.readText(file)
        ?: throw CloudMusicError.DsNotSupport("底层驱动不支持文本读取: ${file.path}")
