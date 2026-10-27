package dev.rea.sonytransfer

/**
 * Sony 相机 NFC 标签（NDEF MIME application/x-sony-pmm）的纯 Kotlin 解析器。
 * 与 Android 解耦，便于 JVM 单元测试。
 *
 * 格式（官方 Imaging Edge Mobile 7.8.5 反编译 com.sony.playmemories.mobile.nfc.NdefDescription）：
 * payload = 若干 TLV：tag(2 字节, 大端) + len(2 字节) + value(len 字节)
 * 长度解码：len = b0 * 10 + b1（官方源码原样如此，<100 时与 16bit BE 等价）
 *
 * 已知 tag：
 *   0x0001 Version            value 0x10 = "10"（官方要求必须等于 "10"）
 *   0x1000 SSID               UTF-8
 *   0x1001 Password           UTF-8
 *   0x1002 NetworkInfo        复合结构：IP[0..3] + Mask[4..7] + Gateway[8..11] + MAC[12..17]
 *                             + WPS[18] + Freq[19] + Channel[20] + WPS PIN[21..]
 *   0x1003 DeviceDescriptionURL 开头字节 1=http/2=https + IP[1..4] + Port[5..6](BE)
 *                             + hostLen[7] + host[8..]（拼成 scheme://ip:port/host.xml）
 *   0x1004 WifiSetting        非全 0 时： "01"=Wi-Fi 可用，其它值=不可用；全 0 视为无此字段（官方语义）
 *
 * 实测 a6300 标签只有 Version + SSID + Password（没有 IP/DD/网络信息段），
 * 因此 IP/DD 字段允许为 null，发现相机走 SSDP。
 */
data class CameraNfcInfo(
    val versionHex: String?,
    val ssid: String?,
    val password: String?,
    val ipAddress: String? = null,
    val subnetMask: String? = null,
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val mac: String? = null,
    val wpsSetup: String? = null,
    val goFrequency: String? = null,
    val goChannel: String? = null,
    val wpsPin: String? = null,
    val ddUrl: String? = null,
    val wifiSettingHex: String? = null,
    val rawHex: String? = null,
) {
    val wifiAvailable: Boolean
        get() = wifiSettingHex == null || wifiSettingHex == "01"

    fun describe(): String = buildString {
        append("版本: ${versionHex ?: "-"}")
        append("  SSID: ${ssid ?: "-"}")
        append("  密码: ${if (password.isNullOrEmpty()) "-" else "(已读取)"}")
        if (ipAddress != null) append("\n相机 IP: $ipAddress  前缀: ${prefixLength ?: "-"}")
        if (gateway != null) append("  网关: $gateway")
        if (mac != null) append("\nMAC: $mac")
        if (ddUrl != null) append("\nDD URL: $ddUrl")
        if (wifiSettingHex != null) append("\nWifiSetting: $wifiSettingHex")
        if (ipAddress == null && ddUrl == null) {
            append("\n(标签未含 IP/DD 字段，连接后由 SSDP 发现相机)")
        }
        append("\n原始 NDEF: ${rawHex ?: "-"}")
    }
}

object NdefPayloadParser {
    /** 官方要求 Version 字段等于 "10"（即 0x10）。 */
    const val SUPPORTED_VERSION = "10"

    const val TAG_VERSION = 0x0001
    const val TAG_SSID = 0x1000
    const val TAG_PASSWORD = 0x1001
    const val TAG_NETWORK = 0x1002
    const val TAG_DD_URL = 0x1003
    const val TAG_WIFI_SETTING = 0x1004

    data class Tlv(val tag: Int, val value: ByteArray)

    fun parseTlvs(payload: ByteArray): List<Tlv> {
        val result = ArrayList<Tlv>()
        var offset = 0
        while (offset + 4 <= payload.size) {
            val tag = ((payload[offset].toInt() and 0xFF) shl 8) or
                (payload[offset + 1].toInt() and 0xFF)
            val b0 = payload[offset + 2].toInt() and 0xFF
            val b1 = payload[offset + 3].toInt() and 0xFF
            val len = b0 * 10 + b1
            if (offset + 4 + len > payload.size) break
            result += Tlv(tag, payload.copyOfRange(offset + 4, offset + 4 + len))
            offset += 4 + len
        }
        return result
    }

