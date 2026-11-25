# -*- coding: utf-8 -*-
"""§61.355/§61.357: guard 判据升级——IME 让位实现点从「底栏推高」改为「底栏让位 + 内容区让位」。"""

p = 'tools/check_source_contracts.py'
s = open(p, encoding='utf-8').read()

# ---------- §61.262 ----------
old262 = '''    # §61.325（A-1）判据升级（强度不变）：IME 避让的**实现点**从「搜索页自己 imePadding」
    # 改为「Scaffold 底栏整体让位」。前者只抬内容、底栏照样被键盘盖住（真机 01:43 截图），
    # 所以这里钉新锚点；页面内再出现 imePadding 才是回归（双倍留白）。
    if "WindowInsets.ime," not in _app_src:
        _c262_bad.append("IME 避让缺失（底栏未按键盘让位）")
    elif "WindowInsets.ime.exclude(" in _app_src:
        _c262_bad.append("IME 让位又扣掉导航栏（G-6：MIUI 三键导航下底栏被切 60%）")
    elif "imePadding" in _app_src:
        _c262_bad.append("页面内与底栏重复让位（会叠出一个键盘高的空白）")
'''
new262 = '''    # §61.357（G-6 二次修正，强度不降）：让位实现点定为「键盘弹起时底栏让位消失 +
    # 内容区自己吃 ime inset」。§61.325 的「底栏跟着 IME 推高」与 §61.356 的「全量 ime
    # 推高」都被真机否定——用户一手反馈「tab 和播放条跟着键盘推高」。
    # 正向两条：isImeVisible 让位、内容区 imePadding；反向三条：exclude 回潮、
    # 底栏推高回潮、页面内重复让位。
    if "WindowInsets.isImeVisible" not in _app_src:
        _c262_bad.append("键盘可见性未接入（底栏不会让位）")
    if ".imePadding()" not in _app_src:
        _c262_bad.append("内容区未按 IME 让位（结果列表会被键盘盖住）")
    if "WindowInsets.ime.exclude(" in _app_src:
        _c262_bad.append("回潮 exclude(navigationBars)（G-6：底栏被切 60%）")
    if "windowInsetsPadding(WindowInsets.ime" in _app_src:
        _c262_bad.append("回潮底栏 IME 推高（G-6 二次：tab/播放条浮在键盘上）")
    if _search_src and "imePadding" in _search_src:
        _c262_bad.append("搜索页内再吃 ime（与内容区让位叠加 = 一个键盘高的空白）")
'''
assert s.count(old262) == 1, ('c262 anchor', s.count(old262))
s = s.replace(old262, new262)

