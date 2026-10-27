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

data class CameraItem(
    val id: String,
    val title: String,
    val resList: List<ResEntry>,
) {
    /** 原图 = protocolInfo 里没有 DLNA.ORG_PN 的那个 res（官方 App 的 PN_ORIGINAL） */
    val originalRes: ResEntry?
        get() = resList.firstOrNull { !it.protocolInfo.contains("DLNA.ORG_PN") }
            ?: resList.lastOrNull()
}

object Upnp {
    const val CDS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    const val XPS = "urn:schemas-sony-com:service:XPushList:1"

    fun httpGetBytes(url: String, timeoutMs: Int = 30000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Connection", "close")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $url")
            }
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                input.copyTo(out)
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

    fun browse(controlUrl: String, objectId: String, count: Int = 200): List<CameraItem> {
        val inner = "<ObjectID>$objectId</ObjectID>" +
            "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
            "<Filter>*</Filter><StartingIndex>0</StartingIndex>" +
            "<RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>"
        val response = soap(controlUrl, CDS, "Browse", inner, 30000)
        val result = Regex("<Result>(.*?)</Result>", RegexOption.DOT_MATCHES_ALL)
            .find(response)?.groupValues?.get(1) ?: return emptyList()
        val didl = unescape(result)
        return Regex("<item\\b([^>]*)>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .findAll(didl).map { match ->
                val attrs = match.groupValues[1]
                val body = match.groupValues[2]
                val id = Regex("id=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1) ?: ""
                val title = Regex("<dc:title>([^<]*)</dc:title>").find(body)
                    ?.groupValues?.get(1)?.let { unescape(it) } ?: "IMG.jpg"
                val resList = Regex("<res\\b([^>]*)>([^<]*)</res>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(body).map { resMatch ->
                        val resAttrs = resMatch.groupValues[1]
                        val proto = Regex("protocolInfo=\"([^\"]*)\"").find(resAttrs)
                            ?.groupValues?.get(1) ?: ""
                        val size = Regex("size=\"(\\d+)\"").find(resAttrs)
                            ?.groupValues?.get(1)?.toLongOrNull()
                        ResEntry(proto, unescape(resMatch.groupValues[2].trim()), size)
                    }.toList()
                CameraItem(id, title, resList)
            }.toList()
    }

    fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#x0A;", "\n")
        .replace("&#10;", "\n")
        .replace("&amp;", "&")
}
