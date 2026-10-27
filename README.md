# Sony 相机传图（Kotlin / Android MVP）

从 Sony 相机（已验证 **ILCE-6300 / a6300**）的「发送到智能手机」模式接收照片。
**不依赖 PTP/IP**，全程标准 UPnP/HTTP + Sony 私有 `XPushList` SOAP，无配对、无认证。

协议规格见：[`work/P0-总结与Android实现规格.md`](../../work/P0-总结与Android实现规格.md)（PC 端 P0 实机验证记录在 `work/p0/`）。

## 功能（MVP 已实现）

1. **NFC 一碰**：读 `application/x-sony-pmm` 标签 → 解析 SSID / 密码 / IP / DD-URL（按官方语义校验）。
2. **连相机 Wi-Fi**：Android 10+ 用 `WifiNetworkSpecifier`（系统确认一次），并 `bindProcessToNetwork`。
3. **发现**：SSDP M-SEARCH（`DigitalImaging` / `MediaServer`）→ `dd.xml`。
4. **接收（在本机上选择 / 相机端选图推送）**：
   - `X_TransferStart` → `X_GetPushRoot` → `ContentDirectory#Browse("PushRoot")` → 逐张 `X_TransferProgress` + GET 原图 → `X_TransferEnd`；
   - 单张快路径：`/DigitalImagingDesc.xml` 的 `X_CurrentContent_URL`；
   - 自动保存到相册 `Pictures/SonyTransfer`。
5. **传图进度条**：总进度 + 当前文件字节进度（`已下载/总大小`，大小来自 res 的 `size` 属性）。
6. **传图预览**：开始传输前先取相机端 TN 缩略图显示；每张保存后再解码原图预览（自动 EXIF 旋转、按 1280px 采样防 OOM）。
7. 手动兜底：直接输入 SSID/密码连接、断开、实时日志。

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

产物：`app/build/outputs/apk/debug/app-debug.apk`（已构建成功，约 850 KB）。
`local.properties` 指向本机 SDK：`E:/Projects/REAProjects/toolchain/android-sdk`；
用 Android Studio 打开时改成本机 SDK 路径即可。

安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（需要手机开启 USB 调试）。

## 使用

1. 相机：「发送到智能手机 → 在本机上选择」→ 选好照片 → 屏幕出现 SSID/密码 +「正在连接…」。
2. 手机：打开 App → 贴相机 NFC（或手动填 SSID/密码）→ App 自动连 Wi-Fi、发现、接收、保存。
3. 传输时 App 显示进度条 + 当前照片预览；照片保存到相册 `Pictures/SonyTransfer`。
4. 传完相机会自动退出图传并关闭热点（`X_TransferEnd` 后），属正常。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | UI（程序化布局：预览区 + 进度条 + 日志）、NFC 前台分发、流程编排 |
| `SonyNfc.kt` | NDEF Intent → payload，官方语义校验（版本/ WifiSetting / SSID / 密码） |
| `NdefPayloadParser.kt` | 纯 Kotlin TLV 解析（可 JVM 单测） |
| `CameraWifi.kt` | `WifiNetworkSpecifier` 连接 + `bindProcessToNetwork` |
| `Ssdp.kt` | SSDP 发现，返回 `dd.xml` LOCATION |
| `Upnp.kt` | HTTP GET（流式+进度回调）/ SOAP / DD 解析 / DIDL 解析 / res 选择 |
| `SonyCameraClient.kt` | 「在本机上选择」完整接收时序（含进度与缩略图回调） |
| `ImageSaver.kt` | MediaStore 保存（API 29+）/ 旧版公共目录 |

## 已知限制 / TODO

- NFC 已真机验证；**a6300 的标签只有 3 个 TLV（版本 + SSID + 密码，共 38 字节）**，没有 IP/DD-URL 段，
  所以相机发现用 SSDP（官方 App 同样如此）。完整标签（含网络信息/DD-URL）的机型也已在解析器里支持，并有单测覆盖。
- **「在智能手机上选择」子模式经真机确认只能看到相机端已选中的图片，无法浏览相机全部相册**，
  因此浏览模式已移除（PC P0 当时只有 1 张样本，误判为全量相册，已更正文档）。
- 视频（MP4/XAVC S）、HEIF、RAW 未处理。
- 国产 ROM 的 Wi-Fi 绑定/后台限制兼容性待真机验证（目前只测了一台设备）。
- 相机传完即关热点，一次会话只接收一批；要再传需重新进相机菜单。

## 真机验证记录

2026-10-28 01:37，首版 APK 在真机上一次跑通：
NFC 读到 `DIRECT-91E0:WWNNL` + 密码 → 连 Wi-Fi → SSDP → `X_TransferStart` → `X_GetPushRoot` →
`Browse(PushRoot)=1` → 接收 `DSC04303.JPG` → `X_TransferEnd(0)` → 保存到相册（「完成：接收 1/1 张」）。
截图：`work/p0/evidence/android-first-run-20261028-0137.jpg`。
