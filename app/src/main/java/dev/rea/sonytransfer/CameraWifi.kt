package dev.rea.sonytransfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build

/**
 * 连接 Sony 相机热点。
 * Android 10+ 用 WifiNetworkSpecifier（系统会弹一次确认框），成功后 bindProcessToNetwork，
 * 保证后续 SSDP / HTTP 都走相机网络（相机热点没有 Internet）。
 */
class CameraWifi(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var network: Network? = null
        private set

    fun connect(ssid: String, passphrase: String?, timeoutMs: Int = 40000, onResult: (Boolean, String) -> Unit) {
        disconnect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (!passphrase.isNullOrEmpty()) {
                specifierBuilder.setWpa2Passphrase(passphrase)
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            var done = false
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(net: Network) {
                    if (done) return
                    done = true
                    network = net
                    connectivityManager.bindProcessToNetwork(net)
                    onResult(true, "已连接相机 Wi-Fi: $ssid")
                }

                override fun onUnavailable() {
                    if (done) return
                    done = true
                    onResult(false, "相机 Wi-Fi 连接失败/被取消")
                }

                override fun onLost(net: Network) {
                    if (network == net) network = null
                }
            }
            callback = cb
            try {
                connectivityManager.requestNetwork(request, cb, timeoutMs)
            } catch (e: Exception) {
                onResult(false, "请求网络失败: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            legacyConnect(ssid, passphrase, onResult)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyConnect(ssid: String, passphrase: String?, onResult: (Boolean, String) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (!passphrase.isNullOrEmpty()) preSharedKey = "\"$passphrase\""
        }
        val netId = wifi.addNetwork(config)
        val ok = netId >= 0 && wifi.enableNetwork(netId, true)
        onResult(ok, if (ok) "已连接相机 Wi-Fi(legacy): $ssid" else "连接失败(legacy)")
    }

    fun disconnect() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        callback = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
        network = null
    }
}
