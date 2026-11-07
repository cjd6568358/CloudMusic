package com.netease.cloudmusic.module.playerstyle;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.LifecycleOwnerKt;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import ap0.k1;
import ap0.v1;
import bv0.f0;
import ck.d;
import com.netease.cloudmusic.INoProguard;
import com.netease.cloudmusic.meta.MusicInfo;
import com.netease.cloudmusic.module.player.redux.meta.PlayerPageState;
import com.netease.cloudmusic.s1;
import com.netease.cloudmusic.service.MainProcessPlayService;
import com.netease.cloudmusic.theme.ui.CustomThemeRelativeLayout;
import com.netease.cloudmusic.ui.AnimableDrawerImageView;
import com.netease.cloudmusic.ui.NeteaseMusicSimpleDraweeView;
import com.netease.cloudmusic.ui.PlayerDiscViewFlipper;
import com.netease.cloudmusic.ui.PlayerSeekBarNew;
import com.netease.cloudmusic.ui.RotationRelativeLayout;
import com.netease.cloudmusic.ui.component.PlayerActivityUIState;
import com.netease.cloudmusic.utils.al;
import com.netease.cloudmusic.utils.bl;
import com.netease.cloudmusic.utils.lt;
import com.netease.cloudmusic.utils.sr;
import com.netease.cloudmusic.utils.tl;
import com.netease.cloudmusic.utils.x1;
import com.netease.cloudmusic.utils.xk;
import com.netease.cloudmusic.utils.z4;
import com.netease.cloudmusic.utils.zk;
import dk.p;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import ji0.s;
import kotlin.Pair;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlin.coroutines.jvm.internal.DebugMetadata;
import kotlin.coroutines.jvm.internal.SuspendLambda;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlinx.coroutines.n0;
import kotlinx.coroutines.p0;
import n7.k;
import n7.q;
import n7.r;
import n7.u0;
import n7.z0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;

public final class PlayerVinylViewHolder implements r, k {

    @Nullable
    private final NeteaseMusicSimpleDraweeView fakeVideoCover;

    @Nullable
    private final ImageView fakeVideoCoverMask;

    @Nullable
    private final bn0.k mPlayerStoryGuideAnimationHelper;

    private boolean noAnimGestureEnable;

    private boolean canScrollLeft;

    private boolean canScrollRight;

    @Nullable
    private com.netease.cloudmusic.module.playerstyle.b playerVinylHelper;

    private int playState;

    @Nullable
    private final PlayerSeekBarNew playSeekBar;

    @NotNull
    private final q playerView;

    @NotNull
    private final Function2<Integer, Integer, Unit> afterAdjustUI;

    @Nullable
    private final RelativeLayout artistImageContainer;

    @Nullable
    private final RelativeLayout mDiskView;

    @Nullable
    private final PlayerDiscViewFlipper discSwitcher;

    @Nullable
    private RotationRelativeLayout disc;

    @Nullable
    private final ImageView playDiscBg;

    @Nullable
    private final ImageView smallAlbumDisc0;

    @Nullable
    private final ImageView smallAlbumDisc1;

    @Nullable
    private final AnimableDrawerImageView smallAlbumCover0;

    @Nullable
    private final AnimableDrawerImageView smallAlbumCover1;

    private long mVisualizerId;

    @Nullable
    private ViewGroup mVisualizerView;

    @NotNull
    private final BroadcastReceiver mVisualizerReceiver;

    @Nullable
    private final ImageView discHead;

    private ObjectAnimator discHeadUpRotateAnimation;

    private ObjectAnimator discHeadDownRotateAnimation;

    private Runnable discHeadUpRunnable;

    private Runnable discHeadDownRunnable;

    private int discHeadStatus;

    private int playerDiscNeedleOffsetX;

    private int playerDiscNeedleOffsetY;

    private final int discHeadDownDuration;

    @Nullable
    private final CustomThemeRelativeLayout fakeBackHistory;

    private static final class UpdatePlayStateConfig implements INoProguard, Serializable {
        private static final long serialVersionUID = 479578708795498974L;
        private final double samplingRate;
        private final boolean useRealPlayState;

        public UpdatePlayStateConfig() {
            this(0.0d, false, 3, null);
        }

        public UpdatePlayStateConfig(double d, boolean z) {
            this.samplingRate = d;
            this.useRealPlayState = z;
        }

        public UpdatePlayStateConfig(double d, boolean z, int i, DefaultConstructorMarker defaultConstructorMarker) {
            this((i & 1) != 0 ? 0.0d : d, (i & 2) != 0 ? false : z);
        }

        public static UpdatePlayStateConfig copy$default(UpdatePlayStateConfig updatePlayStateConfig, double d, boolean z, int i, Object obj) {
            if ((i & 1) != 0) {
                d = updatePlayStateConfig.samplingRate;
            }
            if ((i & 2) != 0) {
                z = updatePlayStateConfig.useRealPlayState;
            }
            return updatePlayStateConfig.copy(d, z);
        }

        public final double getSamplingRate() {
            return this.samplingRate;
        }

        public final boolean getUseRealPlayState() {
            return this.useRealPlayState;
        }

