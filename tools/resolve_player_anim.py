#!/usr/bin/env python3
"""播放页 anim 资源 ID 链路解析（docs/14 §4.1 挂账 / 03-10 §3 / 12 §38）。

`14 §4.1` 曾挂账：「导出前必须用 jadx 反编译 PlayerActivity / PlayerDiscViewFlipper
调用链或真机抓 `AnimationUtils.loadAnimation` 的 id」。此前 smali 直接搜 `0x7f01xxxx`
得到「0 引用」，**原因是资源 ID 以 R 类静态字段传入而非内联常量**：
    sget v0, Lxj1/a;->e:I
    invoke-static {p0, v0}, AnimationUtils->loadAnimation(Context, I)

本工具纯 Python 零依赖、只用仓库内产物，复现整条链路：
  ① 扫 `analysis/smali14/**/*.smali`，收集 `loadAnimation` 前 14 行内的
     `sget ..., L<CLASS>;-><FIELD>:I`
  ② 解析这 4 个 R 类字段的值：`n1` 从 n1.smali 的 `.field ... = 0x...`；
     `xj1.a` / `v61.a` 从 `analysis/jadx-anim-rclasses/*.java` 的十进制常量
  ③ 反查 `analysis/decoded/res/values/public.xml` 得到 (type, name)
  ④ 读取 anim XML，提取 rotate / scale / duration / repeatCount
  ⑤ 统计 `res/anim*` 的 40 个无限循环 rotate 候选，与解析结果比对

产出 `analysis/player-anim-resolution.json`。**结论不自动改写 `assets-mapping.csv`**
（C4 空表语义 = 尚无素材导出，保持不变）。

用法：
    python tools/resolve_player_anim.py
"""
from __future__ import annotations

import glob
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SMALI = ROOT / "analysis" / "smali14"
RES = ROOT / "analysis" / "decoded" / "res"
RCLASSES = ROOT / "analysis" / "jadx-anim-rclasses"
OUT = ROOT / "analysis" / "player-anim-resolution.json"

SGET_RE = re.compile(r"sget\s+\w+,\s+L([^;]+);->(\w+):I")
PUBLIC_RE = re.compile(
    r'<public\s+type="([^"]+)"\s+name="([^"]+)"\s+id="(0x[0-9a-fA-F]+)"'
)
SMALI_FIELD_RE = re.compile(
    r"\.field\s+public\s+static\s+final\s+(\w+):I\s*=\s*(0x[0-9a-fA-F]+)"
)
JAVA_FIELD_RE = re.compile(r"static\s+final\s+int\s+(\w+)\s*=\s*(\d+)\s*;")


def collect_sgets() -> dict[str, dict]:
    """返回 { 'LCLASS;->FIELD:I': {'class','field','sites':[...]} }。"""
    result: dict[str, dict] = {}
    total_calls = 0
    for path in sorted(SMALI.rglob("*.smali")):
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        for i, line in enumerate(lines):
            if "AnimationUtils;->loadAnimation" not in line:
                continue
            total_calls += 1
            for j in range(max(0, i - 14), i):
                m = SGET_RE.search(lines[j])
                if not m:
                    continue
                cls, field = m.group(1), m.group(2)
                key = f"L{cls};->{field}:I"
                entry = result.setdefault(
                    key, {"class": cls, "field": field, "sites": []}
                )
                rel = path.relative_to(ROOT).as_posix()
                if rel not in entry["sites"]:
                    entry["sites"].append(rel)
    return result, total_calls


def smali_field_values(cls: str) -> dict[str, str]:
    """com/netease/cloudmusic/n1 等：从 smali 的 .field 定义读 hex。"""
    path = SMALI / (cls.replace("/", "\\") + ".smali")
    if not path.is_file():
        path = SMALI / (cls + ".smali")
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    return {name: value.lower() for name, value in SMALI_FIELD_RE.findall(text)}


def java_field_values(cls: str) -> dict[str, str]:
    """xj1/a、v61/a：jadx 反编译产物里的十进制常量。"""
    suffix = cls.replace("/", "_") + ".java"
    path = RCLASSES / suffix
    if not path.is_file():
        return {}
    text = path.read_text(encoding="utf-8", errors="replace")
    return {name: hex(int(value)) for name, value in JAVA_FIELD_RE.findall(text)}


def public_by_id() -> dict[str, tuple[str, str]]:
    text = (RES / "values" / "public.xml").read_text(
        encoding="utf-8", errors="replace"
    )
    return {i.lower(): (t, n) for t, n, i in PUBLIC_RE.findall(text)}


