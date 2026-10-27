package dev.rea.sonytransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NFC payload 解析单元测试。
 *
 * 真机数据：2026-10-28 a6300（ILCE-6300）NFC 标签原始 payload 为
 *   00 01 00 01 10                                        Version = 0x10
 *   10 00 00 11 "DIRECT-91E0:WWNNL"                       SSID (17 字节)
 *   10 01 00 08 "********"                                Password (8 字节)
 * 共 38 字节，没有 0x1002/0x1003/0x1004 段——所以 IP/DD 必然为空，
 * 相机发现走 SSDP。密码字节在测试里替换为 TESTPASS（长度不变）。
 */
class NdefPayloadParserTest {

    private fun asciiHex(text: String): String =
        text.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    private fun bytesOf(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even: ${hex.length}" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val length = value.size
        // 官方编码：len = b0 * 10 + b1
        return byteArrayOf(
            (tag shr 8).toByte(),
            tag.toByte(),
            (length / 10).toByte(),
            (length % 10).toByte(),
        ) + value
    }

    private val realTagHex: String =
        "0001000110" +                        // 00 01 | 00 01 | 10
            "10000011" + asciiHex("DIRECT-91E0:WWNNL") +
            "10010008" + asciiHex("TESTPASS")

    @Test
    fun parsesRealDeviceTag() {
        val info = NdefPayloadParser.parse(bytesOf(realTagHex))

        assertEquals("10", info.versionHex)
        assertEquals("DIRECT-91E0:WWNNL", info.ssid)
        assertEquals("TESTPASS", info.password)
        assertNull(info.ipAddress)
        assertNull(info.gateway)
        assertNull(info.ddUrl)
        assertTrue("没有 WifiSetting 段时应视为可用", info.wifiAvailable)
        assertNull("真机标签没有 0x1004", info.wifiSettingHex)
        assertTrue(info.rawHex!!.startsWith("000100011010000011"))
    }

    @Test
    fun realTagHasExactlyThreeTlvs() {
        val tlvs = NdefPayloadParser.parseTlvs(bytesOf(realTagHex))
        assertEquals(3, tlvs.size)
        assertEquals(NdefPayloadParser.TAG_VERSION, tlvs[0].tag)
        assertEquals(NdefPayloadParser.TAG_SSID, tlvs[1].tag)
        assertEquals(NdefPayloadParser.TAG_PASSWORD, tlvs[2].tag)
    }

