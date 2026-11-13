package com.example.cloudmusic.ui.player

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
 * `12 §61.42` 本批改动：视觉层从自绘色块换成 D-146 导出的**竞品真实位图**，
 * 且按竞品 layout 的 z 序重排（证据 `analysis/l3-reference/legacy-disc-layers.png`）：
 * - `legacy_gc.xml`：`playDiscBg=@drawable/ga6`（浅色底盘）→
 *   `smallAlbumCover=@drawable/g9f`（红色唱片盘，居中 170dip）→
 *   `smallAlbumDisc=@drawable/ga0`（黑胶环，margin 10dip）；
 * - `legacy_bv5.xml`：`ga9`（NoShadow 外圈）→ `ga8`（WithShadow 框）依次叠加。
 * 位图自身 alpha 原样保留，不额外调色，避免自造颜色产生色差。
 *
 * 唱针位图不在竞品导出闭包内，按竞品形态自绘（金属杆 + 顶部 pivot），
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

    /** `g9f` 588x588 红色唱片盘：竞品真实位图，作为中心「专辑封面」的默认占位。 */
    private val albumLabel = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscRed))
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

    /** 唱针：竞品闭包内未定位到位图，按竞品形态自绘；pivot 固定在顶部。 */
    private val needle = FrameLayout(context).apply { rotation = NEEDLE_UP_DEGREES }

    /**
     * §61.109（P2 ③）：基准图 `competitor-player-baseline.png` 的唱臂是**白色弧形**
     * （顶部白色 pivot 圆 + 弧形杆 + 末端唱头），旧灰直杆与其不符。按基准形态自绘
     * （平台 View 体系 `onDraw`，仍非 Compose Canvas）；像素级校准待真机截图比对
     * （`pixel_level_1to1` 未达成前 False）。
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
        needle.addView(needleArm)
        addView(needle, LayoutParams(60, 260, Gravity.TOP or Gravity.END))
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

    /** 按竞品 `legacy_gc.xml` 的比例把各层尺寸精确化；可重复调用，幂等。 */
    private fun applySizes(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val discSize = (minOf(w, h) * 0.86f).toInt()
        discContainer.layoutParams = LayoutParams(discSize, discSize, Gravity.CENTER)
        discBacking.layoutParams = LayoutParams(discSize, discSize, Gravity.CENTER)

        // `legacy_gc.xml`：黑胶盘 fill_parent + margin @dimen/auh(10dip)，比底盘略小。
        val ringSize = (discSize * 0.92f).toInt()
        listOf(vinylPlate, vinylRing, discFrame).forEach {
            it.layoutParams = LayoutParams(ringSize, ringSize, Gravity.CENTER)
        }

        // `legacy_gc.xml` smallAlbumCover 170dip 居中，约底盘的 0.60。
        val labelSize = (discSize * 0.60f).toInt()
        albumLabel.layoutParams = LayoutParams(labelSize, labelSize, Gravity.CENTER)

        // §61.109：唱臂容器加宽以容纳弧形杆（基准形态），pivot 圆心仍在容器顶缘中点。
        val needleW = (discSize * 0.16f).toInt().coerceAtLeast(10)
        val needleH = (discSize * 0.50f).toInt()
        needle.layoutParams = LayoutParams(needleW, needleH, Gravity.TOP or Gravity.END).apply {
            topMargin = (h * 0.02f).toInt()
            rightMargin = (w * 0.16f).toInt()
        }
        needle.pivotX = needleW / 2f
        // §61.109：起落动画绕基准图 pivot 圆心转（= NeedleArmView 顶缘圆心 y）。
        needle.pivotY = needleW * 0.42f
        needleArm.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
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
     * §61.109（P2 ②）：圆形真实封面替代红色占位盘（基准图形态）。封面缺失 /
     * 解码失败时回退竞品 `g9f` 位图，不出现空盘。圆形裁切用平台
     * `RoundedBitmapDrawable`（非 Compose Canvas），与自绘边界一致。
     */
    fun setCover(cover: Bitmap?) {
        // AndroidView.update 每次 recomposition 都会调；同一位图直接跳过，
        // 避免 positionMs 每秒 tick 重建 drawable。
        if (cover === lastCover) return
        lastCover = cover
        if (cover == null) {
            albumLabel.setImageBitmap(loadLegacyBitmap(context, LegacyAsset.DiscRed))
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

    /** 基准形态白色弧形唱臂（自绘）：pivot 圆 + 二次曲线杆 + 末端唱头。 */
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
        private val armPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            // pivot：顶部白色圆（基准图唱臂轴心）。
            val pivotR = w * 0.42f
            val pivotY = w * 0.42f
            canvas.drawCircle(w * 0.5f, pivotY, pivotR, fillPaint)
            // 弧形杆：pivot 下方起笔，控制点右拉形成基准图的弓形，落在唱头处。
            val startX = w * 0.5f
            val startY = pivotY + pivotR * 0.6f
            val endX = w * 0.68f
            val endY = h - w * 0.9f
            armPath.reset()
            armPath.moveTo(startX, startY)
            armPath.quadTo(w * 0.72f, h * 0.42f, endX, endY)
            paint.strokeWidth = w * 0.16f
            canvas.drawPath(armPath, paint)
            // 唱头：末端白色圆角矩形（沿杆方向的矮块）。
            val headW = w * 0.5f
            val headH = w * 0.8f
            canvas.drawRoundRect(
                endX - headW / 2f, endY - headH / 2f,
                endX + headW / 2f, endY + headH / 2f,
                headW * 0.35f, headW * 0.35f, fillPaint,
            )
        }
    }
}
