package dev.rea.sonytransfer

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter

/**
 * Sony 相机 NFC 标签（NDEF MIME application/x-sony-pmm）解析。
 *
 * 规格来自官方 Imaging Edge Mobile 7.8.5 反编译：
 *   com.sony.playmemories.mobile.nfc.NdefDescription
 * payload = 若干 TLV：tag(2 字节) + len(2 字节) + value(len 字节)
 * TLV 长度按官方代码解码：len = b0 * 10 + b1（对 <100 长度与 16bit BE 等价）
 */
data class CameraNfcInfo(
    val version: String?,
    val ssid: String?,
    val password: String?,
    val ipAddress: String?,
    val subnetMask: String?,
    val gateway: String?,
    val mac: String?,
    val ddUrl: String?,
    val rawHex: String? = null,
) {
    fun describe(): String = buildString {
        append("NFC 版本: ${version ?: "-"}\n")
        append("SSID: ${ssid ?: "-"}\n")
        append("密码: ${if (password.isNullOrEmpty()) "-" else "(已读到)"}\n")
        append("相机 IP: ${ipAddress ?: "-"}  网关: ${gateway ?: "-"}\n")
        append("DD URL: ${ddUrl ?: "-"}\n")
        append("原始 NDEF: ${rawHex ?: "-"}")
    }
}

object SonyNfc {
    const val MIME = "application/x-sony-pmm"

    private val TAG_VERSION = byteArrayOf(0x00, 0x01)
    private val TAG_SSID = byteArrayOf(0x10, 0x00)
    private val TAG_PASSWORD = byteArrayOf(0x10, 0x01)
    private val TAG_NETWORK = byteArrayOf(0x10, 0x02)
    private val TAG_DD_URL = byteArrayOf(0x10, 0x03)

    fun parse(intent: Intent?): CameraNfcInfo? {
        val payload = readSonyPayload(intent) ?: return null
        val version = findTlv(payload, TAG_VERSION)?.joinToString("") { "%02x".format(it) }
        val ssid = findTlv(payload, TAG_SSID)?.let { bytesToText(it) }
        val password = findTlv(payload, TAG_PASSWORD)?.let { bytesToText(it) }

        var ip: String? = null
        var mask: String? = null
        var gateway: String? = null
        var mac: String? = null
        findTlv(payload, TAG_NETWORK)?.let { net ->
            if (net.size >= 12) {
                ip = ipv4(net, 0)
                mask = ipv4(net, 4)
                gateway = ipv4(net, 8)
            }
            if (net.size >= 18) {
                mac = "%02x:%02x:%02x:%02x:%02x:%02x".format(
                    net[12], net[13], net[14], net[15], net[16], net[17]
                )
            }
        }

        val ddUrl = findTlv(payload, TAG_DD_URL)?.let { parseDdUrl(it) }
        val rawHex = payload.take(120).joinToString("") { "%02x".format(it) } +
            if (payload.size > 120) "…(共${payload.size}字节)" else ""
        return CameraNfcInfo(version, ssid, password, ip, mask, gateway, mac, ddUrl, rawHex)
    }

    private fun ipv4(bytes: ByteArray, offset: Int): String =
        "${bytes[offset].toInt() and 0xFF}.${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}.${bytes[offset + 3].toInt() and 0xFF}"

    /** [0]=1 http / 2 https, [1..4]=IP, [5..6]=port(BE), [7]=hostLen, [8..]=host */
    private fun parseDdUrl(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        val scheme = when (bytes[0].toInt() and 0xFF) {
            1 -> "http"
            2 -> "https"
            else -> return null
        }
        val ip = ipv4(bytes, 1)
        val port = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)
        val hostLen = bytes[7].toInt() and 0xFF
        if (bytes.size < 8 + hostLen) return null
        val host = String(bytes.copyOfRange(8, 8 + hostLen), Charsets.UTF_8).trim('\u0000')
        return "$scheme://$ip:$port/$host.xml"
    }

    private fun bytesToText(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).trim('\u0000').trim()

    private fun findTlv(payload: ByteArray, tag: ByteArray): ByteArray? {
        var i = 0
        while (i + 3 < payload.size) {
            val t0 = payload[i].toInt() and 0xFF
            val t1 = payload[i + 1].toInt() and 0xFF
            val len = (payload[i + 2].toInt() and 0xFF) * 10 + (payload[i + 3].toInt() and 0xFF)
            val start = i + 4
            if (start + len > payload.size) return null
            if (t0 == (tag[0].toInt() and 0xFF) && t1 == (tag[1].toInt() and 0xFF)) {
                return payload.copyOfRange(start, start + len)
            }
            i = start + len
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun readSonyPayload(intent: Intent?): ByteArray? {
        if (intent == null || intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        val message = raw.firstOrNull() as? NdefMessage ?: return null
        val record = message.records.firstOrNull() ?: return null
        val type = String(record.type, Charsets.US_ASCII)
        if (!type.equals(MIME, ignoreCase = true)) return null
        return record.payload
    }
}
