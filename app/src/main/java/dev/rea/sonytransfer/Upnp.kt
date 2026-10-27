package dev.rea.sonytransfer

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpnpService(val serviceType: String, val controlUrl: String, val scpdUrl: String)

data class ResEntry(
    val protocolInfo: String,
    val url: String,
    val sizeHint: Long?,
)

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

    /** 原始文件：视频优先 video/*（视频 res 可能带 DLNA.ORG_PN），图片优先无 PN 且体积最大的 res。 */
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
     *  1. 有 video/* → 取最大的 video res（视频的 res 常带 DLNA.ORG_PN，不能按"无 PN"判断）；
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
