import subprocess, time

DEV = "192.168.1.14:39211"
url = open(r"D:\workspace\CloudMusic\.cmdb3\url.txt").read().strip()

def adb_curl(sh):
    r = subprocess.run(["adb", "-s", DEV, "shell", sh], capture_output=True, text=True)
    return (r.stdout or r.stderr).strip()

# 拿一次 Location（天翼云 URL）
loc = adb_curl(f"curl -s -D - -o /dev/null --max-time 10 '{url}' | grep -i '^Location:' | head -1")
loc = loc.split(":", 1)[1].strip() if ":" in loc else ""
print("拿到 Location:", bool(loc), "len", len(loc))
if not loc:
    raise SystemExit(1)

# 用同一个解析后的 URL 连续发 3 次 Range，观察耗时与是否失效
for i in range(3):
    sh = f"curl -s -L -o /dev/null -w 'code=%{{http_code}} total=%{{time_total}} redirect=%{{time_redirect}}\\n' -r {i*65536}-{i*65536+65535} --max-time 20 '{loc}'"
    print(f"复用#{i+1}:", adb_curl(sh)[:150])
    time.sleep(1)
