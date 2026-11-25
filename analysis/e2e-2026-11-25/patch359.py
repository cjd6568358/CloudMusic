# -*- coding: utf-8 -*-
"""§61.359: 图标 v3 圆头 + app 名「云音乐」 + 启动页红统一到 #E54545。"""

def rw(path, fn):
    s = open(path, encoding='utf-8').read()
    s2 = fn(s)
    assert s2 != s, 'no change: ' + path
    open(path, 'w', encoding='utf-8', newline='').write(s2)
    print('updated', path)

# 1. icon: round caps on the two strokes
def icon(s):
    s = s.replace('''        android:strokeColor="#1AFFFFFF"
        android:strokeWidth="1.0"''', '''        android:strokeColor="#1AFFFFFF"
        android:strokeWidth="1.0"
        android:strokeLineCap="round"''')
    s = s.replace('''        android:strokeColor="#2E2E2E"
        android:strokeWidth="0.7"''', '''        android:strokeColor="#2E2E2E"
        android:strokeWidth="0.7"
        android:strokeLineCap="round"''')
    return s
rw('app/src/main/res/drawable/ic_launcher_foreground.xml', icon)

# 2. app name
rw('app/src/main/res/values/strings.xml',
   lambda s: s.replace('<string name="app_name">CloudMusic</string>',
                       '<string name="app_name">云音乐</string>'))

# 3. manifest label
def manifest(s):
    old = '''    <application
        android:icon="@mipmap/ic_launcher"'''
    new = '''    <!-- §61.359（G-1，用户拍板 B）：应用名「云音乐」。此前没有 android:label，
         桌面直接显示包名 com.ccmsc.cloudmusic（真机截图 43-real-icon2.png 实证）。 -->
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"'''
    assert s.count(old) == 1
    return s.replace(old, new)
rw('app/src/main/AndroidManifest.xml', manifest)

# 4. splash red -> brand red
def assets(s):
    old = '''    /** 真机启动屏 1440x3200 六点采样，2026-11-09，值一致（渐变顶）。 */
    val BrandRed = 0xFFA22016.toInt()

    /** 真机启动屏渐变底（docs/01 §0 严格像素级，2026-11-16 PIL 采样 #AE2318）。 */
    val SplashRedBottom = 0xFFAE2318.toInt()'''
    new = '''    /**
     * 启动屏红（渐变顶）。
     *
     * §61.359（2026-11-25 用户拍板「统一」）：原值 `#A22016` 是 2026-11-09 对竞品启动屏的
     * 六点采样（照搬基准）；但自家图标底色是品牌红 `#E54545`，两个红并排看像两个应用。
     * 现统一到 [com.ccmsc.cloudmusic.ui.FnComp.IconRed] 同值 `#E54545`。
     */
    val BrandRed = 0xFFE54545.toInt()

    /** 启动屏红（渐变底）。§61.359：与渐变顶同值 = 视觉纯色，不再有深红压底。 */
    val SplashRedBottom = 0xFFE54545.toInt()'''
    assert s.count(old) == 1
    return s.replace(old, new)
rw('app/src/main/kotlin/com/ccmsc/cloudmusic/ui/player/LegacyAssets.kt', assets)

# 5. splash comment
def splash(s):
    old = '''                        Color(LegacyPalette.BrandRed),          // #A22016 顶
                        Color(LegacyPalette.SplashRedBottom),   // #AE2318 底'''
    new = '''                        // §61.359：统一到品牌红 #E54545（原 #A22016/#AE2318 是竞品采样值）。
                        Color(LegacyPalette.BrandRed),
                        Color(LegacyPalette.SplashRedBottom),'''
    assert s.count(old) == 1
    return s.replace(old, new)
rw('app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SplashScreen.kt', splash)

# 6. l3 spec expectation
def spec(s):
    old = '''    if palette.get("BrandRed") != "0xFFA22016":
        color_mismatches.append("BrandRed 与真机启动屏六点采样 #A22016 不符")'''
    new = '''    # §61.359（2026-11-25 用户拍板）：启动屏红不再沿用竞品采样 #A22016，统一到品牌红 #E54545。
    if palette.get("BrandRed") != "0xFFE54545":
        color_mismatches.append("BrandRed 未统一到品牌红 #E54545（§61.359）")'''
    assert s.count(old) == 1
    return s.replace(old, new)
rw('tools/l3_visual_replica_spec.py', spec)
print('all patched')
