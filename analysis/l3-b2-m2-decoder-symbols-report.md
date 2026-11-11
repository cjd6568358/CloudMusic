# B2 M2 前置补证 · libffmpeg.so decoder/demuxer 导出符号表实证（12 §61.83）

> 纯 stdlib / 纯离线 / 只读；机器证据 `analysis/l3-b2-m2-decoder-symbols.json`。

## 结论

```text
M2_PREFLIGHT_SYMBOL_EVIDENCE_UPGRADE__M2_GATE_NOT_PASSED__F4_BLOCKED：承接 §61.82 留下的「9 个格式 decoder 编入未确认」缺口，本批纯离线用手写 ELF32/64 解析器读 libffmpeg.so 的 .dynsym 导出符号表，把证据等级从 §61.44 的「二进制可打印字符串扫描」提升到「ELF 导出符号」。arm64-v8a 与 armeabi-v7a 两个 ABI 各自恰好 100 个 ff_*_decoder + 75 个 ff_*_demuxer 导出符号，集合完全一致，且与 §61.44 的 100/75 计数及清单吻合；两份 so 的 .symtab 均已被 strip，但这些 AVCodec 全局变量作为导出符号保留在 .dynsym，故符号表级实证成立。9 个存疑格式与 15 个候选格式已逐项给出精确符号存在性与族匹配的二值结果（见 JSON）。铁律不变：导出符号存在 ≠ decoder 可用 ≠ 真机可播；逐格式可播仍需 F3 REMOTE/LOCAL 双路径真机矩阵（NOT RUN）。本批不产出 B2 扩展产物、不改 P0 源码、不动解码器、不连 adb / 不联网 / 不下载，M2 阶段门禁未通过、M2 未开工、未完成。F4 仍 BLOCKED，§61.75 终局裁决不变。符号表补证 ≠ M2 完成。
```

| 项 | 值 |
|---|---|
| 证据等级 | ELF `.dynsym` 导出符号（前一级：`§61.44` 二进制可打印字符串扫描） |
| `.symtab` | 两份 so 均已 strip（导出符号保留在 `.dynsym`） |
| decoder / demuxer 符号 | 100 / 75（两 ABI 一致） |
| 双 ABI 集合一致 | True |
| 与 `§61.44` 计数 / 清单一致 | True |
| checks | 18 / 18 全 PASS = True |
| M2 门禁 / F4 | 未通过 / BLOCKED |

## 1. 方法与双 ABI 交叉

主工具手写 ELF32/64 little-endian 解析（section header table → `.dynsym` → `sh_link` 指向的 `.dynstr` → `Elf32/64_Sym.st_name`），零第三方依赖。

| ABI | 文件 | 字节 | SHA-256（前 16） | ELF | e_machine | `.dynsym` 符号 | decoder | demuxer |
|---|---|---|---|---|---|---|---|---|
| arm64-v8a | `engine-legacy/src/main/jniLibs/arm64-v8a/libffmpeg.so` | 6658592 | `a527e23288f5281c` | ELF64 | 183 | 3750 | 100 | 75 |
| armeabi-v7a | `analysis/decoded/lib/armeabi-v7a/libffmpeg.so` | 5340888 | `f73dc5adef4aa0e5` | ELF32 | 40 | 3874 | 100 | 75 |

两 ABI 的 `ff_*_decoder` 集合与 `ff_*_demuxer` 集合**逐项完全一致 = True**；与 `§61.44` 字符串法的 100 / 75 计数及清单吻合 = **True**。

## 2. §61.82 遗留的 9 个「decoder 编入未确认」格式 — 逐项符号判定

> 查询名取自 `§61.82` 报告第 3 节原文；判定为**机械二值查询**，
> 「族匹配」是子串包含的检索辅助，不是解码能力断言。

| 查询名 | decoder 精确符号 | demuxer 精确符号 | decoder 族匹配 | demuxer 族匹配 | 符号判定 |
|---|---|---|---|---|---|
| `tak` | no | no | — | — | `NO_SYMBOL` |
| `tta` | no | no | — | — | `NO_SYMBOL` |
| `wavpack` | no | no | — | — | `NO_SYMBOL` |
| `dsf` | no | YES | — | `dsf` | `DEMUXER_SYMBOL_ONLY` |
| `dff` | no | no | — | — | `NO_SYMBOL` |
| `aiff` | no | no | — | — | `NO_SYMBOL` |
| `shorten` | no | no | — | — | `NO_SYMBOL` |
| `truehd` | no | no | — | — | `NO_SYMBOL` |
| `mlp` | no | no | — | — | `NO_SYMBOL` |

