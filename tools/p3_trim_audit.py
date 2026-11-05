#!/usr/bin/env python3
"""P3 · 三个照搬舱 Java 依赖闭包裁剪清单复核（docs/11 §7 #3 / 12 §7 #4）。

不依赖 jadx：直接复用 `analysis/spike0-java-closure.json` 的 BFS 产物，
再用 20 个 dex 的 `class_def` 做三件确定性校验：

  ① 84 个 JNI 种子类：按 4 个包族**精确前缀**重新枚举，核对 48/8/26/2；
  ② 168 个一层边界：剔除 Dalvik 原始类型描述符伪影，核对每个类在 dex 里是否有定义；
  ③ 给每个边界类打「包族 / 类别 / stub 策略」标签，产出可执行的裁剪清单。

这把 `11 §7 #3` 的「jadx 到手后只需校验 84 + 168 个类」提前到纯 JVM 侧完成。
产物：`analysis/p3-trim-audit.json` / `.csv`。

用法：
    python tools/p3_trim_audit.py
"""
from __future__ import annotations

import csv
import glob
import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from spike0_java_closure import DexFile  # noqa: E402

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
SPIKE0 = ROOT / "analysis" / "spike0-java-closure.json"
DEX_GLOB = str(ROOT / "analysis" / "decoded" / "classes*.dex")
OUT_JSON = ROOT / "analysis" / "p3-trim-audit.json"
OUT_CSV = ROOT / "analysis" / "p3-trim-audit.csv"

# 11 §2.1 的 4 个 JNI 包族（精确前缀，不用子串 marker）
SEED_FAMILIES = {
    "playerengine": "com.netease.cloudmusic.android.sdk.playerengine.",
    "playlist.core": "com.netease.cloudmusic.playlist.core.",
    "NMCommonCache": "com.netease.cloudmusic.sdk.NMCommonCache.",
    "NEAudioDevice": "com.netease.cloudmusic.media.NEAudioDevice",
}
EXPECTED = {"playerengine": 48, "playlist.core": 8, "NMCommonCache": 26, "NEAudioDevice": 2}

# Dalvik 原始类型描述符（spike0 的 dotted() 未过滤，被误计为「类」）
PRIMITIVES = {"B", "C", "D", "F", "I", "J", "S", "V", "Z"}

CATEGORY_RULES = (
    (re.compile(r"\.R\$|\.R$"), "resource-r-class", "empty-stub"),
    (re.compile(r"core\.logsalvage|core\.apm|core\.patch|core\.startup|core\.heat"),
     "telemetry-startup", "empty-stub"),
    (re.compile(r"core\.mnn"), "mnn-model", "empty-stub"),
    (re.compile(r"core\.mp\b"), "mp-framework", "empty-stub"),
    (re.compile(r"core\.nim2|nimlib|\.im\b"), "im-online", "empty-stub"),
    (re.compile(r"core\.lyric"), "lyric-model", "keep-signature"),
    (re.compile(r"commoninterface|Callback$|Listener$|\.callback\.|DataProvider|Listener"),
     "callback-interface", "keep-signature"),
    (re.compile(r"\.ui\.|BottomSheetDialog|Dialog"), "ui-widget", "empty-stub"),
    (re.compile(r"^(?:[a-z0-9]{1,4})(?:\.|$)"), "obfuscated-short", "empty-stub"),
)


def categorize(name: str):
    for pat, category, strategy in CATEGORY_RULES:
        if pat.search(name):
            return category, strategy
    return "other", "empty-stub"


def collect_dex_classes():
    """返回 {全限定类名: dex 文件名}。"""
    out: dict[str, str] = {}
    for path in sorted(glob.glob(DEX_GLOB)):
        df = DexFile(path)
        dex = Path(path).name
        for _cdi, cidx, _s, _i, _cdo in df.class_defs():
            desc = df.type_desc(cidx)
            if desc.startswith("L") and desc.endswith(";"):
                out.setdefault(desc[1:-1].replace("/", "."), dex)
    return out


def main() -> int:
    if not SPIKE0.is_file():
        print(f"[FAIL] 缺 {SPIKE0}")
        return 1
    spike = json.loads(SPIKE0.read_text(encoding="utf-8"))
    dex_classes = collect_dex_classes()
    print(f"dex 类定义 {len(dex_classes)} 个（20 个 dex）")

    # ① 种子复核
    exact_seeds = {
        fam: sorted(n for n in dex_classes if n.startswith(prefix))
        for fam, prefix in SEED_FAMILIES.items()
    }
    seed_check = {
        fam: {"exact": len(names), "documented": EXPECTED[fam],
              "spike0_seed_hit": sum(1 for n in names if n in set(spike["seeds"]))}
        for fam, names in exact_seeds.items()
    }

    # ② 一层边界复核
    boundary = sorted(spike["minimal_boundary_classes"])
    artifacts = [n for n in boundary if n in PRIMITIVES or n.startswith("[")]
    real = [n for n in boundary if n not in artifacts]
    with_def = [n for n in real if n in dex_classes]
    ghost = [n for n in real if n not in dex_classes]

    # ③ 分类与策略
    rows = []
    for name in real:
        category, strategy = categorize(name)
        rows.append({
            "class": name,
            "package": ".".join(name.split(".")[:-1]),
            "category": category,
            "stub_strategy": strategy,
            "has_class_def": name in dex_classes,
            "dex": dex_classes.get(name, ""),
        })
    by_category = Counter(r["category"] for r in rows)
    by_strategy = Counter(r["stub_strategy"] for r in rows)

    payload = {
        "method": "P3 裁剪清单复核：spike0 BFS 产物 + 20 dex 的 class_def 精确校验",
        "seed_recheck": {
            "by_family": seed_check,
            "spike0_seed_count": spike["seed_count"],
            "exact_total": sum(len(v) for v in exact_seeds.values()),
        },
        "boundary_recheck": {
            "spike0_count": len(boundary),
            "primitive_artifacts": sorted(artifacts),
            "artifact_count": len(artifacts),
            "real_class_count": len(real),
            "with_class_def": len(with_def),
            "ghost_without_def": sorted(ghost),
        },
        "by_category": dict(by_category.most_common()),
        "by_stub_strategy": dict(by_strategy.most_common()),
        "boundary_classes": rows,
        "exact_seeds": exact_seeds,
    }
    OUT_JSON.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    with OUT_CSV.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["class", "package", "category", "stub_strategy", "has_class_def", "dex"])
        for r in rows:
            w.writerow([r["class"], r["package"], r["category"], r["stub_strategy"],
                        r["has_class_def"], r["dex"]])

    print("\n=== ① 84 个 JNI 种子类（精确前缀复核） ===")
    for fam, v in seed_check.items():
        print(f"  {fam:<18} 精确={v['exact']:<3} 文档={v['documented']:<3} "
              f"spike0 命中={v['spike0_seed_hit']}")
    print(f"  合计精确={payload['seed_recheck']['exact_total']} / spike0={spike['seed_count']}")
    print("\n=== ② 168 个一层边界 ===")
    print(f"  spike0 计数={len(boundary)}；伪影={len(artifacts)} {sorted(artifacts)}")
    print(f"  真实类={len(real)}；有类定义={len(with_def)}；无定义(ghost)={len(ghost)}")
    print("\n=== ③ 类别 / stub 策略 ===")
    for k, v in by_category.most_common():
        print(f"  {k:<22} {v}")
    for k, v in by_strategy.most_common():
        print(f"  strategy {k:<18} {v}")
    print(f"\nJSON -> {OUT_JSON}\nCSV  -> {OUT_CSV}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
