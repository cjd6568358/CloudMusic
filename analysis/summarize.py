"""Summarize decoded APK: manifest components/permissions + dex string intelligence."""
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(sys.argv[1])
NS = "{http://schemas.android.com/apk/res/android}"
STR = ROOT / "analysis" / "strings"
OUT = ROOT / "analysis"
manifest = ROOT / "analysis" / "decoded" / "AndroidManifest.xml"

print("=" * 70)
print("MANIFEST")
print("=" * 70)
m = ET.parse(manifest).getroot()
print("package     :", m.get("package"))
print("versionCode :", m.get(NS + "versionCode"))
print("versionName :", m.get(NS + "versionName"))

sdk = m.find("uses-sdk")
if sdk is not None:
    print("minSdk/targetSdk/compileSdk:",
          sdk.get(NS + "minSdkVersion"), sdk.get(NS + "targetSdkVersion"), sdk.get(NS + "compileSdkVersion"))

perms = [e.get(NS + "name") for e in m.findall("uses-permission")]
print("\n-- permissions (%d) --" % len(perms))
for p in sorted(set(perms)):
    print(" ", p)

feats = [e.get(NS + "name") for e in m.findall("uses-feature")]
print("\n-- uses-feature (%d) --" % len(feats))
for f in sorted(set(feats)):
    print(" ", f)

app = m.find("application")
for tag in ("activity", "service", "receiver", "provider", "activity-alias"):
    items = app.findall(tag)
    print(f"\n-- {tag} ({len(items)}) --")
    names = [e.get(NS + "name") for e in items]
    for n in names[:400]:
        print("  ", n)
    if len(names) > 400:
        print("   ... truncated", len(names) - 400)

# ---- string intelligence ----
files = sorted(STR.glob("*.strings.txt"))
lines = []
for f in files:
    lines.extend(f.read_text(encoding="utf-8", errors="replace").splitlines())
print("\n" + "=" * 70)
print("DEX STRINGS:", len(lines), "from", len(files), "dex")
print("=" * 70)

# class descriptors
classes = [s for s in lines if s.startswith("L") and s.endswith(";") and "/" in s]
print("\n-- class descriptors:", len(classes))

def pkg_prefix(s, depth):
    body = s[1:-1]
    parts = body.split("/")
    return "/".join(parts[:depth])

for depth in (2, 3):
    cnt = Counter(pkg_prefix(c, depth) for c in classes)
    print(f"\n-- top package prefixes (depth {depth}) --")
    for k, v in cnt.most_common(45):
        print(f"  {v:7d}  {k}")

KEYWORDS = [
    "dlna", "upnp", "cling", "jupnp", "dmr", "dmp", "dms", "mediarouter", "cast",
    "bluetooth", "a2dp", "avrcp", "ble", "ldac", "lhdc", "aptx", "hfp",
    "exoplayer", "media3", "ijkplayer", "vlc", "ffmpeg", "mediacodec", "audiotrack",
    "opensl", "aaudio", "oboe", "sonic", "soundtouch",
    "webdav", "sardine", "sftp", "jsch", "sshj", "nfs", "smb", "samba", "ftp", "curl",
    "strm", "m3u", "m3u8", "pls",
    "flac", "ape", "dsd", "dff", "dsf", "wav", "aiff", "opus", "ogg", "mp3", "aac", "m4a", "wma", "cue", "tak", "wavpack",
    "media3.session", "mediasession", "notification", "audioservice",
    "androidx.car", "carplay", "androidauto",
    "room", "wcdb", "sqlite", "mmkv", "greendao",
    "okhttp", "retrofit", "cronet", "glide", "coil", "fresco",
    "reactnative", "hermes", "com.facebook.react",
    "taglib", "jaudiotagger", "id3", "vorbiscomment",
    "lyric", "lrc", "karaoke",
    "equalizer", "bassboost", "virtualizer", "presetreverb", "audiovisualizer",
    "usb", "usbaudio", "hifi", "hires",
]
print("\n-- keyword hits (unique strings) --")
low = [(s.lower(), s) for s in set(lines)]
for kw in KEYWORDS:
    hits = [orig for l, orig in low if kw in l and len(orig) < 160]
    if hits:
        print(f"\n[{kw}] {len(hits)} hits, sample:")
        for h in sorted(hits, key=len)[:8]:
            print("   ", h[:150])

# URLs / endpoints
urls = sorted({s for s in lines if s.startswith("http") and len(s) < 130})
print(f"\n-- URLs ({len(urls)}) sample --")
for u in urls[:40]:
    print("   ", u)
