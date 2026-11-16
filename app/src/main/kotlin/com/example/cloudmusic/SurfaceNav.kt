package com.example.cloudmusic

/**
 * §61.182 三层守护网之 **③ 画面证据自动化**（§61.183 落地）：验收期 **debug 导航通道** ——
 * 用 intent 直达 UI surface，替代被 MIUI 禁掉的 `input tap`（INJECT_EVENTS）。
 *
 * 背景：三层网的前两层（写侧审计 / 显示值契约）已落地，但「用户看见什么」的最后一环
 * 仍只能靠人眼 —— MIUI 拒绝注入点击，验收 runbook 无法自动走到播放页 / 弹层截图。
 * 本通道让 `tools/b2_surface_sweep.py` 以纯 `am start` 免注入导航，全 surface 截屏
 * 取证（判读仍由会话内 agent 读图或用户，脚本只做确定性取证，不伪造判读）。
 *
 * 收口口径（与 `12 §61.33` 的 PlaybackService exported 同族）：本通道属**验收期调试面**，
 * 经显式组件 intent（`am start -n …MainActivity -a <ACTION> --activity-single-top
 * --activity-clear-top`）触发，不新增 Manifest intent-filter / 权限 / 依赖；
 * 生产收口时随 PlaybackService exported 一并评估移除（见 03-12 §8 发布阻断清单）。
 *
 * 纯 JVM 判定（同 `MediaKeyAction` 抽离先例）：`parse` 不触 Android 类，单测直测。
 */
object SurfaceNav {

    /** 打开播放页（等价于点击 MiniPlayer）。 */
    const val ACTION_OPEN_PLAYER = "com.example.cloudmusic.action.OPEN_PLAYER"

    /** 回到首页（关闭播放页覆盖层；等价于播放页返回）。 */
    const val ACTION_CLOSE_PLAYER = "com.example.cloudmusic.action.CLOSE_PLAYER"

    /** intent 可直达的 surface；后续 surface（曲库 / 队列 / 设置…）按需扩展。 */
    enum class Surface { PLAYER, HOME }

    /**
     * intent action → 目标 surface。`null` = 常规启动 / 未知 action（零行为变化，
     * 不影响 LAUNCHER 正常入口）。
     */
    fun parse(action: String?): Surface? = when (action) {
        ACTION_OPEN_PLAYER -> Surface.PLAYER
        ACTION_CLOSE_PLAYER -> Surface.HOME
        else -> null
    }
}
