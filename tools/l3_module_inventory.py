#!/usr/bin/env python3
"""12 §61.43：竞品「一级模块 / 二级入口」清单的机器可读实证。

纯 Python 零依赖、只读反编译产物，产出 analysis/l3-module-inventory.json。

取证口径（与项目真相源层级一致）：
  1. analysis/decoded/AndroidManifest.xml —— 799 个 <activity> 声明是「可进入的页面」
     的可数证据；activity-alias / service / receiver / provider 是组件规模证据。
  2. analysis/decoded/res/values/strings.xml —— 竞品中文功能名（一级 / 二级模块）。
  3. analysis/smali14/ —— 交互 / UI 代码的落点（按包路径统计 .smali 类数）。
  4. analysis/decoded/res/layout|drawable*|anim|values —— UI 骨架与素材的落点。
  5. settings.gradle.kts / docs/01-产品文档.md —— 自研侧模块与产品范围，用于对照。

本脚本只做「可数证据」的统计；「保留 / 剔除 / 冲突」的判定与开发优先级写在
docs/17-竞品模块清单与复刻规划.md，判定依据逐条可回溯，不在此伪造结论。
"""
from __future__ import annotations

import json
import re
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "analysis" / "decoded" / "AndroidManifest.xml"
STRINGS = ROOT / "analysis" / "decoded" / "res" / "values" / "strings.xml"
SMALI = ROOT / "analysis" / "smali14"
RES = ROOT / "analysis" / "decoded" / "res"
SETTINGS = ROOT / "settings.gradle.kts"
PRD = ROOT / "docs" / "01-产品文档.md"
LEGACY_ASSETS = ROOT / "app" / "src" / "main" / "assets" / "legacy-player"
OUT = ROOT / "analysis" / "l3-module-inventory.json"

# strings.xml 里实证存在的中文功能名（不做翻译、不做补全）。
CHINESE_LABELS = [
    "发现", "首页", "我的", "云村", "播客", "电台", "视频", "商城", "直播", "关注",
    "动态", "私人漫游", "私人FM", "每日推荐", "排行榜", "歌单", "搜索", "本地音乐",
    "正在播放", "听歌识曲", "设置", "扫描设置", "收藏", "评论", "云圈", "MLOG",
    "MV", "专辑", "歌手", "歌曲", "声音", "群聊", "笔记", "草稿箱", "云盘",
    "我的音乐", "歌单广场", "消息", "私信", "会员", "VIP", "音效", "歌词",
    "投屏", "蓝牙", "DLNA", "驾驶", "HiCar", "广告", "分享",
]

# 竞品包名 → 一级功能域归并（人工映射，依据 = activity 包路径字面量）。
# 未命中的组保留原始 key，标 unclassified，不强行归类。
DOMAIN_MAP: dict[str, str] = {
    "activity": "播放器 / 主 Activity 族",
    "music.biz.player": "播放器",
    "music.biz.playlist": "歌单",
    "music.biz.search": "搜索",
    "music.biz.localmusic": "本地音乐 / 曲库",
    "music.biz.scan": "扫描 / 本地音乐",
    "music.biz.clouddisk": "我的音乐云盘",
    "music.biz.lyric": "歌词",
    "music.biz.voice": "播客 / 电台 / 声音",
    "music.biz.sidebar": "侧边栏 / 设置",
    "music.biz.setting": "设置",
    "music.biz.comment": "评论",
    "music.biz.user": "账号 / 个人主页",
    "music.biz.login": "登录 / 账号",
    "music.biz.video": "视频 / MV",
    "music.biz.live": "直播",
    "music.biz.mall": "商城",
    "music.biz.pay": "支付 / 会员",
    "music.biz.ad": "广告",
    "music.biz.social": "社交 / 动态",
    "music.biz.cloudvillage": "云村 / 社区",
    "music.biz.message": "消息 / 私信",
    "music.biz.recommend": "推荐 / 每日推荐",
    "music.biz.radio": "电台 / 私人漫游",
    "music.biz.dlna": "DLNA / 投屏",
    "music.biz.bluetooth": "蓝牙 / 输出",
    "music.biz.output": "输出设备",
    "messagecenter": "消息中心",
    "module": "业务模块根",
    "social": "社交",
    "login": "登录",
    "user": "用户",
    "comment": "评论",
    "search": "搜索",
    "video": "视频",
    "live": "直播",
    "mall": "商城",
    "shop": "商城",
    "pay": "支付",
    "ad": "广告",
    "push": "推送",
    "webview": "WebView / H5",
    "dialogtest": "调试 / 测试",
}

