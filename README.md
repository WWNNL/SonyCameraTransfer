# Sony 相机传图（Kotlin / Android MVP）

从 Sony 相机（已验证 **ILCE-6300 / a6300**）的「发送到智能手机」模式接收照片。
**不依赖 PTP/IP**，全程标准 UPnP/HTTP + Sony 私有 `XPushList` SOAP，无配对、无认证。

协议规格见：[`work/P0-总结与Android实现规格.md`](../../work/P0-总结与Android实现规格.md)（PC 端 P0 实机验证记录在 `work/p0/`）。

## 功能（MVP 已实现）

1. **NFC 一碰**：读 `application/x-sony-pmm` 标签 → 解析 SSID / 密码 / IP / DD-URL（按官方语义校验）。
2. **连相机 Wi-Fi**：Android 10+ 用 `WifiNetworkSpecifier`（系统确认一次），并 `bindProcessToNetwork`；
   若手机已连在相机热点（无 Internet 的 Wi-Fi）上，则直接复用该网络（跳过系统确认框）。
3. **发现**：优先探测已知地址（上次成功地址缓存 / NFC DD 主机 / 默认网关 / 固定 IP）的 `dd.xml`，
   全部未命中才用 SSDP M-SEARCH（`DigitalImaging` / `MediaServer`，3s 超时、期间重发）。
4. **接收（在本机上选择 / 相机端选图推送）**：
   - `X_TransferStart` → `X_GetPushRoot` → `ContentDirectory#Browse("PushRoot")` → 逐张 `X_TransferProgress` + GET 原图 → `X_TransferEnd`；
   - 单张快路径：`/DigitalImagingDesc.xml` 的 `X_CurrentContent_URL`；
   - 自动保存到相册 `Pictures/SonyTransfer`。
5. **传图进度条**：总进度条（`总进度 2/3 · 68%`）+ 当前图片进度条（`DSC04304.JPG · 45% · 4.8 MB/s`，速度按最近 ~1s 增量平滑），结束后日志给出总用时与平均速度。
6. **传图预览**：开始传输前先取相机端 TN 缩略图显示；每张保存后再解码原图预览（自动 EXIF 旋转、按 1280px 采样防 OOM）；底部缩略图条累积所有已接收图片。
7. **全屏查看 + 信息侧栏**：点击缩略图（或大图预览区）进入全屏查看器，双指缩放（1x~8x）、单指拖动、双击缩放；右侧侧栏显示当前图片详情（文件名、大小、分辨率、拍摄时间、机身、曝光参数、保存位置），右上角「信息」可收起。
8. **预览旋转**：全屏查看器右上角「旋转」按钮，图片/视频预览每点一次顺时针 90°（只影响预览显示，不改原文件）。
8. **照片日期修正**（应对相机没电后时钟归零、照片变成 2016 年）：
   - 单张：全屏查看器侧栏「修改日期」→ 选目标日期（可用于个别拍歪时间）；
   - 整批：主界面「按偏移量改日期」→ 输入 年/月/日/时/分 偏移（如全部 +10 年），每张按各自原时间平移，**彼此相对时间差保持不变**；
   - 图片同时改写 EXIF（DateTime / DateTimeOriginal / DateTimeDigitized）和 MediaStore `DATE_TAKEN`；视频只改相册日期；相册排序/显示同步更新。
9. **视频接收/预览**：相机端发送视频时按 `video/mp4` 保存到 `Movies/SonyTransfer`；缩略图条用视频首帧，点击全屏播放（自绘播放器：按比例居中不变形、双指/双击缩放、拖动、底部播放/暂停与进度条）。
10. **横竖屏适配**：旋转屏幕不重建界面（接收进度/日志/缩略图不丢），主界面内容可整体上下滑动，横屏也能完整操作；日志框自动占满剩余屏幕空间（竖屏铺满，横屏限高 96–220dp），并自动滚到最新一行。
11. 手动兜底：直接输入 SSID/密码连接、断开、实时日志。

## 构建

工程带 Gradle Wrapper（8.9）：

```bash
./gradlew assembleDebug          # macOS/Linux/git-bash
gradlew.bat assembleDebug        # Windows cmd
```

也可以直接用本机工具链（`E:/Projects/REAProjects/toolchain`）：

