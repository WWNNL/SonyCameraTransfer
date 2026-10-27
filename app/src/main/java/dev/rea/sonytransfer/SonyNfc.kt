package dev.rea.sonytransfer

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter

/**
 * 从 NFC Intent 中取出 Sony 相机标签 payload，交给 [NdefPayloadParser] 解析。
 * MIME: application/x-sony-pmm（官方 App 同款前台分发 filter）。
 */
object SonyNfc {
    const val MIME = "application/x-sony-pmm"

    fun parse(intent: Intent?): CameraNfcInfo? {
        val payload = readPayload(intent) ?: return null
        return NdefPayloadParser.parse(payload)
    }

    /**
     * 按官方 App 语义校验标签：
     *   - Version 必须为 "10"
     *   - WifiSetting 若存在必须为 "01"
     *   - SSID / Password 必须存在
     * IP / DD-URL 可缺省（a6300 就没写，官方同样靠 SSDP 发现）。
     */
    fun validate(info: CameraNfcInfo?): String? {
        if (info == null) return "不是 Sony 相机的 NFC 标签（请贴相机机身上的 NFC 区域）"
        if (info.versionHex != NdefPayloadParser.SUPPORTED_VERSION) {
            return "标签版本 ${info.versionHex ?: "未知"} 不受支持（官方要求 10）"
        }
        if (!info.wifiAvailable) {
            return "相机的 Wi-Fi 传输当前不可用（请在相机上重新进入「发送到智能手机」）"
        }
        if (info.ssid.isNullOrEmpty()) {
            return "标签里没有 SSID（请手动输入相机屏幕上显示的 SSID）"
        }
        if (info.password.isNullOrEmpty()) {
            return "标签里没有 Wi-Fi 密码（请手动输入相机屏幕上显示的密码）"
        }
        return null
    }

    private fun readPayload(intent: Intent?): ByteArray? {
        if (intent == null || intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return null
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) ?: return null
        val message = raw.firstOrNull() as? NdefMessage ?: return null
        val record = message.records.firstOrNull() ?: return null
        val type = String(record.type, Charsets.US_ASCII)
        if (!type.equals(MIME, ignoreCase = true)) return null
        return record.payload
    }
}
