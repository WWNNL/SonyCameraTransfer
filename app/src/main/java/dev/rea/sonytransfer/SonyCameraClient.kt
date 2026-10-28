package dev.rea.sonytransfer

import java.io.File

/**
 * 「发送到智能手机 → 在本机上选择」接收流程（PC P0 验证过的完整时序）：
 *
 *   dd.xml → 解析 controlURL
 *   X_TransferStart
 *   X_GetPushRoot        → ObjectID (PushRoot)
 *   Browse(PushRoot)     → 相机端选中的图片列表
 *   对每张：X_TransferProgress → 下载 TN 缩略图（预览用）→ 流式 GET 原图落盘 → 保存
 *   X_TransferEnd(0)     → 相机退出图传、关闭热点
 *
 * 单张快路径：PushRoot 为空时读 /DigitalImagingDesc.xml 的 X_CurrentContent_URL。
 *
 * 稳定性：原图/视频先流式下载到 [tempDir] 下的临时文件（带停滞续传、重试、可取消），
 * 保存完成后再删临时文件，避免整块读进内存（视频几百 MB 会 OOM/GC 卡死）。
 */
class SonyCameraClient(
    private val tempDir: File,
    private val log: (String) -> Unit,
) {

    data class Result(val ok: Boolean, val message: String, val savedCount: Int)

    /** 单文件下载进度；totalBytes 来自 res 的 size 属性，可能为 0（未知）。 */
    data class Progress(
        val index: Int,
        val total: Int,
        val title: String,
        val downloaded: Long,
        val totalBytes: Long,
    )

    @Volatile
    private var cancelled = false

    /** 由 UI 线程调用：请求中断当前接收（下一次读取循环时生效）。 */
    fun cancel() {
        cancelled = true
    }

    fun receivePush(
        baseUrl: String,
        onItems: (List<CameraItem>) -> Unit = {},
        onThumbnail: (ByteArray) -> Unit = {},
        onProgress: (Progress) -> Unit = {},
        save: (String, String, File) -> Boolean,
    ): Result {
        var saved = 0
        var totalItems = 0
        var pushUrlForEnd: String? = null
        return try {
            val ddXml = String(Upnp.httpGetBytes("$baseUrl/dd.xml"), Charsets.UTF_8)
            val services = Upnp.parseDd(ddXml)
            log("DD 服务: " + services.joinToString {
                it.serviceType.substringBeforeLast(':').substringAfterLast(':')
            })

            fun controlOf(typePart: String): String? {
                val url = services.firstOrNull { it.serviceType.contains(typePart) }?.controlUrl
                    ?: return null
                return if (url.startsWith("http")) url else baseUrl + url
            }

            val cdsUrl = controlOf("ContentDirectory")
                ?: return Result(false, "DD 中没找到 ContentDirectory", 0)
            val pushUrl = controlOf("XPushList")
                ?: return Result(false, "DD 中没找到 XPushList", 0)
            pushUrlForEnd = pushUrl

            log("X_TransferStart")
            Upnp.soap(pushUrl, Upnp.XPS, "X_TransferStart")

            val pushRootResponse = Upnp.soap(pushUrl, Upnp.XPS, "X_GetPushRoot")
            val pushRoot = Regex("<ObjectID>([^<]*)</ObjectID>").find(pushRootResponse)
                ?.groupValues?.get(1) ?: "PushRoot"
            log("X_GetPushRoot -> $pushRoot")

            var items = Upnp.browse(cdsUrl, pushRoot)
            log("Browse($pushRoot) -> ${items.size} 张")

            if (items.isEmpty()) {
                log("PushRoot 为空，尝试 DigitalImagingDesc 单张快路径…")
                items = itemsFromDigitalImaging(baseUrl)
                log("单张快路径 -> ${items.size} 张")
            }

            if (items.isEmpty()) {
                runCatching { Upnp.soap(pushUrl, Upnp.XPS, "X_TransferEnd", "<ErrCode>0</ErrCode>") }
                return Result(false, "相机端没有可接收的图片（请先在相机上选图）", 0)
            }

            totalItems = items.size
            onItems(items)

            items.forEachIndexed { index, item ->
                if (cancelled) throw DownloadCancelledException()
                log("接收 ${index + 1}/${items.size}: ${item.title}")
                runCatching {
                    Upnp.soap(
                        pushUrl, Upnp.XPS, "X_TransferProgress",
                        "<NumTotal>${items.size}</NumTotal><NumTransferd>$index</NumTransferd>",
                    )
                }
                val res = item.originalRes
                if (res == null) {
                    log("跳过 ${item.title}：没有可用 res")
                    return@forEachIndexed
                }
                val sizeHint = res.sizeHint ?: 0L
                log(
                    "  ${item.title}: ${item.resList.size} 个 res → " +
                        "${Upnp.mimeOf(res.protocolInfo)}, ${sizeHint / 1024} KB",
                )
                onProgress(Progress(index, items.size, item.title, 0, sizeHint))

                // 先取 TN 缩略图做预览（小、快；失败不影响传输）
                val thumbnail = item.resList.firstOrNull { it.protocolInfo.contains("JPEG_TN") }
                if (thumbnail != null) {
                    runCatching { Upnp.httpGetBytes(thumbnail.url, 8_000) }
                        .onSuccess { onThumbnail(it) }
                        .onFailure { log("缩略图获取失败: ${it.message}") }
                }

                val tempFile = File(tempDir, "recv_${index}_${System.currentTimeMillis()}.tmp")
                try {
                    val received = Upnp.downloadToFile(
                        url = res.url,
                        target = tempFile,
                        stallTimeoutMs = 15_000,
                        maxAttempts = 3,
                        onProgress = { downloaded, total ->
                            onProgress(
                                Progress(
                                    index, items.size, item.title,
                                    downloaded, if (total > 0) total else sizeHint,
                                ),
                            )
                        },
                        shouldCancel = { cancelled },
                        log = { line -> log("  $line") },
                    )
                    if (received <= 0) {
                        log("  ${item.title}: 收到 0 字节，跳过")
                    } else if (save(item.title, Upnp.mimeOf(res.protocolInfo), tempFile)) {
                        saved++
                    }
                } finally {
                    runCatching { tempFile.delete() }
                }
            }

            log("X_TransferEnd(0)")
            Upnp.soap(pushUrl, Upnp.XPS, "X_TransferEnd", "<ErrCode>0</ErrCode>")
            Result(true, "完成：接收 $saved/${items.size} 张", saved)
        } catch (e: DownloadCancelledException) {
            val totalText = if (totalItems > 0) totalItems.toString() else "?"
            log("已取消接收（完成 $saved/$totalText 张），通知相机退出图传…")
            pushUrlForEnd?.let { url ->
                runCatching { Upnp.soap(url, Upnp.XPS, "X_TransferEnd", "<ErrCode>0</ErrCode>", 4000) }
            }
            Result(false, "已取消：接收 $saved/${totalItems.ifZero("?")} 张", saved)
        } catch (e: Exception) {
            Result(false, "接收失败：${e.javaClass.simpleName}: ${e.message}", saved)
        }
    }

    private fun Int.ifZero(alt: String): String = if (this == 0) alt else toString()

    private fun itemsFromDigitalImaging(baseUrl: String): List<CameraItem> {
        val xml = String(Upnp.httpGetBytes("$baseUrl/DigitalImagingDesc.xml"), Charsets.UTF_8)
        val resList = Regex(
            "<X_CurrentContent_URL_URL\\b([^>]*)>([^<]*)</X_CurrentContent_URL_URL>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml).map { match ->
            val attrs = match.groupValues[1]
            val profile = Regex("profileID=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: "UNKNOWN"
            val proto = if (profile == "UNKNOWN") {
                "http-get:*:image/jpeg:*"
            } else {
                "http-get:*:image/jpeg:DLNA.ORG_PN=$profile;DLNA.ORG_CI=1"
            }
            val sizeHint = Regex("size=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toLongOrNull()
            ResEntry(proto, Upnp.unescape(match.groupValues[2].trim()), sizeHint)
        }.toList()
        if (resList.isEmpty()) return emptyList()
        val fileName = Regex("/([^/?]+)\\?").find(resList.last().url)?.groupValues?.get(1)
            ?: "DID_IMAGE.JPG"
        return listOf(CameraItem("did-current", fileName, resList))
    }
}
