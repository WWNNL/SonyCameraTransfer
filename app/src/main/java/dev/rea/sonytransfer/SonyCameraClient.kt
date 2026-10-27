package dev.rea.sonytransfer

/**
 * 「发送到智能手机 → 在本机上选择」接收流程（PC P0 验证过的完整时序）：
 *
 *   dd.xml → 解析 controlURL
 *   X_TransferStart
 *   X_GetPushRoot        → ObjectID (PushRoot)
 *   Browse(PushRoot)     → 相机端选中的图片列表
 *   对每张：X_TransferProgress → GET 原图 → 保存
 *   X_TransferEnd(0)     → 相机退出图传、关闭热点
 *
 * 单张快路径：PushRoot 为空时读 /DigitalImagingDesc.xml 的 X_CurrentContent_URL。
 */
class SonyCameraClient(private val log: (String) -> Unit) {

    data class Result(val ok: Boolean, val message: String, val savedCount: Int)

    fun receivePush(
        baseUrl: String,
        save: (String, ByteArray) -> Boolean,
    ): Result {
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

            var saved = 0
            items.forEachIndexed { index, item ->
                log("进度 ${index}/${items.size}: ${item.title}")
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
                val data = Upnp.httpGetBytes(res.url, 180000)
                if (save(item.title, data)) saved++
            }

            log("X_TransferEnd(0)")
            Upnp.soap(pushUrl, Upnp.XPS, "X_TransferEnd", "<ErrCode>0</ErrCode>")
            Result(true, "完成：接收 $saved/${items.size} 张", saved)
        } catch (e: Exception) {
            Result(false, "接收失败：${e.javaClass.simpleName}: ${e.message}", 0)
        }
    }

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
