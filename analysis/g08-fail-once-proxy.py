"""G08 / §61.26b 真机故障注入代理（一次性 GET 拒绝）。

用途：验证 `RangeFetcher.fetch()` 的「连接期失败立即重试一次」是否真的
能把无线隧道 / 弱网瞬断救回来，而不是被 native 判成 -103 整曲跳过。

拓扑（不改动 App 的 URL 夹具）：
    设备 127.0.0.1:8765 --(adb reverse)--> PC 127.0.0.1:8766 (本代理)
                                            └--> PC 127.0.0.1:8765 (python http.server 音源)

规则：HEAD 全部放行（`EngineAdapter.resolveLength()` 长度探测必须先成功），
**第一个 GET 连接不发任何响应字节、以 RST 关闭**（模拟连接期瞬断），
之后所有连接透明转发。若修复生效，同一个 native `Start id=N` 会在
logcat 里对应两条 `open url=`，第二条成功 → `onBegin` / `onEnd(error=null)`。

运行：python analysis/g08-fail-once-proxy.py
退出：Ctrl-C / 结束后台任务。恢复现场：adb reverse tcp:8765 tcp:8765
"""

import select
import socket
import struct
import threading
import time

LISTEN = ("127.0.0.1", 8766)
UPSTREAM = ("127.0.0.1", 8765)

_lock = threading.Lock()
_get_count = 0
_fail_used = False


def log(message: str) -> None:
    print(f"{time.strftime('%H:%M:%S')}.{int(time.time() * 1000) % 1000:03d} {message}", flush=True)


def _pipe(client: socket.socket, upstream: socket.socket) -> None:
    pair = [client, upstream]
    try:
        while True:
            readable, _, _ = select.select(pair, [], [], 30)
            if not readable:
                break
            for sock in readable:
                data = sock.recv(65536)
                if not data:
                    return
                (upstream if sock is client else client).sendall(data)
    except OSError as exc:
        log(f"pipe ended: {exc}")
    finally:
        for sock in pair:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                sock.close()
            except OSError:
                pass


def _handle(client: socket.socket, addr) -> None:
    global _get_count, _fail_used
    client.settimeout(10)
    request = b""
    try:
        while b"\r\n\r\n" not in request and len(request) < 65536:
            chunk = client.recv(4096)
            if not chunk:
                break
            request += chunk
    except OSError as exc:
        log(f"read request error: {exc}")

    head = request.split(b"\r\n\r\n", 1)[0].decode("latin-1", "replace")
    method = head.split(" ", 1)[0] if head else "?"
    range_line = next(
        (line for line in head.splitlines() if line.lower().startswith("range:")), "-"
    )

    fail = False
    with _lock:
        if method == "GET":
            _get_count += 1
            get_no = _get_count
            if not _fail_used:
                _fail_used = True
                fail = True
        else:
            get_no = 0

    log(f"{addr[0]}:{addr[1]} method={method} {range_line.strip()} get#{get_no} "
        f"-> {'FAIL_ONCE (RST)' if fail else 'PASS'}")

    if fail:
        # SO_LINGER(1, 0)：不发响应、直接 RST，等价于连接期瞬断。
        try:
            client.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        except OSError:
            pass
        client.close()
        return

    try:
        upstream = socket.create_connection(UPSTREAM, timeout=10)
    except OSError as exc:
        log(f"upstream connect failed: {exc}")
        client.close()
        return
    if request:
        upstream.sendall(request)
    threading.Thread(target=_pipe, args=(client, upstream), daemon=True).start()


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(LISTEN)
    server.listen(16)
    log(f"listening {LISTEN} -> {UPSTREAM}; first GET will be dropped by RST")
    try:
        while True:
            conn, addr = server.accept()
            threading.Thread(target=_handle, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        log("stopped")
    finally:
        server.close()


if __name__ == "__main__":
    main()