# PRD §3 范围总览的「明确不做」清单（逐字取自 docs/01-产品文档.md 第 63 行）。
PRD_NEVER = ["账号", "社交", "评论", "推荐", "商城", "直播", "广告", "支付"]

# `com.netease.cloudmusic.activity.*` 的 120 个类名语义化（未混淆），按类名关键词
# 归并成「一级功能域」；先匹配先归类，未命中归「其他」。这是二级入口的直接证据。
ACTIVITY_FAMILY_RULES: list[tuple[str, list[str]]] = [
    ("播放器（含横屏 / 电台 / 车机 / 迷你 / 锁屏变体）",
     ["Player", "MiMiniPlayer", "Vinyl", "LockScreen"]),
    ("歌单", ["PlayList", "Playlist", "MainPlaylist", "AddToPlayList", "EditPlayList",
              "PlayListTagManager", "RNPlaylistSort", "MusicSheet", "ColumnCollection"]),
    ("曲库 / 本地音乐 / 扫描 / 音质",
     ["ScanActivity", "SDcardAuthorize", "MissingFile", "AlbumActivity", "ArtistActivity",
      "ArtistList", "ArtistBillboard", "TrackActivitySearch", "AudioCacheSetting",
      "MusicDownloadQuality", "MusicOnlineQuality", "ForwardTrack"]),
    ("搜索 / 推荐 / 榜单",
     ["Search", "SimilarRecommend", "SongTaste", "LookMoreClassify", "ListenMusicRank"]),
    ("歌词", ["Lyric", "FloatLyric"]),
    ("账号 / 社交 / 关注 / 邀请 / 个人主页",
     ["Follow", "InviteFriend", "StarUser", "ProfileBgEdit", "SetAlias",
      "CloudMusicMusician", "ColumnActivity"]),
    ("评论 / 反馈 / 举报", ["EditComment", "ReportActivity", "FeedbackActivity"]),
    ("直播 / K歌 / 电台节目",
     ["Live", "Ktv", "ListenLive", "ListenMoreLive", "SportRadioFinish",
      "MainPageCircleLive", "MainPageMoreLive"]),
    ("视频 / MV", ["Video", "PictureVideo", "SimpleVideo"]),
    ("会员 / 打赏 / 广告", ["Vip", "Reward", "Ad", "LoadingAd"]),
    ("主题 / 装扮 / 黑胶 DIY", ["Theme", "VinylCrop", "VinylDIY", "VinylPictureVideo"]),
    ("H5 / WebView / RN 容器", ["Browser", "Web", "ReactNative", "RN", "H5",
                                "Redirect", "RouterDemo", "StudyRoom"]),
    ("设备 / 车机 / 智联", ["DeviceManagement", "SmartDevice", "VehicleFM"]),
    ("设置 / 关于 / 隐私", ["About", "PlayProtect", "PrivacySafe", "GeneralResource",
                            "Introduce", "DownloadPrompt"]),
    ("分享 / 图片", ["Share", "Image", "Pic", "ThanksForReward"]),
    ("调试 / 中转 / 加载", ["Demo", "LoadingActivity", "Transparent",
                            "ShortcutTrampoline", "MainProcess", "EmbedBrowser",
                            "AdActionClientInfo"]),
]


def activity_family_breakdown(activities: list[str]) -> list[dict]:
    """把主 Activity 包的语义类名归并成一级功能域，给出二级入口数与代表类。"""
    named = sorted({a.rsplit(".", 1)[-1] for a in activities
                    if a.startswith("com.netease.cloudmusic.activity.")})
    buckets: dict[str, list[str]] = defaultdict(list)
    for cls in named:
        for domain, keys in ACTIVITY_FAMILY_RULES:
            if any(k in cls for k in keys):
                buckets[domain].append(cls)
                break
        else:
            buckets["其他（未归类）"].append(cls)
    return [
        {
            "domain": domain,
            "second_level_entry_count": len(classes),
            "activity_classes": classes[:20],
        }
        for domain, classes in sorted(buckets.items(), key=lambda kv: -len(kv[1]))
    ]


