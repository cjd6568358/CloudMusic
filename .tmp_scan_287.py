"""§61.287 探测：列出 ui 目录所有 .height( 调用，判定其所属块内是否含 fontSize（=有文字）。"""
import re, pathlib, glob

UI = pathlib.Path("app/src/main/kotlin/com/example/cloudmusic/ui")

def blocks(src):
    """返回所有 { } 块区间 (start, end)，按 start 升序。"""
    stack, out = [], []
    for i, ch in enumerate(src):
        if ch == "{":
            stack.append(i)
        elif ch == "}" and stack:
            s = stack.pop()
            out.append((s, i))
    return out

def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), src, flags=re.S)
    out = []
    for line in src.split("\n"):
        i = line.find("//")
        out.append(line if i < 0 else line[:i] + " " * (len(line) - i))
    return "\n".join(out)

for f in sorted(glob.glob(str(UI / "*.kt"))) + sorted(glob.glob(str(UI / "player" / "*.kt"))):
    raw = pathlib.Path(f).read_text(encoding="utf-8", errors="replace")
    src = strip_comments(raw)
    bs = blocks(src)
    hits = []
    for m in re.finditer(r"\.height\(", src):
        pos = m.start()
        line_no = src.count("\n", 0, pos) + 1
        # 该 .height( 的参数（配对括号）
        d, j = 0, m.end() - 1
        while j < len(src):
            if src[j] == "(": d += 1
            elif src[j] == ")":
                d -= 1
                if d == 0: break
            j += 1
        arg = src[m.end():j].replace("\n", " ").strip()
        # 最内层包含块
        cand = [b for b in bs if b[0] < pos < b[1]]
        if not cand:
            continue
        inner = min(cand, key=lambda b: b[1] - b[0])
        body = src[inner[0]:inner[1]]
        has_text = "fontSize" in body
        # 是否 Spacer
        line_start = src.rfind("\n", 0, pos) + 1
        line_txt = src[line_start:src.find("\n", pos)]
        is_spacer = "Spacer(" in line_txt
        if has_text and not is_spacer:
            hits.append((line_no, arg, "文本"))
        elif not is_spacer:
            hits.append((line_no, arg, "-"))
    if not hits:
        continue
    rel = f.replace(str(UI) + "\\", "").replace(str(UI) + "/", "")
    print("=== %s" % rel)
    for ln, arg, tag in hits:
        print("   L%-5d %-52s %s" % (ln, arg[:52], tag))
