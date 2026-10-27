package dev.rea.sonytransfer

/**
 * 「在智能手机上选择」= 手机端浏览相机相册（DLNA Pull）。
 *
 * a6300 目录树：0 → PhotoRoot → Date → yyyy-M-d → 照片；
 * 下载 = HTTP GET 60151 的原图 res，无需 XPushList 握手，相机也不会自动退出
 * （它会一直停在「发送到智能手机」等待，直到用户在相机上退出/超时）。
 */
class SonyCameraBrowser(private val log: (String) -> Unit) {

    private var baseUrl: String? = null
    private var contentDirectoryUrl: String? = null

    fun useBase(url: String) {
        if (baseUrl == url && contentDirectoryUrl != null) return
        baseUrl = url
        contentDirectoryUrl = null
    }

    fun browse(objectId: String, count: Int = 200, sort: String = ""): List<BrowseEntry> {
        val cds = contentDirectory()
        val page = Upnp.browsePage(cds, objectId, count = count, sort = sort)
        log("Browse($objectId) -> ${page.entries.size} 项 / 共 ${page.totalMatches}")
        return page.entries
    }

    fun download(entry: BrowseEntry): ByteArray {
        val res = entry.originalRes
            ?: throw IllegalStateException("${entry.title} 没有可下载的 res")
        return Upnp.httpGetBytes(res.url, 180_000)
    }

    private fun contentDirectory(): String {
        contentDirectoryUrl?.let { return it }
        val base = baseUrl ?: throw IllegalStateException("未设置相机地址")
        val ddXml = String(Upnp.httpGetBytes("$base/dd.xml"), Charsets.UTF_8)
        val controlUrl = Upnp.parseDd(ddXml)
            .firstOrNull { it.serviceType.contains("ContentDirectory") }
            ?.controlUrl
            ?: throw IllegalStateException("DD 中没找到 ContentDirectory")
        val absolute = if (controlUrl.startsWith("http")) controlUrl else base + controlUrl
        contentDirectoryUrl = absolute
        return absolute
    }
}