def cross_dex_hits() -> dict[str, int]:
    """跨 20 个 dex 的字符串表统计 `com/netease/cloudmusic/<X>/...` 命中数。

    这是「交互 / UI 代码落点」的全量证据：analysis/smali14/ 只反汇编了 classes14
    一个 dex，不能代表全量；这里用 analysis/strings/*.strings.txt 补齐覆盖面。
    """
    counts: Counter[str] = Counter()
    pattern = re.compile(r"com/netease/cloudmusic/([a-zA-Z0-9_]+)(?:/([a-zA-Z0-9_]+))?")
    for f in sorted((ROOT / "analysis" / "strings").glob("*.strings.txt")):
        with f.open(encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = pattern.search(line)
                if not m:
                    continue
                first, second = m.group(1), m.group(2)
                if first == "music" and second == "biz":
                    tail = line[m.end():].lstrip("/")
                    third = tail.split("/", 1)[0].split(";", 1)[0]
                    counts["music.biz." + third] += 1
                elif first == "music" and second:
                    counts["music." + second] += 1
                else:
                    counts[first] += 1
    return dict(counts)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def group_of(name: str) -> str:
    """按 com.netease.cloudmusic.<X> / music.biz.<Y> 归并，其余保留首段。"""
    prefix = "com.netease.cloudmusic."
    if not name.startswith(prefix):
        return "third_party"
    parts = name[len(prefix):].split(".")
    if parts[0] == "music" and len(parts) >= 3 and parts[1] == "biz":
        return "music.biz." + parts[2]
    if parts[0] == "music" and len(parts) >= 2:
        return "music." + parts[1]
    return parts[0]


def smali_count(group: str) -> int:
    if group == "third_party":
        return 0
    d = SMALI / "com" / "netease" / "cloudmusic" / Path(*group.split("."))
    if not d.is_dir():
        return 0
    return sum(1 for _ in d.rglob("*.smali"))


def leaf_features(names: list[str]) -> list[str]:
    """从 Activity 类名里提取二级子功能词（去重、去通用后缀），作为子功能线索。"""
    noise = {"activity", "activityv1", "ui", "fragment", "dialog", "view", "page",
             "host", "main", "base", "new", "v1", "v2", "detail", "activityv2"}
    out: Counter[str] = Counter()
    for n in names:
        leaf = n.rsplit(".", 1)[-1]
        words = re.findall(r"[A-Z][a-z0-9]*|[a-z]+", leaf)
        for w in words:
            lw = w.lower()
            if lw and lw not in noise and len(lw) >= 3 and not lw.endswith("activity"):
                out[w] += 1
    return [w for w, _ in out.most_common(18)]


def chinese_hits(text: str) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for label in CHINESE_LABELS:
        ids = re.findall(r'<string name="([^"]+)">[^<]*' + re.escape(label) + r'[^<]*</string>', text)
        if ids:
            out[label] = {"string_ids": ids[:6], "hit_count": len(ids)}
    return out


def count_includes(text: str) -> int:
    return sum(1 for ln in text.splitlines()
               if not ln.lstrip().startswith("//") and re.match(r"^\s*include\(", ln))


def res_counts() -> dict[str, int]:
    out: dict[str, int] = {}
    for d in sorted(RES.iterdir()):
        if d.is_dir() and d.name.startswith(("layout", "drawable", "anim", "color", "values")):
            out[d.name] = sum(1 for _ in d.iterdir() if _.is_file())
    return out


def main() -> int:
    manifest = read(MANIFEST)
    strings_xml = read(STRINGS)
    settings = read(SETTINGS)
    prd = read(PRD)

    activities = re.findall(r'<activity\s[^>]*?android:name="([^"]+)"', manifest, re.S)
    aliases = re.findall(r'<activity-alias\b[^>]*?android:name="([^"]+)"', manifest, re.S)
    services = re.findall(r'<service\b[^>]*?android:name="([^"]+)"', manifest, re.S)
    receivers = re.findall(r'<receiver\b[^>]*?android:name="([^"]+)"', manifest, re.S)
    providers = re.findall(r'<provider\b[^>]*?android:name="([^"]+)"', manifest, re.S)
    permissions = re.findall(r"<uses-permission\b[^>]*?android:name=\"([^\"]+)\"", manifest, re.S)

    launcher = re.findall(
        r'<activity\s[^>]*?android:name="([^"]+)"[^>]*>.*?'
        r'<action android:name="android.intent.action.MAIN".*?</activity>', manifest, re.S,
    )

    groups: Counter[str] = Counter()
    group_acts: dict[str, list[str]] = defaultdict(list)
    for a in activities:
        g = group_of(a)
        groups[g] += 1
        group_acts[g].append(a)

    dex_hits = cross_dex_hits()
    modules = []
    for key, cnt in groups.most_common():
        names = sorted(group_acts[key])
        modules.append({
            "package_group": key,
            "domain": DOMAIN_MAP.get(key, "未归类（原样保留包路径）"),
            "activity_count": cnt,                       # 该一级模块下可进入的页面数
            "second_level_entries": len({n.rsplit(".", 1)[-1] for n in names}),
            "representative_activities": names[:10],
            "sub_feature_hints": leaf_features(names),  # 从类名提取的子功能线索
            "code_path": ("analysis/smali14/com/netease/cloudmusic/" + key.replace(".", "/") + "/")
                         if key != "third_party" else "非 com.netease.cloudmusic 包",
            "smali14_class_count": smali_count(key),     # 仅 classes14 单 dex 覆盖数，非全量
            "cross_dex_string_hits": dex_hits.get(key, 0),  # 20 个 dex 字符串表的全量引用量
        })

    self_modules = re.findall(r'include\(":([\w-]+)"\)', settings)
    never_line = next((ln.strip() for ln in prd.splitlines() if "永久不做" in ln), "")

    inventory = {
        "method": "12 §61.43：竞品模块清单实证（Manifest 组件计数 + strings.xml 中文功能名 + smali/res 落点）",
        "generated_by": "tools/l3_module_inventory.py",
        "sources": [
            "analysis/decoded/AndroidManifest.xml",
            "analysis/decoded/res/values/strings.xml",
            "analysis/decoded/res/layout|drawable*|anim|values/",
            "analysis/smali14/",
            "analysis/manifest-and-strings-report.txt",
            "settings.gradle.kts",
            "docs/01-产品文档.md",
        ],
        "competitor_totals": {
            "activities": len(activities),
            "activity_aliases": len(aliases),
            "services": len(services),
            "receivers": len(receivers),
            "providers": len(providers),
            "permissions": len(permissions),
            "launcher_activities": launcher[:5],
            "res_dir_file_counts": res_counts(),
            "smali_class_total": sum(1 for _ in SMALI.rglob("*.smali")),
            "exported_legacy_asset_files": sum(1 for _ in LEGACY_ASSETS.rglob("*") if _.is_file())
                                           if LEGACY_ASSETS.is_dir() else 0,
        },
        "chinese_module_labels": chinese_hits(strings_xml),
        "activity_family_breakdown": activity_family_breakdown(activities),
        "package_groups": modules,
        "cross_dex_group_hits": dex_hits,
        "self_side": {
            "module_count": count_includes(settings),
            "modules": self_modules,
            "prd_never_line": never_line,
            "prd_never_keywords": PRD_NEVER,
        },
        "evidence_paths": {
            "interaction_and_ui_code": "analysis/smali14/com/netease/cloudmusic/<package_group>/",
            "ui_skeleton": "analysis/decoded/res/layout*/（含 android:tag 语义名）",
            "visual_assets": "analysis/decoded/res/drawable*/ 与 analysis/decoded/res/anim/",
            "exported_reusable_assets": "app/src/main/assets/legacy-player/（D-146 导出 51 文件）",
            "chinese_names": "analysis/decoded/res/values/strings.xml",
            "resource_id_map": "analysis/decoded/res/values/public.xml",
        },
        "honest_notes": [
            "静态 Manifest 只能数出「可进入的页面」，不能还原底部导航 tab 的精确个数与顺序；"
            "一级模块清单按功能域分组，导航顺序需真机 UI 层级 / 截图实证，不推测。",
            "sub_feature_hints 由 Activity 类名机械提取，是线索不是完整子功能定义。",
            "analysis/smali14/ 只反汇编了 classes14 单个 dex，smali14_class_count 为该 dex 的覆盖数；"
            "cross_dex_string_hits 才是跨 20 个 dex 的引用量证据（analysis/strings/*.strings.txt）。",
            "保留 / 剔除 / 冲突判定与开发优先级在 docs/17，判定依据逐条回溯到 PRD §3 与用户口径。",
        ],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    t = inventory["competitor_totals"]
    print(f"[INFO] activity={t['activities']} alias={t['activity_aliases']} service={t['services']} "
          f"receiver={t['receivers']} provider={t['providers']} permission={t['permissions']}")
    print(f"[INFO] res layout={t['res_dir_file_counts'].get('layout')} "
          f"drawable-xxhdpi={t['res_dir_file_counts'].get('drawable-xxhdpi')} "
          f"anim={t['res_dir_file_counts'].get('anim')} smali={t['smali_class_total']}")
    print(f"[INFO] 包分组 {len(modules)} 个 / 中文模块名 {len(inventory['chinese_module_labels'])} 个 / "
          f"自研模块 {inventory['self_side']['module_count']} 个")
    print("[INFO] 主 Activity 包的一级功能域（二级入口数）：")
    for f in inventory["activity_family_breakdown"]:
        print(f"    {f['domain']:<42} 入口={f['second_level_entry_count']:>3}")
    print("[INFO] 前 15 个包分组：")
    for m in modules[:15]:
        print(f"    {m['package_group']:<34} activity={m['activity_count']:>4} "
              f"dex命中={m['cross_dex_string_hits']:>6}  {m['domain']}")
    print(f"[INFO] 证据 -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
