package com.example.cloudmusic

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.cloudmusic.common.PermissionGate

/**
 * 12 §52（第四十三批 · `12 §50.7 #3`）· **按动作申请权限**的 Android 侧闸门。
 *
 * `03-11 §2` 的 `PermissionGate` 是纯 JVM 时序表，此前全库无生产调用方
 * （真机 A3 实测三项运行时权限 `granted=false`）。本类把它接到
 * `ActivityResultContracts.RequestMultiplePermissions`，判定与降级交给
 * [PermissionDecision]，**不自己发明策略**。
 *
 * 不变量：
 * - **启动阶段零申请**：`PermissionGate.requestsForStartup()` 为空，本类只在
 *   [request] 被显式调用时才动；`MainActivity.onCreate` 不调用它。
 * - **按设备 SDK 而非 targetSdk 选权限**：API 32 及以下用 `READ_EXTERNAL_STORAGE`，
 *   33+ 用 `READ_MEDIA_AUDIO`（B09 / B10）。
 * - **SAF / 本地浏览 / 本地播放不申请**：`requiredFor` 返回空 → 直接回调、不弹窗。
 * - **拒绝不阻断核心能力**：调用方（播放 / 导入）按 `PermissionDecision` 的降级结果
 *   决定是否继续，通知权限被拒仍照常播放（B03）。
 * - `BLUETOOTH_CONNECT` / `SHOW_BLUETOOTH_NAME` **本批不接入**：蓝牙 ACL 广播与
 *   运行时授权流程仍需权限口径拍板（`12 §48.6` ③ / §46 挂账），不擅自扩大范围。
 *
 * 必须在 Activity 构造期（lifecycle INITIALIZED）实例化，
 * 因为 `registerForActivityResult` 要求在 `CREATED` 之前注册。
 */
class PermissionRequester(private val activity: ComponentActivity) {

    /** V0.1 单动作串行：同一时刻只保留一个待决回调。 */
    private var pending: ((PermissionGate.GrantResult) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            // 被拒且系统不再显示 rationale = 用户勾了「不再询问」（B01，不循环弹窗）。
            val rationale = grants.keys.filterTo(LinkedHashSet()) {
                activity.shouldShowRequestPermissionRationale(it)
            }
            val result = PermissionDecision.classify(grants, rationale)
            val callback = pending
            pending = null
            callback?.invoke(result)
        }

    /**
     * 按 [action] 申请所需权限并把分类结果交给 [onDecided]。
     * 无需权限 / 已全部授予时**同步**回调，不弹窗、不重复申请。
     */
    fun request(
        action: PermissionGate.UserAction,
        onDecided: (PermissionGate.GrantResult) -> Unit,
    ) {
        val required = PermissionGate.requiredFor(action, Build.VERSION.SDK_INT)
        if (required.isEmpty()) {
            onDecided(PermissionGate.GrantResult())
            return
        }
        val missing = required.filterNot {
            activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onDecided(PermissionGate.GrantResult(granted = required.toSet()))
            return
        }
        pending = onDecided
        launcher.launch(missing.toTypedArray())
    }
}
