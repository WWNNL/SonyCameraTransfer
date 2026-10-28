package dev.rea.sonytransfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class UpnpService(val serviceType: String, val controlUrl: String, val scpdUrl: String)

data class ResEntry(
    val protocolInfo: String,
    val url: String,
    val sizeHint: Long?,
)

/** 用户主动取消接收。 */
class DownloadCancelledException : Exception("已取消接收")

/** 浏览结果：可能是目录（container）也可能是照片/视频（item）。 */
data class BrowseEntry(
    val id: String,
    val title: String,
    val upnpClass: String,
    val childCount: Int?,
    val resList: List<ResEntry>,
) {
    val isContainer: Boolean
        get() = upnpClass.contains("container", ignoreCase = true)

    /** 原始文件：视频优先 video 类型（视频 res 可能带 DLNA.ORG_PN），图片优先无 PN 且体积最大的 res。 */
    val originalRes: ResEntry?
        get() = Upnp.selectOriginalRes(resList)
}

data class BrowsePage(val entries: List<BrowseEntry>, val totalMatches: Int)

/** 推送模式使用的最小照片条目（保持原字段，不破坏已有流程）。 */
data class CameraItem(
    val id: String,
    val title: String,
    val resList: List<ResEntry>,
) {
    val originalRes: ResEntry?
        get() = Upnp.selectOriginalRes(resList)
}

object Upnp {
    const val CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val XPS = "urn:schemas-sony-com:service:XPushList:1"

    /** protocolInfo 第 3 段是 mime，例如 "http-get:*:video/mp4:DLNA.ORG_PN=..." → "video/mp4"。 */
    fun mimeOf(protocolInfo: String): String {
        val mime = protocolInfo.split(':').getOrNull(2)?.trim()
        return if (mime.isNullOrEmpty()) "image/jpeg" else mime
    }

    /**
     * 从一组 res 里挑"原始文件"：
     *  1. 有 video 类型 → 取最大的 video res（视频的 res 常带 DLNA.ORG_PN，不能按"无 PN"判断）；
     *  2. 图片 → 优先无 DLNA.ORG_PN（真原图），多个取最大；
     *  3. 兜底 → 排除 JPEG_TN 后取最大，避免把缩略图当原图。
     */
    fun selectOriginalRes(resList: List<ResEntry>): ResEntry? {
        if (resList.isEmpty()) return null
        val videos = resList.filter { mimeOf(it.protocolInfo).startsWith("video/") }
        if (videos.isNotEmpty()) {
            return videos.maxByOrNull { it.sizeHint ?: 0L } ?: videos.first()
        }
        val images = resList.filter { mimeOf(it.protocolInfo).startsWith("image/") }
        val pool = images.ifEmpty { resList }
        val originals = pool.filterNot { it.protocolInfo.contains("DLNA.ORG_PN") }
        if (originals.isNotEmpty()) {
            return originals.maxByOrNull { it.sizeHint ?: 0L } ?: originals.first()
        }
        val nonThumbnails = pool.filterNot { it.protocolInfo.contains("JPEG_TN") }
        return (nonThumbnails.ifEmpty { pool }).maxByOrNull { it.sizeHint ?: 0L }
    }

