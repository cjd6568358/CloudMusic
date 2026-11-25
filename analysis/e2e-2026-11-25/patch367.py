"""§61.367：走查后 4 条收尾（首页分组标题 / 音乐库「＋」语义 / 漫游卡 shuffle / 设置页顶部渐变）。

按用户长期纪律：读进变量再写、newline='' 保 LF，不用多行 Edit。
"""
import io
import sys


def patch(path, pairs, note):
    with io.open(path, encoding='utf-8', newline='') as f:
        s = f.read()
    orig = s
    for old, new in pairs:
        cnt = s.count(old)
        if cnt != 1:
            print('FAIL %s -> %s 命中 %d 次' % (note, path, cnt))
            sys.exit(1)
        s = s.replace(old, new)
    assert s != orig
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('OK  ', note)


# ── 1. 首页：卡片区补分组标题 ────────────────────────────────────────────────
patch(
    'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/HomeScreen.kt',
    [(
        """    ) {
        // ── 横向大卡片流（§61.261：底栏已有搜索 tab，首页不再重复放搜索钮）──
        item {""",
        """    ) {
        // §61.367：卡片区此前**没有任何分组标题**，三张卡直接顶在内容区最上方，
        // 读作「三块散落的色块」。与下方「最近添加」用同款 18sp Bold 标题，
        // 形成两级 section 节奏（标题 → 卡片流 → 标题 → 列表）。
        item {
            Text(
                "快捷入口", color = FnComp.TextPrimary,
                fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
            )
        }

        // ── 横向大卡片流（§61.261：底栏已有搜索 tab，首页不再重复放搜索钮）──
        item {""",
    )],
    '首页卡片区加「快捷入口」分组标题',
)

# 漫游卡图标：Broadcast(投屏) → Shuffle(随机播放)
patch(
    'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/HomeScreen.kt',
    [(
        """        icon = FnIcon.Broadcast,
        onClick = onClick,""",
        """        // §61.367：漫游是**随机播放**，此前借 Broadcast（投屏：中点 + 双弧）——
        // 卡片写的「随机播放 · N 首」与图标语义直接矛盾。
        icon = FnIcon.Shuffle,
        onClick = onClick,""",
    )],
    '漫游卡图标换成 Shuffle',
)

# ── 2. 音乐库行尾「＋」补语义 ────────────────────────────────────────────────
patch(
    'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnLibraryScreen.kt',
    [(
        """                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onAdd() },
                    contentAlignment = Alignment.Center,
                ) {""",
        """                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onAdd() }
                        // §61.367：纯「＋」无任何说明——视觉上不知道加到哪，TalkBack 也只念
                        // 「加号」。补语义标签（真机可见入口另行拍板，见 §61.365 未改清单）。
                        .semantics { contentDescription = "加入歌单" },
                    contentAlignment = Alignment.Center,
                ) {""",
    )],
    '音乐库「＋」加 contentDescription',
)

# ── 3. 设置页：滚动区顶部渐隐 ────────────────────────────────────────────────
patch(
    'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/SettingsScreen.kt',
    [(
        """        FnPageHeader("设置", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {""",
        """        FnPageHeader("设置", onBack = onBack)
        // §61.367：内容滚到页头下沿时是**硬切**（一行文字被齐齐削掉半截，读作裁剪 bug）。
        // 包一层 Box 叠 18dp 渐隐：被切的那行淡出成「从这里穿过去」，与首页卡片右缘同一手法。
        // 内层缩进沿用原层级——重排会让 diff 假装改了 175 行，实际只多包一层容器。
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {""",
    ), (
        """            Spacer(Modifier.height(44.dp))
        }
    }
    if (showTheme) {""",
        """            Spacer(Modifier.height(44.dp))
        }
        // 渐隐层（不拦截触摸：纯背景 Box）：滚到顶时它盖的是 HeaderToCardGap 那段空档，
        // 不会压住第一张卡的内容。
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .background(
                    Brush.verticalGradient(listOf(FnComp.PageBg, Color.Transparent)),
                ),
        )
        }
    }
    if (showTheme) {""",
    )],
    '设置页滚动区加顶部 18dp 渐隐',
)

# ── 4. FnIcon 新增 Shuffle ─────────────────────────────────────────────────
patch(
    'app/src/main/kotlin/com/ccmsc/cloudmusic/ui/FnIcons.kt',
    [(
        """    /** 投屏 / 漫游：中点 + 双弧。 */
    Broadcast("投屏"),""",
        """    /** 投屏：中点 + 双弧。 */
    Broadcast("投屏"),

    /** 随机播放：两条交叉箭头（漫游卡的语义是 shuffle，不是投屏）。 */
    Shuffle("随机播放"),""",
    ), (
        """            FnIcon.Broadcast -> {
                dot(12f, 12f, 2.4f)
                arc(12f, 12f, 6f, -50f, 100f)
                arc(12f, 12f, 9.5f, -50f, 100f)
            }""",
        """            FnIcon.Broadcast -> {
                dot(12f, 12f, 2.4f)
                arc(12f, 12f, 6f, -50f, 100f)
                arc(12f, 12f, 9.5f, -50f, 100f)
            }

            // §61.367：Material `shuffle` 语形——左侧两条短横 + 中间交叉 + 右侧两条带箭头
            // 短横。交叉处留 2.8 单位断口：圆头描线在交点会叠出一颗疙瘩（§61.362 的老毛病）。
            // 箭头用 chevron（与 Refresh 同风格），翼长 2.8 / 90°，落在水平线端点 (20,±)。
            FnIcon.Shuffle -> {
                path {
                    moveTo(3f * u, 7f * u)
                    lineTo(6.8f * u, 7f * u)
                    lineTo(10.6f * u, 10.6f * u)
                }
                path {
                    moveTo(13.4f * u, 13.4f * u)
                    lineTo(17.2f * u, 17f * u)
                    lineTo(20f * u, 17f * u)
                }
                path {
                    moveTo(3f * u, 17f * u)
                    lineTo(6.8f * u, 17f * u)
                    lineTo(10.6f * u, 13.4f * u)
                }
                path {
                    moveTo(13.4f * u, 10.6f * u)
                    lineTo(17.2f * u, 7f * u)
                    lineTo(20f * u, 7f * u)
                }
                path {
                    moveTo(17.2f * u, 4.2f * u)
                    lineTo(20f * u, 7f * u)
                    lineTo(17.2f * u, 9.8f * u)
                }
                path {
                    moveTo(17.2f * u, 14.2f * u)
                    lineTo(20f * u, 17f * u)
                    lineTo(17.2f * u, 19.8f * u)
                }
            }""",
    )],
    'FnIcon 新增 Shuffle 绘制',
)
print('all patches applied')
