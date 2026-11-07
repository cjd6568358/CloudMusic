package com.netease.cloudmusic.ui;

import android.R;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.RelativeLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.netease.cloudmusic.ui.RotationRelativeLayout;
import com.netease.cloudmusic.utils.gq;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class RotationRelativeLayout extends RelativeLayout {
    private static final boolean DEBUG = false;
    private static final String TAG = "DiscRotation";
    private PaintFlagsDrawFilter ANTI_ALIAS_FILTER;
    private boolean isLollipopAndUp;
    private boolean mActivityPaused;
    private ValueAnimator mAnimator;
    private int mDelayTime;
    private boolean mFirst;
    private AnimationHolder mHolder;
    private Runnable mResumeRunnable;
    private float mSpeed;
    private boolean pauseOnDetach;

    public static class AnimationHolder implements AnimationHolderImpl, ValueAnimator.AnimatorUpdateListener {
        private static final String TAG = "AnimationHolder";
        private final WeakReference<RotationRelativeLayout> mViewRef;
        private HashMap<Integer, Float> stableViewIds;
        private HashMap<View, Float> stableViews;
        private float mRotationOffset = 0.0f;
        private float mPrevRotation = 0.0f;
        private float mCurrentRotation = 0.0f;
        private boolean mPrepared = false;
        private boolean mRunning = false;
        private boolean mStartCalled = false;

        public AnimationHolder(@NonNull RotationRelativeLayout rotationRelativeLayout) {
            this.mViewRef = new WeakReference<>(rotationRelativeLayout);
        }

        @Nullable
        public static View findViewDeep(@NonNull View view, int i) {
            if (view.getId() == i) {
                return view;
            }
            if (!(view instanceof ViewGroup)) {
                return null;
            }
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i2 = 0; i2 < viewGroup.getChildCount(); i2++) {
                View findViewDeep = findViewDeep(viewGroup.getChildAt(i2), i);
                if (findViewDeep != null) {
                    return findViewDeep;
                }
            }
            return null;
        }

        public static Unit lambda$startAnimator$0(Map map) {
            map.put("msg", "start animator but viewRef is null");
            return Unit.INSTANCE;
        }

        public static Unit lambda$startAnimator$1(n7.c cVar) {
            cVar.a = TAG;
            return Unit.INSTANCE;
        }

        private void resetRotation(float f) {
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout == null) {
                return;
            }
            rotationRelativeLayout.setRotation(f);
            if (!gq.a.a()) {
                HashMap<View, Float> hashMap = this.stableViews;
                if (hashMap == null || hashMap.size() <= 0) {
                    return;
                }
                for (View view : this.stableViews.keySet()) {
                    view.setRotation((-f) + this.stableViews.get(view).floatValue());
                }
                return;
            }
            HashMap<Integer, Float> hashMap2 = this.stableViewIds;
            if (hashMap2 == null || hashMap2.size() <= 0) {
                return;
            }
            for (Integer num : this.stableViewIds.keySet()) {
                View findViewDeep = findViewDeep(rotationRelativeLayout, num.intValue());
                if (findViewDeep != null) {
                    Float f2 = this.stableViewIds.get(num);
                    if (f2 == null) {
                        f2 = Float.valueOf(0.0f);
                    }
                    findViewDeep.setRotation((-f) + f2.floatValue());
                }
            }
        }

        private void startAnimator() {
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout == null) {
                n7.c.n.d().o((View) null, new Function1() {
                    public final Object invoke(Object obj) {
                        Unit lambda$startAnimator$0;
                        lambda$startAnimator$0 = RotationRelativeLayout.AnimationHolder.lambda$startAnimator$0((Map) obj);
                        return lambda$startAnimator$0;
                    }
                }, new Function1() {
                    public final Object invoke(Object obj) {
                        Unit lambda$startAnimator$1;
                        lambda$startAnimator$1 = RotationRelativeLayout.AnimationHolder.lambda$startAnimator$1((n7.c) obj);
                        return lambda$startAnimator$1;
                    }
                });
                return;
            }
            if (rotationRelativeLayout.mAnimator.isStarted()) {
                rotationRelativeLayout.mAnimator.end();
                rotationRelativeLayout.mAnimator.removeAllUpdateListeners();
            }
            this.mPrevRotation = 0.0f;
            rotationRelativeLayout.mAnimator.addUpdateListener(this);
            rotationRelativeLayout.mAnimator.start();
            this.mRunning = true;
        }

        private void stopAnimator() {
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout == null) {
                return;
            }
            if (rotationRelativeLayout.mAnimator.isStarted()) {
                rotationRelativeLayout.mAnimator.end();
                rotationRelativeLayout.mAnimator.removeAllUpdateListeners();
            }
            this.mPrevRotation = 0.0f;
            this.mRunning = false;
        }

        public void clearStableViews() {
            HashMap<View, Float> hashMap = this.stableViews;
            if (hashMap != null) {
                hashMap.clear();
            }
            HashMap<Integer, Float> hashMap2 = this.stableViewIds;
            if (hashMap2 != null) {
                hashMap2.clear();
            }
        }

        public float getCurrentRotation() {
            return this.mCurrentRotation;
        }

        @Override
        public boolean isPrepared() {
            return this.mPrepared;
        }

        @Override
        public boolean isRunning() {
            return this.mRunning;
        }

        @Override
        public void onAnimationUpdate(ValueAnimator valueAnimator) {
            float animatedFraction = ((valueAnimator.getAnimatedFraction() * 360.0f) + this.mRotationOffset) % 360.0f;
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout != null) {
                rotationRelativeLayout.setRotation(animatedFraction);
                if (gq.a.a()) {
                    HashMap<Integer, Float> hashMap = this.stableViewIds;
                    if (hashMap != null && hashMap.size() > 0) {
                        for (Integer num : this.stableViewIds.keySet()) {
                            View findViewDeep = findViewDeep(rotationRelativeLayout, num.intValue());
                            if (findViewDeep != null) {
                                Float f = this.stableViewIds.get(num);
                                if (f == null) {
                                    f = Float.valueOf(0.0f);
                                }
                                findViewDeep.setRotation((-animatedFraction) + f.floatValue());
                            }
                        }
                    }
                } else {
                    HashMap<View, Float> hashMap2 = this.stableViews;
                    if (hashMap2 != null && hashMap2.size() > 0) {
                        for (View view : this.stableViews.keySet()) {
                            view.setRotation((-animatedFraction) + this.stableViews.get(view).floatValue());
                        }
                    }
                }
            }
            this.mPrevRotation = animatedFraction;
            this.mCurrentRotation = animatedFraction;
        }

        @Override
        public void pause() {
            this.mStartCalled = false;
            if (this.mRunning && this.mPrepared) {
                this.mRotationOffset = this.mPrevRotation;
                stopAnimator();
            }
        }

        @Override
        public void prepareAnimation() {
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout == null || rotationRelativeLayout.mActivityPaused) {
                return;
            }
            this.mPrepared = true;
            if (!this.mStartCalled || this.mRunning) {
                return;
            }
            startAnimator();
        }

        @Override
        public void reset() {
            resetRotation(0.0f);
            this.mPrevRotation = 0.0f;
            this.mRotationOffset = 0.0f;
            this.mCurrentRotation = 0.0f;
        }

        public void setInitialRotation(float f) {
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout != null) {
                rotationRelativeLayout.setRotation(f);
            }
            this.mPrevRotation = f;
            this.mCurrentRotation = f;
            this.mRotationOffset = f;
        }

        public void setStableViewIds(int... iArr) {
            if (gq.a.a()) {
                this.stableViewIds = new HashMap<>();
            } else {
                this.stableViews = new HashMap<>();
            }
            RotationRelativeLayout rotationRelativeLayout = this.mViewRef.get();
            if (rotationRelativeLayout != null) {
                for (int i : iArr) {
                    View findViewById = rotationRelativeLayout.findViewById(i);
                    if (findViewById != null) {
                        if (gq.a.a()) {
                            this.stableViewIds.put(Integer.valueOf(i), Float.valueOf(findViewById.getRotation()));
                        } else {
                            this.stableViews.put(findViewById, Float.valueOf(findViewById.getRotation()));
                        }
                    }
                }
            }
        }

        @Override
        public void start() {
            this.mStartCalled = true;
            if (!this.mPrepared || this.mRunning) {
                return;
            }
            startAnimator();
        }

        @Override
        public void stop() {
            this.mStartCalled = false;
            if (this.mRunning) {
                this.mRotationOffset = this.mPrevRotation;
            }
            stopAnimator();
            this.mPrepared = false;
        }
    }

    public interface AnimationHolderImpl {
        boolean isPrepared();

        boolean isRunning();

        void pause();

        void prepareAnimation();

        void reset();

        void start();

        void stop();
    }

    public RotationRelativeLayout(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mFirst = true;
        this.isLollipopAndUp = false;
        this.mActivityPaused = false;
        this.ANTI_ALIAS_FILTER = new PaintFlagsDrawFilter(0, 3);
        init();
    }

    public static HashMap<Integer, int[]> getAnimationInfo(View view) {
        HashMap<Integer, int[]> hashMap = new HashMap<>();
        int[] iArr = new int[2];
        int[] iArr2 = new int[5];
        view.getLocationOnScreen(iArr);
        iArr2[0] = iArr[0] + view.getPaddingLeft();
        iArr2[1] = iArr[1] + view.getPaddingTop();
        if (view.getParent() instanceof RotationRelativeLayout) {
            View view2 = (View) view.getParent().getParent();
            Rect rect = new Rect();
            view2.getGlobalVisibleRect(rect);
            iArr2[0] = (rect.centerX() - (view.getWidth() / 2)) + view.getPaddingLeft();
            iArr2[1] = (rect.centerY() - (view.getHeight() / 2)) + view.getPaddingTop();
        }
        iArr2[2] = (view.getWidth() - view.getPaddingLeft()) - view.getPaddingRight();
        iArr2[3] = (view.getHeight() - view.getPaddingTop()) - view.getPaddingBottom();
        iArr2[4] = 1;
        hashMap.put(0, iArr2);
        return hashMap;
    }

    private void init() {
        ValueAnimator ofFloat = ValueAnimator.ofFloat(0.0f, 1.0f);
        this.mAnimator = ofFloat;
        ofFloat.setDuration(yh0.m.b());
        this.mAnimator.setInterpolator(new LinearInterpolator());
        this.mAnimator.setRepeatCount(-1);
        this.mAnimator.setRepeatMode(1);
        this.mHolder = new AnimationHolder(this);
        this.isLollipopAndUp = com.netease.cloudmusic.utils.z3.q0();
        this.mDelayTime = getResources().getInteger(R.integer.config_mediumAnimTime);
    }

    public void onResumeCalledInner(boolean z) {
        if (z) {
            this.mHolder.pause();
        }
        if (this.mHolder.isPrepared()) {
            return;
        }
        this.mHolder.prepareAnimation();
    }

    public void clearStableViews() {
        AnimationHolder animationHolder = this.mHolder;
        if (animationHolder != null) {
            animationHolder.clearStableViews();
        }
    }

    @Override
    public void dispatchDraw(Canvas canvas) {
        canvas.setDrawFilter(this.ANTI_ALIAS_FILTER);
        super.dispatchDraw(canvas);
    }

    public AnimationHolder getAnimationHolder() {
        return this.mHolder;
    }

    public float getCurrentRotation() {
        return this.mHolder.getCurrentRotation();
    }

    public boolean isPauseOnDetach() {
        return this.pauseOnDetach;
    }

    public boolean isPrepared() {
        return this.mHolder.isPrepared();
    }

    public boolean isRunning() {
        return this.mHolder.isRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (this.pauseOnDetach) {
            pause();
        }
    }

    public void onEnterAnimationCompleteCalled(boolean z) {
        removeCallbacks(this.mResumeRunnable);
        onResumeCalledInner(z);
    }

    public void onPauseCalled() {
        this.mActivityPaused = true;
        removeCallbacks(this.mResumeRunnable);
        this.mHolder.stop();
    }

    public void onResumeCalled(final PlayerSeekBar playerSeekBar) {
        this.mActivityPaused = false;
        if (this.isLollipopAndUp) {
            this.mResumeRunnable = new Runnable() {
                @Override
                public void run() {
                    RotationRelativeLayout.this.onResumeCalledInner(playerSeekBar.isCaching());
                }
            };
        } else {
            this.mResumeRunnable = new Runnable() {
                @Override
                public void run() {
                    boolean z = false;
                    if (RotationRelativeLayout.this.mFirst) {
                        RotationRelativeLayout.this.mFirst = false;
                        RotationRelativeLayout.this.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                RotationRelativeLayout.this.getViewTreeObserver().removeOnPreDrawListener(this);
                                AnonymousClass2 anonymousClass2 = AnonymousClass2.this;
                                RotationRelativeLayout rotationRelativeLayout = RotationRelativeLayout.this;
                                PlayerSeekBar playerSeekBar2 = playerSeekBar;
                                rotationRelativeLayout.onResumeCalledInner(playerSeekBar2 != null && playerSeekBar2.isCaching());
                                return false;
                            }
                        });
                        return;
                    }
                    RotationRelativeLayout rotationRelativeLayout = RotationRelativeLayout.this;
                    PlayerSeekBar playerSeekBar2 = playerSeekBar;
                    if (playerSeekBar2 != null && playerSeekBar2.isCaching()) {
                        z = true;
                    }
                    rotationRelativeLayout.onResumeCalledInner(z);
                }
            };
        }
        postDelayed(this.mResumeRunnable, this.isLollipopAndUp ? this.mDelayTime * 2 : this.mDelayTime);
    }

    public void pause() {
        this.mHolder.pause();
    }

    public void prepareAnimation() {
        this.mHolder.prepareAnimation();
    }

    public void reset() {
        this.mHolder.reset();
    }

    public void setDuration(long j) {
        ValueAnimator valueAnimator = this.mAnimator;
        if (valueAnimator != null) {
            valueAnimator.setDuration(j);
        }
    }

    public void setPauseOnDetach(boolean z) {
        this.pauseOnDetach = z;
    }

    public void setRotateSpeed(float f) {
        if (this.mSpeed != f) {
            this.mSpeed = f;
            this.mAnimator.setDuration(yh0.m.c(f));
            this.mAnimator.setCurrentPlayTime((long) ((this.mHolder.mCurrentRotation / 360.0f) * this.mAnimator.getDuration()));
            this.mHolder.mRotationOffset = 0.0f;
        }
    }

    public void setStableViewIds(int... iArr) {
        AnimationHolder animationHolder = this.mHolder;
        if (animationHolder != null) {
            animationHolder.setStableViewIds(iArr);
        }
    }

    public void start() {
        this.mHolder.start();
    }

    public void stop() {
        this.mHolder.stop();
    }

    public void stopAndRest() {
        this.mHolder.stop();
        this.mHolder.reset();
    }
}
