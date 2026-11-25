package com.ccmsc.cloudmusic.ui.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * `12 §39` / `12 §61.40` / `12 §61.42`：唱片旋转与唱针起落。
 *
 * 交互参数逐项照搬竞品反编译实证（证据见 `analysis/jadx-player-views/` 与
 * `analysis/rotation-anim-resolution.json`），9 个参数标记不得改动：
 * `ValueAnimator.ofFloat(0.0f, 1.0f)` + `LinearInterpolator` + `setRepeatCount(-1)`
 * + `setRepeatMode(1)` + `setDuration(20000.0f / getPlaySpeed(...))`，
 * `AnimationHolder.onAnimationUpdate()` 把 `getAnimatedFraction() * 360.0f`
 * 传给 `View.setRotation(...)`；唱针由两个 `ObjectAnimator` 起落。
 *
 * `12 §61.42`：视觉层从自绘色块换成 D-146 导出的**竞品真实位图**，
 * 且按竞品 layout 的 z 序重排（证据 `analysis/l3-reference/legacy-disc-layers.png`）：
 * - `legacy_gc.xml`：`playDiscBg=@drawable/ga6`（浅色底盘）→
 *   `smallAlbumCover=@drawable/g9f`（红色唱片盘，居中 170dip）→
 *   `smallAlbumDisc=@drawable/ga0`（黑胶环，margin 10dip）；
 * - `legacy_bv5.xml`：`ga9`（NoShadow 外圈）→ `ga8`（WithShadow 框）依次叠加。
 * 位图自身 alpha 原样保留，不额外调色，避免自造颜色产生色差。
 *
 * `12 §61.174`（播放页整体 1:1）几何重做：旧实现按 `min(w,h)*0.86` 居中，
 * 真机上唱片偏大、唱针悬在右上角。现按基准图逐像素量化（[PlayerBaseline]）：
 * - 唱片直径 = `min(屏宽 × 0.757, 区高 × 0.666)`，**底部对齐**（上方空隙留给唱针）；
 * - 唱针轴心固定在 `(0.506 × 宽, 0.152 × 高)`；唱头自 §61.197 起改为**从唱片圆心按
 *   44°/0.86r 求落点**（压在盘缘右上）——旧固定比例 (0.705w, 0.463h) 在唱片区坐标系
 *   里落在盘顶上方 37dp（悬空白棒根因，用户「探针还是没改」实证）；
 * - 唱臂 §61.190 起为「细直段 4.5dp + 外弯粗弧段 8.5dp」两段式 + 轴心光晕 +
 *   唱头圆角块（凹槽刻线两道），形态对齐 `cp-crop-02-disc.png`。
 *
 * 唱针位图不在竞品导出闭包内，按基准图形态自绘（平台 View 体系 `onDraw`），
 * 属用户授权的「其余全部自己实现」，如实挂账。
 *
 * 合规边界（`03-10 §11 #1` / `§3.3`）：**不用 Compose Canvas 重绘唱片**，也不用
 * `animateFloatAsState` 去「近似」参数；动画用平台属性动画 [ValueAnimator]
 * + [View.setRotation]。唱片旋转与唱针**不加载任何 `res/anim/` XML**。
 */
class VinylDiscView(context: Context) : FrameLayout(context) {

    companion object {
        /** 竞品 `yh0.m.b()` 的基准分子：`20000.0f / getPlaySpeed(...)`。 */
        const val BASE_PERIOD_MS = 20_000L

        /** 唱针抬起角度（暂停态）。 */
        private const val NEEDLE_UP_DEGREES = -28f

        /** 唱针落下角度（播放态）。 */
        private const val NEEDLE_DOWN_DEGREES = 0f

        /** 唱针起落时长（`03-10 §3.3` 代码常量）。 */
        private const val NEEDLE_ANIM_MS = 260L
    }

    /** 竞品 `RotationRelativeLayout disc`：整个唱片容器做 `setRotation`，唱针不随之旋转。 */
    private val discContainer = FrameLayout(context)

