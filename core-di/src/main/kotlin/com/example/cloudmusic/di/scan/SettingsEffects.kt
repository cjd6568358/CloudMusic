package com.example.cloudmusic.di.scan

import com.example.cloudmusic.di.settings.SettingsAccess
import com.example.cloudmusic.di.settings.SettingsUpdate
import com.example.cloudmusic.di.settings.toModel
import com.example.cloudmusic.output.dlna.CachingHeadProbe
import com.example.cloudmusic.scanner.ScanCoordinator
import com.example.cloudmusic.scanner.ScanRequest
import com.example.cloudmusic.storage.Effect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C-14（`12 §31` / `08 §7`）· `SettingsUpdate.effects` 的**唯一执行者**。
 *
 * 第十八批（T-A2）只让 `SettingsStore.update()` **返回** effects，D-51 明确挂账
 * 「执行者属 T-A5 / T-A6」；第二十一批核对（`12 §30`）确认当时仍无消费方。
 * 这里把「改了什么 → 必须做什么」做成可单测的纯策略 + 生产执行者，由
 * `PlaybackRuntime` 订阅 `SettingsEffectsBus` 后调用 [apply]。
 */
enum class EffectAction {
    /** `REBUILD_DRIVER_AND_FULL_RESCAN`：重建 Driver 实例并发起全量重扫。 */
    FULL_RESCAN,

    /** 随全量重扫清 `CoverLookupCache`，避免残留旧封面路径。 */
    CLEAR_COVER_CACHE,

    /** `INVALIDATE_DIRECT_URL_CACHE`：清匿名直链探测缓存，下首重新决策。 */
    INVALIDATE_DIRECT_CACHE,

    /** `RESOLVE_NEXT_TRACK` / `NEXT_SCAN_ONLY` / `RECOMPOSE_IMMEDIATELY` / `SAVE_ONLY`。 */
    NOOP,
}

object EffectsPolicy {
    fun actionsFor(effects: Set<Effect>): Set<EffectAction> = buildSet {
        if (Effect.REBUILD_DRIVER_AND_FULL_RESCAN in effects) {
            add(EffectAction.FULL_RESCAN)
            add(EffectAction.CLEAR_COVER_CACHE)
        }
        if (Effect.INVALIDATE_DIRECT_URL_CACHE in effects) {
            add(EffectAction.INVALIDATE_DIRECT_CACHE)
        }
        // RESOLVE_NEXT_TRACK 由 C-09 的 settingsSource 在下一首 resolve() 时自动生效；
        // NEXT_SCAN_ONLY 明确不中断进行中扫描；RECOMPOSE_IMMEDIATELY 属 UI（T-A6）；
        // SAVE_ONLY 无运行时动作。这四类都不在此产生副作用。
        if (isEmpty()) add(EffectAction.NOOP)
    }
}

@Singleton
class SettingsEffectsRunner @Inject constructor(
    private val access: SettingsAccess,
    private val binder: StorageDriverBinder,
    private val coordinator: ScanCoordinator,
    private val covers: CoverLookupCache,
    private val directProbe: CachingHeadProbe,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 返回本次实际计划执行的动作；全量重扫在此等待完成，便于 Service 更新通知。 */
    suspend fun apply(update: SettingsUpdate): Set<EffectAction> {
        val actions = EffectsPolicy.actionsFor(update.effects)
        if (EffectAction.CLEAR_COVER_CACHE in actions) covers.clear()
        if (EffectAction.INVALIDATE_DIRECT_CACHE in actions) directProbe.invalidate()
        if (EffectAction.FULL_RESCAN in actions) {
            // C-10：Storage.modified 变化时 binder 先 remove 再 create + init + put；
            // 随后用 refresh=true 绕过快照比较，等价于 08 §7 的「全量重扫」。
            val active = access.snapshot().storages.map { it.toModel() }.filter { it.isWork }
            binder.sync(active)
            coordinator.run(ScanRequest(refresh = true))
        }
        return actions
    }

    /** 供诊断：当前执行者持有的作用域，避免调用方自建协程。 */
    fun scope(): CoroutineScope = scope
}