## 3. 15 个格式覆盖候选集（14 `AUDIO_EXT` + `alac`） — 逐项符号判定

| 查询名 | decoder 精确符号 | demuxer 精确符号 | decoder 族匹配 | demuxer 族匹配 | 符号判定 |
|---|---|---|---|---|---|
| `mp3` | YES | YES | `mp3` | `mp3` | `DECODER_SYMBOL_PRESENT` |
| `flac` | YES | YES | `flac` | `flac` | `DECODER_SYMBOL_PRESENT` |
| `ogg` | no | YES | — | `ogg` | `DEMUXER_SYMBOL_ONLY` |
| `m4a` | no | no | — | — | `NO_SYMBOL` |
| `wav` | no | YES | `adpcm_ima_wav` | `wav` | `DECODER_SYMBOL_PRESENT` |
| `opus` | YES | no | `opus` | — | `DECODER_SYMBOL_PRESENT` |
| `wma` | no | no | `wmalossless`, `wmapro`, `wmav1`, `wmav2`, `wmavoice` | — | `DECODER_SYMBOL_PRESENT` |
| `aac` | YES | YES | `aac`, `aac_latm` | `aac` | `DECODER_SYMBOL_PRESENT` |
| `ape` | YES | YES | `ape` | `ape` | `DECODER_SYMBOL_PRESENT` |
| `dsf` | no | YES | — | `dsf` | `DEMUXER_SYMBOL_ONLY` |
| `dff` | no | no | — | — | `NO_SYMBOL` |
| `aiff` | no | no | — | — | `NO_SYMBOL` |
| `wv` | no | no | — | — | `NO_SYMBOL` |
| `tak` | no | no | — | — | `NO_SYMBOL` |
| `alac` | YES | no | `alac` | — | `DECODER_SYMBOL_PRESENT` |

## 4. 容器 / codec 辅助查询（客观 codec 名精确符号存在性）

| 查询名 | decoder 精确符号 | demuxer 精确符号 | decoder 族匹配 | demuxer 族匹配 | 符号判定 |
|---|---|---|---|---|---|
| `vorbis` | YES | no | `vorbis` | — | `DECODER_SYMBOL_PRESENT` |
| `dsd` | no | no | `dsd_lsbf`, `dsd_lsbf_planar`, `dsd_msbf`, `dsd_msbf_planar` | — | `DECODER_SYMBOL_PRESENT` |
| `pcm_s16le` | YES | YES | `pcm_s16le` | `pcm_s16le` | `DECODER_SYMBOL_PRESENT` |
| `wmav1` | YES | no | `wmav1` | — | `DECODER_SYMBOL_PRESENT` |
| `wmav2` | YES | no | `wmav2` | — | `DECODER_SYMBOL_PRESENT` |
| `wmapro` | YES | no | `wmapro` | — | `DECODER_SYMBOL_PRESENT` |
| `wmalossless` | YES | no | `wmalossless` | — | `DECODER_SYMBOL_PRESENT` |
| `wmavoice` | YES | no | `wmavoice` | — | `DECODER_SYMBOL_PRESENT` |
| `adpcm_ima_wav` | YES | no | `adpcm_ima_wav` | — | `DECODER_SYMBOL_PRESENT` |
| `ac3` | no | no | `eac3_mediacodec` | — | `DECODER_SYMBOL_PRESENT` |
| `eac3` | no | no | `eac3_mediacodec` | — | `DECODER_SYMBOL_PRESENT` |
| `dts` | no | YES | — | `dts` | `DEMUXER_SYMBOL_ONLY` |
| `mp3` | YES | YES | `mp3` | `mp3` | `DECODER_SYMBOL_PRESENT` |
| `aac` | YES | YES | `aac`, `aac_latm` | `aac` | `DECODER_SYMBOL_PRESENT` |
| `aac_latm` | YES | no | `aac_latm` | — | `DECODER_SYMBOL_PRESENT` |
| `alac` | YES | no | `alac` | — | `DECODER_SYMBOL_PRESENT` |
| `flac` | YES | YES | `flac` | `flac` | `DECODER_SYMBOL_PRESENT` |
| `ape` | YES | YES | `ape` | `ape` | `DECODER_SYMBOL_PRESENT` |
| `opus` | YES | no | `opus` | — | `DECODER_SYMBOL_PRESENT` |