```bash
export JAVA_HOME='C:\Program Files\Java\jdk-21'
E:/Projects/REAProjects/toolchain/gradle-8.9/bin/gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（已构建成功，约 870 KB）。
`local.properties` 指向本机 SDK：`E:/Projects/REAProjects/toolchain/android-sdk`；
用 Android Studio 打开时改成本机 SDK 路径即可。

安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（需要手机开启 USB 调试）。

## 使用

1. 相机：「发送到智能手机 → 在本机上选择」→ 选好照片 → 屏幕出现 SSID/密码 +「正在连接…」。
2. 手机：打开 App → 贴相机 NFC（或手动填 SSID/密码）→ App 自动连 Wi-Fi、发现、接收、保存。
   想更快：可先在系统 Wi-Fi 设置里手动连上相机热点，App 会直接复用该网络（不弹系统确认框、不再重新关联）。
3. 传输时 App 显示总进度 / 当前图片进度 / 实时速度，并预览当前照片；底部缩略图条累积所有已接收图片，点击可看大图。
4. 照片保存到相册 `Pictures/SonyTransfer`；传完相机会自动退出图传并关闭热点（`X_TransferEnd` 后），属正常。
5. 如果照片日期不对（相机没电后时钟归零会变成 2016）：点缩略图进全屏 → 侧栏「修改日期」单张修正；或主界面「按偏移量改日期」整批平移（保留相对时间差）。
6. 相机端选了视频会一起接收：存到 `Movies/SonyTransfer`，缩略图显示首帧，点击全屏播放。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | UI（程序化布局：预览区 + 进度条 + 日志）、NFC 前台分发、流程编排 |
| `SonyNfc.kt` | NDEF Intent → payload，官方语义校验（版本/ WifiSetting / SSID / 密码） |
| `NdefPayloadParser.kt` | 纯 Kotlin TLV 解析（可 JVM 单测） |
| `CameraWifi.kt` | `WifiNetworkSpecifier` 连接 + `bindProcessToNetwork` |
| `Ssdp.kt` | SSDP 发现，返回 `dd.xml` LOCATION |
| `Upnp.kt` | HTTP GET（流式+进度回调）/ SOAP / DD 解析 / DIDL 解析 / res 选择 |
| `SonyCameraClient.kt` | 「在本机上选择」完整接收时序（含进度、缩略图、图片/视频类型回调） |
| `FullImageViewer.kt` | 全屏查看器（图片双指缩放 / 视频播放）+ 半透明信息侧栏 |
| `VideoPlayerView.kt` | 视频播放器：TextureView + MediaPlayer + 缩放/拖动 + 自绘控制条 |
| `PhotoDateEditor.kt` | 修改日期（EXIF + MediaStore）、日期选择/偏移工具 |
| `ImageSaver.kt` | 保存（图片 Pictures、视频 Movies；API 29+ MediaStore / 旧版公共目录） |

## 已知限制 / TODO

- NFC 已真机验证；**a6300 的标签只有 3 个 TLV（版本 + SSID + 密码，共 38 字节）**，没有 IP/DD-URL 段，
  所以相机发现用 SSDP（官方 App 同样如此）。完整标签（含网络信息/DD-URL）的机型也已在解析器里支持，并有单测覆盖。
- **「在智能手机上选择」子模式经真机确认只能看到相机端已选中的图片，无法浏览相机全部相册**，
  因此浏览模式已移除（PC P0 当时只有 1 张样本，误判为全量相册，已更正文档）。
- 视频（MP4/XAVC S）、HEIF、RAW 未处理。
- 国产 ROM 的 Wi-Fi 绑定/后台限制兼容性待真机验证（目前只测了一台设备）。
- Android 10+ 首次连接未知相机热点时，系统的「连接到此 WLAN？」确认框无法绕过（系统安全限制）；
  连过之后可在系统 Wi-Fi 设置里保留该网络，App 下次会直接复用。
- 相机传完即关热点，一次会话只接收一批；要再传需重新进相机菜单。

## 真机验证记录

2026-10-28 01:37，首版 APK 在真机上一次跑通：
NFC 读到 `DIRECT-91E0:WWNNL` + 密码 → 连 Wi-Fi → SSDP → `X_TransferStart` → `X_GetPushRoot` →
`Browse(PushRoot)=1` → 接收 `DSC04303.JPG` → `X_TransferEnd(0)` → 保存到相册（「完成：接收 1/1 张」）。
截图：`work/p0/evidence/android-first-run-20261028-0137.jpg`。
