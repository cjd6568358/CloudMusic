# E2E Runbook：m3u 歌单导入 + 真实曲库源真机验证（§61.254 闭环判据，2026-11-20）

> 前置状态（已就绪）：**m3u 已上传服务器** `ecloud_self/我的音乐/歌单测试.m3u`（201，durable），
> 内容 = 3 条相对条目（陈琳-爱就爱了.flac / G.E.M.邓紫棋-后会无期.flac / dollyy99-官方回答.mp3，
> 均实存于同目录）。代码 = §61.254 修复全量已 push。设备 DataStore = 单源未动（手术未写入）。

## 步骤（设备上线后机械执行）

1. **连接**：用户发裸端口 → mDNS 或试 `192.168.1.11:<port>`。
2. **DataStore 手术**（force-stop 先行）：
   - `run-as cat files/datastore/cloudmusic_settings.pb`（JSON 裸文本）→
   - storages 追加 `{"id":"ecloud-music","mountPath":"/ecloud-music","driverType":"webdav",
     "additionRaw":"{\"address\":\"http://192.168.1.1:15244/dav/ecloud_self/我的音乐\",
     \"username\":\"admin\",\"password\":\"e3eb773F\"}","remark":"真实曲库","disabled":false,
     "errorMsg":"","modified":1795110000000}` → 推回（`cp` 覆盖 pb）。
3. **装机**：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（当前构建即含全量修复）。
4. **冷启动 + 扫描**：`am start MainActivity` → 轮询 logcat `scan done`（135 文件首扫，
   ecloud 上游元数据可能分钟级；CM-Scan 打点逐步可见）。
5. **判据 A（歌单导入）**：`run-as cat databases/cloudmusic.db` 拉库本地读——
   - `playlists` 含 `pl_imp_*` 行（name=歌单测试）；
   - `playlist_tracks` 该 id 下 3 行（顺序 = m3u 序）；
   - `tracks` 含 ecloud-music 源行（预计 ~135 + m3u 展开的 PLAYLIST 轨 3 条）。
6. **判据 B（播放）**：DB 手术 `playback_state.current_track_id` = 展开轨任一 → 重启 →
   `am startservice -a com.example.cloudmusic.action.PLAY -n .PlaybackService` →
   - dumpsys media_session：state=3 + position 推进（两次采样差 ≈ 真实间隔）；
   - `dumpsys media.audio_flinger`：Standby:no（音频流动）；
   - 顶栏错误可见性（若失败：lastError 文案直接定性）。
7. **判据 C（歌单页视觉）**：截屏（歌单页需 UI 导航，SurfaceNav 无 OPEN_PLAYLISTS——
   用 LIBRARY 页 + 手动滑动或仅 DB+播放证据收口，视觉 pending）。
8. **收尾**：`svc power stayon false`；**保留 ecloud-music 源**（用户真实曲库——
   这是目标态，非测试残留）；m3u 留服务器；登记判读结果。

## 已知风险

- ecloud 上游（天翼云）元数据请求慢 → 首扫分钟级属正常（§61.144 批量写已优化）。
- CUE 文件（CDImage.cue）会触发 §61.221 分轨展开——附带解锁 CUE 真机判据。
- MIUI Doze：熄屏时网络阻断（§61.189/252 已知）——判读期间保持亮屏（`svc power stayon true`）。

> AI生成
