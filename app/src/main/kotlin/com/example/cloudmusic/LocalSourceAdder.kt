package com.example.cloudmusic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.di.playback.PlaybackRuntime
import com.example.cloudmusic.storage.StorageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 12 §52（第四十三批 · `12 §50.7 #3`）· **添加本地数据源 → 全量重扫**的闭环。
 *
 * 真机 A3 的两处断点（`analysis/a3-device-acceptance-2026-11-07.md`）：
 * ① 数据源管理页只有展示、没有添加入口（`Room tracks=0`，只能靠 `run-as` 灌夹具）；
 * ② `READ_MEDIA_AUDIO` 从未按动作申请。本类把两者接到既有生产端口
 * （`SafDirectorySource` / `MediaStoreDirectorySource`）与 `StorageRepository.add`，
 * 写成功后立即 `PlaybackRuntime.rescanAll()`，**不再需要 Room 测试夹具**。
 *
 * 通道按 `03-11 §2.1` 的 SAF 优先：
 * - SAF：用户显式选目录 → 持久化读授权 → 无需任何读取权限；
 * - MediaStore：按动作申请 `READ_MEDIA_AUDIO`，被拒按 `§3` 降级矩阵引导改 SAF，
 *   不弹循环授权、不阻断应用。
 *
 * 本类**不注册** ActivityResult launcher（由 [MainActivity] 在构造期注册并传入 Uri），
 * 因此可以用 `by lazy` 在 Hilt 注入完成后再构造。
 */
class LocalSourceAdder(
    private val context: Context,
    private val storages: StorageRepository,
    private val runtime: PlaybackRuntime,
    private val permissions: PermissionRequester,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** SAF 目录返回后调用；`03-02 §3.4` 要求持久化授权，否则驱动 init 会判「授权已失效」。 */
    fun addSaf(uri: Uri) {
        val persisted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        if (!persisted) {
            toast("无法持久化目录授权，请重新选择目录")
            return
        }
        add(LocalSourceSpec.safAddition(uri.toString()), "已添加本地目录，开始全量重扫")
    }

    /** MediaStore 通道：先按动作申请权限，被拒则按降级矩阵给出 SAF 引导。 */
    fun addMediaStore() {
        permissions.request(PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE) { result ->
            if (!PermissionDecision.mediaStoreImportAvailable(result)) {
                toast(
                    PermissionDecision.hints(result).firstOrNull { "SAF" in it }
                        ?: "读取权限被拒，请改用「添加本地目录」(SAF)",
                )
                return@request
            }
            add(LocalSourceSpec.mediaStoreAddition(), "已添加媒体库数据源，开始全量重扫")
        }
    }

    /** 写 Storage 与触发重扫在同一协程内顺序执行，避免「有数据源但没扫」。 */
    private fun add(additionJson: String, okMessage: String) {
        scope.launch {
            val outcome = runCatching {
                storages.add(LocalSourceSpec.DRIVER_TYPE, additionJson)
                runtime.rescanAll()
            }
            toast(outcome.fold({ okMessage }, { "添加数据源失败：${it.message}" }))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