    /** `ga6` 900x900 浅色圆盘：竞品真实位图，唱片底盘 / 边缘光晕。 */
    private val discBacking = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscBacking))
    }

    /**
     * 中心「专辑封面」层。占位盘 = **自绘**红色标签（[DiscLabelArt]）：
     * 竞品 `g9f` 位图上带两圈版权文字（「网易云音乐保留全部著作权权利」/
     * "All rights are reserved by NetEase Cloud Music"），§61.278 起不再使用该位图。
     */
    private val albumLabel = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(DiscLabelArt.redLabel(context))
    }

    /** `ga0` 828x828 黑胶环：竞品真实位图，中心透明，露出红色唱片盘。 */
    private val vinylPlate = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscVinyl))
    }

    /** `ga9` 924x925 黑胶外圈：竞品真实位图（`legacy_bv5.xml` NoShadow 层）。 */
    private val vinylRing = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscRing))
    }

    /** `ga8` 924x924 带阴影框：竞品真实位图（`legacy_bv5.xml` WithShadow 层，最上）。 */
    private val discFrame = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscFrame))
    }

    /** 唱针容器：铺满整个视图（§61.174），旋转轴心用绝对坐标，起落不再依赖容器边距。 */
    private val needle = FrameLayout(context).apply { rotation = NEEDLE_UP_DEGREES }

    /**
     * `12 §61.109` / `§61.174`：基准图唱臂是**白色弧形**
     * （轴心白色圆 + 近竖直起步的弧杆 + 末端唱头），按基准图逐像素测量自绘。
     */
    private val needleArm = NeedleArmView(context)

    private var rotationAnimator: ValueAnimator? = null
    private var needleAnimator: ObjectAnimator? = null

    /** 生产侧无倍速设置源，固定 1.0；接口按竞品 `20000f / playSpeed` 保留（`12 §61.40` 挂账）。 */
    private var playSpeed = 1f
    private var playing = false

    init {
        // z 序严格照 `legacy_gc.xml` / `legacy_bv5.xml`：底盘 → 中心标签 → 黑胶环 → 外圈 → 阴影框。
        // 用 MATCH_PARENT 兜底：Compose AndroidView 首帧若以 0 尺寸测量，0x0 的子 View
        // 会永远停在 0（12 §61.42 真机实证），这里保证至少铺满，再由 applySizes 精确化。
        fun fill(gravity: Int) = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, gravity,
        )
        discContainer.addView(discBacking, fill(Gravity.CENTER))
        discContainer.addView(albumLabel, fill(Gravity.CENTER))
        discContainer.addView(vinylPlate, fill(Gravity.CENTER))
        discContainer.addView(vinylRing, fill(Gravity.CENTER))
        discContainer.addView(discFrame, fill(Gravity.CENTER))
        addView(discContainer, fill(Gravity.CENTER))
        needle.addView(needleArm, fill(Gravity.CENTER))
        addView(needle, fill(Gravity.CENTER))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // attach 后重算一次，覆盖「onSizeChanged 早于 AndroidView 最终尺寸」的时序。
        post { applySizes(width, height) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySizes(w, h)
    }

    /**
     * `12 §61.174` 按基准图量化值精确化各层（可重复调用，幂等）。
     *
     * 唱片**底部对齐**：基准图唱片底边正好贴着唱片区下缘（y=550dp 绝对），
     * 上方 153dp 空档留给唱针轴心与弧杆。
     */
    private fun applySizes(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val discSize = minOf(
            w * PlayerBaseline.DiscDiameterRatio,
            h * PlayerBaseline.DiscMaxHeightRatio,
        ).toInt().coerceAtLeast(1)

        discContainer.layoutParams = LayoutParams(discSize, discSize, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        discBacking.layoutParams = LayoutParams(discSize, discSize, Gravity.CENTER)

        // `legacy_gc.xml`：黑胶盘 fill_parent + margin @dimen/auh(10dip)，比底盘略小。
        val ringSize = (discSize * 0.92f).toInt()
        listOf(vinylPlate, vinylRing, discFrame).forEach {
            it.layoutParams = LayoutParams(ringSize, ringSize, Gravity.CENTER)
        }

        // `legacy_gc.xml` smallAlbumCover 170dip 居中，约底盘的 0.60。
        val labelSize = (discSize * 0.60f).toInt()
        albumLabel.layoutParams = LayoutParams(labelSize, labelSize, Gravity.CENTER)

        // 唱针容器铺满，旋转轴心 = 基准图白色 pivot 圆心（绝对比例坐标）。
        needle.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        needle.pivotX = w * PlayerBaseline.NeedlePivotXRatio
        needle.pivotY = h * PlayerBaseline.NeedlePivotYRatio
        needleArm.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // §61.197（用户「探针还是没改」真因）：唱头位置不再用固定比例——旧比例
        // (0.705w, 0.463h) 在唱片区坐标系里落在盘顶上方 37dp（悬空白棒根因）。
        // 改为**从唱片圆心按 44°/0.86r 求唱头落点**（落在盘缘右上，与
        // `cp-crop-02-disc.png` 一致：针头压在黑胶边缘上）。
        val discCx = w / 2f
        val discCy = h - discSize / 2f // 唱片底部对齐 → 圆心
        val headAngle = Math.toRadians(44.0)
        val headR = discSize / 2f * 0.86f
        needleArm.setGeometry(
            pivotX = w * PlayerBaseline.NeedlePivotXRatio,
            pivotY = h * PlayerBaseline.NeedlePivotYRatio,
            headX = (discCx + headR * cos(headAngle)).toFloat(),
            headY = (discCy - headR * sin(headAngle)).toFloat(),
            density = density,
        )
        invalidate()
    }

    /** `03-10 §6` 映射表：PLAYING → 转 + 针落；PAUSED / IDLE / ENDED / ERROR → 停 + 针抬。 */
    fun setPlaying(next: Boolean) {
        if (playing == next) return
        playing = next
        if (next) startRotation() else pauseRotation()
        animateNeedle(next)
    }

    /** `yh0.m.b() = (long)(20000.0f / getPlaySpeed(...))`。 */
    private fun periodMs(): Long = (BASE_PERIOD_MS / playSpeed).toLong()

    /** 生产侧暂无倍速设置源，接口保留以便后续按竞品公式接入（`12 §61.40` 挂账）。 */
    fun setPlaySpeed(speed: Float) {
        val next = speed.coerceIn(0.25f, 4f)
        if (next == playSpeed) return
        playSpeed = next
        if (playing) rotationAnimator?.duration = periodMs()
    }

    private fun startRotation() {
        val animator = rotationAnimator ?: ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE      // 竞品 setRepeatCount(-1)
            repeatMode = ValueAnimator.RESTART       // 竞品 setRepeatMode(1)
            // 竞品 AnimationHolder.onAnimationUpdate()：fraction * 360f → View.setRotation
            addUpdateListener { a -> discContainer.rotation = a.animatedFraction * 360f }
        }.also { rotationAnimator = it }
        animator.duration = periodMs()
        when {
            animator.isPaused -> animator.resume()   // 暂停→恢复：续转，不重置角度
            !animator.isStarted -> animator.start()
        }
    }

    private fun pauseRotation() {
        val animator = rotationAnimator ?: return
        if (animator.isStarted && !animator.isPaused) animator.pause()
    }

    /** 竞品 `discHeadUpRotateAnimation` / `discHeadDownRotateAnimation`（两个 `ObjectAnimator`）。 */
    private fun animateNeedle(down: Boolean) {
        needleAnimator?.cancel()
        val target = if (down) NEEDLE_DOWN_DEGREES else NEEDLE_UP_DEGREES
        needleAnimator = ObjectAnimator.ofFloat(
            needle, View.ROTATION, needle.rotation, target,
        ).apply {
            duration = NEEDLE_ANIM_MS
            start()
        }
    }

    /** View 脱离窗口时释放动画，避免持有 View 引用泄漏；重新 attach 后由 `setPlaying` 重建。 */
    fun release() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        needleAnimator?.cancel()
        needleAnimator = null
        playing = false
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    /**
     * `12 §61.109`：圆形真实封面替代红色占位盘。封面缺失 / 解码失败时回退**自绘**
     * 红标（[DiscLabelArt]，§61.278 起不再用带竞品版权文字的 `g9f` 位图），不出现空盘。
     * 圆形裁切用平台 `RoundedBitmapDrawable`
     * （非 Compose Canvas），与自绘边界一致。
     */
    fun setCover(cover: Bitmap?) {
        // AndroidView.update 每次 recomposition 都会调；同一位图直接跳过，
        // 避免 positionMs 每秒 tick 重建 drawable。
        if (cover === lastCover) return
        lastCover = cover
        if (cover == null) {
            albumLabel.setImageBitmap(DiscLabelArt.redLabel(context))
            return
        }
        val rounded = RoundedBitmapDrawableFactory.create(context.resources, cover).apply {
            isCircular = true
            setAntiAlias(true)
        }
        albumLabel.setImageDrawable(rounded)
    }

    /** 上一次设置的封面引用（去抖用，不持有额外生命周期）。 */
    private var lastCover: Bitmap? = null

    /**
     * 基准图唱臂（自绘，`12 §61.174`；§61.190 按 `cp-crop-02-disc.png` 逐形态重绘）：
     * 竞品形态五要素——① 轴心光晕（半透明大圆）② 轴心白点 ③ 近竖直细短臂
     * ④ 外弯加粗弧臂（细→粗锥形观感）⑤ 唱头圆角块（两道凹槽刻线）落在盘缘右上。
     * 旧实现单段均匀细杆、无光晕无凹槽 ⇒ 用户「探针跟竞品不一致」。
     */
    private class NeedleArmView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            alpha = 36
        }
        private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(222, 222, 222)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val armPath = Path()

        private var pivotX = 0f
        private var pivotY = 0f
        private var headX = 0f
        private var headY = 0f
        private var density = 1f

        fun setGeometry(
            pivotX: Float,
            pivotY: Float,
            headX: Float,
            headY: Float,
            density: Float,
        ) {
            this.pivotX = pivotX
            this.pivotY = pivotY
            this.headX = headX
            this.headY = headY
            this.density = density
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pivotX <= 0f || headY <= pivotY) return
            val pivotR = PlayerBaseline.NeedlePivotRadiusDp * density
            val headW = PlayerBaseline.NeedleHeadWidthDp * density
            val headH = PlayerBaseline.NeedleHeadHeightDp * density
            val thinW = 4.5f * density
            val thickW = 8.5f * density

            // ① 轴心光晕（半透明大圆）
            canvas.drawCircle(pivotX, pivotY, pivotR * 2.3f, haloPaint)
            // ② 轴心白点
            canvas.drawCircle(pivotX, pivotY, pivotR, fillPaint)

            // ③④ 两段臂：细直段（轴心 → 膝，近竖直下行 42%）+ 粗弧段（膝 → 唱头外弯）。
            // 锥形观感 = 两段 stroke 宽度 4.5dp → 8.5dp。
            val kneeX = pivotX + (headX - pivotX) * 0.18f
            val kneeY = pivotY + (headY - pivotY) * 0.42f
            paint.strokeWidth = thinW
            canvas.drawLine(
                pivotX, pivotY + pivotR * 0.4f, kneeX, kneeY, paint,
            )
            paint.strokeWidth = thickW
            armPath.reset()
            armPath.moveTo(kneeX, kneeY)
            armPath.quadTo(
                kneeX + (headX - kneeX) * 0.30f, kneeY + (headY - kneeY) * 0.52f,
                headX, headY,
            )
            canvas.drawPath(armPath, paint)

            // ⑤ 唱头：沿杆方向倾斜的白色圆角块 + 两道凹槽刻线（基准 74x62px，倾角约 24°）。
            canvas.save()
            canvas.rotate(24f, headX, headY)
            canvas.drawRoundRect(
                headX - headW / 2f, headY - headH / 2f,
                headX + headW / 2f, headY + headH / 2f,
                headH * 0.34f, headH * 0.34f, fillPaint,
            )
            groovePaint.strokeWidth = 1.5f * density
            canvas.drawLine(
                headX - headW * 0.20f, headY - headH * 0.30f,
                headX - headW * 0.20f, headY + headH * 0.30f, groovePaint,
            )
            canvas.drawLine(
                headX + headW * 0.08f, headY - headH * 0.30f,
                headX + headW * 0.08f, headY + headH * 0.30f, groovePaint,
            )
            canvas.restore()
        }
    }
}