    /**
     * 流式 GET，可选进度回调（downloadedBytes, totalBytes；total 未知时为 0）。
     * 回调节流：约每 150ms 一次，末尾必回调一次。
     */
    fun httpGetBytes(
        url: String,
        timeoutMs: Int = 30000,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Connection", "close")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $url")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                var lastReportAt = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    downloaded += n
                    val now = System.currentTimeMillis()
                    if (onProgress != null && (now - lastReportAt >= 150 || n < buffer.size)) {
                        onProgress(downloaded, total)
                        lastReportAt = now
                    }
                }
                onProgress?.invoke(downloaded, total)
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun soap(
        controlUrl: String,
        serviceType: String,
        action: String,
        innerXml: String = "",
        timeoutMs: Int = 20000,
    ): String {
        val envelope = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$serviceType\">$innerXml</u:$action>" +
            "</s:Body></s:Envelope>"
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        conn.setRequestProperty("Connection", "close")
        try {
            conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("SOAP $action -> HTTP $code $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 断点续传式流式下载：相机传到一半停住时不再无限等待。
     *
     *  - 读超时 = stallTimeoutMs，超过即判定"停滞"，用 HTTP Range 从已收字节续传（最多 maxAttempts 次）；
     *  - 相机不支持 Range（续传请求返回 200）时自动从头重下；
     *  - 全程只写文件，不把整张原图/视频读进内存（几百 MB 的 MP4 也不会 OOM）；
     *  - 每约 150ms 回调一次进度；返回最终落盘字节数。
     */
    fun downloadToFile(
        url: String,
        target: File,
        stallTimeoutMs: Int = 15_000,
        maxAttempts: Int = 3,
        onProgress: ((Long, Long) -> Unit)? = null,
        shouldCancel: (() -> Boolean)? = null,
        log: ((String) -> Unit)? = null,
    ): Long {
        target.parentFile?.mkdirs()
        var received = 0L
        var total: Long
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            if (shouldCancel?.invoke() == true) throw DownloadCancelledException()
            if (attempt > 1) runCatching { Thread.sleep(400) }
            var conn: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 10_000
                c.readTimeout = stallTimeoutMs
                c.setRequestProperty("Connection", "close")
                // 要求原始字节，避免相机对任何内容做传输编码导致长度对不上
                c.setRequestProperty("Accept-Encoding", "identity")
                if (received > 0) c.setRequestProperty("Range", "bytes=$received-")

                val append: Boolean
                when (c.responseCode) {
                    206 -> { // 续传成功
                        append = true
                        total = received + c.contentLengthLong
                    }
                    200 -> { // 相机忽略 Range，从头下
                        append = false
                        received = 0L
                        total = c.contentLengthLong.takeIf { it > 0 } ?: 0L
                    }
                    else -> throw IllegalStateException("HTTP ${c.responseCode}")
                }

                FileOutputStream(target, append).use { fos ->
                    c.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReportAt = 0L
                        while (true) {
                            if (shouldCancel?.invoke() == true) throw DownloadCancelledException()
                            val n = try {
                                input.read(buffer)
                            } catch (e: SocketTimeoutException) {
                                throw IOException("超过 ${stallTimeoutMs / 1000}s 没有收到数据", e)
                            }
                            if (n < 0) break
                            fos.write(buffer, 0, n)
                            received += n
                            val now = System.currentTimeMillis()
                            if (onProgress != null && (now - lastReportAt >= 150 || n < buffer.size)) {
                                onProgress(received, if (total > 0) total else 0L)
                                lastReportAt = now
                            }
                        }
                    }
                }

                if (total > 0 && received < total) {
                    lastError = IOException("连接提前结束：$received/$total")
                    log?.invoke(
                        "下载提前结束（${received / 1024} KB / ${total / 1024} KB），" +
                            "第 $attempt/$maxAttempts 次续传…",
                    )
                    continue
                }
                onProgress?.invoke(received, if (total > 0) total else received)
                return received
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt >= maxAttempts) break
                log?.invoke(
                    "下载中断（已收 ${received / 1024} KB）：${e.javaClass.simpleName}，" +
                        "第 $attempt/$maxAttempts 次重试…",
                )
            } finally {
                conn?.disconnect()
            }
        }
        throw IOException(
            "下载失败（已收 ${received / 1024} KB，重试 $maxAttempts 次）：" +
                (lastError?.message ?: "未知错误"),
        )
    }

    /**
     * 快速探测某个 base URL 是否真的是索尼相机的 UPnP 根（dd.xml）。
     * 用于跳过 SSDP 广播等待：已知/猜测的地址先探测，命中就直接用。
     */
    fun probeCamera(baseUrl: String, timeoutMs: Int = 900): Boolean {
        val url = baseUrl.trimEnd('/') + "/dd.xml"
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "GET"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Connection", "close")
            if (c.responseCode != 200) {
                false
            } else {
                val text = c.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    val n = input.read(buffer)
                    if (n <= 0) "" else String(buffer, 0, n, Charsets.UTF_8)
                }
                text.contains("<root", ignoreCase = true) ||
                    text.contains("DigitalImaging", ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun parseDd(ddXml: String): List<UpnpService> {
        val blocks = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(ddXml).map { it.groupValues[1] }.toList()
        return blocks.mapNotNull { block ->
            fun tag(name: String): String =
                Regex("<$name>([^<]*)</$name>").find(block)?.groupValues?.get(1) ?: ""
            val type = tag("serviceType")
            if (type.isEmpty()) null
            else UpnpService(type, tag("controlURL"), tag("SCPDURL"))
        }
    }

    /**
     * Browse 一个容器，返回目录 + 文件混排列表（按 DIDL 文档顺序）。
     * a6300 目录树：0 → PhotoRoot → Date → yyyy-M-d → 照片。
     */
    fun browsePage(
        controlUrl: String,
        objectId: String,
        startIndex: Int = 0,
        count: Int = 200,
        sort: String = "",
    ): BrowsePage {
        val inner = "<ObjectID>$objectId</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter><StartingIndex>$startIndex</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount><SortCriteria>$sort</SortCriteria>"
        val response = soap(controlUrl, CDS, "Browse", inner, 30000)
        val totalMatches = Regex("<TotalMatches>(\\d+)</TotalMatches>")
            .find(response)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val result = Regex("<Result>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1)
            ?: return BrowsePage(emptyList(), totalMatches)
        return BrowsePage(parseDidl(unescape(result)), totalMatches)
    }

    /** 推送模式用：只要照片条目。保留旧行为（Browse(PushRoot)）。 */
    fun browse(controlUrl: String, objectId: String, count: Int = 200): List<CameraItem> {
        return browsePage(controlUrl, objectId, count = count).entries
            .filter { !it.isContainer }
            .map { CameraItem(it.id, it.title, it.resList) }
    }

    fun parseDidl(didl: String): List<BrowseEntry> {
        val entryRegex = Regex(
            "<(container|item)\\b([^>]*)>(.*?)</\\1>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        return entryRegex.findAll(didl).map { match ->
            val tagName = match.groupValues[1]
            val attrs = match.groupValues[2]
            val body = match.groupValues[3]
            val title = Regex("<dc:title>([^<]*)</dc:title>").find(body)
                ?.groupValues?.get(1)?.let { unescape(it) } ?: ""
            val upnpClass = Regex("<upnp:class>([^<]*)</upnp:class>").find(body)
                ?.groupValues?.get(1) ?: ""
            val resList = Regex("<res\\b([^>]*)>([^<]*)</res>", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).map { resMatch ->
                    val resAttrs = resMatch.groupValues[1]
                    ResEntry(
                        protocolInfo = attr(resAttrs, "protocolInfo") ?: "",
                        url = unescape(resMatch.groupValues[2].trim()),
                        sizeHint = attr(resAttrs, "size")?.toLongOrNull(),
                    )
                }.toList()
            BrowseEntry(
                id = attr(attrs, "id") ?: "",
                title = title,
                upnpClass = upnpClass.ifEmpty {
                    if (tagName == "container") "object.container" else "object.item"
                },
                childCount = attr(attrs, "childCount")?.toIntOrNull(),
                resList = resList,
            )
        }.toList()
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("(?:^|\\s)$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#x0A;", "\n")
        .replace("&#10;", "\n")
        .replace("&amp;", "&")
}
