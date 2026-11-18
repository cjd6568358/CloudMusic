import io

# ① PlaybackComposition.updateTheme + theme 投影
p = 'core-di/src/main/kotlin/com/example/cloudmusic/di/playback/PlaybackComposition.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    /** §61.226 同族：设置流投影（MainActivity 设置页的 fade 开关显示源）。 */''',
'''    /** §61.234：主题模式持久化（ThemeMode 四值；MainActivity 改后 recreate 生效）。 */
    fun updateTheme(mode: String) {
        scope.launch {
            runCatching { settings.update { it.copy(theme = mode) } }
                .onFailure { Log.w("CloudMusicRuntime", "updateTheme 失败", it) }
        }
    }

    /** §61.226 同族：设置流投影（MainActivity 设置页的 fade 开关显示源）。 */''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ② MainActivity.applyThemeFromSettings
p = 'app/src/main/kotlin/com/example/cloudmusic/MainActivity.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    private val playerBridge by lazy {''',
'''    /** §61.234：按 settings.theme + 系统深色计算 isDark 并切 Comp/FnComp token。 */
    private fun applyThemeFromSettings() {
        val mode = runCatching { settingsStore.current().theme }
            .getOrDefault(com.example.cloudmusic.storage.ThemeMode.DARK_FOLLOW_SYSTEM)
        val systemDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isDark = when (mode) {
            com.example.cloudmusic.storage.ThemeMode.LIGHT -> false
            com.example.cloudmusic.storage.ThemeMode.DARK -> true
            com.example.cloudmusic.storage.ThemeMode.FOLLOW_SYSTEM -> systemDark
            else -> true   // DARK_FOLLOW_SYSTEM（默认）与历史行为一致：深色优先
        }
        com.example.cloudmusic.ui.CompeteUi.applyThemeCompat(isDark)
    }

    private val playerBridge by lazy {''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ③ CompeteUi.applyThemeCompat（Comp + FnComp 统一入口；FnComp import 环避免——放 CompeteUi 调 FeiniuUi）
p = 'app/src/main/kotlin/com/example/cloudmusic/ui/CompeteUi.kt'
s = io.open(p, encoding='utf-8').read()
s = s.rstrip()
s = s[:s.rfind('}')].rstrip() + '''

/** §61.234：主题统一切换入口（Comp + FnComp 同口径，MainActivity 启动/设置页调用）。 */
fun CompeteUi.applyThemeCompat(isDark: Boolean) {
    Comp.applyTheme(isDark)
    com.example.cloudmusic.ui.FnComp.applyTheme(isDark)
}
}'''
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ④ CloudMusicApp 主签名：themeMode/onThemeChanged + 设置页「主题」行
p = 'app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicApp.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''    eqEnabled: Boolean = false,
    eqEffectLevel: Int = 0,
    eqBandsMillibel: List<Int> = emptyList(),
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit = { _, _, _ -> },''',
'''    eqEnabled: Boolean = false,
    eqEffectLevel: Int = 0,
    eqBandsMillibel: List<Int> = emptyList(),
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit = { _, _, _ -> },
    /** §61.234：主题模式（设置页四选项；改动即 recreate）。 */
    themeMode: String = "DARK_FOLLOW_SYSTEM",
    onThemeChanged: (String) -> Unit = { _ -> },''')
s = s.replace('''                    eqBandsMillibel = settings.current().audiofx.bandsMillibel,
                    onApplyEqualizer = { en, lvl, bands -> runtime.applyEqualizer(en, lvl, bands) },''',
'''                    eqBandsMillibel = settings.current().audiofx.bandsMillibel,
                    onApplyEqualizer = { en, lvl, bands -> runtime.applyEqualizer(en, lvl, bands) },
                    themeMode = themeMode,
                    onThemeChanged = onThemeChanged,''')
s = s.replace('''                    onAnalyzeReplayGain: () -> Unit,
                    fadeEnabled: Boolean,
                    onFadeEnabledChanged: (Boolean) -> Unit,
                    eqEnabled: Boolean,
                    eqEffectLevel: Int,
                    eqBandsMillibel: List<Int>,
                    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,''',
'''                    onAnalyzeReplayGain: () -> Unit,
                    fadeEnabled: Boolean,
                    onFadeEnabledChanged: (Boolean) -> Unit,
                    eqEnabled: Boolean,
                    eqEffectLevel: Int,
                    eqBandsMillibel: List<Int>,
                    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,
                    themeMode: String,
                    onThemeChanged: (String) -> Unit,''')
s = s.replace('''    onAnalyzeReplayGain: () -> Unit,
    fadeEnabled: Boolean,
    onFadeEnabledChanged: (Boolean) -> Unit,
    eqEnabled: Boolean,
    eqEffectLevel: Int,
    eqBandsMillibel: List<Int>,
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,''',
'''    onAnalyzeReplayGain: () -> Unit,
    fadeEnabled: Boolean,
    onFadeEnabledChanged: (Boolean) -> Unit,
    eqEnabled: Boolean,
    eqEffectLevel: Int,
    eqBandsMillibel: List<Int>,
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,
    themeMode: String,
    onThemeChanged: (String) -> Unit,''')
# 设置页「主题」行（淡入淡出行前）+ showTheme 状态 + ThemeSheet 渲染
s = s.replace('''    var showScanDuration by remember { mutableStateOf(false) }
    var showIgnoreDirs by remember { mutableStateOf(false) }''',
'''    var showScanDuration by remember { mutableStateOf(false) }
    var showIgnoreDirs by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }''')
s = s.replace('''                FnRow(
                    dividerAbove = true,
                    glyph = "∿",
                    title = "淡入淡出",''',
'''                FnRow(
                    dividerAbove = true,
                    glyph = "◐",
                    title = "主题",
                    onClick = { showTheme = true },
                    value = when (themeMode) {
                        "LIGHT" -> "浅色"
                        "DARK" -> "深色"
                        "FOLLOW_SYSTEM" -> "跟随系统"
                        else -> "深色优先 · 跟随系统"
                    },
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "∿",
                    title = "淡入淡出",''')
s = s.replace('''    if (showEq) {
        EqDialog(''',
'''    if (showTheme) {
        CompSheetDialog(
            onDismiss = { showTheme = false },
            title = "主题",
            actions = listOf(
                "深色优先 · 跟随系统（默认）" to {
                    onThemeChanged("DARK_FOLLOW_SYSTEM"); showTheme = false
                },
                "跟随系统" to { onThemeChanged("FOLLOW_SYSTEM"); showTheme = false },
                "深色" to { onThemeChanged("DARK"); showTheme = false },
                "浅色" to { onThemeChanged("LIGHT"); showTheme = false },
                "关闭" to { showTheme = false },
            ),
        )
    }
    if (showEq) {
        EqDialog(''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("theme ui wired")
