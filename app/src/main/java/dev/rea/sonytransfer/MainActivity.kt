package dev.rea.sonytransfer

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var cameraWifi: CameraWifi
    private var nfcAdapter: NfcAdapter? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastNfc: CameraNfcInfo? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraWifi = CameraWifi(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        buildUi()
        requestWifiPermissions()
        handleIntent(intent)
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "等待 NFC 一碰，或手动输入 SSID/密码"
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ssidEdit = EditText(this).apply { hint = "SSID（DIRECT-…）"; setSingleLine() }
        passEdit = EditText(this).apply {
            hint = "密码"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputRow.addView(ssidEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        inputRow.addView(passEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        root.addView(inputRow)

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val connectButton = Button(this).apply { text = "连接并接收" }
        val receiveButton = Button(this).apply { text = "开始接收" }
        val disconnectButton = Button(this).apply { text = "断开" }
        buttonRow.addView(connectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(receiveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(disconnectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonRow)

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            movementMethod = ScrollingMovementMethod()
            text = ""
        }
        root.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        connectButton.setOnClickListener {
            val ssid = ssidEdit.text.toString().trim()
            if (ssid.isEmpty()) {
                appendLog("请先填 SSID（或用 NFC 一碰读取）")
                return@setOnClickListener
            }
            connectAndReceive(ssid, passEdit.text.toString().ifEmpty { null })
        }
        receiveButton.setOnClickListener { startReceive() }
        disconnectButton.setOnClickListener {
            cameraWifi.disconnect()
            releaseMulticast()
            statusView.text = "已断开相机网络"
            appendLog("已断开相机网络")
        }

        setContentView(root)
    }

    private fun requestWifiPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.NEARBY_WIFI_DEVICES
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.ACCESS_FINE_LOCATION
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
    }

    override fun onResume() {
        super.onResume()
        enableForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraWifi.disconnect()
        releaseMulticast()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val filter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            filter.addDataType(SonyNfc.MIME)
        } catch (_: IntentFilter.MalformedMimeTypeException) {
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        adapter.enableForegroundDispatch(this, pending, arrayOf(filter), null)
    }

    private fun handleIntent(intent: Intent?) {
        val info = SonyNfc.parse(intent) ?: return
        lastNfc = info
        statusView.text = info.describe()
        appendLog("== NFC 标签 ==")
        appendLog(info.describe().replace("\n", " | "))
        if (!info.ssid.isNullOrEmpty()) {
            ssidEdit.setText(info.ssid)
            passEdit.setText(info.password ?: "")
            connectAndReceive(info.ssid, info.password)
        } else {
            appendLog("标签里没有 SSID，需要手动输入")
        }
    }

    private fun connectAndReceive(ssid: String, pass: String?) {
        if (busy) return
        busy = true
        appendLog("连接相机 Wi-Fi: $ssid")
        acquireMulticast()
        cameraWifi.connect(ssid, pass) { ok, message ->
            mainHandler.post {
                busy = false
                appendLog(message)
                statusView.text = message
                if (ok) startReceive()
            }
        }
    }

    private fun startReceive() {
        if (busy) return
        busy = true
        Thread {
            val base = resolveBaseUrl()
            if (base == null) {
                appendLog("找不到相机地址")
                busy = false
                return@Thread
            }
            appendLog("相机 base URL: $base")
            val client = SonyCameraClient { line -> appendLog(line) }
            val result = client.receivePush(base) { name, data ->
                val uri = ImageSaver.save(applicationContext, name, data)
                if (uri != null) {
                    appendLog(String.format(Locale.US, "已保存 %s (%.1f MB)", name, data.size / 1048576.0))
                } else {
                    appendLog("保存失败: $name")
                }
                uri != null
            }
            appendLog(result.message)
            mainHandler.post {
                busy = false
                statusView.text = result.message
            }
        }.start()
    }

    private fun resolveBaseUrl(): String? {
        val location = Ssdp.findCameraLocation(cameraWifi.network, 6000)
        if (location != null) {
            appendLog("SSDP: $location")
            Regex("(https?://[^/]+)").find(location)?.let { return it.groupValues[1] }
        }
        lastNfc?.ddUrl?.let { dd ->
            appendLog("NFC DD URL: $dd")
            Regex("(https?://[^/]+)").find(dd)?.let { return it.groupValues[1] }
        }
        appendLog("SSDP 失败，尝试默认地址 http://192.168.122.1:64321")
        return "http://192.168.122.1:64321"
    }

    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("sony-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticast() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun appendLog(line: String) {
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logView.append("[$time] $line\n")
        }
    }
}