## 5. 完整符号清单（arm64-v8a，与 armeabi-v7a 一致）

<details><summary>100 个 ff_*_decoder 短名</summary>

```text
aac, aac_latm, ac4_mediacodec, adpcm_4xm, adpcm_afc, adpcm_agm, adpcm_aica, adpcm_argo, adpcm_ct, adpcm_dtk, adpcm_ea, adpcm_ea_maxis_xa, adpcm_ea_r1, adpcm_ea_r2, adpcm_ea_r3, adpcm_ea_xas, adpcm_ima_alp, adpcm_ima_amv, adpcm_ima_apc, adpcm_ima_apm, adpcm_ima_cunning, adpcm_ima_dat4, adpcm_ima_dk3, adpcm_ima_dk4, adpcm_ima_ea_eacs, adpcm_ima_ea_sead, adpcm_ima_iss, adpcm_ima_mtf, adpcm_ima_oki, adpcm_ima_qt, adpcm_ima_rad, adpcm_ima_smjpeg, adpcm_ima_ssi, adpcm_ima_wav, adpcm_ima_ws, adpcm_ms, adpcm_mtaf, adpcm_psx, adpcm_sbpro_2, adpcm_sbpro_3, adpcm_sbpro_4, adpcm_swf, adpcm_thp, adpcm_thp_le, adpcm_xa, adpcm_yamaha, adpcm_zork, alac, amrnb, amrwb, ape, dca, dsd_lsbf, dsd_lsbf_planar, dsd_msbf, dsd_msbf_planar, eac3_mediacodec, flac, flv, h263, h263i, h263p, h264, h264_mediacodec, hevc, hevc_mediacodec, init_cabac, mp2, mp3, mpeg4, opus, pcm_alaw, pcm_f32le, pcm_f64le, pcm_mulaw, pcm_s16le, pcm_s24be, pcm_s24le, pcm_s32le, pcm_s8, pcm_u8, png, theora, vorbis, vp3, vp56_init_range, vp6, vp6a, vp6f, vp7, vp8_mediacodec, vp9_mediacodec, wmalossless, wmapro, wmav1, wmav2, wmavoice, xma1, xma2, yuv4
```

</details>

<details><summary>75 个 ff_*_demuxer 短名</summary>

```text
aac, amr, ape, asf, av3a, avi, dsf, dts, flac, flv, h263, h264, hevc, image2, image2pipe, image_bmp_pipe, image_dds_pipe, image_dpx_pipe, image_exr_pipe, image_gif_pipe, image_j2k_pipe, image_jpeg_pipe, image_jpegls_pipe, image_pam_pipe, image_pbm_pipe, image_pcx_pipe, image_pgm_pipe, image_pgmyuv_pipe, image_pictor_pipe, image_png_pipe, image_ppm_pipe, image_psd_pipe, image_qdraw_pipe, image_sgi_pipe, image_sunrast_pipe, image_svg_pipe, image_tiff_pipe, image_webp_pipe, image_xpm_pipe, image_xwd_pipe, kux, live_flv, m4v, matroska, mov, mp3, mpegps, mpegts, mpegtsraw, mpegvideo, ogg, pcm_alaw, pcm_f32be, pcm_f32le, pcm_f64be, pcm_f64le, pcm_mulaw, pcm_s16be, pcm_s16le, pcm_s24be, pcm_s24le, pcm_s32be, pcm_s32le, pcm_s8, pcm_u16be, pcm_u16le, pcm_u24be, pcm_u24le, pcm_u32be, pcm_u32le, pcm_u8, pcm_vidc, sln, wav, webm_dash_manifest
```

</details>

## 6. 独立交叉校验（pyelftools，隔离 venv）

