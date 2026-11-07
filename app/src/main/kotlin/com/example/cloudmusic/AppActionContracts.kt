package com.example.cloudmusic

import com.example.cloudmusic.common.DegradationPolicy
import com.example.cloudmusic.common.PermissionGate

/**
 * 12 §52（第四十三批 · `12 §50.7 #3`）· 应用动作的**纯 JVM 判定层**。
 *
 * `03-11 §2 / §3` 的 `PermissionGate` / `DegradationPolicy` 早已落地，但此前 `:app`
 * 没有任何生产调用方 —— 真机 A3 报告（`analysis/a3-device-acceptance-2026-11-07.md`）
 * 实测三项运行时权限 `granted=false`、曲库 0 首。本对象把「平台权限结果 → 契约对象」
 * 与「拒绝后的降级提示」做成不依赖 Android 框架的纯函数，供 [PermissionRequester]
 * 调用并由 `:app` 的 JVM 单测覆盖；`Activity.requestPermissions` 仍属 Android 侧。
 *
 * 硬规则（`03-11 §3`）：**任何权限拒绝都不得阻断本地曲库浏览与本地播放**。
 */
object PermissionDecision {

    /**
     * 把 `ActivityResultContracts.RequestMultiplePermissions` 的 `Map<String, Boolean>`
     * 分类成 [PermissionGate.GrantResult] 的三个互斥集合。
     *
     * [rationale] 是本次申请中 `shouldShowRequestPermissionRationale == true` 的权限；
     * 被拒且**不在**其中即为「不再询问」（永久拒绝），只给去系统设置的说明，不循环弹窗（B01）。
     */
    fun classify(
        grants: Map<String, Boolean>,
        rationale: Set<String> = emptySet(),
    ): PermissionGate.GrantResult {
        val granted = grants.filterValues { it }.keys.toSet()
        val refused = grants.filterValues { !it }.keys.toSet()
        val permanent = refused - rationale
        val retryable = refused - permanent
        return PermissionGate.GrantResult(
            granted = granted,
            denied = retryable,
            permanentlyDenied = permanent,
        )
    }

    /** `03-11 §2` explainDenied + `§3` 降级矩阵提示，交给 UI 渲染（不抛异常、不阻断）。 */
    fun hints(result: PermissionGate.GrantResult): List<String> =
        PermissionGate.explainDenied(result) + DegradationPolicy.evaluate(result.allDenied).hints

    /** 核心能力是否可用：本地浏览 / 本地播放恒可用，权限拒绝不是致命错误（§8 #3）。 */
    fun coreAvailable(result: PermissionGate.GrantResult): Boolean =
        !DegradationPolicy.evaluate(result.allDenied).isCoreCapabilityBlocked

    /** MediaStore 通道是否可导入：读媒体权限被拒时引导改用 SAF（`03-11 §2.1`）。 */
    fun mediaStoreImportAvailable(result: PermissionGate.GrantResult): Boolean =
        DegradationPolicy.evaluate(result.allDenied).mediaStoreImportAvailable
}

/**
 * 12 §52 · 本地数据源的 Addition JSON 构造（纯 JVM，不含 Android 框架类型）。
 *
 * 键名逐字对齐 `:datasource-local` 的 `LocalAddition.from`（`03-02 §3.1`），
 * `driverType` 对齐 `DataSourceModule.KEY_LOCAL`；由 [LocalSourceAdder] 写进
 * `StorageRepository.add(type, additionJson)` 后触发 `PlaybackRuntime.rescanAll()`。
 *
 * 不引 kotlinx-serialization 到 `:app`：Addition 只有两个字符串字段，用最小 JSON
 * 转义即可； correctness 由 JVM 单测用 `LocalAddition.parse` 反向校验。
 */
object LocalSourceSpec {

    /** 与 `DataSourceModule.KEY_LOCAL` 一致（静态守护交叉校验）。 */
    const val DRIVER_TYPE = "local"

    /**
     * 与 `MediaStoreDirectorySource.VOLUME_ROOT` 一致：V0.1 的 MediaStore 生产实现
     * 把 `RELATIVE_PATH` 拼到该卷根下，故沙箱根必须与之对齐才能 group 出曲目。
     */
    const val MEDIA_STORE_ROOT = "/storage/emulated/0"

    /** SAF 优先路径（`03-11 §2.1`）：用户显式授权目录，**无需任何读取权限**。 */
    fun safAddition(treeUri: String): String = buildString {
        append("{\"tree_uri\":")
        append(quote(treeUri))
        append(",\"root_folder_path\":\"\",\"show_hidden\":true}")
    }

    /** MediaStore 兜底通道：需 `READ_MEDIA_AUDIO`，按动作申请后再调用。 */
    fun mediaStoreAddition(root: String = MEDIA_STORE_ROOT): String = buildString {
        append("{\"root_folder_path\":")
        append(quote(root))
        append(",\"show_hidden\":true}")
    }

    /** 最小 JSON 字符串转义：URI / 路径可能含引号、反斜杠与控制字符。 */
    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