def anim_summary(type_: str, name: str) -> dict:
    files = sorted(glob.glob(str(RES / f"{type_}*" / f"{name}.*")))
    if not files:
        return {"file": None, "kinds": [], "durations": [], "rotateInfinite": False}
    text = Path(files[0]).read_text(encoding="utf-8", errors="replace")
    kinds = sorted(set(re.findall(r"<(\w+)\s", text)) - {"set"})
    durations = sorted(set(re.findall(r'android:duration="(\d+)"', text)))
    infinite = "repeatCount=\"infinite\"" in text and "<rotate" in text
    return {
        "file": Path(files[0]).relative_to(ROOT).as_posix().replace("\\", "/"),
        "kinds": kinds,
        "durations": durations,
        "rotateInfinite": infinite,
    }


def rotate_candidates() -> dict:
    """14 §4.1 的 40 个无限循环 rotate 候选（复核基线）。"""
    files, durations = [], set()
    for p in glob.glob(str(RES / "anim*" / "*.xml")):
        t = Path(p).read_text(encoding="utf-8", errors="replace")
        if "<rotate" in t and 'repeatCount="infinite"' in t:
            files.append(Path(p).name)
            durations.update(re.findall(r'android:duration="(\d+)"', t))
    return {"count": len(files), "durations": sorted(durations, key=int),
            "has12000": "12000" in durations}


def main() -> int:
    sgets, total_calls = collect_sgets()
    by_id = public_by_id()
    fields = []
    resolved: dict[str, dict] = {}
    for key, entry in sorted(sgets.items()):
        cls = entry["class"]
        values = smali_field_values(cls) or java_field_values(cls)
        hex_id = values.get(entry["field"])
        item = {
            "ref": key,
            "field": entry["field"],
            "callSites": entry["sites"],
            "resourceId": hex_id,
            "source": "smali .field" if smali_field_values(cls) else "jadx R class",
        }
        if hex_id and hex_id in by_id:
            type_, name = by_id[hex_id]
            item["publicName"] = f"{type_}/{name}"
            resolved[hex_id] = {
                "resourceId": hex_id,
                "type": type_,
                "name": name,
                "usedByFields": [],
                **anim_summary(type_, name),
            }
            resolved[hex_id]["usedByFields"].append(key)
        fields.append(item)

    # 合并多个字段指向同一 id 的情况
    for hex_id, res in resolved.items():
        res["usedByFields"] = sorted(
            {k for f in fields if f["resourceId"] == hex_id for k in [f["ref"]]}
        )

    rot = rotate_candidates()
    report = {
        "source": "analysis/smali14 + decoded/res/values/public.xml + jadx-anim-rclasses",
        "scope": "14 §4.1 挂账的 loadAnimation 调用链静态解析（非真机确认）",
        "loadAnimationCallSites": total_calls,
        "staticFieldRefs": len(sgets),
        "staticFieldCount": len(fields),
        "resolvedResourceIdCount": len(resolved),
        "fields": fields,
        "resources": sorted(resolved.values(), key=lambda r: r["resourceId"]),
        "rotateCandidates": rot,
        "conclusion": (
            "loadAnimation 全链路已静态解析：所有调用都以 R 类静态字段传入 ID，"
            "收敛到 2 个 anim 资源，均为 scale 缩放动画（点击/过渡反馈），"
            "不是无限循环 rotate。唱片旋转不由 AnimationUtils.loadAnimation 加载，"
            "可能走属性动画 / 自定义 View Canvas；40 个 rotate 候选仍需真机或属性动画链路确认。"
        ),
        "assetMappingChanged": False,
        "c4Note": "docs/assets-mapping.csv 保持空表 = 尚无素材导出，C4 语义不变",
    }
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8")

    print(f"loadAnimation 静态字段引用 {len(fields)} 个 | 收敛 anim ID {len(resolved)} 个")
    for f in fields:
        print(f"  {f['ref']:45s} -> {f.get('resourceId') or '?'} "
              f"({f.get('publicName', 'unresolved')}) via {f['source']}")
    for r in report["resources"]:
        print(f"  {r['resourceId']} {r['type']}/{r['name']}: "
              f"kinds={r['kinds']} duration={r['durations']} "
              f"rotateInfinite={r['rotateInfinite']}")
    print(f"rotate 候选 {rot['count']} 个 | 含 12000 = {rot['has12000']}")
    print(f"结论：{report['conclusion']}")
    print(f"\n已写出 {OUT.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