隔离 venv `pyelftools 0.33` 独立复算 `.dynsym`：

| ABI | ff_*_decoder | ff_*_demuxer | 与 stdlib 解析一致 |
|---|---|---|---|
| arm64-v8a | 100 | 75 | True |
| armeabi-v7a | 100 | 75 | True |

**结论**：pyelftools 独立复算两 ABI 的 .dynsym 导出符号，与主工具纯 stdlib 手写解析的 100 个 ff_*_decoder / 75 个 ff_*_demuxer 集合逐项完全一致；交叉校验通过。这是独立实现互证，不是同一份代码的重复运行。

## 7. 诚实边界

- 本工具只做 ELF .dynsym 导出符号存在性实证，不实现 M2、不产出 FFmpeg 扩展产物、不联网、不连 adb
- 导出符号存在 ≠ decoder 可用 ≠ 真机可播；逐格式可播结论只能由 F3 REMOTE/LOCAL 双路径真机矩阵给出（NOT RUN）
- 族匹配（子串包含）只是机械检索辅助，不是「该格式由该 decoder 解码」的能力断言
- .symtab 已被 strip，本证据只覆盖 .dynsym 导出符号；未导出且被 strip 的实现不在本证据范围内，不作「未编入」的绝对断言
- §61.82 第 3 节「有 demuxer 或字符串痕迹但未确认 decoder 编入」按本批符号表结果只追加批注、不改历史正文
- M0/M1/M2 前置历史证据（§61.50~§61.82）冻结不改；本批不重跑历史工具
- R7 / GPL v2+ 边界只登记不作法律结论；不引 media3-session、18 个 so 暂不移除、不动解码器
- 符号表补证 ≠ M2 完成，M2 门禁未通过不得进入 M3；F4 仍 BLOCKED、§61.75 终局裁决不变

## 8. 复现

主证据（任意 Python 3，零第三方依赖）：

```bash
python tools/l3_b2_m2_decoder_symbol_audit.py
```

独立交叉校验（隔离 venv，`pyelftools 0.33`），产出 `analysis/l3-b2-m2-decoder-symbols-crosscheck.json` 后重跑主工具，结论会自动并入本报告 §6：

```bash
"C:/Users/Administrator/.workbuddy/binaries/python/envs/default/Scripts/python.exe" - <<'PY'
import json, re
from pathlib import Path
from elftools.elf.elffile import ELFFile
R = Path('.')
main = json.loads((R/'analysis/l3-b2-m2-decoder-symbols.json').read_text(encoding='utf-8'))
per = {}
for abi, rel in (('arm64-v8a','engine-legacy/src/main/jniLibs/arm64-v8a/libffmpeg.so'),
                ('armeabi-v7a','analysis/decoded/lib/armeabi-v7a/libffmpeg.so')):
    with (R/rel).open('rb') as f:
        ns = [s.name for s in ELFFile(f).get_section_by_name('.dynsym').iter_symbols() if s.name]
    dec = sorted({m.group(1) for n in ns if (m := re.fullmatch(r'ff_([a-z0-9_]+)_decoder', n))})
    dem = sorted({m.group(1) for n in ns if (m := re.fullmatch(r'ff_([a-z0-9_]+)_demuxer', n))})
    per[abi] = {'decoder': len(dec), 'demuxer': len(dem), 'dynsym_names': len(ns),
                'matches_stdlib': dec == main['full_decoder_symbols'] and dem == main['full_demuxer_symbols']}
out = {'tool': 'pyelftools 0.33 (isolated venv) independent crosscheck',
       'pyelftools_version': '0.33',
       'method': "ELFFile.get_section_by_name('.dynsym').iter_symbols() -> ff_*_decoder / ff_*_demuxer",
       'per_abi': per, 'all_match': all(v['matches_stdlib'] for v in per.values()),
       'note': '独立实现互证：两 ABI 的 100 decoder / 75 demuxer 集合与纯 stdlib 主证据逐项一致。'}
(R/'analysis/l3-b2-m2-decoder-symbols-crosscheck.json').write_text(
    json.dumps(out, ensure_ascii=False, indent=2) + chr(10), encoding='utf-8')
print('[CROSSCHECK] all_match =', out['all_match'])
PY
```

> AI生成