    @Test
    fun parsesFullTagWithNetworkAndDdUrl() {
        val network = byteArrayOf(
            // IP 192.168.122.1
            192.toByte(), 168.toByte(), 122, 1,
            // 子网掩码 255.255.0.0（前缀 /16）
            255.toByte(), 255.toByte(), 0, 0,
            // 网关 192.168.122.1
            192.toByte(), 168.toByte(), 122, 1,
            // MAC 00:11:22:33:44:55
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55,
            // WPS = PBC
            1,
            // 2.4GHz
            1,
            // channel 6
            6,
            // WPS PIN = 123456（大端 4 字节）
            0x00, 0x01, 0xE2.toByte(), 0x40,
        )
        val ddUrl = byteArrayOf(
            1,                                    // http
            192.toByte(), 168.toByte(), 122, 1,   // IP
            0xFB.toByte(), 0x41,                  // port 64321
            2,                                    // hostLen
            'd'.code.toByte(), 'd'.code.toByte(), // host "dd"
        )
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x10)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray()) +
            tlv(NdefPayloadParser.TAG_PASSWORD, "12345678".toByteArray()) +
            tlv(NdefPayloadParser.TAG_NETWORK, network) +
            tlv(NdefPayloadParser.TAG_DD_URL, ddUrl) +
            tlv(NdefPayloadParser.TAG_WIFI_SETTING, byteArrayOf(0x01))

        val info = NdefPayloadParser.parse(payload)

        assertEquals("192.168.122.1", info.ipAddress)
        assertEquals("255.255.0.0", info.subnetMask)
        assertEquals(16, info.prefixLength)
        assertEquals("192.168.122.1", info.gateway)
        assertEquals("00:11:22:33:44:55", info.mac)
        assertEquals("WPS_SETUP_PBC", info.wpsSetup)
        assertEquals("2.4GHz", info.goFrequency)
        assertEquals("6", info.goChannel)
        assertEquals("00123456", info.wpsPin)
        assertEquals("http://192.168.122.1:64321/dd.xml", info.ddUrl)
        assertEquals("01", info.wifiSettingHex)
        assertTrue(info.wifiAvailable)
        assertNull(SonyNfc.validate(info))
    }

    @Test
    fun wifiSettingOtherThan01MeansUnavailable() {
        // 官方：全 0 字节视为「无此字段」（可用）；非 0 且 != "01" 才表示 Wi-Fi 不可用
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x10)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray()) +
            tlv(NdefPayloadParser.TAG_PASSWORD, "12345678".toByteArray()) +
            tlv(NdefPayloadParser.TAG_WIFI_SETTING, byteArrayOf(0x02))

        val info = NdefPayloadParser.parse(payload)

        assertFalse(info.wifiAvailable)
        assertEquals("02", info.wifiSettingHex)
        assertNotNull(SonyNfc.validate(info))
    }

    @Test
    fun allZeroWifiSettingIsTreatedAsAbsent() {
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x10)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray()) +
            tlv(NdefPayloadParser.TAG_PASSWORD, "12345678".toByteArray()) +
            tlv(NdefPayloadParser.TAG_WIFI_SETTING, byteArrayOf(0x00))

        val info = NdefPayloadParser.parse(payload)

        assertNull(info.wifiSettingHex)
        assertTrue(info.wifiAvailable)
        assertNull(SonyNfc.validate(info))
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x20)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray()) +
            tlv(NdefPayloadParser.TAG_PASSWORD, "12345678".toByteArray())

        val info = NdefPayloadParser.parse(payload)

        assertEquals("20", info.versionHex)
        assertNotNull(SonyNfc.validate(info))
    }

    @Test
    fun allZeroPasswordIsTreatedAsMissing() {
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x10)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray()) +
            tlv(NdefPayloadParser.TAG_PASSWORD, ByteArray(8))

        val info = NdefPayloadParser.parse(payload)

        assertNull(info.password)
        assertNotNull(SonyNfc.validate(info))
    }

    @Test
    fun supportsThreeDigitLengthEncoding() {
        // 官方长度编码 b0*10+b1：长度 105 = 0x0A 0x05
        val value = ByteArray(105) { it.toByte() }
        val payload = byteArrayOf(0x00, 0x01, 0x0A, 0x05) + value

        val tlvs = NdefPayloadParser.parseTlvs(payload)

        assertEquals(1, tlvs.size)
        assertEquals(105, tlvs[0].value.size)
    }

    @Test
    fun truncatedPayloadDoesNotCrash() {
        val payload = tlv(NdefPayloadParser.TAG_VERSION, byteArrayOf(0x10)) +
            tlv(NdefPayloadParser.TAG_SSID, "DIRECT-AB12:CDEF".toByteArray())
        val truncated = payload.copyOf(payload.size - 5)

        val info = NdefPayloadParser.parse(truncated)

        assertEquals("10", info.versionHex)
        assertNull("被截断的 SSID 不应被解析出来", info.ssid)
        assertNotNull("校验应失败", SonyNfc.validate(info))
    }

    @Test
    fun garbagePayloadDoesNotCrash() {
        val info = NdefPayloadParser.parse(byteArrayOf(0x00, 0x01, 0x00, 0x01, 0x10, 0x7F))
        assertEquals("10", info.versionHex)
        assertNull(info.ssid)
        assertNull(info.password)
    }
}
