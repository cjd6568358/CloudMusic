package com.netease.cloudmusic.android.sdk.playerengine;

import com.netease.cloudmusic.biz.social.socialinput.SocialInputConfig;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import kotlin.Metadata;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.JvmOverloads;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.InlineMarker;
import kotlin.jvm.internal.Intrinsics;
import o2.c;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Metadata(d1 = {"\u0000\u009e\u0001\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\t\n\u0000\n\u0002\u0018\u0002\n\u0002\b\b\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0004\n\u0002\u0010\b\n\u0002\b\u0006\n\u0002\u0018\u0002\n\u0002\b\u000e\n\u0002\u0010\u0007\n\u0000\n\u0002\u0010\u000b\n\u0002\b\u0005\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\bA\n\u0002\u0010\u0011\n\u0000\n\u0002\u0010\u0015\n\u0002\b\u001c\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u000f\n\u0002\u0010$\n\u0002\b\u000e\u0018\u0000 Ã\u00012\u00020\u0001:\u0002Ã\u0001B!\u0012\n\b\u0002\u0010\u0002\u001a\u0004\u0018\u00010\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u0012\u0006\u0010\u0006\u001a\u00020\u0007¢\u0006\u0002\u0010\bJ\u0016\u0010\u0018\u001a\u00020\u00192\u0006\u0010\u001a\u001a\u00020\u00052\u0006\u0010\u001b\u001a\u00020\u001cJ\u0006\u0010\u001d\u001a\u00020\u0019J\u0006\u0010\u001e\u001a\u00020\u0019J\u000e\u0010\u001f\u001a\u00020\u00192\u0006\u0010 \u001a\u00020!J\u0006\u0010\"\u001a\u00020\u0019J\u0006\u0010#\u001a\u00020\u0019J\u0006\u0010$\u001a\u00020!J\u0006\u0010%\u001a\u00020\u0005J\u0006\u0010&\u001a\u00020!J\u0006\u0010'\u001a\u00020(J\u0006\u0010)\u001a\u00020!J\u0006\u0010*\u001a\u00020\u0005J\u0006\u0010+\u001a\u00020\u001cJ\u0006\u0010,\u001a\u00020\u0005J\u0006\u0010-\u001a\u00020!J\u000e\u0010.\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cJ\u000e\u00100\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cJ\u000e\u00101\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cJ\u000e\u00102\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cJ\u0006\u00103\u001a\u00020!J\u0016\u00104\u001a\u00020\u001c2\u0006\u0010\u001b\u001a\u00020\u001c2\u0006\u00105\u001a\u00020\u001cJ\u0006\u00106\u001a\u000207J\u0006\u00108\u001a\u000209J\u0006\u0010:\u001a\u000209J\u0006\u0010;\u001a\u000209J\u0006\u0010<\u001a\u000209J\u0006\u0010=\u001a\u000209J\u0018\u0010>\u001a\u0004\u0018\u00010?2\u0006\u0010@\u001a\u00020A2\u0006\u0010B\u001a\u00020CJ!\u0010D\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u001a\u001a\u00020\u00052\u0006\u0010\u001b\u001a\u00020\u001cH\u0082 J\u0011\u0010F\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010G\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0019\u0010H\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010 \u001a\u00020!H\u0082 J\u0011\u0010I\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J#\u0010J\u001a\u00020\u00052\b\u0010\u0002\u001a\u0004\u0018\u00010\u00032\u0006\u0010\u0004\u001a\u00020\u00052\u0006\u0010\u0006\u001a\u00020\u0007H\u0082 J\u0011\u0010K\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010L\u001a\u00020!2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010M\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010N\u001a\u00020!2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0013\u0010O\u001a\u0004\u0018\u00010(2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010P\u001a\u00020!2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010Q\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010R\u001a\u00020\u001c2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010S\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010T\u001a\u00020!2\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0019\u0010U\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cH\u0082 J\u0019\u0010V\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cH\u0082 J\u0019\u0010W\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cH\u0082 J\u0019\u0010X\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u00052\u0006\u0010/\u001a\u00020\u001cH\u0082 J\u0011\u0010Y\u001a\u00020!2\u0006\u0010E\u001a\u00020\u0005H\u0082 J!\u0010Z\u001a\u00020\u001c2\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u001b\u001a\u00020\u001c2\u0006\u00105\u001a\u00020\u001cH\u0082 J\u0011\u0010[\u001a\u0002072\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010\\\u001a\u0002092\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010]\u001a\u0002092\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010^\u001a\u0002092\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010_\u001a\u0002092\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010`\u001a\u0002092\u0006\u0010E\u001a\u00020\u0005H\u0082 J!\u0010a\u001a\u00020\u00052\u0006\u0010E\u001a\u00020\u00052\u0006\u0010@\u001a\u00020A2\u0006\u0010B\u001a\u00020CH\u0082 J\u0011\u0010b\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010c\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0019\u0010d\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010e\u001a\u000209H\u0082 J\u0019\u0010f\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010g\u001a\u000209H\u0082 J1\u0010h\u001a\u00020!2\u0006\u0010E\u001a\u00020\u00052\u0006\u0010i\u001a\u00020\u00052\u0006\u0010j\u001a\u00020A2\u0006\u0010k\u001a\u00020\u001c2\u0006\u0010l\u001a\u00020\u001cH\u0082 J\u0011\u0010m\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0011\u0010n\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u0019\u0010o\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010p\u001a\u00020\u0005H\u0082 J\u0019\u0010q\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010r\u001a\u00020!H\u0082 J\u0019\u0010s\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010t\u001a\u00020\u0005H\u0082 J)\u0010u\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010v\u001a\u00020!2\u0006\u0010w\u001a\u00020!2\u0006\u0010x\u001a\u00020!H\u0082 J\u0019\u0010y\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010z\u001a\u000207H\u0082 J!\u0010{\u001a\u00020!2\u0006\u0010E\u001a\u00020\u00052\u0006\u0010i\u001a\u00020\u00052\u0006\u0010j\u001a\u00020AH\u0082 J\u0019\u0010|\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010r\u001a\u00020!H\u0082 J\u001b\u0010}\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\b\u0010\u0006\u001a\u0004\u0018\u00010\u0007H\u0082 J\u0019\u0010~\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u007f\u001a\u00020!H\u0082 J\u001a\u0010\u0080\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010z\u001a\u000207H\u0082 J\u001b\u0010\u0081\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0007\u0010\u0082\u0001\u001a\u000209H\u0082 J2\u0010\u0083\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u000e\u0010\u0084\u0001\u001a\t\u0012\u0004\u0012\u00020\u001c0\u0085\u00012\b\u0010\u0086\u0001\u001a\u00030\u0087\u0001H\u0082 ¢\u0006\u0003\u0010\u0088\u0001J\"\u0010\u0089\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010i\u001a\u00020\u00052\u0006\u0010j\u001a\u00020AH\u0082 J\u001b\u0010\u008a\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0007\u0010\u008b\u0001\u001a\u000207H\u0082 J\u001a\u0010\u008c\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u007f\u001a\u00020!H\u0082 J\u001b\u0010\u008d\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0007\u0010\u008e\u0001\u001a\u000209H\u0082 J\"\u0010\u008f\u0001\u001a\u00020!2\u0006\u0010E\u001a\u00020\u00052\u0006\u0010k\u001a\u00020\u001c2\u0006\u0010l\u001a\u00020\u001cH\u0082 J\u001c\u0010\u0090\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\b\u0010\u0006\u001a\u0004\u0018\u00010\u0017H\u0082 J\u001a\u0010\u0091\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010z\u001a\u000207H\u0082 J\u0012\u0010\u0092\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u0005H\u0082 J\u001a\u0010\u0093\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u001a\u001a\u00020\u0005H\u0082 J#\u0010\u0094\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0006\u0010\u001b\u001a\u00020\u001c2\u0007\u0010\u0095\u0001\u001a\u00020\u001cH\u0082 J\u001b\u0010\u0096\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0007\u0010\u0097\u0001\u001a\u00020!H\u0082 J$\u0010\u0098\u0001\u001a\u00020\u00192\u0006\u0010E\u001a\u00020\u00052\u0007\u0010\u0099\u0001\u001a\u00020\u001c2\u0007\u0010\u009a\u0001\u001a\u00020\u001cH\u0082 J\u0007\u0010\u009b\u0001\u001a\u00020\u0019J\u0007\u0010\u009c\u0001\u001a\u00020\u0019J\u0011\u0010\u009d\u0001\u001a\u00020\u00192\b\b\u0002\u0010e\u001a\u000209J\u0011\u0010\u009e\u0001\u001a\u00020\u00192\b\b\u0002\u0010g\u001a\u000209J \u0010\u009f\u0001\u001a\u00020!2\u0007\u0010 \u0001\u001a\u00020?2\u0006\u0010k\u001a\u00020\u001c2\u0006\u0010l\u001a\u00020\u001cJ@\u0010¡\u0001\u001a\u0003H¢\u0001\"\u0005\b\u0000\u0010¢\u00012\u000f\u0010£\u0001\u001a\n\u0012\u0005\u0012\u0003H¢\u00010¤\u00012\u0015\u0010¥\u0001\u001a\u0010\u0012\u0004\u0012\u00020\u0005\u0012\u0005\u0012\u0003H¢\u00010¦\u0001H\u0082\b¢\u0006\u0003\u0010§\u0001J\u0007\u0010¨\u0001\u001a\u00020\u0019J\u0013\u0010©\u0001\u001a\u00020\u00192\b\b\u0002\u0010g\u001a\u000209H\u0007J\u000f\u0010ª\u0001\u001a\u00020\u00192\u0006\u0010p\u001a\u00020\u0005J\u000f\u0010«\u0001\u001a\u00020\u00192\u0006\u0010r\u001a\u00020!J\u000f\u0010¬\u0001\u001a\u00020\u00192\u0006\u0010t\u001a\u00020\u0005J\u001f\u0010\u00ad\u0001\u001a\u00020\u00192\u0006\u0010v\u001a\u00020!2\u0006\u0010w\u001a\u00020!2\u0006\u0010x\u001a\u00020!J\u000f\u0010®\u0001\u001a\u00020\u00192\u0006\u0010z\u001a\u000207J\u0010\u0010¯\u0001\u001a\u00020!2\u0007\u0010 \u0001\u001a\u00020?J\u000f\u0010°\u0001\u001a\u00020\u00192\u0006\u0010r\u001a\u00020!J\u000f\u0010±\u0001\u001a\u00020\u00192\u0006\u0010\u007f\u001a\u00020!J\u000f\u0010²\u0001\u001a\u00020\u00192\u0006\u0010z\u001a\u000207J\u0010\u0010³\u0001\u001a\u00020\u00192\u0007\u0010\u0082\u0001\u001a\u000209J\u001d\u0010´\u0001\u001a\u00020\u00192\u0014\u0010µ\u0001\u001a\u000f\u0012\u0004\u0012\u00020\u001c\u0012\u0004\u0012\u00020!0¶\u0001J\u0010\u0010·\u0001\u001a\u00020\u00192\u0007\u0010 \u0001\u001a\u00020?J\u0010\u0010¸\u0001\u001a\u00020\u00192\u0007\u0010\u008b\u0001\u001a\u000207J\u000f\u0010¹\u0001\u001a\u00020\u00192\u0006\u0010\u007f\u001a\u00020!J\u0010\u0010º\u0001\u001a\u00020\u00192\u0007\u0010\u008e\u0001\u001a\u000209J\u0017\u0010»\u0001\u001a\u00020!2\u0006\u0010k\u001a\u00020\u001c2\u0006\u0010l\u001a\u00020\u001cJ\u0011\u0010¼\u0001\u001a\u00020\u00192\b\u0010\u0006\u001a\u0004\u0018\u00010\u0017J\u000f\u0010½\u0001\u001a\u00020\u00192\u0006\u0010z\u001a\u000207J\u0007\u0010¾\u0001\u001a\u00020\u0019J\u000f\u0010¿\u0001\u001a\u00020\u00192\u0006\u0010\u001a\u001a\u00020\u0005J\u0018\u0010À\u0001\u001a\u00020\u00192\u0006\u0010\u001b\u001a\u00020\u001c2\u0007\u0010\u0095\u0001\u001a\u00020\u001cJ\u0010\u0010Á\u0001\u001a\u00020\u00192\u0007\u0010\u0097\u0001\u001a\u00020!J\u0019\u0010Â\u0001\u001a\u00020\u00192\u0007\u0010\u0099\u0001\u001a\u00020\u001c2\u0007\u0010\u009a\u0001\u001a\u00020\u001cR(\u0010\u0006\u001a\u0004\u0018\u00010\u00072\b\u0010\t\u001a\u0004\u0018\u00010\u0007@FX\u0086\u000e¢\u0006\u000e\n\u0000\u001a\u0004\b\n\u0010\u000b\"\u0004\b\f\u0010\rR\u000e\u0010\u000e\u001a\u00020\u0005X\u0082\u000e¢\u0006\u0002\n\u0000R\u000e\u0010\u000f\u001a\u00020\u0010X\u0082\u0004¢\u0006\u0002\n\u0000R\u0016\u0010\u0011\u001a\n \u0013*\u0004\u0018\u00010\u00120\u0012X\u0082\u0004¢\u0006\u0002\n\u0000R\u0016\u0010\u0014\u001a\n \u0013*\u0004\u0018\u00010\u00150\u0015X\u0082\u0004¢\u0006\u0002\n\u0000R\u0010\u0010\u0016\u001a\u0004\u0018\u00010\u0017X\u0082\u000e¢\u0006\u0002\n\u0000¨\u0006Ä\u0001"}, d2 = {"Lcom/netease/cloudmusic/android/sdk/playerengine/Playback;", "", "platform", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackPlatform;", "cacheManagerHandle", "", "listener", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackListener;", "(Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackPlatform;JLcom/netease/cloudmusic/android/sdk/playerengine/PlaybackListener;)V", "value", "getListener", "()Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackListener;", "setListener", "(Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackListener;)V", "nativeHandle", "nativeLifetimeLock", "Ljava/util/concurrent/locks/ReentrantReadWriteLock;", "nativeReadLock", "Ljava/util/concurrent/locks/ReentrantReadWriteLock$ReadLock;", "kotlin.jvm.PlatformType", "nativeWriteLock", "Ljava/util/concurrent/locks/ReentrantReadWriteLock$WriteLock;", "unitEventListener", "Lcom/netease/cloudmusic/android/sdk/playerengine/UnitEventListener;", "attachAudioEffect", "", "effectHandle", "songTag", "", "beginQualityContinuation", "cancelTransition", "changeQuality", "targetBitrate", "", "commitTransitionSwitch", "discardUSBDevice", "getAudioDeviceType", "getAudioLatency", "getAudioTrackSessionID", "getCacheProgress", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackCacheProgress;", "getCurrentBitrate", "getCurrentPosition", "getCurrentSongId", "getDuration", "getPlaybackState", "getPlayedTime", "songId", "getPlayedTimeWithSpeed", "getRoundPlayedTime", "getRoundPlayedTimeWithSpeed", "getTransitionState", "getUnitPropertyBySongTag", "property", "getVolume", "", "isBuffering", "", "isMuted", "isPlaying", "isSeeking", "markPlaybackRoundStarted", "musicInfoToDataSource", "Lcom/netease/cloudmusic/android/sdk/playerengine/PlaybackDataSource;", SocialInputConfig.FUNCTION_MUSIC, "Lcom/netease/cloudmusic/android/sdk/playerengine/MusicInfo;", "dataSourcePlatform", "Lcom/netease/cloudmusic/android/sdk/playerengine/DataSourcePlatform;", "nativeAttachAudioEffect", "handle", "nativeBeginQualityContinuation", "nativeCancelTransition", "nativeChangeQuality", "nativeCommitTransitionSwitch", "nativeCreate", "nativeDiscardUSBDevice", "nativeGetAudioDeviceType", "nativeGetAudioLatency", "nativeGetAudioTrackSessionId", "nativeGetCacheProgress", "nativeGetCurrentBitrate", "nativeGetCurrentPosition", "nativeGetCurrentSongId", "nativeGetDuration", "nativeGetPlaybackState", "nativeGetPlayedTime", "nativeGetPlayedTimeWithSpeed", "nativeGetRoundPlayedTime", "nativeGetRoundPlayedTimeWithSpeed", "nativeGetTransitionState", "nativeGetUnitPropertyBySongTag", "nativeGetVolume", "nativeIsBuffering", "nativeIsMuted", "nativeIsPlaying", "nativeIsSeeking", "nativeMarkPlaybackRoundStarted", "nativeMusicInfoToDataSource", "nativeNotifyAudioEffectChanged", "nativeNotifyNetworkQualityReduce", "nativePause", "fadeout", "nativePlay", "fadein", "nativePrepareNext", "dataSourceHandle", "musicInfo", "mixInfoJson", "mixType", "nativeRelease", "nativeResume", "nativeSeekTo", "positionMs", "nativeSetAudioDeviceType", "type", "nativeSetAudioListener", "listenerHandle", "nativeSetAudioSystemVolume", "maxVolume", "minVolume", "curVolume", "nativeSetBackgroundMusicVolume", "volume", "nativeSetDataSource", "nativeSetHeadSetType", "nativeSetListener", "nativeSetMusicMode", "mode", "nativeSetMusicVoiceVolume", "nativeSetMute", "muted", "nativeSetNativeABTestFlags", "keys", "", "values", "", "(J[Ljava/lang/String;[I)V", "nativeSetNextDataSource", "nativeSetPlaybackSpeed", "speed", "nativeSetSepTrackEnabled", "nativeSetSupportDolby", "support", "nativeSetTransitionInfo", "nativeSetUnitEventListener", "nativeSetVolume", "nativeStop", "nativeUnattachAudioEffect", "nativeUpdateAudioEffectConfigBySongTag", "config", "nativeUseUSBDevice", "deviceId", "nativeWriteCacheMeta", "resourceId", "metaJson", "notifyAudioEffectChanged", "notifyNetworkQualityReduce", "pause", "play", "prepareNext", "dataSource", "queryNative", "T", "defaultValue", "Lkotlin/Function0;", "query", "Lkotlin/Function1;", "(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;)Ljava/lang/Object;", "release", "resume", "seekTo", "setAudioDeviceType", "setAudioListener", "setAudioSystemVolume", "setBackgroundMusicVolume", "setDataSource", "setHeadSetType", "setMusicMode", "setMusicVoiceVolume", "setMute", "setNativeABTestFlags", "flags", "", "setNextDataSource", "setPlaybackSpeed", "setSepTrackEnabled", "setSupportDolby", "setTransitionInfo", "setUnitEventListener", "setVolume", "stop", "unattachAudioEffect", "updateAudioEffectConfigBySongTag", "useUSBDevice", "writeCacheMeta", "Companion", "playerengine_release"}, k = 1, mv = {1, 6, 0}, xi = 48)
public final class Playback {

    @Nullable
    private PlaybackListener listener;
    private volatile long nativeHandle;

    @NotNull
    private final ReentrantReadWriteLock nativeLifetimeLock;
    private final ReentrantReadWriteLock.ReadLock nativeReadLock;
    private final ReentrantReadWriteLock.WriteLock nativeWriteLock;

    @Nullable
    private volatile UnitEventListener unitEventListener;

    static {
        c.e("ffmpeg");
        c.e("NMCommonCache");
        c.e("ncmaudioplayer");
        c.e("neaudioeffects");
        c.e("ncmplayerengine");
    }

    public Playback(@Nullable PlaybackPlatform playbackPlatform, long j, @NotNull PlaybackListener playbackListener) {
        Intrinsics.checkNotNullParameter(playbackListener, "listener");
        ReentrantReadWriteLock reentrantReadWriteLock = new ReentrantReadWriteLock(true);
        this.nativeLifetimeLock = reentrantReadWriteLock;
        this.nativeReadLock = reentrantReadWriteLock.readLock();
        this.nativeWriteLock = reentrantReadWriteLock.writeLock();
        this.nativeHandle = nativeCreate(playbackPlatform, j, playbackListener);
    }

    public Playback(PlaybackPlatform playbackPlatform, long j, PlaybackListener playbackListener, int i, DefaultConstructorMarker defaultConstructorMarker) {
        this((i & 1) != 0 ? null : playbackPlatform, j, playbackListener);
    }

    private final native void nativeAttachAudioEffect(long handle, long effectHandle, String songTag);

    private final native void nativeBeginQualityContinuation(long handle);

    private final native void nativeCancelTransition(long handle);

    private final native void nativeChangeQuality(long handle, int targetBitrate);

    private final native void nativeCommitTransitionSwitch(long handle);

    private final native long nativeCreate(PlaybackPlatform platform, long cacheManagerHandle, PlaybackListener listener);

    private final native void nativeDiscardUSBDevice(long handle);

    private final native int nativeGetAudioDeviceType(long handle);

    private final native long nativeGetAudioLatency(long handle);

    private final native int nativeGetAudioTrackSessionId(long handle);

    private final native PlaybackCacheProgress nativeGetCacheProgress(long handle);

    private final native int nativeGetCurrentBitrate(long handle);

    private final native long nativeGetCurrentPosition(long handle);

    private final native String nativeGetCurrentSongId(long handle);

    private final native long nativeGetDuration(long handle);

    private final native int nativeGetPlaybackState(long handle);

    private final native long nativeGetPlayedTime(long handle, String songId);

    private final native long nativeGetPlayedTimeWithSpeed(long handle, String songId);

    private final native long nativeGetRoundPlayedTime(long handle, String songId);

    private final native long nativeGetRoundPlayedTimeWithSpeed(long handle, String songId);

    private final native int nativeGetTransitionState(long handle);

    private final native String nativeGetUnitPropertyBySongTag(long handle, String songTag, String property);

    private final native float nativeGetVolume(long handle);

    private final native boolean nativeIsBuffering(long handle);

    private final native boolean nativeIsMuted(long handle);

    private final native boolean nativeIsPlaying(long handle);

    private final native boolean nativeIsSeeking(long handle);

    private final native boolean nativeMarkPlaybackRoundStarted(long handle);

    private final native long nativeMusicInfoToDataSource(long handle, MusicInfo music, DataSourcePlatform dataSourcePlatform);

    private final native void nativeNotifyAudioEffectChanged(long handle);

    private final native void nativeNotifyNetworkQualityReduce(long handle);

    private final native void nativePause(long handle, boolean fadeout);

    private final native void nativePlay(long handle, boolean fadein);

    private final native int nativePrepareNext(long handle, long dataSourceHandle, MusicInfo musicInfo, String mixInfoJson, String mixType);

    private final native void nativeRelease(long handle);

    private final native void nativeResume(long handle);

    private final native void nativeSeekTo(long handle, long positionMs);

    private final native void nativeSetAudioDeviceType(long handle, int type);

    private final native void nativeSetAudioListener(long handle, long listenerHandle);

    private final native void nativeSetAudioSystemVolume(long handle, int maxVolume, int minVolume, int curVolume);

    private final native void nativeSetBackgroundMusicVolume(long handle, float volume);

    private final native int nativeSetDataSource(long handle, long dataSourceHandle, MusicInfo musicInfo);

    private final native void nativeSetHeadSetType(long handle, int type);

    private final native void nativeSetListener(long handle, PlaybackListener listener);

    private final native void nativeSetMusicMode(long handle, int mode);

    private final native void nativeSetMusicVoiceVolume(long handle, float volume);

    private final native void nativeSetMute(long handle, boolean muted);

    private final native void nativeSetNativeABTestFlags(long handle, String[] keys, int[] values);

    private final native void nativeSetNextDataSource(long handle, long dataSourceHandle, MusicInfo musicInfo);

    private final native void nativeSetPlaybackSpeed(long handle, float speed);

    private final native void nativeSetSepTrackEnabled(long handle, int mode);

    private final native void nativeSetSupportDolby(long handle, boolean support);

    private final native int nativeSetTransitionInfo(long handle, String mixInfoJson, String mixType);

    private final native void nativeSetUnitEventListener(long handle, UnitEventListener listener);

    private final native void nativeSetVolume(long handle, float volume);

    private final native void nativeStop(long handle);

    private final native void nativeUnattachAudioEffect(long handle, long effectHandle);

    private final native void nativeUpdateAudioEffectConfigBySongTag(long handle, String songTag, String config);

    private final native void nativeUseUSBDevice(long handle, int deviceId);

    private final native void nativeWriteCacheMeta(long handle, String resourceId, String metaJson);

    public static void pause$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.pause(z);
    }

    public static void play$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.play(z);
    }

    private final <T> T queryNative(Function0<? extends T> defaultValue, Function1<? super Long, ? extends T> query) {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? (T) query.invoke(Long.valueOf(j)) : (T) defaultValue.invoke();
        } finally {
            InlineMarker.finallyStart(1);
            this.nativeReadLock.unlock();
            InlineMarker.finallyEnd(1);
        }
    }

    public static void resume$default(Playback playback, boolean z, int i, Object obj) {
        if ((i & 1) != 0) {
            z = false;
        }
        playback.resume(z);
    }

    public final void attachAudioEffect(long effectHandle, @NotNull String songTag) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        if (this.nativeHandle != 0) {
            nativeAttachAudioEffect(this.nativeHandle, effectHandle, songTag);
        }
    }

    public final void beginQualityContinuation() {
        if (this.nativeHandle != 0) {
            nativeBeginQualityContinuation(this.nativeHandle);
        }
    }

    public final void cancelTransition() {
        if (this.nativeHandle != 0) {
            nativeCancelTransition(this.nativeHandle);
        }
    }

    public final void changeQuality(int targetBitrate) {
        if (this.nativeHandle != 0) {
            nativeChangeQuality(this.nativeHandle, targetBitrate);
        }
    }

    public final void commitTransitionSwitch() {
        if (this.nativeHandle != 0) {
            nativeCommitTransitionSwitch(this.nativeHandle);
        }
    }

    public final void discardUSBDevice() {
        if (this.nativeHandle != 0) {
            nativeDiscardUSBDevice(this.nativeHandle);
        }
    }

    public final int getAudioDeviceType() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioDeviceType(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getAudioLatency() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioLatency(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final int getAudioTrackSessionID() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetAudioTrackSessionId(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final PlaybackCacheProgress getCacheProgress() {
        PlaybackCacheProgress playbackCacheProgress;
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            if (j != 0) {
                playbackCacheProgress = nativeGetCacheProgress(j);
                if (playbackCacheProgress == null) {
                    playbackCacheProgress = new PlaybackCacheProgress(0L, 0L, 3, null);
                }
            } else {
                playbackCacheProgress = new PlaybackCacheProgress(0L, 0L, 3, null);
            }
            return playbackCacheProgress;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final int getCurrentBitrate() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentBitrate(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getCurrentPosition() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentPosition(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final String getCurrentSongId() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetCurrentSongId(j) : "";
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getDuration() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetDuration(j) : 0L;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @Nullable
    public final PlaybackListener getListener() {
        return this.listener;
    }

    public final int getPlaybackState() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetPlaybackState(j) : 1;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final long getPlayedTime(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetPlayedTime(j, songId) : 0L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getPlayedTimeWithSpeed(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetPlayedTimeWithSpeed(j, songId) : 0L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getRoundPlayedTime(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetRoundPlayedTime(j, songId) : -1L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final long getRoundPlayedTimeWithSpeed(@NotNull String songId) {
        Intrinsics.checkNotNullParameter(songId, "songId");
        if (songId.length() > 0) {
            this.nativeReadLock.lock();
            try {
                long j = this.nativeHandle;
                r1 = j != 0 ? nativeGetRoundPlayedTimeWithSpeed(j, songId) : -1L;
            } finally {
                this.nativeReadLock.unlock();
            }
        }
        return r1;
    }

    public final int getTransitionState() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetTransitionState(j) : 0;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    @NotNull
    public final String getUnitPropertyBySongTag(@NotNull String songTag, @NotNull String property) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        Intrinsics.checkNotNullParameter(property, "property");
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetUnitPropertyBySongTag(j, songTag, property) : "{\"code\":1,\"msg\":\"engine released\"}";
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final float getVolume() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeGetVolume(j) : 1.0f;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isBuffering() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsBuffering(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isMuted() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsMuted(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isPlaying() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsPlaying(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean isSeeking() {
        this.nativeReadLock.lock();
        try {
            long j = this.nativeHandle;
            return j != 0 ? nativeIsSeeking(j) : false;
        } finally {
            this.nativeReadLock.unlock();
        }
    }

    public final boolean markPlaybackRoundStarted() {
        if (this.nativeHandle != 0) {
            return nativeMarkPlaybackRoundStarted(this.nativeHandle);
        }
        return false;
    }

    @Nullable
    public final synchronized PlaybackDataSource musicInfoToDataSource(@NotNull MusicInfo music, @NotNull DataSourcePlatform dataSourcePlatform) {
        Intrinsics.checkNotNullParameter(music, SocialInputConfig.FUNCTION_MUSIC);
        Intrinsics.checkNotNullParameter(dataSourcePlatform, "dataSourcePlatform");
        long j = this.nativeHandle;
        if (j == 0) {
            return null;
        }
        long nativeMusicInfoToDataSource = nativeMusicInfoToDataSource(j, music, dataSourcePlatform);
        return nativeMusicInfoToDataSource != 0 ? new PlaybackDataSource(nativeMusicInfoToDataSource, music) : null;
    }

    public final void notifyAudioEffectChanged() {
        if (this.nativeHandle != 0) {
            nativeNotifyAudioEffectChanged(this.nativeHandle);
        }
    }

    public final void notifyNetworkQualityReduce() {
        if (this.nativeHandle != 0) {
            nativeNotifyNetworkQualityReduce(this.nativeHandle);
        }
    }

    public final void pause(boolean fadeout) {
        if (this.nativeHandle != 0) {
            nativePause(this.nativeHandle, fadeout);
        }
    }

    public final void play(boolean fadein) {
        if (this.nativeHandle != 0) {
            nativePlay(this.nativeHandle, fadein);
        }
    }

    public final synchronized int prepareNext(@NotNull PlaybackDataSource dataSource, @NotNull String mixInfoJson, @NotNull String mixType) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        Intrinsics.checkNotNullParameter(mixInfoJson, "mixInfoJson");
        Intrinsics.checkNotNullParameter(mixType, "mixType");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return PlaybackErrorCode.DATA_SOURCE;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return PlaybackErrorCode.DATA_SOURCE;
        }
        return nativePrepareNext(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo(), mixInfoJson, mixType);
    }

    public final synchronized void release() {
        this.nativeWriteLock.lock();
        try {
            long j = this.nativeHandle;
            this.nativeHandle = 0L;
            this.nativeWriteLock.unlock();
            if (j != 0) {
                nativeRelease(j);
            }
        } catch (Throwable th2) {
            this.nativeWriteLock.unlock();
            throw th2;
        }
    }

    @JvmOverloads
    public final void resume() {
        resume$default(this, false, 1, null);
    }

    @JvmOverloads
    public final void resume(boolean fadein) {
        play(fadein);
    }

    public final void seekTo(long positionMs) {
        if (this.nativeHandle != 0) {
            nativeSeekTo(this.nativeHandle, positionMs);
        }
    }

    public final void setAudioDeviceType(int type) {
        if (this.nativeHandle != 0) {
            nativeSetAudioDeviceType(this.nativeHandle, type);
        }
    }

    public final void setAudioListener(long listenerHandle) {
        if (this.nativeHandle != 0) {
            nativeSetAudioListener(this.nativeHandle, listenerHandle);
        }
    }

    public final void setAudioSystemVolume(int maxVolume, int minVolume, int curVolume) {
        if (this.nativeHandle != 0) {
            nativeSetAudioSystemVolume(this.nativeHandle, maxVolume, minVolume, curVolume);
        }
    }

    public final void setBackgroundMusicVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetBackgroundMusicVolume(this.nativeHandle, volume);
        }
    }

    public final synchronized int setDataSource(@NotNull PlaybackDataSource dataSource) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return PlaybackErrorCode.DATA_SOURCE;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return PlaybackErrorCode.DATA_SOURCE;
        }
        return nativeSetDataSource(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo());
    }

    public final void setHeadSetType(int type) {
        if (this.nativeHandle != 0) {
            nativeSetHeadSetType(this.nativeHandle, type);
        }
    }

    public final synchronized void setListener(@Nullable PlaybackListener playbackListener) {
        this.listener = playbackListener;
        if (this.nativeHandle != 0) {
            nativeSetListener(this.nativeHandle, playbackListener);
        }
    }

    public final void setMusicMode(int mode) {
        if (this.nativeHandle != 0) {
            nativeSetMusicMode(this.nativeHandle, mode);
        }
    }

    public final void setMusicVoiceVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetMusicVoiceVolume(this.nativeHandle, volume);
        }
    }

    public final void setMute(boolean muted) {
        if (this.nativeHandle != 0) {
            nativeSetMute(this.nativeHandle, muted);
        }
    }

    public final void setNativeABTestFlags(@NotNull Map<String, Integer> flags) {
        Intrinsics.checkNotNullParameter(flags, "flags");
        if (this.nativeHandle == 0 || flags.isEmpty()) {
            return;
        }
        long j = this.nativeHandle;
        Object[] array = flags.keySet().toArray(new String[0]);
        if (array == null) {
            throw new NullPointerException("null cannot be cast to non-null type kotlin.Array<T of kotlin.collections.ArraysKt__ArraysJVMKt.toTypedArray>");
        }
        nativeSetNativeABTestFlags(j, (String[]) array, CollectionsKt.toIntArray(flags.values()));
    }

    public final synchronized void setNextDataSource(@NotNull PlaybackDataSource dataSource) {
        Intrinsics.checkNotNullParameter(dataSource, "dataSource");
        long j = this.nativeHandle;
        if (j == 0) {
            dataSource.close();
            return;
        }
        long takeNativeHandle$playerengine_release = dataSource.takeNativeHandle$playerengine_release();
        if (takeNativeHandle$playerengine_release == 0) {
            return;
        }
        nativeSetNextDataSource(j, takeNativeHandle$playerengine_release, dataSource.getMusicInfo());
    }

    public final void setPlaybackSpeed(float speed) {
        if (this.nativeHandle != 0) {
            nativeSetPlaybackSpeed(this.nativeHandle, speed);
        }
    }

    public final void setSepTrackEnabled(int mode) {
        if (this.nativeHandle != 0) {
            nativeSetSepTrackEnabled(this.nativeHandle, mode);
        }
    }

    public final void setSupportDolby(boolean support) {
        if (this.nativeHandle != 0) {
            nativeSetSupportDolby(this.nativeHandle, support);
        }
    }

    public final int setTransitionInfo(@NotNull String mixInfoJson, @NotNull String mixType) {
        Intrinsics.checkNotNullParameter(mixInfoJson, "mixInfoJson");
        Intrinsics.checkNotNullParameter(mixType, "mixType");
        if (this.nativeHandle != 0) {
            return nativeSetTransitionInfo(this.nativeHandle, mixInfoJson, mixType);
        }
        return -1;
    }

    public final synchronized void setUnitEventListener(@Nullable UnitEventListener listener) {
        this.unitEventListener = listener;
        if (this.nativeHandle != 0) {
            nativeSetUnitEventListener(this.nativeHandle, listener);
        }
    }

    public final void setVolume(float volume) {
        if (this.nativeHandle != 0) {
            nativeSetVolume(this.nativeHandle, volume);
        }
    }

    public final void stop() {
        if (this.nativeHandle != 0) {
            nativeStop(this.nativeHandle);
        }
    }

    public final void unattachAudioEffect(long effectHandle) {
        if (this.nativeHandle != 0) {
            nativeUnattachAudioEffect(this.nativeHandle, effectHandle);
        }
    }

    public final void updateAudioEffectConfigBySongTag(@NotNull String songTag, @NotNull String config) {
        Intrinsics.checkNotNullParameter(songTag, "songTag");
        Intrinsics.checkNotNullParameter(config, "config");
        if (this.nativeHandle != 0) {
            nativeUpdateAudioEffectConfigBySongTag(this.nativeHandle, songTag, config);
        }
    }

    public final void useUSBDevice(int deviceId) {
        if (this.nativeHandle != 0) {
            nativeUseUSBDevice(this.nativeHandle, deviceId);
        }
    }

    public final void writeCacheMeta(@NotNull String resourceId, @NotNull String metaJson) {
        Intrinsics.checkNotNullParameter(resourceId, "resourceId");
        Intrinsics.checkNotNullParameter(metaJson, "metaJson");
        if (this.nativeHandle != 0) {
            nativeWriteCacheMeta(this.nativeHandle, resourceId, metaJson);
        }
    }
}