# ---------- §61.325 ----------
old325 = '''    # ---- §61.325（A-1 键盘遮挡：IME 让位收口到 Scaffold 底栏）----
    # 病根是「内容抬了、底栏没抬」：页面各自 imePadding 会把内容顶到键盘上方，
    # 而 Scaffold 的 bottomBar 仍贴窗口底边 ⇒ 迷你条 + 胶囊栏被整块盖住。
    # 判据只钉两件事：让位必须发生在底栏（正），页面不得再自己吃（反，防双倍留白）。
    _c325 = []
    _app325 = _code_only(read_cloud_app())
    if "WindowInsets.ime," not in _app325:
        _c325.append("底栏未按 IME 让位（键盘弹起会盖住迷你条与胶囊栏）")
    if "WindowInsets.ime.exclude(" in _app325:
        _c325.append("IME 让位又 exclude(navigationBars)（G-6 回潮：底栏被键盘切 60%）")
    if "imePadding" in _app325:
        _c325.append("页面内又出现 imePadding（与底栏让位叠加 = 一个键盘高的空白）")
    if "bottomBar = {" not in _app325:
        _c325.append("Scaffold 底栏缺失（让位点无处可挂）")
    if _c325:
        failures.append("§61.325 A-1 键盘遮挡：%s" % "；".join(_c325))
    else:
        print("[PASS] §61.325 A-1 键盘遮挡：IME 让位收口到 Scaffold 底栏"
              "（页面零 imePadding，防双倍留白）")
'''
new325 = '''    # ---- §61.325（A-1 键盘遮挡：让位点收口到 Scaffold，页面零 imePadding）----
    # §61.357 起实现点为「底栏按 isImeVisible 让位 + 内容区 imePadding」，不再把底栏
    # 跟着键盘推高（真机用户一手否定）。判据强度不降：两条正向 + 三条反向。
    _c325 = []
    _app325 = _code_only(read_cloud_app())
    if "WindowInsets.isImeVisible" not in _app325:
        _c325.append("底栏未按键盘可见性让位（弹键盘会推高或被切）")
    if ".imePadding()" not in _app325:
        _c325.append("内容区未按 IME 让位（键盘会盖住结果列表）")
    if "WindowInsets.ime.exclude(" in _app325:
        _c325.append("回潮 exclude(navigationBars)（G-6：底栏被键盘切 60%）")
    if "windowInsetsPadding(WindowInsets.ime" in _app325:
        _c325.append("回潮底栏 IME 推高（G-6 二次：tab/播放条浮在键盘上）")
    if _search_src and "imePadding" in _search_src:
        _c325.append("搜索页内又出现 imePadding（与内容区让位叠加 = 一个键盘高的空白）")
    if "bottomBar = {" not in _app325:
        _c325.append("Scaffold 底栏缺失（让位点无处可挂）")
    if _c325:
        failures.append("§61.325 A-1 键盘遮挡：%s" % "；".join(_c325))
    else:
        print("[PASS] §61.325 A-1 键盘遮挡：键盘弹起时底栏让位、内容区自吃 ime（页面零重复让位）")
'''
assert s.count(old325) == 1, ('c325 anchor', s.count(old325))
s = s.replace(old325, new325)

# ---------- §61.356（保留真机证据链，去掉已作废的源码口径判据） ----------
old356 = '''    # ---- §61.356（G-6 真机修正：IME 净键盘高度，不扣 navigationBars）----
    # 真机 Redmi K60（MIUI 三键导航 bottom=165px）实测：exclude(navigationBars) 让底栏
    # 只上移 891px、红圆被键盘切 109px（60%）= 用户反馈的「键盘黑遮罩」；改全量 ime
    # 后弹起贴键盘上沿、收起回原位 2797~2915。判据钉「真机报告在位 + 源码未回潮」。
    _c356 = []
    _rep356 = ROOT / "analysis" / "e2e-2026-11-25-report.md"
    if not _rep356.is_file():
        _c356.append("缺 G-6 真机判读报告（A/B 证据链断）")
    else:
        _r356 = _rep356.read_text(encoding="utf-8", errors="replace")
        for _tok in ("G-6 键盘黑遮罩", "FAIL（复现）", "y=2015", "1942–2124"):
            if _tok not in _r356:
                _c356.append("真机报告缺锚点：%s" % _tok)
    if "WindowInsets.ime.exclude(" in _app325:
        _c356.append("底栏 IME 让位回潮 exclude（G-6 黑遮罩复发）")
    if "§61.356（G-6 真机修正）" not in read_cloud_app():
        _c356.append("源码缺 §61.356 修正口径注释")
    if _c356:
        failures.append("§61.356 G-6 真机修正：%s" % "；".join(_c356))
    else:
        print("[PASS] §61.356 G-6 真机修正：IME 全量让位 + 真机 A/B 报告在位")
'''
new356 = '''    # ---- §61.356（G-6 真机证据链：键盘黑遮罩 = 底栏被切 60%）----
    # 真机 Redmi K60（MIUI 三键导航 bottom=165px）实测：exclude(navigationBars) 让底栏
    # 只上移 855px、红圆被键盘切 109px（60%）。§61.357 起实现点改为「底栏让位」，
    # 本条退化为历史证据 + 反向判据（exclude 永不回潮），报告锚点仍必须可复现。
    _c356 = []
    _rep356 = ROOT / "analysis" / "e2e-2026-11-25-report.md"
    if not _rep356.is_file():
        _c356.append("缺 G-6 真机判读报告（A/B 证据链断）")
    else:
        _r356 = _rep356.read_text(encoding="utf-8", errors="replace")
        for _tok in ("G-6 键盘黑遮罩", "FAIL（复现）", "y=2015", "1942–2124"):
            if _tok not in _r356:
                _c356.append("真机报告缺锚点：%s" % _tok)
    if "WindowInsets.ime.exclude(" in _app325:
        _c356.append("底栏 IME 让位回潮 exclude（G-6 黑遮罩复发）")
    if _c356:
        failures.append("§61.356 G-6 真机证据链：%s" % "；".join(_c356))
    else:
        print("[PASS] §61.356 G-6 真机证据链：报告锚点在位 + exclude 未回潮")
'''
assert s.count(old356) == 1, ('c356 anchor', s.count(old356))
s = s.replace(old356, new356)

