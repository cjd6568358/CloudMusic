import io

# ① CloudMusicApp：设置页加「均衡器」行 + EqDialog
p = 'app/src/main/kotlin/com/example/cloudmusic/ui/CloudMusicApp.kt'
s = io.open(p, encoding='utf-8').read()

s = s.replace('''    /** §61.230：淡入淡出开关（AudiofxSettings.fadeEnabled 热更新）。 */
    fadeEnabled: Boolean = false,
    onFadeEnabledChanged: (Boolean) -> Unit = { _ -> },''',
'''    /** §61.230：淡入淡出开关（AudiofxSettings.fadeEnabled 热更新）。 */
    fadeEnabled: Boolean = false,
    onFadeEnabledChanged: (Boolean) -> Unit = { _ -> },
    /** §61.231：均衡器面板（AudiofxSettings + applyEqualizer 全链已接）。 */
    eqEnabled: Boolean = false,
    eqEffectLevel: Int = 0,
    eqBandsMillibel: List<Int> = emptyList(),
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit = { _, _, _ -> },''')
s = s.replace('''                    onAnalyzeReplayGain,
                    fadeEnabled,
                    onFadeEnabledChanged,''',
'''                    onAnalyzeReplayGain,
                    fadeEnabled,
                    onFadeEnabledChanged,
                    eqEnabled,
                    eqEffectLevel,
                    eqBandsMillibel,
                    onApplyEqualizer,''')
s = s.replace('''    onAnalyzeReplayGain: () -> Unit,
    fadeEnabled: Boolean,
    onFadeEnabledChanged: (Boolean) -> Unit,''',
'''    onAnalyzeReplayGain: () -> Unit,
    fadeEnabled: Boolean,
    onFadeEnabledChanged: (Boolean) -> Unit,
    eqEnabled: Boolean,
    eqEffectLevel: Int,
    eqBandsMillibel: List<Int>,
    onApplyEqualizer: (Boolean, Int, List<Int>) -> Unit,''')
s = s.replace('''                FnRow(
                    dividerAbove = true,
                    glyph = "∿",
                    title = "淡入淡出",
                    onClick = { onFadeEnabledChanged(!fadeEnabled) },
                    value = if (fadeEnabled) "开（起播渐入 / 分轨渐出）" else "关",
                    showChevron = false,
                    trailing = { FnSwitch(checked = fadeEnabled, onChange = onFadeEnabledChanged) },
                )
            }''',
'''                FnRow(
                    dividerAbove = true,
                    glyph = "∿",
                    title = "淡入淡出",
                    onClick = { onFadeEnabledChanged(!fadeEnabled) },
                    value = if (fadeEnabled) "开（起播渐入 / 分轨渐出）" else "关",
                    showChevron = false,
                    trailing = { FnSwitch(checked = fadeEnabled, onChange = onFadeEnabledChanged) },
                )
                FnRow(
                    dividerAbove = true,
                    glyph = "≡",
                    title = "均衡器",
                    onClick = { showEq = true },
                    value = if (eqEnabled) "开 · 档位 " + eqEffectLevel else "关",
                )
            }''')
s = s.replace('''    var showFtp by remember { mutableStateOf(false) }''',
'''    var showFtp by remember { mutableStateOf(false) }
    var showEq by remember { mutableStateOf(false) }''')
s = s.replace('''    if (showScanDuration) {
        CompSheetDialog(
            onDismiss = { showScanDuration = false },''',
'''    if (showEq) {
        EqDialog(
            enabled = eqEnabled,
            effectLevel = eqEffectLevel,
            bandsMillibel = eqBandsMillibel,
            onApply = { en, lvl, bands -> onApplyEqualizer(en, lvl, bands) },
            onDismiss = { showEq = false },
        )
    }
    if (showScanDuration) {
        CompSheetDialog(
            onDismiss = { showScanDuration = false },''')

eq_dialog = '''/** §61.231：均衡器面板——5 段滑杆（millibel，±15dB 口径）+ 档位 + 开关；
 *  应用走 applyEqualizer 全链（engine setEqualizerBands band 数不匹配取 min）。 */
@Composable
private fun EqDialog(
    enabled: Boolean,
    effectLevel: Int,
    bandsMillibel: List<Int>,
    onApply: (Boolean, Int, List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val bands = remember(bandsMillibel) {
        if (bandsMillibel.size >= 5) bandsMillibel.take(5) else List(5) { bandsMillibel.getOrNull(it) ?: 0 }
    }
    var on by remember { mutableStateOf(enabled) }
    var level by remember { mutableStateOf(effectLevel.coerceIn(0, 5)) }
    var b0 by remember { mutableStateOf(bands[0].toFloat()) }
    var b1 by remember { mutableStateOf(bands[1].toFloat()) }
    var b2 by remember { mutableStateOf(bands[2].toFloat()) }
    var b3 by remember { mutableStateOf(bands[3].toFloat()) }
    var b4 by remember { mutableStateOf(bands[4].toFloat()) }
    val labels = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val values = listOf(b0, b1, b2, b3, b4)
    val db = { v: Float -> "%.1f dB".format(v / 100f) }
    CompSheetDialog(
        onDismiss = onDismiss,
        title = "均衡器",
        content = {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                FnRow(
                    glyph = "≡",
                    title = "启用均衡器",
                    onClick = { on = !on },
                    showChevron = false,
                    trailing = { FnSwitch(checked = on, onChange = { on = it }) },
                )
                if (on) {
                    Text(
                        "效果档位：$level",
                        color = Comp.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt() },
                        valueRange = 0f..5f,
                        steps = 4,
                    )
                    labels.forEachIndexed { i, label ->
                        Text(
                            label + " · " + db(values[i]),
                            color = Comp.TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        val setter = when (i) {
                            0 -> ({ v -> b0 = v })
                            1 -> ({ v -> b1 = v })
                            2 -> ({ v -> b2 = v })
                            3 -> ({ v -> b3 = v })
                            else -> ({ v -> b4 = v })
                        }
                        Slider(
                            value = values[i],
                            onValueChange = setter,
                            valueRange = -1500f..1500f,
                        )
                    }
                }
            }
        },
        actions = listOf(
            "应用" to {
                onApply(
                    on,
                    level,
                    listOf(b0, b1, b2, b3, b4).map { it.toInt() },
                )
                onDismiss()
            },
            "关闭" to onDismiss,
        ),
    )
}

/** §61.151：STRM 独立源添加（b5y 面板；字段与 StrmDriver str() 对齐——'''
s = s.replace('''/** §61.151：STRM 独立源添加（b5y 面板；字段与 StrmDriver str() 对齐——''', eq_dialog)
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)

# ② MainActivity：EQ 参数接线
p = 'app/src/main/kotlin/com/example/cloudmusic/MainActivity.kt'
s = io.open(p, encoding='utf-8').read()
s = s.replace('''                    fadeEnabled = runtime.fadeEnabled().collectAsState(false).value,
                    onFadeEnabledChanged = { on -> runtime.updateFadeEnabled(on) },''',
'''                    fadeEnabled = runtime.fadeEnabled().collectAsState(false).value,
                    onFadeEnabledChanged = { on -> runtime.updateFadeEnabled(on) },
                    eqEnabled = settings.current().audiofx.enabled,
                    eqEffectLevel = settings.current().audiofx.effectLevel,
                    eqBandsMillibel = settings.current().audiofx.bandsMillibel,
                    onApplyEqualizer = { en, lvl, bands -> runtime.applyEqualizer(en, lvl, bands) },''')
io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
print("eq dialog wired")
