#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
reverse-engine 交付链的编译 / 测试驱动器（纯 JVM，零外部依赖）。

职责：
  1. 从本机 Gradle 缓存自动发现 Kotlin embeddable compiler 及运行时 jar；
  2. 用 java 调 org.jetbrains.kotlin.cli.jvm.K2JVMCompiler 全量编译
     kt/src/*.kt + test/*.kt → build/classes；
  3. 运行契约测试主类 cloudmusic.reverse.kernel.ContractTests；
  4. 把编译/运行的结构化结果写入 build/test-report.json。

设计约束（与项目纪律一致）：
  - 不写死任何 JDK / SDK 绝对路径（java 由 shutil.which / JAVA_HOME 推断）；
  - 不接入 settings.gradle.kts、不改任何 build 文件、不碰二进制资源；
  - 编译产物只在 reverse-engine/build/ 内，可随时整体清理；
  - 编译 classpath 与 JVM classpath 分开传递，避免 -no-stdlib 语义混淆。

依赖发现策略：
  embeddable compiler 的 intellij 内核在多文件 collectSources / IR codegen
  阶段会按需加载 gnu.trove、org.jetbrains.annotations 等。逐个试错太脆弱，
  故 JVM classpath = 精确必需 jar + Kotlin 2.0.20 全工件 + intellij deps 全工件
  的并集（用 dict.fromkeys 去重）。编译/运行 classpath 仍只给源码真正用到的
  stdlib / coroutines，保持语义干净。

用法：
  python build.py                # 编译 + 测试 + 写报告
  python build.py --compile-only # 只编译
  python build.py --run-only     # 只运行（要求 build/classes 已存在）
"""

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

RE = Path(__file__).resolve().parent.parent
SRC_DIR = RE / "kt" / "src"
TEST_DIR = RE / "test"
BUILD_DIR = RE / "build"
CLASSES_DIR = BUILD_DIR / "classes"
REPORT_PATH = BUILD_DIR / "test-report.json"

GRADLE_CACHE = Path(
    os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle")
) / "caches" / "modules-2" / "files-2.1"

KOTLIN_VERSION = "2.0.20"
COROUTINES_VERSION = "1.6.4"
TROVE4J_VERSION = "1.0.20200330"
ANNOTATIONS_VERSION = "23.0.0"

MAIN_CLASS = "cloudmusic.reverse.kernel.ContractTests"


def discover(pattern: str) -> Path:
    """在 Gradle 缓存里按 glob 找 jar，取排序后最后一个（同版本多 hash 目录时取稳定项）。"""
    hits = sorted(GRADLE_CACHE.glob(pattern))
    if not hits:
        raise FileNotFoundError(f"jar not found in Gradle cache: {pattern}")
    return hits[-1]


def build_jars() -> dict:
    """返回精确必需 jar 的具名映射（供编译/运行 classpath 与报告使用）。"""
    return {
        "compiler": discover(f"org.jetbrains.kotlin/kotlin-compiler-embeddable/{KOTLIN_VERSION}/*/*.jar"),
        "stdlib": discover(f"org.jetbrains.kotlin/kotlin-stdlib/{KOTLIN_VERSION}/*/*.jar"),
        "script_runtime": discover(f"org.jetbrains.kotlin/kotlin-script-runtime/{KOTLIN_VERSION}/*/*.jar"),
        "reflect": discover(f"org.jetbrains.kotlin/kotlin-reflect/{KOTLIN_VERSION}/*/*.jar"),
        "coroutines": discover(f"org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/{COROUTINES_VERSION}/*/*.jar"),
        "trove4j": discover(f"org.jetbrains.intellij.deps/trove4j/{TROVE4J_VERSION}/*/*.jar"),
        "annotations": discover(f"org.jetbrains/annotations/{ANNOTATIONS_VERSION}/*/*.jar"),
    }


def jvm_classpath(jars: dict) -> str:
    """JVM 加载 compiler 自身的 classpath：精确 jar + 同版本全工件兜底，去重。"""
    extras = sorted(GRADLE_CACHE.glob(f"org.jetbrains.kotlin/*/{KOTLIN_VERSION}/*/*.jar"))
    extras += sorted(GRADLE_CACHE.glob("org.jetbrains.intellij.deps/*/*/*/*.jar"))
    extras += sorted(GRADLE_CACHE.glob(f"org.jetbrains/annotations/*/*/*/*.jar"))
    all_jars = list(jars.values()) + list(extras)
    unique = list(dict.fromkeys(all_jars))
    return sep().join(str(p) for p in unique), len(unique)