        @NotNull
        public final UpdatePlayStateConfig copy(double samplingRate, boolean useRealPlayState) {
            return new UpdatePlayStateConfig(samplingRate, useRealPlayState);
        }

        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UpdatePlayStateConfig)) {
                return false;
            }
            UpdatePlayStateConfig updatePlayStateConfig = (UpdatePlayStateConfig) other;
            return Intrinsics.areEqual(Double.valueOf(this.samplingRate), Double.valueOf(updatePlayStateConfig.samplingRate)) && this.useRealPlayState == updatePlayStateConfig.useRealPlayState;
        }

        public final double getSamplingRate() {
            return this.samplingRate;
        }

        public final boolean getUseRealPlayState() {
            return this.useRealPlayState;
        }

        public int hashCode() {
            int a = com.netease.cloudmusic.android.sdk.playerengine.a.a(this.samplingRate) * 31;
            boolean z = this.useRealPlayState;
            int i = z;
            if (z != 0) {
                i = 1;
            }
            return a + i;
        }

        @NotNull
        public String toString() {
            return "UpdatePlayStateConfig(samplingRate=" + this.samplingRate + ", useRealPlayState=" + this.useRealPlayState + ")";
        }
    }

    @DebugMetadata(c = "com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder$1", f = "PlayerVinylViewHolder.kt", i = {}, l = {}, m = "invokeSuspend", n = {}, s = {})
    static final class a extends SuspendLambda implements Function2<n0, Continuation<? super Unit>, Object> {
        int a;
        private Object b;

        @DebugMetadata(c = "com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder$1$1", f = "PlayerVinylViewHolder.kt", i = {}, l = {1059}, m = "invokeSuspend", n = {}, s = {})
        static final class C0164a extends SuspendLambda implements Function2<n0, Continuation<? super Unit>, Object> {
            int a;
            final PlayerVinylViewHolder b;

            public static final class C0165a implements kotlinx.coroutines.flow.h<PlayerActivityUIState> {
                final PlayerVinylViewHolder a;

                public C0165a(PlayerVinylViewHolder playerVinylViewHolder) {
                    this.a = playerVinylViewHolder;
                }

                @Nullable
                public Object emit(PlayerActivityUIState playerActivityUIState, @NotNull Continuation<? super Unit> continuation) {
                    com.netease.cloudmusic.module.playerstyle.b bVar;
                    if ((sf2.a.b() || x1.a.j()) && (bVar = this.a.playerVinylHelper) != null) {
                        com.netease.cloudmusic.module.playerstyle.b.d(bVar, this.a.getPlayerView().l0().getPlayerCustomState().getVinylType(), false, 2, null);
                    }
                    this.a.R();
                    return Unit.INSTANCE;
                }
            }

            public static final class b implements kotlinx.coroutines.flow.g<PlayerActivityUIState> {
                final kotlinx.coroutines.flow.g a;

                public static final class C0166a implements kotlinx.coroutines.flow.h<PlayerPageState> {
                    final kotlinx.coroutines.flow.h a;

                    @DebugMetadata(c = "com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder$1$1$invokeSuspend$$inlined$map$1$2", f = "PlayerVinylViewHolder.kt", i = {}, l = {137}, m = "emit", n = {}, s = {})
                    public static final class C0167a extends ContinuationImpl {
                        Object a;
                        int b;

                        public C0167a(Continuation continuation) {
                            super(continuation);
                        }

                        @Nullable
                        public final Object invokeSuspend(@NotNull Object obj) {
                            this.a = obj;
                            this.b |= Integer.MIN_VALUE;
                            return C0166a.this.emit(null, this);
                        }
                    }

                    public C0166a(kotlinx.coroutines.flow.h hVar) {
                        this.a = hVar;
                    }

                    @org.jetbrains.annotations.Nullable
                    public java.lang.Object emit(java.lang.Object r6, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation r7) {
                        throw new UnsupportedOperationException("Method not decompiled: com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder.a.C0164a.b.C0166a.emit(java.lang.Object, kotlin.coroutines.Continuation):java.lang.Object");
                    }
                }

                public b(kotlinx.coroutines.flow.g gVar) {
                    this.a = gVar;
                }

                @Nullable
                public Object collect(@NotNull kotlinx.coroutines.flow.h hVar, @NotNull Continuation continuation) {
                    Object collect = this.a.collect(new C0166a(hVar), continuation);
                    return collect == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? collect : Unit.INSTANCE;
                }
            }

            C0164a(PlayerVinylViewHolder playerVinylViewHolder, Continuation<? super C0164a> continuation) {
                super(2, continuation);
                this.b = playerVinylViewHolder;
            }

            @NotNull
            public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
                return new C0164a(this.b, continuation);
            }

            @Nullable
            public final Object invoke(@NotNull n0 n0Var, @Nullable Continuation<? super Unit> continuation) {
                return create(n0Var, continuation).invokeSuspend(Unit.INSTANCE);
            }

            @Nullable
            public final Object invokeSuspend(@NotNull Object obj) {
                Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                int i = this.a;
                if (i == 0) {
                    ResultKt.throwOnFailure(obj);
                    kotlinx.coroutines.flow.n0<PlayerPageState> z1 = this.b.getPlayerView().z1();
                    Intrinsics.checkNotNullExpressionValue(z1, "playerView.stateFlow");
                    kotlinx.coroutines.flow.g m = kotlinx.coroutines.flow.i.m(new b(z1));
                    C0165a c0165a = new C0165a(this.b);
                    this.a = 1;
                    if (m.collect(c0165a, this) == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                } else {
                    if (i != 1) {
                        throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                    }
                    ResultKt.throwOnFailure(obj);
                }
                return Unit.INSTANCE;
            }
        }

        @DebugMetadata(c = "com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder$1$2", f = "PlayerVinylViewHolder.kt", i = {}, l = {1059}, m = "invokeSuspend", n = {}, s = {})
        static final class b extends SuspendLambda implements Function2<n0, Continuation<? super Unit>, Object> {
            int a;
            final PlayerVinylViewHolder b;

            public static final class C0168a implements kotlinx.coroutines.flow.h<String> {
                final PlayerVinylViewHolder a;

                public C0168a(PlayerVinylViewHolder playerVinylViewHolder) {
                    this.a = playerVinylViewHolder;
                }

                @Nullable
                public Object emit(String str, @NotNull Continuation<? super Unit> continuation) {
                    String str2 = str;
                    com.netease.cloudmusic.module.playerstyle.b bVar = this.a.playerVinylHelper;
                    Unit unit = null;
                    if (bVar != null) {
                        com.netease.cloudmusic.module.playerstyle.b.d(bVar, str2, false, 2, null);
                        unit = Unit.INSTANCE;
                    }
                    return unit == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? unit : Unit.INSTANCE;
                }
            }

            public static final class C0169b implements kotlinx.coroutines.flow.g<String> {
                final kotlinx.coroutines.flow.g a;

                public static final class C0170a implements kotlinx.coroutines.flow.h<PlayerPageState> {
                    final kotlinx.coroutines.flow.h a;

                    @DebugMetadata(c = "com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder$1$2$invokeSuspend$$inlined$map$1$2", f = "PlayerVinylViewHolder.kt", i = {}, l = {137}, m = "emit", n = {}, s = {})
                    public static final class C0171a extends ContinuationImpl {
                        Object a;
                        int b;

                        public C0171a(Continuation continuation) {
                            super(continuation);
                        }

                        @Nullable
                        public final Object invokeSuspend(@NotNull Object obj) {
                            this.a = obj;
                            this.b |= Integer.MIN_VALUE;
                            return C0170a.this.emit(null, this);
                        }
                    }

                    public C0170a(kotlinx.coroutines.flow.h hVar) {
                        this.a = hVar;
                    }

                    @org.jetbrains.annotations.Nullable
                    public java.lang.Object emit(java.lang.Object r6, @org.jetbrains.annotations.NotNull kotlin.coroutines.Continuation r7) {
                        throw new UnsupportedOperationException("Method not decompiled: com.netease.cloudmusic.module.playerstyle.PlayerVinylViewHolder.a.b.C0169b.C0170a.emit(java.lang.Object, kotlin.coroutines.Continuation):java.lang.Object");
                    }
                }

                public C0169b(kotlinx.coroutines.flow.g gVar) {
                    this.a = gVar;
                }

                @Nullable
                public Object collect(@NotNull kotlinx.coroutines.flow.h hVar, @NotNull Continuation continuation) {
                    Object collect = this.a.collect(new C0170a(hVar), continuation);
                    return collect == IntrinsicsKt.getCOROUTINE_SUSPENDED() ? collect : Unit.INSTANCE;
                }
            }

            b(PlayerVinylViewHolder playerVinylViewHolder, Continuation<? super b> continuation) {
                super(2, continuation);
                this.b = playerVinylViewHolder;
            }

            @NotNull
            public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
                return new b(this.b, continuation);
            }

            @Nullable
            public final Object invoke(@NotNull n0 n0Var, @Nullable Continuation<? super Unit> continuation) {
                return create(n0Var, continuation).invokeSuspend(Unit.INSTANCE);
            }

            @Nullable
            public final Object invokeSuspend(@NotNull Object obj) {
                Object coroutine_suspended = IntrinsicsKt.getCOROUTINE_SUSPENDED();
                int i = this.a;
                if (i == 0) {
                    ResultKt.throwOnFailure(obj);
                    kotlinx.coroutines.flow.n0<PlayerPageState> z1 = this.b.getPlayerView().z1();
                    Intrinsics.checkNotNullExpressionValue(z1, "playerView.stateFlow");
                    kotlinx.coroutines.flow.g m = kotlinx.coroutines.flow.i.m(new C0169b(z1));
                    C0168a c0168a = new C0168a(this.b);
                    this.a = 1;
                    if (m.collect(c0168a, this) == coroutine_suspended) {
                        return coroutine_suspended;
                    }
                } else {
                    if (i != 1) {
                        throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
                    }
                    ResultKt.throwOnFailure(obj);
                }
                return Unit.INSTANCE;
            }
        }

        a(Continuation<? super a> continuation) {
            super(2, continuation);
        }

        @NotNull
        public final Continuation<Unit> create(@Nullable Object obj, @NotNull Continuation<?> continuation) {
            a aVar = PlayerVinylViewHolder.this.new a(continuation);
            aVar.b = obj;
            return aVar;
        }

        @Nullable
        public final Object invoke(@NotNull n0 n0Var, @Nullable Continuation<? super Unit> continuation) {
            return create(n0Var, continuation).invokeSuspend(Unit.INSTANCE);
        }

        @Nullable
        public final Object invokeSuspend(@NotNull Object obj) {
            IntrinsicsKt.getCOROUTINE_SUSPENDED();
            if (this.a != 0) {
                throw new IllegalStateException("call to 'resume' before 'invoke' with coroutine");
            }
            ResultKt.throwOnFailure(obj);
            n0 n0Var = (n0) this.b;
            kotlinx.coroutines.j.d(n0Var, (CoroutineContext) null, (p0) null, new C0164a(PlayerVinylViewHolder.this, null), 3, (Object) null);
            kotlinx.coroutines.j.d(n0Var, (CoroutineContext) null, (p0) null, new b(PlayerVinylViewHolder.this, null), 3, (Object) null);
            return Unit.INSTANCE;
        }
    }

    public static final class b implements GestureDetector.OnDoubleTapListener {
        b() {
        }

        @Override
        public boolean onDoubleTap(@NotNull MotionEvent e) {
            Intrinsics.checkNotNullParameter(e, "e");
            PlayerVinylViewHolder.this.getPlayerView().p4();
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(@NotNull MotionEvent e) {
            Intrinsics.checkNotNullParameter(e, "e");
            return false;
        }

        @Override
        public boolean onSingleTapConfirmed(@NotNull MotionEvent e) {
            Intrinsics.checkNotNullParameter(e, "e");
            return false;
        }
    }

    public static final class c implements PlayerDiscViewFlipper.OnPlayerDiscListener {
        c() {
        }

        public void manualScroll(boolean left) {
            xk.a aVar = xk.f;
            aVar.l((View) null);
            if (left) {
                aVar.i(PlayerVinylViewHolder.this.discSwitcher, "single", "left");
            } else {
                aVar.i(PlayerVinylViewHolder.this.discSwitcher, "single", "right");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("manualScroll left = ");
            sb.append(left);
        }

        public void onDiscDirectionChange(@Nullable Boolean isRight) {
            int i = isRight == null ? 1 : isRight.booleanValue() ? 0 : 2;
            if (PlayerVinylViewHolder.this.getPlayerView().X7(i) == 1) {
                View nextView = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                if (nextView == null) {
                    throw new NullPointerException("null cannot be cast to non-null type android.view.ViewGroup");
                }
                ((ViewGroup) nextView).getChildAt(0).setVisibility(8);
                View nextView2 = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                if (nextView2 == null) {
                    throw new NullPointerException("null cannot be cast to non-null type android.view.ViewGroup");
                }
                ((ViewGroup) nextView2).getChildAt(1).setVisibility(8);
            } else {
                View nextView3 = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                if (nextView3 == null) {
                    throw new NullPointerException("null cannot be cast to non-null type android.view.ViewGroup");
                }
                ((ViewGroup) nextView3).getChildAt(0).setVisibility(0);
                View nextView4 = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                if (nextView4 == null) {
                    throw new NullPointerException("null cannot be cast to non-null type android.view.ViewGroup");
                }
                ((ViewGroup) nextView4).getChildAt(1).setVisibility(0);
            }
            q playerView = PlayerVinylViewHolder.this.getPlayerView();
            View nextView5 = PlayerVinylViewHolder.this.discSwitcher.getNextView();
            if (nextView5 == null) {
                throw new NullPointerException("null cannot be cast to non-null type android.view.ViewGroup");
            }
            View childAt = ((ViewGroup) nextView5).getChildAt(0);
            if (childAt == null) {
                throw new NullPointerException("null cannot be cast to non-null type android.widget.ImageView");
            }
            playerView.qb((ImageView) childAt, PlayerVinylViewHolder.this.getPlayerView().A6(i), PlayerVinylViewHolder.this.getPlayerView().g8(i));
        }

        public void onDiscSwitchComplete(boolean restore, boolean justSwitchDisc, boolean isRight) {
            RotationRelativeLayout rotationRelativeLayout;
            PlayerVinylViewHolder.this.getPlayerView().j2(false);
            if (restore) {
                PlayerVinylViewHolder.this.getPlayerView().c6(1);
            } else {
                if (justSwitchDisc) {
                    PlayerVinylViewHolder playerVinylViewHolder = PlayerVinylViewHolder.this;
                    RotationRelativeLayout currentView = playerVinylViewHolder.discSwitcher.getCurrentView();
                    playerVinylViewHolder.disc = currentView instanceof RotationRelativeLayout ? currentView : null;
                    View nextView = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                    rotationRelativeLayout = nextView instanceof RotationRelativeLayout ? (RotationRelativeLayout) nextView : null;
                    if (rotationRelativeLayout != null) {
                        rotationRelativeLayout.stopAndRest();
                    }
                    RotationRelativeLayout rotationRelativeLayout2 = PlayerVinylViewHolder.this.disc;
                    if (rotationRelativeLayout2 != null) {
                        rotationRelativeLayout2.prepareAnimation();
                    }
                } else {
                    Handler a2 = PlayerVinylViewHolder.this.getPlayerView().a2();
                    if (a2 != null) {
                        a2.removeMessages(15);
                    }
                    PlayerVinylViewHolder.this.discSwitcher.setGestureEnable(false);
                    boolean isPlayQuickListen = PlayerVinylViewHolder.this.getPlayerView().l0().getPlayerControlState().isPlayQuickListen();
                    if (il0.h.b(PlayerVinylViewHolder.this.getPlayerView()) || isPlayQuickListen) {
                        z0.Q0(isRight ? "SLIDE_NEXT_IN_PLAY_PAGE" : "SLIDE_PREV_IN_PLAY_PAGE");
                        PlayerVinylViewHolder.this.getPlayerView().sendMessageToService(isRight ? 4 : 5, 1, 0, null);
                        if (iy0.c.a.c().b() || isPlayQuickListen) {
                            u72.g.a.c(isRight ? 2 : 1);
                        }
                    } else if (isRight) {
                        PlayerVinylViewHolder.this.getPlayerView().f3();
                    } else {
                        PlayerVinylViewHolder.this.getPlayerView().T5();
                    }
                    rx0.a.D("PlayerView: onDiscSwitchComplete, isRight = " + isRight);
                    PlayerVinylViewHolder playerVinylViewHolder2 = PlayerVinylViewHolder.this;
                    RotationRelativeLayout currentView2 = playerVinylViewHolder2.discSwitcher.getCurrentView();
                    playerVinylViewHolder2.disc = currentView2 instanceof RotationRelativeLayout ? currentView2 : null;
                    View nextView2 = PlayerVinylViewHolder.this.discSwitcher.getNextView();
                    rotationRelativeLayout = nextView2 instanceof RotationRelativeLayout ? (RotationRelativeLayout) nextView2 : null;
                    if (rotationRelativeLayout != null) {
                        rotationRelativeLayout.stopAndRest();
                    }
                    RotationRelativeLayout rotationRelativeLayout3 = PlayerVinylViewHolder.this.disc;
                    if (rotationRelativeLayout3 != null) {
                        rotationRelativeLayout3.prepareAnimation();
                    }
                    f0 oa = PlayerVinylViewHolder.this.getPlayerView().oa();
                    if (oa != null) {
                        oa.b();
                    }
                }
            }
            if (PlayerVinylViewHolder.this.e0() && restore) {
                PlayerVinylViewHolder.this.O();
            }
        }

        public void onDiscSwitchHalf(@Nullable Boolean isRight) {
            PlayerVinylViewHolder.this.getPlayerView().c6(isRight == null ? 1 : isRight.booleanValue() ? 0 : 2);
        }

        public void onScrolled(boolean isHorizontalSlide, boolean byUser) {
            RelativeLayout relativeLayout = PlayerVinylViewHolder.this.artistImageContainer;
            boolean z = false;
            if (relativeLayout != null && relativeLayout.getVisibility() == 8) {
                return;
            }
            RelativeLayout relativeLayout2 = PlayerVinylViewHolder.this.mDiskView;
            if (relativeLayout2 != null && relativeLayout2.getVisibility() == 8) {
                z = true;
            }
            if (z) {
                return;
            }
            if ((!byUser || isHorizontalSlide) && !PlayerVinylViewHolder.this.noAnimGestureEnable) {
                xk.f.s(PlayerVinylViewHolder.this.discSwitcher);
                PlayerVinylViewHolder.this.P();
                PlayerVinylViewHolder.this.getPlayerView().j2(true);
                RotationRelativeLayout rotationRelativeLayout = PlayerVinylViewHolder.this.disc;
                if (rotationRelativeLayout != null) {
                    rotationRelativeLayout.pause();
                }
            }
        }

        public void onScrolling(boolean scroll) {
            Object D1 = PlayerVinylViewHolder.this.getPlayerView().D1();
            sr srVar = D1 instanceof sr ? (sr) D1 : null;
            if (srVar != null) {
                srVar.b(scroll);
            }
        }
    }

    public static final class d extends BroadcastReceiver {
        d() {
        }

        @Override
        public void onReceive(@NotNull Context context, @NotNull Intent intent) {
            Intrinsics.checkNotNullParameter(context, "context");
            Intrinsics.checkNotNullParameter(intent, "intent");
            long j = PlayerVinylViewHolder.this.mVisualizerId;
            PlayerVinylViewHolder.this.mVisualizerId = intent.getLongExtra("visualizer_id", -1L);
            if (sf2.a.b() || x1.a.j()) {
                PlayerVinylViewHolder.this.getPlayerView().e8();
            }
            PlayerVinylViewHolder.this.L0(j);
        }
    }

    public static final class f extends GestureDetector.SimpleOnGestureListener {
        f() {
        }

        @Override
        public boolean onDoubleTap(@NotNull MotionEvent e) {
            Intrinsics.checkNotNullParameter(e, "e");
            PlayerVinylViewHolder.this.getPlayerView().p4();
            return true;
        }

        @Override
        public boolean onFling(@Nullable MotionEvent e1, @NotNull MotionEvent e2, float velocityX, float velocityY) {
            Intrinsics.checkNotNullParameter(e2, "e2");
            if (ua0.z0.E()) {
                return false;
            }
            if (velocityX > 0.0f) {
                PlayerVinylViewHolder.this.getPlayerView().X3().performClick();
                return true;
            }
            if (velocityX >= 0.0f) {
                return true;
            }
            PlayerVinylViewHolder.this.getPlayerView().W3().performClick();
            return true;
        }

        @Override
        public void onLongPress(@NotNull MotionEvent e) {
            MusicInfo currentMusic;
            Intrinsics.checkNotNullParameter(e, "e");
            com.netease.cloudmusic.module.listentogether.im.k P9 = PlayerVinylViewHolder.this.getPlayerView().P9();
            boolean z = false;
            if (P9 != null && P9.q0()) {
                z = true;
            }
            if (z || (currentMusic = PlayerVinylViewHolder.this.getPlayerView().getCurrentMusic()) == null) {
                return;
            }
            String albumCoverUrl = currentMusic.getAlbumCoverUrl();
            if (TextUtils.isEmpty(albumCoverUrl)) {
                albumCoverUrl = currentMusic.getLocalAlbumCoverUrl();
            }
            lu.a.c(PlayerVinylViewHolder.this.getPlayerView(), albumCoverUrl);
        }

        @Override
        public boolean onSingleTapConfirmed(@NotNull MotionEvent e) {
            Intrinsics.checkNotNullParameter(e, "e");
            PlayerVinylViewHolder.this.getPlayerView().G7();
            return true;
        }
    }

    public static final class g implements Runnable {
        g() {
        }

        @Override
        public void run() {
            if (PlayerVinylViewHolder.this.discHeadStatus == 1 || PlayerVinylViewHolder.this.discHeadStatus == 3) {
                Handler a2 = PlayerVinylViewHolder.this.getPlayerView().a2();
                if (a2 != null) {
                    a2.postDelayed(this, 50L);
                    return;
                }
                return;
            }
            if (PlayerVinylViewHolder.this.discHeadStatus == 2) {
                ImageView imageView = PlayerVinylViewHolder.this.discHead;
                if (imageView == null) {
                    return;
                }
                imageView.setRotation(0.0f);
                return;
            }
            bn0.k kVar = PlayerVinylViewHolder.this.mPlayerStoryGuideAnimationHelper;
            if (!(kVar != null && kVar.getIsInStoryGuide()) || PlayerVinylViewHolder.this.mPlayerStoryGuideAnimationHelper.getIsPostMessage()) {
                ObjectAnimator objectAnimator = PlayerVinylViewHolder.this.discHeadDownRotateAnimation;
                if (objectAnimator == null) {
                    Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRotateAnimation");
                    objectAnimator = null;
                }
                objectAnimator.start();
            }
        }
    }

    public static final class h implements Runnable {
        h() {
        }

        @Override
        public void run() {
            if (PlayerVinylViewHolder.this.discHeadStatus == 1 || PlayerVinylViewHolder.this.discHeadStatus == 3) {
                Handler a2 = PlayerVinylViewHolder.this.getPlayerView().a2();
                if (a2 != null) {
                    a2.postDelayed(this, 50L);
                    return;
                }
                return;
            }
            if (PlayerVinylViewHolder.this.discHeadStatus == 4) {
                ImageView imageView = PlayerVinylViewHolder.this.discHead;
                if (imageView == null) {
                    return;
                }
                imageView.setRotation(-25.0f);
                return;
            }
            bn0.k kVar = PlayerVinylViewHolder.this.mPlayerStoryGuideAnimationHelper;
            if (!(kVar != null && kVar.getIsInStoryGuide()) || PlayerVinylViewHolder.this.mPlayerStoryGuideAnimationHelper.getIsPostMessage()) {
                ObjectAnimator objectAnimator = PlayerVinylViewHolder.this.discHeadUpRotateAnimation;
                if (objectAnimator == null) {
                    Intrinsics.throwUninitializedPropertyAccessException("discHeadUpRotateAnimation");
                    objectAnimator = null;
                }
                objectAnimator.start();
            }
        }
    }

    public static final class i implements Animator.AnimatorListener {
        public i(PlayerVinylViewHolder playerVinylViewHolder) {
        }

        @Override
        public void onAnimationCancel(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
        }

        @Override
        public void onAnimationEnd(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
            PlayerVinylViewHolder.this.discHeadStatus = 4;
            RotationRelativeLayout rotationRelativeLayout = PlayerVinylViewHolder.this.disc;
            if (rotationRelativeLayout != null) {
                rotationRelativeLayout.pause();
            }
            ImageView imageView = PlayerVinylViewHolder.this.discHead;
            if (imageView != null) {
                imageView.setPivotX(PlayerVinylViewHolder.this.playerDiscNeedleOffsetX);
            }
            ImageView imageView2 = PlayerVinylViewHolder.this.discHead;
            if (imageView2 == null) {
                return;
            }
            imageView2.setPivotY(PlayerVinylViewHolder.this.playerDiscNeedleOffsetY);
        }

        @Override
        public void onAnimationRepeat(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
        }

        @Override
        public void onAnimationStart(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
            PlayerVinylViewHolder.this.discHeadStatus = 3;
        }
    }

    public static final class j implements Animator.AnimatorListener {
        public j(PlayerVinylViewHolder playerVinylViewHolder) {
        }

        @Override
        public void onAnimationCancel(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
        }

        @Override
        public void onAnimationEnd(@NotNull Animator animator) {
            RotationRelativeLayout.AnimationHolder animationHolder;
            Intrinsics.checkParameterIsNotNull(animator, "animator");
            PlayerVinylViewHolder.this.discHeadStatus = 2;
            RotationRelativeLayout rotationRelativeLayout = PlayerVinylViewHolder.this.disc;
            if (rotationRelativeLayout != null && (animationHolder = rotationRelativeLayout.getAnimationHolder()) != null) {
                Intrinsics.checkNotNullExpressionValue(animationHolder, "animationHolder");
                PlayerVinylViewHolder.this.getPlayerView().Fb(animationHolder);
            }
            ImageView imageView = PlayerVinylViewHolder.this.discHead;
            if (imageView != null) {
                imageView.setPivotX(PlayerVinylViewHolder.this.playerDiscNeedleOffsetX);
            }
            ImageView imageView2 = PlayerVinylViewHolder.this.discHead;
            if (imageView2 == null) {
                return;
            }
            imageView2.setPivotY(PlayerVinylViewHolder.this.playerDiscNeedleOffsetY);
        }

        @Override
        public void onAnimationRepeat(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
        }

        @Override
        public void onAnimationStart(@NotNull Animator animator) {
            Intrinsics.checkParameterIsNotNull(animator, "animator");
            PlayerVinylViewHolder.this.discHeadStatus = 1;
        }
    }

    public PlayerVinylViewHolder(@NotNull q qVar, @NotNull Function2<? super Integer, ? super Integer, Unit> function2) {
        Intrinsics.checkNotNullParameter(qVar, "playerView");
        Intrinsics.checkNotNullParameter(function2, "afterAdjustUI");
        this.playerView = qVar;
        this.afterAdjustUI = function2;
        this.mVisualizerId = -1L;
        this.discHeadStatus = 2;
        this.discHeadDownDuration = 300;
        this.canScrollLeft = true;
        this.canScrollRight = true;
        this.playState = 1;
        PlayerSeekBarNew Lb = qVar.Lb(xj1.f.H7);
        this.playSeekBar = Lb instanceof PlayerSeekBarNew ? Lb : null;
        A0();
        this.mPlayerStoryGuideAnimationHelper = new bn0.k(qVar);
        Long l = (Long) ey0.c.r("ACTION_AUDIO_EFFECT_SWITCHER_getCurrAudioAnimId", new Object[0]);
        this.mVisualizerId = l != null ? l.longValue() : -1L;
        View Lb2 = qVar.Lb(xj1.f.J);
        this.artistImageContainer = Lb2 instanceof RelativeLayout ? (RelativeLayout) Lb2 : null;
        View Lb3 = qVar.Lb(xj1.f.P1);
        this.mDiskView = Lb3 instanceof RelativeLayout ? (RelativeLayout) Lb3 : null;
        PlayerDiscViewFlipper Lb4 = qVar.Lb(xj1.f.O1);
        PlayerDiscViewFlipper playerDiscViewFlipper = Lb4 instanceof PlayerDiscViewFlipper ? Lb4 : null;
        this.discSwitcher = playerDiscViewFlipper;
        View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
        this.disc = currentView instanceof RotationRelativeLayout ? (RotationRelativeLayout) currentView : null;
        View Lb5 = qVar.Lb(xj1.f.D7);
        ImageView imageView = Lb5 instanceof ImageView ? (ImageView) Lb5 : null;
        this.playDiscBg = imageView;
        int i2 = xj1.f.Ia;
        View Lb6 = qVar.Lb(i2);
        ImageView imageView2 = Lb6 instanceof ImageView ? (ImageView) Lb6 : null;
        this.smallAlbumDisc0 = imageView2;
        int i3 = xj1.f.La;
        View Lb7 = qVar.Lb(i3);
        ImageView imageView3 = Lb7 instanceof ImageView ? (ImageView) Lb7 : null;
        this.smallAlbumDisc1 = imageView3;
        AnimableDrawerImageView Lb8 = qVar.Lb(xj1.f.Ga);
        AnimableDrawerImageView animableDrawerImageView = Lb8 instanceof AnimableDrawerImageView ? Lb8 : null;
        this.smallAlbumCover0 = animableDrawerImageView;
        AnimableDrawerImageView Lb9 = qVar.Lb(xj1.f.Ha);
        AnimableDrawerImageView animableDrawerImageView2 = Lb9 instanceof AnimableDrawerImageView ? Lb9 : null;
        this.smallAlbumCover1 = animableDrawerImageView2;
        View Lb10 = qVar.Lb(xj1.f.N1);
        ImageView imageView4 = Lb10 instanceof ImageView ? (ImageView) Lb10 : null;
        this.discHead = imageView4;
        CustomThemeRelativeLayout Lb11 = qVar.Lb(xj1.f.q2);
        this.fakeBackHistory = Lb11 instanceof CustomThemeRelativeLayout ? Lb11 : null;
        NeteaseMusicSimpleDraweeView Lb12 = qVar.Lb(xj1.f.r2);
        this.fakeVideoCover = Lb12 instanceof NeteaseMusicSimpleDraweeView ? Lb12 : null;
        View Lb13 = qVar.Lb(xj1.f.s2);
        this.fakeVideoCoverMask = Lb13 instanceof ImageView ? (ImageView) Lb13 : null;
        if (!bl.a.q()) {
            RotationRelativeLayout Lb14 = qVar.Lb(xj1.f.L1);
            RotationRelativeLayout rotationRelativeLayout = Lb14 instanceof RotationRelativeLayout ? Lb14 : null;
            if (rotationRelativeLayout != null) {
                rotationRelativeLayout.setStableViewIds(new int[]{i2});
            }
            RotationRelativeLayout Lb15 = qVar.Lb(xj1.f.M1);
            RotationRelativeLayout rotationRelativeLayout2 = Lb15 instanceof RotationRelativeLayout ? Lb15 : null;
            if (rotationRelativeLayout2 != null) {
                rotationRelativeLayout2.setStableViewIds(new int[]{i3});
            }
        }
        if (qVar.getActivity() != null && imageView4 != null && imageView != null && imageView3 != null && imageView2 != null && animableDrawerImageView != null && animableDrawerImageView2 != null) {
            FragmentActivity activity = qVar.getActivity();
            Intrinsics.checkNotNull(activity);
            this.playerVinylHelper = new com.netease.cloudmusic.module.playerstyle.b(activity, imageView4, imageView, imageView2, imageView3, animableDrawerImageView, animableDrawerImageView2);
        }
        kotlinx.coroutines.j.d(LifecycleOwnerKt.getLifecycleScope(qVar.w3()), (CoroutineContext) null, (p0) null, new a(null), 3, (Object) null);
        long j2 = this.mVisualizerId;
        if (j2 != -1) {
            K(j2);
            RelativeLayout mDiskView = getMDiskView();
            if (mDiskView != null) {
                mDiskView.setVisibility(8);
            }
        }
        if (playerDiscViewFlipper != null) {
            playerDiscViewFlipper.setOnClickListener(new View.OnClickListener() {
                @Override
                public final void onClick(View view) {
                    PlayerVinylViewHolder.g(PlayerVinylViewHolder.this, view);
                }
            });
        }
        if (playerDiscViewFlipper != null) {
            playerDiscViewFlipper.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public final boolean onLongClick(View view) {
                    boolean h2;
                    h2 = PlayerVinylViewHolder.h(PlayerVinylViewHolder.this, view);
                    return h2;
                }
            });
        }
        if (playerDiscViewFlipper != null) {
            playerDiscViewFlipper.setOnDoubleTapListener(new b());
        }
        if (playerDiscViewFlipper != null) {
            playerDiscViewFlipper.setOnPlayerDiscListener(new c());
        }
        if (playerDiscViewFlipper != null) {
            playerDiscViewFlipper.setPlayerDiscGestureListener(this);
        }
        d dVar = new d();
        this.mVisualizerReceiver = dVar;
        FragmentActivity activity2 = qVar.getActivity();
        if (activity2 != null) {
            LocalBroadcastManager.getInstance(activity2).registerReceiver(dVar, new IntentFilter("com.netease.cloudmusic.action.CHANGE_VISUALIZER"));
        }
    }

    private final void A0() {
        int i2;
        try {
            i2 = u0.a.v();
        } catch (IllegalStateException unused) {
            i2 = 1;
        }
        this.playState = i2;
    }

    private final void H(View artView) {
        if (artView == null) {
            return;
        }
        float w = z4.w(this.playerView.getActivity());
        float f2 = z4.b;
        if (f2 < w) {
            float f3 = w / f2;
            ViewGroup.LayoutParams layoutParams = artView.getLayoutParams();
            layoutParams.width = (int) (layoutParams.width * f3);
            layoutParams.height = (int) (layoutParams.height * f3);
        }
    }

    private final void I() {
        ViewGroup.LayoutParams layoutParams;
        ViewGroup.LayoutParams layoutParams2;
        Resources resources;
        Resources resources2;
        float f2 = z4.f(this.playerView.getActivity());
        FragmentActivity activity = this.playerView.getActivity();
        int i2 = 0;
        this.playerDiscNeedleOffsetX = (activity == null || (resources2 = activity.getResources()) == null) ? 0 : resources2.getDimensionPixelSize(xj1.c.r);
        FragmentActivity activity2 = this.playerView.getActivity();
        this.playerDiscNeedleOffsetY = (activity2 == null || (resources = activity2.getResources()) == null) ? 0 : resources.getDimensionPixelSize(xj1.c.s);
        int X = X();
        ImageView imageView = this.discHead;
        zk.h(imageView, imageView != null ? imageView.getDrawable() : null, f2);
        ImageView imageView2 = this.playDiscBg;
        FragmentActivity activity3 = this.playerView.getActivity();
        zk.h(imageView2, activity3 != null ? activity3.getDrawable(xj1.d.f1) : null, f2);
        ImageView imageView3 = this.smallAlbumDisc0;
        FragmentActivity activity4 = this.playerView.getActivity();
        zk.h(imageView3, activity4 != null ? activity4.getDrawable(xj1.d.g1) : null, f2);
        ImageView imageView4 = this.smallAlbumDisc1;
        FragmentActivity activity5 = this.playerView.getActivity();
        zk.h(imageView4, activity5 != null ? activity5.getDrawable(xj1.d.g1) : null, f2);
        AnimableDrawerImageView animableDrawerImageView = this.smallAlbumCover0;
        FragmentActivity activity6 = this.playerView.getActivity();
        zk.h(animableDrawerImageView, activity6 != null ? activity6.getDrawable(xj1.d.G0) : null, f2);
        AnimableDrawerImageView animableDrawerImageView2 = this.smallAlbumCover1;
        FragmentActivity activity7 = this.playerView.getActivity();
        zk.h(animableDrawerImageView2, activity7 != null ? activity7.getDrawable(xj1.d.G0) : null, f2);
        zk zkVar = zk.a;
        int t = zkVar.t(this.playerView.getActivity(), X);
        ImageView imageView5 = this.playDiscBg;
        ViewGroup.LayoutParams layoutParams3 = imageView5 != null ? imageView5.getLayoutParams() : null;
        RelativeLayout.LayoutParams layoutParams4 = layoutParams3 instanceof RelativeLayout.LayoutParams ? (RelativeLayout.LayoutParams) layoutParams3 : null;
        if (layoutParams4 != null) {
            layoutParams4.topMargin = t;
        }
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        Object layoutParams5 = playerDiscViewFlipper != null ? playerDiscViewFlipper.getLayoutParams() : null;
        RelativeLayout.LayoutParams layoutParams6 = layoutParams5 instanceof RelativeLayout.LayoutParams ? (RelativeLayout.LayoutParams) layoutParams5 : null;
        if (layoutParams6 != null) {
            layoutParams6.topMargin = t;
        }
        zkVar.a(this.playerView.getActivity(), this.discHead, X);
        ImageView imageView6 = this.discHead;
        this.playerDiscNeedleOffsetX = ((imageView6 == null || (layoutParams2 = imageView6.getLayoutParams()) == null) ? 0 : layoutParams2.width) / 2;
        this.playerDiscNeedleOffsetY = (int) (this.playerDiscNeedleOffsetY * f2 * zk.l());
        Function2<Integer, Integer, Unit> function2 = this.afterAdjustUI;
        Integer valueOf = Integer.valueOf(t);
        ImageView imageView7 = this.playDiscBg;
        if (imageView7 != null && (layoutParams = imageView7.getLayoutParams()) != null) {
            i2 = layoutParams.width;
        }
        function2.invoke(valueOf, Integer.valueOf(i2));
        J();
    }

    private final void J() {
        ViewGroup viewGroup = this.mVisualizerView;
        if (viewGroup != null) {
            ViewGroup.LayoutParams layoutParams = viewGroup.getLayoutParams();
            layoutParams.height = Y();
            viewGroup.setLayoutParams(layoutParams);
        }
    }

    public static final Map L() {
        HashMap hashMap = new HashMap();
        hashMap.put("s_cid", n7.p0.I());
        return hashMap;
    }

    public final void R() {
        ImageView imageView = this.discHead;
        if (imageView != null) {
            imageView.clearAnimation();
        }
        this.discHeadStatus = 2;
        zk.D(this.playerView.getActivity(), X());
        RelativeLayout relativeLayout = this.artistImageContainer;
        ViewGroup.LayoutParams layoutParams = relativeLayout != null ? relativeLayout.getLayoutParams() : null;
        RelativeLayout.LayoutParams layoutParams2 = layoutParams instanceof RelativeLayout.LayoutParams ? (RelativeLayout.LayoutParams) layoutParams : null;
        if (layoutParams2 != null) {
            layoutParams2.bottomMargin = tl.r(this.playerView.getActivity());
        }
        I();
        ImageView imageView2 = this.discHead;
        Object layoutParams3 = imageView2 != null ? imageView2.getLayoutParams() : null;
        RelativeLayout.LayoutParams layoutParams4 = layoutParams3 instanceof RelativeLayout.LayoutParams ? (RelativeLayout.LayoutParams) layoutParams3 : null;
        if (layoutParams4 != null) {
            layoutParams4.removeRule(7);
            layoutParams4.addRule(14);
        }
        v0();
        Q();
    }

    private final int X() {
        return al.a.F(this.playerView.getActivity());
    }

    @SuppressLint({"ForbidDeprecatedUsageError"})
    private final int Y() {
        Resources resources;
        ViewGroup.LayoutParams layoutParams;
        ImageView imageView = this.smallAlbumDisc0;
        int i2 = (imageView == null || (layoutParams = imageView.getLayoutParams()) == null) ? 0 : layoutParams.height;
        if (i2 <= 0) {
            ImageView imageView2 = this.smallAlbumDisc0;
            Drawable drawable = imageView2 != null ? imageView2.getDrawable() : null;
            if (drawable == null) {
                FragmentActivity activity = this.playerView.getActivity();
                drawable = (activity == null || (resources = activity.getResources()) == null) ? null : resources.getDrawable(s1.z7);
            }
            if (drawable != null) {
                i2 = drawable.getIntrinsicWidth();
            }
        }
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        Object layoutParams2 = playerDiscViewFlipper != null ? playerDiscViewFlipper.getLayoutParams() : null;
        RelativeLayout.LayoutParams layoutParams3 = layoutParams2 instanceof RelativeLayout.LayoutParams ? (RelativeLayout.LayoutParams) layoutParams2 : null;
        return i2 + (layoutParams3 != null ? layoutParams3.topMargin * 2 : 0);
    }

    private final boolean d0() {
        int i2 = this.playState;
        return (i2 == 1 || i2 == 2 || !f0()) ? false : true;
    }

    public final boolean e0() {
        return this.playState == 3 && f0();
    }

    private final boolean f0() {
        return il0.h.b(this.playerView) || (il0.h.a(this.playerView) && fq0.b.a.l());
    }

    public static final void g(PlayerVinylViewHolder playerVinylViewHolder, View view) {
        xj.a.K(view);
        Intrinsics.checkNotNullParameter(playerVinylViewHolder, "this$0");
        playerVinylViewHolder.playerView.G7();
        xj.a.N(view);
    }

    public static final boolean h(PlayerVinylViewHolder playerVinylViewHolder, View view) {
        Intrinsics.checkNotNullParameter(playerVinylViewHolder, "this$0");
        return playerVinylViewHolder.playerView.F4();
    }

    private final void u0(Boolean canScrollLeft, Boolean canScrollRight) {
        JSONObject jSONObject = new JSONObject();
        if (canScrollLeft != null) {
            jSONObject.put("canSwitchToPreviousSong", canScrollLeft.booleanValue());
        }
        if (canScrollRight != null) {
            jSONObject.put("canSwitchToNextSong", canScrollRight.booleanValue());
        }
        Context activity = this.playerView.getActivity();
        if (activity != null) {
            v1.INSTANCE.h(activity, jSONObject);
        }
        k1.INSTANCE.f().post(new Pair(canScrollLeft, canScrollRight));
    }

    private final void v0() {
        ObjectAnimator ofFloat = ObjectAnimator.ofFloat(this.discHead, "rotation", 0.0f, -25.0f);
        ofFloat.setDuration(this.discHeadDownDuration);
        Intrinsics.checkNotNullExpressionValue(ofFloat, "");
        ofFloat.addListener(new i(this));
        Intrinsics.checkNotNullExpressionValue(ofFloat, "ofFloat(discHead, \"rotat…}\n            )\n        }");
        this.discHeadUpRotateAnimation = ofFloat;
        ObjectAnimator ofFloat2 = ObjectAnimator.ofFloat(this.discHead, "rotation", -25.0f, 0.0f);
        ofFloat2.setDuration(this.discHeadDownDuration);
        Intrinsics.checkNotNullExpressionValue(ofFloat2, "");
        ofFloat2.addListener(new j(this));
        Intrinsics.checkNotNullExpressionValue(ofFloat2, "ofFloat(discHead, \"rotat…}\n            )\n        }");
        this.discHeadDownRotateAnimation = ofFloat2;
        ImageView imageView = this.discHead;
        if (imageView != null) {
            imageView.setPivotX(this.playerDiscNeedleOffsetX);
        }
        ImageView imageView2 = this.discHead;
        if (imageView2 != null) {
            imageView2.setPivotY(this.playerDiscNeedleOffsetY);
        }
        this.discHeadDownRunnable = new g();
        this.discHeadUpRunnable = new h();
    }

    @Override
    @Nullable
    public CustomThemeRelativeLayout getFakeBackHistory() {
        return this.fakeBackHistory;
    }

    @Override
    public int getPlayerDiscNeedleOffsetY() {
        return this.playerDiscNeedleOffsetY;
    }

    @Override
    @Nullable
    public ViewGroup getMVisualizerView() {
        return this.mVisualizerView;
    }

    @Override
    @Nullable
    public ImageView getFakeVideoCoverMask() {
        return this.fakeVideoCoverMask;
    }

    public void K(long visualizerId) {
        this.mVisualizerView = (ViewGroup) ey0.c.r("ACTION_VISUALIZER_VisualizerView_getInstance", new Object[]{this.playerView.getActivity()});
        p7.b.b.d(this.mVisualizerView).e("cell_songplay_musiceffect").d(true).s("mod_songplay_record", "", new d.a().a()).b().p("musiceffect").r(new p() {
            public final Map getViewDynamicParams() {
                Map L;
                L = PlayerVinylViewHolder.L();
                return L;
            }
        });
        ViewGroup viewGroup = this.mVisualizerView;
        if (viewGroup != null) {
            viewGroup.setId(xj1.f.X8);
        }
        PlayerSeekBarNew playerSeekBarNew = this.playSeekBar;
        if (playerSeekBarNew != null) {
            playerSeekBarNew.resetColor();
        }
        int Y = Y();
        RelativeLayout relativeLayout = this.artistImageContainer;
        if (relativeLayout != null) {
            relativeLayout.addView(this.mVisualizerView, new ViewGroup.LayoutParams(-1, Y));
        }
        ey0.c.q("ACTION_VISUALIZER_VisualizerView_setOnGestureListener", new Object[]{this.mVisualizerView, new f()});
        ey0.c.q("ACTION_VISUALIZER_VisualizerView_setVisualizer", new Object[]{this.mVisualizerView});
        ey0.c.q("ACTION_VISUALIZER_VisualizerView_setRender", new Object[]{this.mVisualizerView, Long.valueOf(this.mVisualizerId)});
        ey0.c.q("ACTION_VISUALIZER_VisualizerView_setPlayerDiscGestureListener", new Object[]{this.mVisualizerView, this});
        H((View) ey0.c.r("ACTION_VISUALIZER_VisualizerView_getArtView", new Object[]{this.mVisualizerView}));
    }

    @Override
    public void L0(long oldVisualizerId) {
        Window window;
        View decorView;
        View rootView;
        com.netease.cloudmusic.module.listentogether.im.k P9;
        if (this.mVisualizerId == -1) {
            ViewGroup viewGroup = this.mVisualizerView;
            if (viewGroup != null) {
                ey0.c.q("ACTION_VISUALIZER_VisualizerView_pause", new Object[]{viewGroup});
                t0(false, false);
                ViewGroup viewGroup2 = this.mVisualizerView;
                if (viewGroup2 != null) {
                    viewGroup2.setVisibility(8);
                }
            }
            RelativeLayout relativeLayout = this.mDiskView;
            if (relativeLayout != null) {
                relativeLayout.setVisibility(0);
            }
            y0();
            PlayerSeekBarNew playerSeekBarNew = this.playSeekBar;
            if (playerSeekBarNew != null) {
                playerSeekBarNew.resetColor();
                return;
            }
            return;
        }
        RelativeLayout relativeLayout2 = this.mDiskView;
        if (relativeLayout2 != null) {
            relativeLayout2.setVisibility(8);
        }
        b0();
        ViewGroup viewGroup3 = this.mVisualizerView;
        if (viewGroup3 == null) {
            K(this.mVisualizerId);
            FragmentActivity activity = this.playerView.getActivity();
            if (activity != null && (window = activity.getWindow()) != null && (decorView = window.getDecorView()) != null && (rootView = decorView.getRootView()) != null && (P9 = this.playerView.P9()) != null) {
                P9.i(rootView);
            }
        } else {
            long j2 = this.mVisualizerId;
            if (oldVisualizerId != j2) {
                ey0.c.q("ACTION_VISUALIZER_VisualizerView_setRender", new Object[]{viewGroup3, Long.valueOf(j2)});
                H((View) ey0.c.r("ACTION_VISUALIZER_VisualizerView_getArtView", new Object[]{this.mVisualizerView}));
            }
        }
        ViewGroup viewGroup4 = this.mVisualizerView;
        if (viewGroup4 != null) {
            viewGroup4.setVisibility(0);
        }
        g0(null, null);
    }

    public void O() {
        Handler a2 = this.playerView.a2();
        Runnable runnable = null;
        if (a2 != null) {
            Runnable runnable2 = this.discHeadUpRunnable;
            if (runnable2 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadUpRunnable");
                runnable2 = null;
            }
            a2.removeCallbacks(runnable2);
        }
        Handler a22 = this.playerView.a2();
        if (a22 != null) {
            Runnable runnable3 = this.discHeadDownRunnable;
            if (runnable3 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRunnable");
                runnable3 = null;
            }
            a22.removeCallbacks(runnable3);
        }
        Handler a23 = this.playerView.a2();
        if (a23 != null) {
            Runnable runnable4 = this.discHeadDownRunnable;
            if (runnable4 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRunnable");
            } else {
                runnable = runnable4;
            }
            a23.post(runnable);
        }
    }

    public void P() {
        Handler a2 = this.playerView.a2();
        Runnable runnable = null;
        if (a2 != null) {
            Runnable runnable2 = this.discHeadUpRunnable;
            if (runnable2 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadUpRunnable");
                runnable2 = null;
            }
            a2.removeCallbacks(runnable2);
        }
        Handler a22 = this.playerView.a2();
        if (a22 != null) {
            Runnable runnable3 = this.discHeadDownRunnable;
            if (runnable3 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRunnable");
                runnable3 = null;
            }
            a22.removeCallbacks(runnable3);
        }
        Handler a23 = this.playerView.a2();
        if (a23 != null) {
            Runnable runnable4 = this.discHeadUpRunnable;
            if (runnable4 == null) {
                Intrinsics.throwUninitializedPropertyAccessException("discHeadUpRunnable");
            } else {
                runnable = runnable4;
            }
            a23.post(runnable);
        }
    }

    public void Q() {
        RotationRelativeLayout.AnimationHolder animationHolder;
        RelativeLayout relativeLayout = this.mDiskView;
        boolean z = false;
        if (relativeLayout != null && relativeLayout.getVisibility() == 0) {
            z = true;
        }
        if (z) {
            if (!e0()) {
                if (d0()) {
                    return;
                }
                ImageView imageView = this.discHead;
                if (imageView != null) {
                    imageView.setRotation(-25.0f);
                }
                P();
                return;
            }
            RotationRelativeLayout rotationRelativeLayout = this.disc;
            if (rotationRelativeLayout != null && (animationHolder = rotationRelativeLayout.getAnimationHolder()) != null) {
                this.playerView.Fb(animationHolder);
            }
            if (this.discHeadStatus != 2) {
                ImageView imageView2 = this.discHead;
                if (imageView2 != null) {
                    imageView2.setRotation(0.0f);
                }
                O();
            }
        }
    }

    public final int S(int originMargin) {
        FragmentActivity activity;
        return (ua0.z0.A() || (activity = this.playerView.getActivity()) == null) ? originMargin : zk.a.j(activity, X());
    }

    @Nullable
    public RotationRelativeLayout T() {
        if (this.disc == null) {
            PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
            View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
            this.disc = currentView instanceof RotationRelativeLayout ? (RotationRelativeLayout) currentView : null;
        }
        return this.disc;
    }

    @Override
    @Nullable
    public ImageView getPlayDiscBg() {
        return this.playDiscBg;
    }

    @NotNull
    public final q getPlayerView() {
        return this.playerView;
    }

    public final void Z(@NotNull Message msg) {
        Intrinsics.checkNotNullParameter(msg, "msg");
        if (msg.what == 5226) {
            this.playState = msg.arg1;
        }
    }

    @Override
    public boolean getCanScrollLeft() {
        return this.canScrollLeft;
    }

    public final void a0() {
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
        ViewGroup viewGroup = currentView instanceof ViewGroup ? (ViewGroup) currentView : null;
        View childAt = viewGroup != null ? viewGroup.getChildAt(0) : null;
        if (childAt != null) {
            childAt.setVisibility(8);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper2 = this.discSwitcher;
        View currentView2 = playerDiscViewFlipper2 != null ? playerDiscViewFlipper2.getCurrentView() : null;
        ViewGroup viewGroup2 = currentView2 instanceof ViewGroup ? (ViewGroup) currentView2 : null;
        View childAt2 = viewGroup2 != null ? viewGroup2.getChildAt(1) : null;
        if (childAt2 != null) {
            childAt2.setVisibility(8);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper3 = this.discSwitcher;
        View nextView = playerDiscViewFlipper3 != null ? playerDiscViewFlipper3.getNextView() : null;
        ViewGroup viewGroup3 = nextView instanceof ViewGroup ? (ViewGroup) nextView : null;
        View childAt3 = viewGroup3 != null ? viewGroup3.getChildAt(0) : null;
        if (childAt3 != null) {
            childAt3.setVisibility(8);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper4 = this.discSwitcher;
        View nextView2 = playerDiscViewFlipper4 != null ? playerDiscViewFlipper4.getNextView() : null;
        ViewGroup viewGroup4 = nextView2 instanceof ViewGroup ? (ViewGroup) nextView2 : null;
        View childAt4 = viewGroup4 != null ? viewGroup4.getChildAt(1) : null;
        if (childAt4 == null) {
            return;
        }
        childAt4.setVisibility(8);
    }

    @Override
    public boolean getNoAnimGestureEnable() {
        return this.noAnimGestureEnable;
    }

    public void b0() {
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
        RotationRelativeLayout rotationRelativeLayout = currentView instanceof RotationRelativeLayout ? (RotationRelativeLayout) currentView : null;
        this.disc = rotationRelativeLayout;
        if (rotationRelativeLayout != null) {
            rotationRelativeLayout.stop();
        }
    }

    @Override
    public boolean getCanScrollRight() {
        return this.canScrollRight;
    }

    public void c0(boolean shouldHidePreAHint) {
        bn0.k kVar = this.mPlayerStoryGuideAnimationHelper;
        if (kVar != null) {
            kVar.f();
        }
    }

    @Override
    public void db() {
        RotationRelativeLayout rotationRelativeLayout = this.disc;
        if (rotationRelativeLayout != null) {
            rotationRelativeLayout.pause();
        }
    }

    @Override
    public void e7(@Nullable Boolean canScrollLeft, @Nullable Boolean canScrollRight, @Nullable Boolean noAnimGestureEnable) {
        boolean z = ((canScrollLeft == null || Intrinsics.areEqual(canScrollLeft, Boolean.valueOf(this.canScrollLeft))) && (canScrollRight == null || Intrinsics.areEqual(canScrollRight, Boolean.valueOf(this.canScrollRight)))) ? false : true;
        if (canScrollLeft != null) {
            this.canScrollLeft = canScrollLeft.booleanValue();
        }
        if (canScrollRight != null) {
            this.canScrollRight = canScrollRight.booleanValue();
        }
        if (noAnimGestureEnable != null) {
            this.noAnimGestureEnable = noAnimGestureEnable.booleanValue();
        }
        if (z) {
            u0(Boolean.valueOf(this.canScrollLeft), Boolean.valueOf(this.canScrollRight));
        }
    }

    public void g0(@Nullable String lowUri, @Nullable String uri) {
        if (this.mVisualizerView == null || this.mVisualizerId == -1) {
            return;
        }
        if (!TextUtils.isEmpty(lowUri) || !TextUtils.isEmpty(uri)) {
            ey0.c.q("ACTION_VISUALIZER_VisualizerView_loadArt", new Object[]{this.mVisualizerView, lowUri, uri});
        } else if (this.playerView.getCurrentMusic() != null) {
            ey0.c.q("ACTION_VISUALIZER_VisualizerView_loadArt", new Object[]{this.mVisualizerView, this.playerView.getCurrentMusic().getLocalAlbumCoverUrl(), MainProcessPlayService.getPlayerAlbumImageUrl(this.playerView.getCurrentMusic().getAlbumCoverUrl())});
        }
    }

    public final void h0() {
        FragmentActivity activity = this.playerView.getActivity();
        if (activity != null) {
            sa.a.h(LocalBroadcastManager.getInstance(activity), this.mVisualizerReceiver, "com/netease/cloudmusic/module/playerstyle/PlayerVinylViewHolder.class:onDestroy:()V line-957");
        }
        ey0.c.q("ACTION_VISUALIZER_VisualizerView_destroy", new Object[]{this.mVisualizerView});
    }

    public final void i0() {
        if (this.discHeadStatus == 4) {
            bn0.k kVar = this.mPlayerStoryGuideAnimationHelper;
            if (kVar != null) {
                kVar.p();
            }
        } else {
            bn0.k kVar2 = this.mPlayerStoryGuideAnimationHelper;
            if (kVar2 != null) {
                kVar2.f();
            }
        }
        if (this.disc != null) {
            t6(ol0.a.f(s.a.h().getCurrentPlayType()));
        }
    }

    public final void j0() {
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
        this.disc = currentView instanceof RotationRelativeLayout ? (RotationRelativeLayout) currentView : null;
        A0();
    }

    @Override
    @Nullable
    public NeteaseMusicSimpleDraweeView getFakeVideoCover() {
        return this.fakeVideoCover;
    }

    public final void k0(boolean ifStart) {
        if (ifStart) {
            if (this.playerView.o7().getTag() != null) {
                Object tag = this.playerView.o7().getTag();
                if (!Intrinsics.areEqual(tag instanceof Boolean ? (Boolean) tag : null, Boolean.FALSE)) {
                    return;
                }
            }
            bn0.k kVar = this.mPlayerStoryGuideAnimationHelper;
            if (kVar != null) {
                kVar.p();
                return;
            }
            return;
        }
        if (this.playerView.o7().getTag() != null) {
            Object tag2 = this.playerView.o7().getTag();
            if (!Intrinsics.areEqual(tag2 instanceof Boolean ? (Boolean) tag2 : null, Boolean.TRUE)) {
                return;
            }
        }
        bn0.k kVar2 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar2 != null) {
            kVar2.f();
        }
    }

    public final void m0() {
        if (this.discHeadStatus == 4) {
            bn0.k kVar = this.mPlayerStoryGuideAnimationHelper;
            if (kVar != null) {
                kVar.p();
                return;
            }
            return;
        }
        bn0.k kVar2 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar2 != null) {
            kVar2.f();
        }
    }

    @Override
    public int getPlayerDiscNeedleOffsetX() {
        return this.playerDiscNeedleOffsetX;
    }

    public final void n0(boolean animViewIsVisible, @Nullable Function0<Unit> callback) {
        if (animViewIsVisible) {
            if (this.mVisualizerId == -1) {
                y0();
                return;
            }
            ViewGroup viewGroup = this.mVisualizerView;
            if (viewGroup != null) {
                ey0.c.q("ACTION_VISUALIZER_VisualizerView_resume", new Object[]{viewGroup});
                t0(true, true);
                return;
            }
            return;
        }
        if (callback != null) {
            callback.invoke();
        }
        if (this.mVisualizerId == -1) {
            b0();
            return;
        }
        ViewGroup viewGroup2 = this.mVisualizerView;
        if (viewGroup2 != null) {
            ey0.c.q("ACTION_VISUALIZER_VisualizerView_pause", new Object[]{viewGroup2});
            t0(false, false);
        }
    }

    @Override
    @Nullable
    public RelativeLayout getMDiskView() {
        return this.mDiskView;
    }

    public void o0(@Nullable String coverUrl) {
        Handler storyGuideHandler;
        Handler storyGuideHandler2;
        if (lt.f(coverUrl)) {
            return;
        }
        bn0.k kVar = this.mPlayerStoryGuideAnimationHelper;
        if (kVar != null) {
            kVar.s(coverUrl);
        }
        bn0.k kVar2 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar2 != null) {
            kVar2.q(true);
        }
        bn0.k kVar3 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar3 != null) {
            kVar3.r(true);
        }
        bn0.k kVar4 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar4 != null && (storyGuideHandler2 = kVar4.getStoryGuideHandler()) != null) {
            storyGuideHandler2.removeCallbacks(this.mPlayerStoryGuideAnimationHelper.getStoryGuideRunnable());
        }
        bn0.k kVar5 = this.mPlayerStoryGuideAnimationHelper;
        if (kVar5 == null || (storyGuideHandler = kVar5.getStoryGuideHandler()) == null) {
            return;
        }
        storyGuideHandler.postDelayed(this.mPlayerStoryGuideAnimationHelper.getStoryGuideRunnable(), 500L);
    }

    public void q0(boolean resume) {
        ViewGroup viewGroup = this.mVisualizerView;
        if (viewGroup != null) {
            if (!resume) {
                ey0.c.q("ACTION_VISUALIZER_VisualizerView_pause", new Object[]{viewGroup});
                return;
            }
            if (viewGroup != null && viewGroup.getVisibility() == 0) {
                ViewGroup viewGroup2 = this.mVisualizerView;
                ViewParent parent = viewGroup2 != null ? viewGroup2.getParent() : null;
                View view = parent instanceof View ? (View) parent : null;
                if (view != null && view.getVisibility() == 0) {
                    ey0.c.q("ACTION_VISUALIZER_VisualizerView_resume", new Object[]{this.mVisualizerView});
                }
            }
        }
    }

    @Override
    @NotNull
    public ObjectAnimator q4() {
        ObjectAnimator objectAnimator = this.discHeadDownRotateAnimation;
        if (objectAnimator != null) {
            return objectAnimator;
        }
        Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRotateAnimation");
        return null;
    }

    public void r0() {
        q0(true);
        t0(true, true);
    }

    public void t0(boolean start, boolean resumeOrReset) {
        ViewGroup viewGroup = this.mVisualizerView;
        if (viewGroup != null) {
            if (!start) {
                ey0.c.q("ACTION_VISUALIZER_VisualizerView_stopArtViewRotationAnimation", new Object[]{viewGroup, Boolean.valueOf(resumeOrReset)});
                return;
            }
            if ((viewGroup != null && viewGroup.getVisibility() == 0) && e0()) {
                PlayerSeekBarNew playerSeekBarNew = this.playSeekBar;
                if ((playerSeekBarNew == null || playerSeekBarNew.isCaching()) ? false : true) {
                    ey0.c.q("ACTION_VISUALIZER_VisualizerView_startArtViewRotationAnimation", new Object[]{this.mVisualizerView, Boolean.valueOf(resumeOrReset)});
                }
            }
        }
    }

    @Override
    public int getDiscHeadStatus() {
        return this.discHeadStatus;
    }

    @Override
    public long getMVisualizerId() {
        return this.mVisualizerId;
    }

    @Override
    public void t6(float speed) {
        ViewGroup viewGroup;
        if (this.mVisualizerId != -1 && (viewGroup = this.mVisualizerView) != null) {
            ey0.c.q("ACTION_VISUALIZER_VisualizerView_setArtViewRotationSpeed", new Object[]{viewGroup, Float.valueOf(speed)});
            return;
        }
        RotationRelativeLayout rotationRelativeLayout = this.disc;
        if (rotationRelativeLayout != null) {
            rotationRelativeLayout.setRotateSpeed(speed);
        }
    }

    @Override
    @Nullable
    public PlayerDiscViewFlipper getDiscSwitcher() {
        return this.discSwitcher;
    }

    @Override
    public void u5() {
        RotationRelativeLayout rotationRelativeLayout;
        if (!d0() || (rotationRelativeLayout = this.disc) == null) {
            return;
        }
        rotationRelativeLayout.start();
    }

    public final void w0(@NotNull Map<String, Object> map) {
        Intrinsics.checkNotNullParameter(map, "map");
        map.put("canSwitchToPreviousSong", Boolean.valueOf(this.canScrollLeft));
        map.put("canSwitchToNextSong", Boolean.valueOf(this.canScrollRight));
    }

    public final void x0() {
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        View currentView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null;
        ViewGroup viewGroup = currentView instanceof ViewGroup ? (ViewGroup) currentView : null;
        View childAt = viewGroup != null ? viewGroup.getChildAt(0) : null;
        if (childAt != null) {
            childAt.setVisibility(0);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper2 = this.discSwitcher;
        View currentView2 = playerDiscViewFlipper2 != null ? playerDiscViewFlipper2.getCurrentView() : null;
        ViewGroup viewGroup2 = currentView2 instanceof ViewGroup ? (ViewGroup) currentView2 : null;
        View childAt2 = viewGroup2 != null ? viewGroup2.getChildAt(1) : null;
        if (childAt2 != null) {
            childAt2.setVisibility(0);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper3 = this.discSwitcher;
        View nextView = playerDiscViewFlipper3 != null ? playerDiscViewFlipper3.getNextView() : null;
        ViewGroup viewGroup3 = nextView instanceof ViewGroup ? (ViewGroup) nextView : null;
        View childAt3 = viewGroup3 != null ? viewGroup3.getChildAt(0) : null;
        if (childAt3 != null) {
            childAt3.setVisibility(0);
        }
        PlayerDiscViewFlipper playerDiscViewFlipper4 = this.discSwitcher;
        View nextView2 = playerDiscViewFlipper4 != null ? playerDiscViewFlipper4.getNextView() : null;
        ViewGroup viewGroup4 = nextView2 instanceof ViewGroup ? (ViewGroup) nextView2 : null;
        View childAt4 = viewGroup4 != null ? viewGroup4.getChildAt(1) : null;
        if (childAt4 == null) {
            return;
        }
        childAt4.setVisibility(0);
    }

    public void y0() {
        ObjectAnimator objectAnimator = this.discHeadUpRotateAnimation;
        if (objectAnimator == null) {
            Intrinsics.throwUninitializedPropertyAccessException("discHeadUpRotateAnimation");
            objectAnimator = null;
        }
        objectAnimator.cancel();
        ObjectAnimator objectAnimator2 = this.discHeadDownRotateAnimation;
        if (objectAnimator2 == null) {
            Intrinsics.throwUninitializedPropertyAccessException("discHeadDownRotateAnimation");
            objectAnimator2 = null;
        }
        objectAnimator2.cancel();
        this.discHeadStatus = 2;
        Q();
        PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
        View nextView = playerDiscViewFlipper != null ? playerDiscViewFlipper.getNextView() : null;
        RotationRelativeLayout rotationRelativeLayout = nextView instanceof RotationRelativeLayout ? (RotationRelativeLayout) nextView : null;
        if (rotationRelativeLayout != null) {
            rotationRelativeLayout.stopAndRest();
        }
        PlayerDiscViewFlipper playerDiscViewFlipper2 = this.discSwitcher;
        View currentView = playerDiscViewFlipper2 != null ? playerDiscViewFlipper2.getCurrentView() : null;
        RotationRelativeLayout rotationRelativeLayout2 = currentView instanceof RotationRelativeLayout ? (RotationRelativeLayout) currentView : null;
        this.disc = rotationRelativeLayout2;
        if (rotationRelativeLayout2 != null) {
            rotationRelativeLayout2.prepareAnimation();
        }
    }

    @Override
    @Nullable
    public RotationRelativeLayout y5(boolean currentCover) {
        RotationRelativeLayout rotationRelativeLayout;
        if (currentCover) {
            PlayerDiscViewFlipper playerDiscViewFlipper = this.discSwitcher;
            rotationRelativeLayout = (RotationRelativeLayout) (playerDiscViewFlipper != null ? playerDiscViewFlipper.getCurrentView() : null);
        } else {
            PlayerDiscViewFlipper playerDiscViewFlipper2 = this.discSwitcher;
            rotationRelativeLayout = (RotationRelativeLayout) (playerDiscViewFlipper2 != null ? playerDiscViewFlipper2.getNextView() : null);
        }
        this.disc = rotationRelativeLayout;
        return rotationRelativeLayout;
    }

    public void z0() {
        ViewGroup viewGroup = this.mVisualizerView;
        if (viewGroup != null) {
            ey0.c.q("ACTION_VISUALIZER_VisualizerView_stopRender", new Object[]{viewGroup});
        }
    }

    @Override
    @Nullable
    public ImageView getDiscHead() {
        return this.discHead;
    }
}
