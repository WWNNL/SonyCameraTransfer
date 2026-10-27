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
5. **浏览（在智能手机上选择 / 手机端浏览相机）**：
   - `Browse 0 → PhotoRoot → Date → yyyy-M-d` 目录树，自动下钻只有单个子文件夹的层级；
   - 列表勾选多张（跨文件夹保持选中）→「下载选中」逐张保存原图；
   - 上级 / 刷新；显示文件夹项数与单张原图大小。
6. 手动兜底：直接输入 SSID/密码连接、断开、实时日志。

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

产物：`app/build/outputs/apk/debug/app-debug.apk`（已构建成功，约 807 KB）。
`local.properties` 指向本机 SDK：`E:/Projects/REAProjects/toolchain/android-sdk`；
用 Android Studio 打开时改成本机 SDK 路径即可。

安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（需要手机开启 USB 调试）。

## 使用

### A. 推送接收（相机端选图，「在本机上选择」）

1. 相机：「发送到智能手机 → 在本机上选择」→ 选好照片 → 屏幕出现 SSID/密码 +「正在连接…」。
2. 手机：打开 App → 贴相机 NFC（或手动填 SSID/密码）→ App 自动连 Wi-Fi、发现、接收、保存。
3. 传完相机会自动退出图传并关闭热点（`X_TransferEnd` 后），属正常。

### B. 浏览相机（手机端浏览相册，「在智能手机上选择」）

1. 相机：「发送到智能手机 → 在智能手机上选择」→ 屏幕显示 SSID/密码。
2. 手机：App 贴 NFC（或手动填 SSID/密码 → 连接相机）→ 点「浏览相机」。
3. 自动进到日期文件夹：点文件夹进入、勾选照片（可跨文件夹）、点「下载选中」保存原图。
4. 相机不会自动退出该模式；退出时在相机上按退出/等待其超时。

## 代码结构

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | UI（程序化布局，推送/浏览双面板）、NFC 前台分发、流程编排、日志 |
| `SonyNfc.kt` | NDEF Intent → payload，官方语义校验（版本/ WifiSetting / SSID / 密码） |
| `NdefPayloadParser.kt` | 纯 Kotlin TLV 解析（可 JVM 单测） |
| `CameraWifi.kt` | `WifiNetworkSpecifier` 连接 + `bindProcessToNetwork` |
| `Ssdp.kt` | SSDP 发现，返回 `dd.xml` LOCATION |
| `Upnp.kt` | HTTP GET / SOAP / DD 解析 / DIDL 解析（container + item）/ res 选择 |
| `SonyCameraClient.kt` | 「在本机上选择」完整接收时序 |
| `SonyCameraBrowser.kt` | 「在智能手机上选择」Browse + 原图下载 |
| `BrowseAdapter.kt` | 浏览列表：文件夹行 / 照片勾选 |
| `ImageSaver.kt` | MediaStore 保存（API 29+）/ 旧版公共目录 |

## 已知限制 / TODO

- NFC 已真机验证；**a6300 的标签只有 3 个 TLV（版本 + SSID + 密码，共 38 字节）**，没有 IP/DD-URL 段，
  所以相机发现用 SSDP（官方 App 同样如此）。完整标签（含网络信息/DD-URL）的机型也已在解析器里支持，并有单测覆盖。
- 浏览模式（「在智能手机上选择」）已实现并通过 DIDL 解析单测，**尚未真机验证**（相机需切到该子模式）。
- 视频（MP4/XAVC S）、HEIF、RAW 未处理。
- 国产 ROM 的 Wi-Fi 绑定/后台限制兼容性待真机验证（目前只测了一台设备）。
- 相机传完即关热点，一次会话只接收一批；要再传需重新进相机菜单。

## 真机验证记录

2026-10-28 01:37，首版 APK 在真机上一次跑通：
NFC 读到 `DIRECT-91E0:WWNNL` + 密码 → 连 Wi-Fi → SSDP → `X_TransferStart` → `X_GetPushRoot` →
`Browse(PushRoot)=1` → 接收 `DSC04303.JPG` → `X_TransferEnd(0)` → 保存到相册（「完成：接收 1/1 张」）。
截图：`work/p0/evidence/android-first-run-20261028-0137.jpg`。