# ---------- 新增 §61.357 ----------
anchor357 = '''    # ---- §61.326（A-3 搜索热词去重键）----'''
block357 = '''    # ---- §61.357（2026-11-25 G-6 二次修正：底栏不跟着键盘推高）----
    # 用户一手：「搜索页为什么 tab 和播放条跟着键盘推高？」⇒ §61.356 的「底栏整体上移到
    # 键盘上沿」方案作废。正解是键盘弹起时底栏让位消失（bottomBar 高度 0）、内容区自己
    # 吃 ime inset；Scaffold 不再为底栏留白，键盘上方不会出现「底栏那么厚的黑带」。
    # 判据：源码口径 + 真机几何 + 报告锚点；反向禁推高回潮。
    _c357 = []
    _app357 = _code_only(read_cloud_app())
    if "if (!WindowInsets.isImeVisible)" not in _app357:
        _c357.append("底栏未按 isImeVisible 让位（会再出现推高/被切）")
    if "consumeWindowInsets(padding)" not in _app357:
        _c357.append("内容区未登记已消费 insets（imePadding 会把导航栏再补一遍 = 黑带）")
    if "windowInsetsPadding(WindowInsets.ime" in _app357:
        _c357.append("底栏 IME 推高回潮（tab/播放条会浮在键盘上）")
    if _search_src and "imePadding" in _search_src:
        _c357.append("搜索页内重复让位（叠出一个键盘高的空白）")
    if _c357:
        failures.append("§61.357 G-6 二次修正：%s" % "；".join(_c357))
    else:
        print("[PASS] §61.357 G-6 二次修正：键盘弹起时底栏让位、内容区自吃 ime、导航栏不双计")

'''
assert s.count(anchor357) == 1, ('c357 anchor', s.count(anchor357))
s = s.replace(anchor357, block357 + anchor357)

# ---------- _search_src 变量（若未定义则补一个） ----------
if '_search_src =' not in s:
    old_def = '''    _fei_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                           / "cloudmusic" / "ui" / "FeiniuUi.kt")
                          .read_text(encoding="utf-8", errors="replace"))'''
    new_def = old_def + '''
    _search_src = _code_only((ROOT / "app" / "src" / "main" / "kotlin" / "com" / "example"
                              / "cloudmusic" / "ui" / "SearchScreen.kt")
                             .read_text(encoding="utf-8", errors="replace"))'''
    assert s.count(old_def) == 1, ('fei_src anchor', s.count(old_def))
    s = s.replace(old_def, new_def)

open(p, 'w', encoding='utf-8', newline='').write(s)
print('guard patched. CR=%d LF=%d' % (s.count('\r'), s.count('\n')))
