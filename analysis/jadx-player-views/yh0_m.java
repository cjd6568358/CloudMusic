package yh0;

import com.netease.cloudmusic.meta.MusicInfo;
import java.util.List;
import ji0.s;
import n7.u0;

public class m {
    public static boolean a(List<MusicInfo> list, MusicInfo musicInfo) {
        if (list != null && list.size() != 0 && musicInfo != null) {
            for (MusicInfo musicInfo2 : list) {
                if (musicInfo2 != null && musicInfo2.getId() == musicInfo.getId()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static long b() {
        int currentPlayType = s.a.h().getCurrentPlayType();
        if (currentPlayType == 3) {
            currentPlayType = 2;
        }
        return (long) (20000.0f / ((Float) u0.a.d("getPlaySpeed", Integer.valueOf(currentPlayType)).c()).floatValue());
    }

    public static long c(float f) {
        return (long) (20000.0f / f);
    }
}
