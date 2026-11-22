#!/usr/bin/env python3
"""C1 · 一次性 ABI 检查（docs/03-12 §3 C1 / §4）。

① JNI 符号对照；② 每个 so 的 DT_NEEDED 递归闭包；③ 打包集内 so 齐全。
纯 Python 解析 ELF64，不依赖 nm / readelf（10 §2 R15；本机 Windows 无 binutils）。

数据来源说明：apktool 解包产物 analysis/decoded/lib/arm64-v8a/ 不完整（只剩 1 个
x86_64 库），APK 包内 lib/arm64-v8a/ 才是 167 个 / 125.6 MiB 的完整来源，
与 05 / 10 / 03-12 的实测口径一致。因此本脚本直接读 APK 条目。
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_common import ROOT, Report, add_mode_arg  # noqa: E402
import elf_probe as ep  # noqa: E402

APK = ROOT / "NeteaseCloudMusic.apk"
ABI = "arm64-v8a"
JNILIBS = ROOT / "engine-legacy" / "src" / "main" / "jniLibs" / ABI
DECODED = ROOT / "analysis" / "decoded" / "lib" / ABI

# 03-12 §4 拍板的 18 个 so 及实测大小（KB，四舍五入值）
EXPECTED_KB: dict[str, float] = {
    "libncmplayerengine.so": 814, "libaudioplayer.so": 191,
    "libnmplaylist.so": 424, "libNMCommonCache.so": 302,
    "libncmaudioplayer.so": 1145, "libncmusbaudio.so": 282,
    "libffmpeg.so": 6503, "libneaudioeffects.so": 1837,
    "libneaudioeffect_core.so": 1690, "libneaudioaiif.so": 422,
    "libMNN.so": 2116, "libMNN_Express.so": 726,
    "libsqlite3x.so": 1033,
    "libnecmMP3Dec.so": 81.7, "libnecmAACDec.so": 248.1,
    "libnecmFlacDec.so": 17.6, "libnecmMediaCodecJDec.so": 133.9,
    "libc++_shared.so": 968.7,  # spike #1 实证补入：必须随 APK 打包（见 elf_probe.PACKED_LIBS 注）
}

# 11 §3.2 校准的 12 个精确 JNI 类（R8 keep 应覆盖这些类）
JNI_CLASSES = (
    "com.netease.cloudmusic.android.sdk.playerengine.AudioPlayerUnit",
    "com.netease.cloudmusic.android.sdk.playerengine.NativeDataRequestErrorCallback",
    "com.netease.cloudmusic.android.sdk.playerengine.Playback",
    "com.netease.cloudmusic.android.sdk.playerengine.PlaybackDataSource",
    "com.netease.cloudmusic.android.sdk.playerengine.PlaybackEngine",
    "com.netease.cloudmusic.playlist.core.PlaylistJniBridge",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMBufferSource",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMCacheBase",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFile",
    "com.netease.cloudmusic.sdk.NMCommonCache.NMVirtualFileManager",
    "com.netease.cloudmusic.sdk.NMCommonCache.SourceDataRequest",
    "com.netease.cloudmusic.media.NEAudioDevice$NEAudioDeviceOnPlaybackPositionUpdateListener",
)

DEX_NATIVE_METHODS = 210  # 11 §3.2 dex 侧实测


def main() -> int:
    parser = argparse.ArgumentParser(description="C1 · 一次性 ABI 检查")
    add_mode_arg(parser)
    args = parser.parse_args()
    report = Report("C1", "一次性 ABI（JNI 符号 / DT_NEEDED 闭包 / 打包集）", args.mode)

    if not APK.exists():
        report.fail(f"参考 APK 缺失：{APK}（勿移动/改名，09 §3.1）")
        return report.emit()

    apk_libs = ep.list_apk_libs(APK, ABI)
    total_mib = sum(apk_libs.values()) / 1048576
    if len(apk_libs) == 167 and abs(total_mib - 125.6) < 0.5:
        report.ok(f"APK 提取侧上限：{len(apk_libs)} 个 / {total_mib:.1f} MiB（与 10 §2 R3 一致）")
    else:
        report.fail(f"APK 提取侧实测 {len(apk_libs)} 个 / {total_mib:.1f} MiB，与 167 / 125.6 MiB 不符")

    # 解析全部 167 个（仅取 needed），再对 4 个入口单独取 JNI 符号。
    # 架构强校验只针对 18 个打包集：参考 APK 自身在 arm64-v8a/ 目录内混放了
    # 一个 x86_64 库（libnesec-x86.so），它不进 APK，不应阻断本项目（03-12 §4）。
    infos: dict[str, dict] = {}
    malformed: list[str] = []
    packed_arch_bad: list[str] = []
    nonpacked_arch_bad: list[str] = []
    for name in apk_libs:
        raw = ep.read_apk_lib(APK, ABI, name)
        if raw is None:
            continue
        try:
            info = ep.parse_elf(raw, with_symbols=False)
        except ValueError as exc:
            malformed.append(f"{name}: {exc}")
            continue
        if info["machine"] != 183:  # EM_AARCH64
            (packed_arch_bad if name in ep.PACKED_LIBS else nonpacked_arch_bad).append(
                f"{name}(machine={info['machine']})"
            )
        infos[name] = info
    if malformed:
        report.fail(f"{len(malformed)} 个 so 无法按 ELF64 解析：{malformed[:3]}")
    if packed_arch_bad:
        report.fail(f"打包集内存在非 AARCH64 库：{packed_arch_bad}")
    else:
        report.ok(f"18 个打包集全部为 ELF64 / EM_AARCH64(183)（共解析 {len(infos)} 个 so）")
    if nonpacked_arch_bad:
        report.warn(
            f"提取侧全集中有 {len(nonpacked_arch_bad)} 个非 AARCH64 库（参考 APK 自身混放，"
            f"不在打包集、不进 APK）：{nonpacked_arch_bad[:3]}"
        )

    # ① 打包集齐全 + 大小对照
    missing = [n for n in ep.PACKED_LIBS if n not in apk_libs]
    if missing:
        report.fail(f"18 个打包集缺失：{missing}")
    else:
        report.ok("18 个打包集在 APK 内齐全")
    size_drift = []
    for name, expected in EXPECTED_KB.items():
        actual = apk_libs.get(name, 0) / 1024
        if expected and abs(actual - expected) / expected > 0.05:
            size_drift.append(f"{name} 期望{expected}KB 实测{actual:.1f}KB")
    if size_drift:
        report.warn(f"打包集大小与 03-12 §4 表存在偏差：{size_drift}")
    else:
        report.ok("18 个打包集大小与 03-12 §4 实测表一致（±5%）")

    # ② DT_NEEDED 递归闭包
    packed = set(ep.PACKED_LIBS)
    seen: set[str] = set()
    stack = list(ep.ENTRY_LIBS)
    external: list[str] = []
    while stack:
        name = stack.pop()
        if name in seen:
            continue
        seen.add(name)
        for dep in infos.get(name, {}).get("needed", []):
            if dep in ep.SYSTEM_LIBS:
                continue
            if dep not in infos:
                external.append(f"{name} → {dep}（APK 内缺失）")
            elif dep not in packed:
                external.append(f"{name} → {dep}（在 167 全集但不在 18 打包集）")
            stack.append(dep)
    if external:
        report.fail(f"递归闭包越出打包集/系统库白名单 {len(external)} 处：{external[:5]}")
    else:
        private = sorted(n for n in seen if n in packed)
        report.ok(f"4 个入口的 DT_NEEDED 递归闭包 = {len(private)} 个打包集内 so，无越界")

    # ③ JNI 符号（11 §3.2 校准口径）
    jni_total = 0
    for name in ep.ENTRY_LIBS:
        raw = ep.read_apk_lib(APK, ABI, name)
        if raw is None:
            continue
        jni_total += len(ep.parse_elf(raw)["jni_symbols"])
    if jni_total == 0:
        report.fail("4 个入口 so 未解析到任何 Java_* 导出符号")
    elif abs(jni_total - DEX_NATIVE_METHODS) <= 2:
        report.ok(f"JNI 导出 {jni_total} 个，与 dex 侧 {DEX_NATIVE_METHODS} 个 native 方法吻合")
    else:
        report.warn(
            f"JNI 导出 {jni_total} 个 vs dex 侧 {DEX_NATIVE_METHODS} 个（差 {DEX_NATIVE_METHODS - jni_total}）；"
            "差额已逐方法定位：Playback.nativeNotifyAudioEffectChanged 1 + PlaylistJniBridge *Raw/日志类 7"
            "（13 §7.4）；实际注册方式待 jadx / 真机 G01 确认"
        )

    # 编码期落地状态
    if JNILIBS.is_dir() and any(JNILIBS.glob("*.so")):
        actual = {p.name for p in JNILIBS.glob("*.so")}
        if actual == packed:
            report.ok(f"jniLibs/{ABI} 已落地且恰为 18 个")
        else:
            report.fail(f"jniLibs 打包集不符：缺 {sorted(packed - actual)}，多 {sorted(actual - packed)}")
    else:
        report.warn("engine-legacy/jniLibs 尚未落地（路线 A 已拍板，slot 后期按需，见 12 §10）；release 前须按 03-12 §4 提取")

    # 解包产物完整性（发现 D-01）
    decoded_count = len(list(DECODED.glob("*.so"))) if DECODED.exists() else 0
    if decoded_count < 167:
        report.warn(
            f"analysis/decoded/lib/{ABI}/ 仅 {decoded_count} 个 so（解包不完整）；"
            "本脚本以 APK 本体为唯一完整来源，勿据 decoded/ 复核 arm64 结论"
        )

    # R8 keep 校准（11 §5 / 12 §10.2 步骤 5；2026-11-05 落地，见 13 §8）
    # 03-12 §2 要求「每个 Java_* 符号对应的类都在 keep 规则覆盖范围内」，
    # 因此这里不只看文件是否存在，还逐条校验精确类、包族通配与符号覆盖。
    rules = ROOT / "engine-legacy" / "proguard-rules.pro"
    if not rules.exists():
        report.warn(f"R8 keep 规则未落地；应采用 11 §3.2 的 {len(JNI_CLASSES)} 个精确类替换 03-12 §2 的示意包名")
        return report.emit()

    text = rules.read_text(encoding="utf-8")
    missing = [c for c in JNI_CLASSES if c not in text]
    if missing:
        report.fail(f"R8 keep 规则缺 {len(missing)} 个精确 JNI 类：{missing[:5]}")

    packages = (
        "com.netease.cloudmusic.android.sdk.playerengine",
        "com.netease.cloudmusic.playlist.core",
        "com.netease.cloudmusic.sdk.NMCommonCache",
        "com.netease.cloudmusic.media",
    )
    no_wildcard = [p for p in packages if f"-keep class {p}." not in text]
    if no_wildcard:
        report.fail(f"R8 keep 规则缺 JNI 包族通配：{no_wildcard}")

    seg_to_pkg = {
        "com_netease_cloudmusic_android_sdk_playerengine_": packages[0],
        "com_netease_cloudmusic_playlist_core_": packages[1],
        "com_netease_cloudmusic_sdk_NMCommonCache_": packages[2],
        "com_netease_cloudmusic_media_": packages[3],
    }
    symbol_count = 0
    uncovered: set[str] = set()
    for name in ep.ENTRY_LIBS:
        raw = ep.read_apk_lib(APK, ABI, name)
        if raw is None:
            continue
        for sym in ep.parse_elf(raw).get("jni_symbols", []):
            symbol_count += 1
            body = sym[len("Java_"):] if sym.startswith("Java_") else sym
            pkg = next((p for seg, p in seg_to_pkg.items() if body.startswith(seg)), None)
            if pkg is None or pkg in no_wildcard:
                uncovered.add(pkg or body[:60])
    if uncovered:
        report.fail(f"{symbol_count} 个 Java_* 符号中有 {len(uncovered)} 个类未被 keep 覆盖：{sorted(uncovered)[:5]}")
    if not missing and not no_wildcard and not uncovered:
        report.ok(
            f"R8 keep 规则已落地：{len(JNI_CLASSES)} 个精确类 + {len(packages)} 个包族通配"
            f" + {symbol_count} 个 Java_* 符号类全覆盖（13 §8）"
        )
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main())
