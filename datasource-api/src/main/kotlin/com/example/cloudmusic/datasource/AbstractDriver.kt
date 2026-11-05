package com.example.cloudmusic.datasource

import com.example.cloudmusic.common.CloudMusicError
import com.example.cloudmusic.model.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * docs/03-02 §2：所有具体驱动的公共基类。
 * Addition.raw 是 JSON 原文字符串（03-01 §3.3），由驱动自己 parse，注册表不感知配置类型。
 */
abstract class AbstractDriver(
    protected val storage: Storage,
    additionJson: String,
) : Driver {

    protected val addition: JsonObject = runCatching {
        Json.parseToJsonElement(additionJson).jsonObject
    }.getOrElse {
        throw CloudMusicError.DsInvalidConfig("Addition 不是合法 JSON: ${it.message}")
    }

    @Volatile
    protected var initialized = false

    protected fun str(key: String, default: String = ""): String = addition.firstStr(key, default = default)

    protected fun bool(key: String, default: Boolean = false): Boolean =
        runCatching { addition[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: default

    protected fun int(key: String, default: Int = 0): Int =
        runCatching { addition[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: default

    /** Go 名优先、Zig 旧名兜底（03-02 §2）：address/url、root_folder_path/root_path。 */
    protected fun strCompat(goKey: String, zigKey: String, default: String = ""): String =
        addition.firstStr(goKey, zigKey, default = default)

    final override suspend fun init(ctx: DriverCtx) {
        try {
            doInit(ctx)
            initialized = true
        } catch (e: Exception) {
            initialized = false
            throw e.toCloudMusicError(ctx)
        }
    }

    protected abstract suspend fun doInit(ctx: DriverCtx)

    /** 幂等：必须容忍 init 失败后被调用（B02 / 03-02 T18）。 */
    final override fun drop() {
        runCatching { doDrop() }
        initialized = false
    }

    protected abstract fun doDrop()

    protected fun ensureReady() = check(initialized) { "Driver 未初始化: ${storage.id}" }

    /** 03-02 §7：底层异常转 CloudMusicError，上游人类可读消息写到 ctx.errDetail。 */
    private fun Exception.toCloudMusicError(ctx: DriverCtx): CloudMusicError = when (this) {
        is CloudMusicError -> this
        is IllegalArgumentException, is IllegalStateException ->
            CloudMusicError.DsInvalidConfig(message ?: "数据源配置无效")
        else -> {
            ctx.setErrDetail(message.orEmpty())
            CloudMusicError.DsUpstream("INIT", message ?: "数据源初始化失败")
        }
    }
}

/** 03-02 §2 的键名兼容工具，公开供各驱动与单测复用。 */
fun JsonObject.firstStr(vararg keys: String, default: String = ""): String =
    keys.firstNotNullOfOrNull { key ->
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() }
    } ?: default
