import subprocess

DEV = "192.168.1.14:39211"
url = open(r"D:\workspace\CloudMusic\.cmdb3\url.txt").read().strip()

def run(label, rng):
    cmd = ["adb", "-s", DEV, "shell",
           "curl -s -L -o /dev/null -w 'code=%{http_code} total=%{time_total} redirect=%{time_redirect} connect=%{time_connect} firstbyte=%{time_starttransfer}\\n' "
           f"-r {rng} --max-time 25 '{url}'"]
    r = subprocess.run(cmd, capture_output=True, text=True)
    print(label, ":", (r.stdout or r.stderr).strip()[:200])

run("range 0-64K   ", "0-65535")
run("range 100K-165K", "100000-165535")
run("range 200K-265K", "200000-265535")
