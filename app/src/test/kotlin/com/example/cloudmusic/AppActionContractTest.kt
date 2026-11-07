package com.example.cloudmusic

import com.example.cloudmusic.common.PermissionCatalog
import com.example.cloudmusic.common.PermissionGate
import com.example.cloudmusic.local.LocalAddition
import com.example.cloudmusic.local.LocalRoot
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 12 §52（第四十三批）· `12 §50.7 #3` 应用侧闭环的 JVM 回归用例。
 *
 * 覆盖两条链路的**纯判定**部分：权限结果分类 / 拒绝降级，以及本地数据源 Addition
 * JSON 的键名与转义（用 `:datasource-local` 的 `LocalAddition.parse` 反向校验，
 * 防止 `:app` 写出的配置驱动读不懂）。平台 API（ActivityResult / SAF / MediaStore）
 * 由 `tools/check_source_contracts.py` 的 §52 长效守护静态断言。
 */
class AppActionContractTest {

    // ---- 按动作申请权限闸门（03-11 §2 / §3）--------------------------------

    @Test
    @DisplayName("12 §52 · 按动作申请：启动零申请、MediaStore 按 API 级别二选一、SAF 免权限")
    fun requiredForActions() {
        assertTrue(PermissionGate.requestsForStartup().isEmpty(), "启动阶段不得申请任何权限")
        assertEquals(
            listOf(PermissionCatalog.READ_MEDIA_AUDIO),
            PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE, 33),
        )
        assertEquals(
            listOf(PermissionCatalog.READ_EXTERNAL_STORAGE),
            PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_MEDIASTORE, 32),
        )
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.ADD_LOCAL_SAF).isEmpty())
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.BROWSE_LOCAL_LIBRARY).isEmpty())
        assertTrue(PermissionGate.requiredFor(PermissionGate.UserAction.PLAY_LOCAL_TRACK).isEmpty())
        assertEquals(
            listOf(PermissionCatalog.POST_NOTIFICATIONS),
            PermissionGate.requiredFor(PermissionGate.UserAction.START_PLAYBACK, 33),
        )
    }

    @Test
    @DisplayName("12 §52 · classify：granted/denied/permanentlyDenied 三集合互斥")
    fun classifyPartitions() {
        val result = PermissionDecision.classify(
            grants = mapOf(
                PermissionCatalog.READ_MEDIA_AUDIO to true,
                PermissionCatalog.POST_NOTIFICATIONS to false,
                PermissionCatalog.BLUETOOTH_CONNECT to false,
            ),
            rationale = setOf(PermissionCatalog.POST_NOTIFICATIONS),
        )
        assertEquals(setOf(PermissionCatalog.READ_MEDIA_AUDIO), result.granted)
        assertEquals(setOf(PermissionCatalog.POST_NOTIFICATIONS), result.denied)
        assertEquals(setOf(PermissionCatalog.BLUETOOTH_CONNECT), result.permanentlyDenied)
        assertEquals(result.denied + result.permanentlyDenied, result.allDenied)
        assertTrue(PermissionDecision.classify(emptyMap()).allDenied.isEmpty())
    }

    @Test
    @DisplayName("12 §52 · 权限拒绝不阻断核心能力；MediaStore 被拒引导改 SAF")
    fun degradationNeverBlocksPlayback() {
        val allDenied = PermissionGate.GrantResult(
            denied = setOf(PermissionCatalog.POST_NOTIFICATIONS),
            permanentlyDenied = setOf(PermissionCatalog.READ_MEDIA_AUDIO),
        )
        assertTrue(PermissionDecision.coreAvailable(allDenied), "本地浏览/播放恒可用")
        assertFalse(PermissionDecision.mediaStoreImportAvailable(allDenied))
        val hints = PermissionDecision.hints(allDenied)
        assertTrue(hints.any { it.contains("SAF") }, "被拒须引导 SAF：$hints")
        assertTrue(hints.any { it.contains("不再询问") }, "永久拒绝须给去设置说明：$hints")
        assertTrue(PermissionDecision.coreAvailable(PermissionGate.GrantResult()), "无拒绝时当然可用")
    }

    @Test
    @DisplayName("12 §52 · 仅通知权限被拒时 MediaStore 通道仍可用、播放继续")
    fun notificationDeniedDoesNotBlock() {
        val notificationOnly = PermissionGate.GrantResult(
            denied = setOf(PermissionCatalog.POST_NOTIFICATIONS),
        )
        assertTrue(PermissionDecision.coreAvailable(notificationOnly))
        assertTrue(PermissionDecision.mediaStoreImportAvailable(notificationOnly))
        assertTrue(PermissionDecision.hints(notificationOnly).any { it.contains("通知") })
    }

    // ---- 本地数据源 Addition（03-02 §3.1）----------------------------------

    @Test
    @DisplayName("12 §52 · SAF Addition 可被 LocalAddition.parse 读回，且走 SAF 分支")
    fun safAdditionRoundTrip() {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val json = LocalSourceSpec.safAddition(treeUri)
        val addition = LocalAddition.parse(json)
        assertEquals(treeUri, addition.treeUri)
        assertEquals("", addition.rootFolderPath)
        assertTrue(LocalRoot.from(addition).useSaf)
        assertEquals("local", LocalSourceSpec.DRIVER_TYPE)
    }

    @Test
    @DisplayName("12 §52 · MediaStore Addition 的沙箱根与生产 VOLUME_ROOT 对齐")
    fun mediaStoreAdditionRoundTrip() {
        val json = LocalSourceSpec.mediaStoreAddition()
        val addition = LocalAddition.parse(json)
        assertEquals("/storage/emulated/0", addition.rootFolderPath)
        assertEquals(LocalSourceSpec.MEDIA_STORE_ROOT, addition.rootFolderPath)
        assertNull(addition.treeUri)
        assertFalse(LocalRoot.from(addition).useSaf)
        assertEquals("/storage/emulated/0", LocalRoot.from(addition).sandboxRoot())
    }

    @Test
    @DisplayName("12 §52 · JSON 转义：URI 含引号/反斜杠/控制字符时仍可原样读回")
    fun jsonEscaping() {
        val tricky = "content://tree/a\"b\\c\nd"
        val addition = LocalAddition.parse(LocalSourceSpec.safAddition(tricky))
        assertEquals(tricky, addition.treeUri, "转义后的 tree_uri 必须无损还原")
    }
}
