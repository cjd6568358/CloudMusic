"""C1~C6 静态检查共用基础设施（零第三方依赖）。

模式语义（docs/03-12 §3）：
  dev     : 尚未落地的产物（jniLibs / Manifest / FINGERPRINT）记为 WARN，只做静态自检
  release : 任何 WARN 升级为 FAIL，任一 FAIL 即阻断 release（退出码 1）
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEV = "dev"
RELEASE = "release"


def add_mode_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--mode", choices=[DEV, RELEASE], default=DEV,
        help="dev=静态自检（默认）；release=发布阻断检查",
    )


class Report:
    def __init__(self, check_id: str, title: str, mode: str = DEV) -> None:
        self.check_id = check_id
        self.title = title
        self.mode = mode
        self.items: list[tuple[str, str]] = []

    def ok(self, msg: str) -> None:
        self.items.append(("PASS", msg))

    def warn(self, msg: str) -> None:
        """dev 模式提示、release 模式阻断。"""
        self.items.append(("FAIL" if self.mode == RELEASE else "WARN", msg))

    def fail(self, msg: str) -> None:
        self.items.append(("FAIL", msg))

    def emit(self) -> int:
        print(f"\n=== [{self.check_id}] {self.title} (mode={self.mode}) ===")
        for status, msg in self.items:
            print(f"  [{status}] {msg}")
        failures = sum(1 for status, _ in self.items if status == "FAIL")
        warns = sum(1 for status, _ in self.items if status == "WARN")
        verdict = "FAIL" if failures else "PASS"
        print(f"  -> {verdict}  (blocking={failures}, warnings={warns})")
        return 1 if failures else 0


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def source_files(*roots: str, suffixes: tuple[str, ...] = (".kt", ".java", ".xml")):
    """遍历自研模块源码（不含 build/ 输出与测试目录，除非 roots 里显式指定）。"""
    for root in roots:
        base = ROOT / root
        if not base.exists():
            continue
        for path in sorted(base.rglob("*")):
            if path.is_file() and path.suffix in suffixes:
                if any(part in {"build", ".gradle", ".git"} for part in path.parts):
                    continue
                yield path