def sep() -> str:
    return os.pathsep  # Windows ';' / POSIX ':'


def resolve_java() -> str:
    found = shutil.which("java")
    if found:
        return found
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.exists():
            return str(candidate)
    raise RuntimeError("java not found on PATH and JAVA_HOME is unset")


def compile_all(jars: dict, java: str) -> dict:
    """分趟编译。

    为什么分趟：Z 组生成物共 388064 条指令 / 176 个 .kt / 17.4 MiB。单趟全量编译
    在 8 GiB 机器上会直接 `java.lang.OutOfMemoryError: Java heap space`（实测
    exit=137、420s 后崩在 FIR 的 ArgumentCheckingProcessor）。堆上限受物理内存
    约束（ergonomic MaxHeapSize≈2.1 GiB），而 38.8 万个 `i(...)` 调用的参数
    约束求解峰值远超该值。因此按「状态层 → 块批次 → 调度层 → 测试」分趟，
    每趟都是独立 JVM，峰值互不叠加。

    依赖是单向的：
      base  : NmOp / NmTranslatedCatalog / NmArm64 (NmArmState)   —— 零依赖
      codeNN: NmCode*.kt 只引用 NmArmState 与 NmOp                  —— 只依赖 base
      top   : NmBlocks / NmMachine / NmFuncs*                       —— 依赖 base+codeNN
      test  : ContractTests.kt                                      —— 依赖全部
    """
    if CLASSES_DIR.exists():
        shutil.rmtree(CLASSES_DIR, ignore_errors=True)
    CLASSES_DIR.mkdir(parents=True, exist_ok=True)

    jvm_cp, jar_count = jvm_classpath(jars)
    lib_cp = sep().join(str(jars[k]) for k in ("stdlib", "coroutines"))

    src = {p.name: p for p in sorted(SRC_DIR.glob("*.kt"))}
    generated_top = ("NmBlocks.kt", "NmMachine.kt")
    base = [p for n, p in src.items()
            if not n.startswith("NmCode") and not n.startswith("NmFuncs")
            and n not in generated_top]
    code = sorted(SRC_DIR.glob("NmCode*.kt"))
    top = sorted(SRC_DIR.glob("NmBlocks.kt")) + sorted(SRC_DIR.glob("NmMachine.kt")) \
        + sorted(SRC_DIR.glob("NmFuncs*.kt"))
    tests = sorted(TEST_DIR.glob("*.kt"))

    heap = os.environ.get("CM_RE_MAX_HEAP", "1500m")
    batch = int(os.environ.get("CM_RE_BATCH", "16"))

    passes = [("base", base)]
    for k in range(0, len(code), batch):
        passes.append(("code%02d" % (k // batch), code[k:k + batch]))
    passes.append(("top", top))
    passes.append(("test", tests))

    results = []
    t0 = time.time()
    for name, sources in passes:
        if not sources:
            continue
        cmd = [
            java, "-Xmx" + heap, "-Xss8m",
            "-cp", jvm_cp,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect",
            "-jvm-target", "17",
            # 每趟必须用独立模块名：默认模块名 main 会让每趟覆写
            # META-INF/main.kotlin_module，导致上一趟的顶层声明（含扩展函数）
            # 在下一趟的 classpath 上不可见，报一堆 unresolved reference。
            "-module-name", "nm" + name,
            "-classpath", lib_cp + sep() + str(CLASSES_DIR),
            "-d", str(CLASSES_DIR),
        ] + [str(s) for s in sources]
        pt = time.time()
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace")
        el = round(time.time() - pt, 3)
        ok = proc.returncode == 0
        results.append({
            "pass": name, "files": len(sources), "ok": ok,
            "exitCode": proc.returncode, "elapsedSec": el,
            "stdout": proc.stdout.strip()[-4000:], "stderr": proc.stderr.strip()[-4000:],
        })
        print(f"[PASS] {name:<8} files={len(sources):<4} ok={ok} exit={proc.returncode} {el}s")
        if not ok:
            print(proc.stdout[-4000:])
            print(proc.stderr[-4000:])
            break

    elapsed = round(time.time() - t0, 3)
    ok = all(r["ok"] for r in results) and len(results) == len(passes)
    return {
        "ok": ok,
        "exitCode": 0 if ok else next(r["exitCode"] for r in results if not r["ok"]),
        "elapsedSec": elapsed,
        "sourceCount": sum(len(s) for _n, s in passes),
        "sources": [s.name for _n, ss in passes for s in ss],
        "jvmClasspathJarCount": jar_count,
        "maxHeap": heap,
        "batchSize": batch,
        "passes": results,
        "stdout": "" if ok else "\n".join(r["stdout"] for r in results if not r["ok"]),
        "stderr": "" if ok else "\n".join(r["stderr"] for r in results if not r["ok"]),
    }


def run_tests(jars: dict, java: str) -> dict:
    if not CLASSES_DIR.exists():
        return {"ok": False, "exitCode": -1, "stdout": "", "stderr": "build/classes missing", "elapsedSec": 0}
    run_cp = sep().join(
        [str(CLASSES_DIR), str(jars["stdlib"]), str(jars["coroutines"])]
    )
    cmd = [java, "-Dfile.encoding=UTF-8", "-cp", run_cp, MAIN_CLASS]
    t0 = time.time()
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    elapsed = round(time.time() - t0, 3)
    return {
        "ok": proc.returncode == 0,
        "exitCode": proc.returncode,
        "elapsedSec": elapsed,
        "stdout": proc.stdout.strip(),
        "stderr": proc.stderr.strip(),
    }


def main() -> int:
    args = set(sys.argv[1:])
    compile_only = "--compile-only" in args
    run_only = "--run-only" in args

    report = {
        "tool": "reverse-engine/tools/build.py",
        "kotlinVersion": KOTLIN_VERSION,
        "coroutinesVersion": COROUTINES_VERSION,
        "java": None,
        "jars": {},
        "compile": None,
        "test": None,
        "overallOk": False,
    }
    try:
        java = resolve_java()
        jars = build_jars()
        report["java"] = java
        report["jars"] = {k: str(v) for k, v in jars.items()}
    except Exception as exc:  # noqa: BLE001
        report["error"] = f"environment setup failed: {exc}"
        REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
        REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
        print("[FATAL]", report["error"])
        return 2

    if not run_only:
        report["compile"] = compile_all(jars, java)
        c = report["compile"]
        print(f"[COMPILE] ok={c['ok']} exit={c['exitCode']} src={c['sourceCount']} "
              f"jars={c['jvmClasspathJarCount']} {c['elapsedSec']}s")
        if not c["ok"]:
            print(c["stdout"])
            print(c["stderr"])
    if not compile_only:
        report["test"] = run_tests(jars, java)
        t = report["test"]
        print(f"[TEST]    ok={t['ok']} exit={t['exitCode']} {t['elapsedSec']}s")
        if t["stdout"]:
            print(t["stdout"])
        if t["stderr"]:
            print("[stderr]", t["stderr"])

    report["overallOk"] = (
        (report["compile"] is None or report["compile"]["ok"])
        and (report["test"] is None or report["test"]["ok"])
    )
    REPORT_PATH.parent.mkdir(parents=True, exist_ok=True)
    REPORT_PATH.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8", newline="\n")
    # 把测试 stdout 里的 F4 链路 Markdown 单独落成可交付报告（UTF-8）
    if report["test"] and report["test"]["stdout"]:
        out = report["test"]["stdout"]
        idx = out.find("# F4 ")
        if idx >= 0:
            chain_md = out[idx:].rstrip() + "\n"
            chain_path = RE / "F4-CHAIN-REPORT.md"
            chain_path.write_text(chain_md.replace("\r\n", "\n"), encoding="utf-8", newline="\n")
            (BUILD_DIR / "contract-tests.log").write_text(out.replace("\r\n", "\n"), encoding="utf-8", newline="\n")
            print(f"[CHAIN]   {chain_path}")
    print(f"[REPORT]  {REPORT_PATH}")
    print(f"[RESULT]  overallOk={report['overallOk']}")
    return 0 if report["overallOk"] else 1


if __name__ == "__main__":
    sys.exit(main())
