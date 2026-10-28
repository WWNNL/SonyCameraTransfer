package dev.rea.sonytransfer

import android.net.Network
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** SSDP M-SEARCH 发现 Sony 相机，返回 dd.xml 的 LOCATION。 */
object Ssdp {
    private const val SSDP_ADDR = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private val SEARCH_TARGETS = listOf(
        "urn:schemas-sony-com:service:DigitalImaging:1",
        "urn:schemas-upnp-org:device:MediaServer:1",
        "ssdp:all",
    )

    fun findCameraLocation(network: Network?, timeoutMs: Int = 6000): String? {
        val target = InetAddress.getByName(SSDP_ADDR)
        var mediaServerLocation: String? = null
        try {
            DatagramSocket().use { socket ->
                try {
                    network?.bindSocket(socket)
                } catch (_: Exception) {
                }
                socket.soTimeout = 500

                fun sendSearch() {
                    for (st in SEARCH_TARGETS) {
                        val message = "M-SEARCH * HTTP/1.1\r\n" +
                            "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                            "MAN: \"ssdp:discover\"\r\n" +
                            "MX: 1\r\n" +
                            "ST: $st\r\n\r\n"
                        val data = message.toByteArray(Charsets.US_ASCII)
                        try {
                            socket.send(DatagramPacket(data, data.size, target, SSDP_PORT))
                        } catch (_: Exception) {
                        }
                    }
                }

                sendSearch()
                val deadline = System.currentTimeMillis() + timeoutMs
                var nextSendAt = System.currentTimeMillis() + 600
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)
                            ?.groupValues?.get(1) ?: continue
                        if (text.contains("DigitalImaging", ignoreCase = true)) return location
                        if (mediaServerLocation == null &&
                            text.contains("MediaServer", ignoreCase = true)
                        ) {
                            mediaServerLocation = location
                        }
                    } catch (_: Exception) {
                    }
                    // 相机偶尔会丢第一个 M-SEARCH：每 600–700ms 重发一次，避免空等到超时。
                    val now = System.currentTimeMillis()
                    if (now >= nextSendAt && now < deadline) {
                        sendSearch()
                        nextSendAt = now + 700
                    }
                }
            }
        } catch (e: Exception) {
            return mediaServerLocation
        }
        return mediaServerLocation
    }
}