    fun parse(payload: ByteArray): CameraNfcInfo {
        val tlvs = parseTlvs(payload)
        fun valueOf(tag: Int): ByteArray? = tlvs.firstOrNull { it.tag == tag }?.value
        val network = valueOf(TAG_NETWORK)?.let { parseNetworkInfo(it) }
        return CameraNfcInfo(
            versionHex = valueOf(TAG_VERSION)?.let { if (isAllZero(it)) null else hex(it) },
            ssid = textValue(valueOf(TAG_SSID)),
            password = textValue(valueOf(TAG_PASSWORD)),
            ipAddress = network?.ip,
            subnetMask = network?.mask,
            prefixLength = network?.prefixLength,
            gateway = network?.gateway,
            mac = network?.mac,
            wpsSetup = network?.wpsSetup,
            goFrequency = network?.frequency,
            goChannel = network?.channel,
            wpsPin = network?.pin,
            ddUrl = valueOf(TAG_DD_URL)?.let { parseDdUrl(it) },
            wifiSettingHex = valueOf(TAG_WIFI_SETTING)?.let { if (isAllZero(it)) null else hex(it) },
            rawHex = payload.take(120).joinToString("") { "%02x".format(it) } +
                if (payload.size > 120) "…(共${payload.size}字节)" else "",
        )
    }

    private data class NetworkInfo(
        val ip: String?,
        val mask: String?,
        val prefixLength: Int?,
        val gateway: String?,
        val mac: String?,
        val wpsSetup: String?,
        val frequency: String?,
        val channel: String?,
        val pin: String?,
    )

    private fun parseNetworkInfo(bytes: ByteArray): NetworkInfo {
        fun zero(from: Int, to: Int): Boolean {
            if (from < 0 || to >= bytes.size) return true
            for (i in from..to) if ((bytes[i].toInt() and 0xFF) != 0) return false
            return true
        }

        fun u(index: Int): Int = bytes[index].toInt() and 0xFF

        fun ipv4(offset: Int): String? {
            if (bytes.size < offset + 4 || zero(offset, offset + 3)) return null
            return "${u(offset)}.${u(offset + 1)}.${u(offset + 2)}.${u(offset + 3)}"
        }

        var prefixLength: Int? = null
        if (bytes.size >= 8 && !zero(4, 7)) {
            var bits = 0
            for (i in 4..7) bits += Integer.bitCount(u(i))
            prefixLength = bits
        }

        var wpsSetup: String? = null
        if (bytes.size >= 19 && bytes[18].toInt() != 0) {
            wpsSetup = when (u(18)) {
                1 -> "WPS_SETUP_PBC"
                2 -> "WPS_SETUP_PIN_DISPLAY"
                3 -> "WPS_SETUP_PIN_INPUT"
                else -> null
            }
        }

        var frequency: String? = null
        if (bytes.size >= 20 && bytes[19].toInt() != 0) {
            frequency = when (u(19)) {
                1 -> "2.4GHz"
                2 -> "5GHz"
                else -> null
            }
        }

        val channel = if (bytes.size >= 21 && bytes[20].toInt() != 0) u(20).toString() else null

        var pin: String? = null
        if (bytes.size >= 22 && !zero(21, bytes.size - 1)) {
            var value = 0
            for (i in 21 until bytes.size) value = (value shl 8) + u(i)
            pin = String.format(java.util.Locale.US, "%08d", value)
        }

        return NetworkInfo(
            ip = ipv4(0),
            mask = ipv4(4),
            prefixLength = prefixLength,
            gateway = ipv4(8),
            mac = if (bytes.size >= 18 && !zero(12, 17)) {
                "%02x:%02x:%02x:%02x:%02x:%02x".format(
                    bytes[12], bytes[13], bytes[14], bytes[15], bytes[16], bytes[17],
                )
            } else null,
            wpsSetup = wpsSetup,
            frequency = frequency,
            channel = channel,
            pin = pin,
        )
    }

    private fun parseDdUrl(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        val scheme = when (bytes[0].toInt() and 0xFF) {
            1 -> "http"
            2 -> "https"
            else -> return null
        }
        fun u(index: Int): Int = bytes[index].toInt() and 0xFF
        val port = (u(5) shl 8) or u(6)
        val hostLength = u(7)
        if (hostLength == 0 || bytes.size < 8 + hostLength) return null
        val host = String(bytes.copyOfRange(8, 8 + hostLength), Charsets.UTF_8).trim('\u0000')
        if (host.isEmpty()) return null
        return "$scheme://${u(1)}.${u(2)}.${u(3)}.${u(4)}:$port/$host.xml"
    }

    private fun textValue(bytes: ByteArray?): String? {
        if (bytes == null || isAllZero(bytes)) return null
        return String(bytes, Charsets.UTF_8).trim('\u0000').trim()
    }

    private fun isAllZero(bytes: ByteArray): Boolean = bytes.all { (it.toInt() and 0xFF) == 0 }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
