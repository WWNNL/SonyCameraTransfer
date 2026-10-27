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
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private enum class Mode { PUSH, BROWSE }

    private data class Crumb(val id: String, val title: String)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // 共用 UI
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var pushPanel: LinearLayout
    private lateinit var browsePanel: LinearLayout
    private lateinit var browsePath: TextView
    private lateinit var browseHint: TextView
    private lateinit var browseList: ListView
    private lateinit var browseAdapter: BrowseAdapter
    private lateinit var downloadButton: Button

    // 状态
    private lateinit var cameraWifi: CameraWifi
    private var nfcAdapter: NfcAdapter? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastNfc: CameraNfcInfo? = null
    private var busy = false
    private var mode = Mode.PUSH
    private var cameraBase: String? = null

    // 浏览状态
    private val crumbs = ArrayList<Crumb>()
    private val selectedEntries = LinkedHashMap<String, BrowseEntry>()
    private var browser: SonyCameraBrowser? = null
    private var browseBusy = false
    private var autoDescendDepth = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraWifi = CameraWifi(this)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        buildUi()
        requestWifiPermissions()
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(8))
        }

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "等待 NFC 一碰，或手动输入 SSID/密码"
        }
        root.addView(statusView, matchWrap())

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
        val connectButton = Button(this).apply { text = "连接相机"; textSize = 13f }
        val receiveButton = Button(this).apply { text = "开始接收"; textSize = 13f }
        val browseButton = Button(this).apply { text = "浏览相机"; textSize = 13f }
        val disconnectButton = Button(this).apply { text = "断开"; textSize = 13f }
        buttonRow.addView(connectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(receiveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(browseButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(disconnectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonRow)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        pushPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        pushPanel.addView(TextView(this).apply {
            text = "推送接收（相机端选图）\n" +
                "① 相机：发送到智能手机 → 在本机上选择 → 勾选照片\n" +
                "② 手机贴相机 NFC（或填 SSID/密码后点「连接相机」）\n" +
                "③ 点「开始接收」，照片存到相册 Pictures/SonyTransfer\n\n" +
                "浏览相机（手机端浏览相册）\n" +
                "① 相机：发送到智能手机 → 在智能手机上选择\n" +
                "② 点「浏览相机」，按日期文件夹浏览、勾选下载原图"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        content.addView(pushPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        browsePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val upButton = Button(this).apply { text = "↑ 上级"; textSize = 12f }
        upButton.setOnClickListener { browseUp() }
        browsePath = TextView(this).apply {
            text = "相机"
            textSize = 13f
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.START
            setTextColor(Color.DKGRAY)
        }
        val refreshButton = Button(this).apply { text = "刷新"; textSize = 12f }
        refreshButton.setOnClickListener {
            loadChildren(crumbs.lastOrNull()?.id ?: "0")
        }
        navRow.addView(upButton)
        navRow.addView(browsePath, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(refreshButton)
        browsePanel.addView(navRow, matchWrap())

        browseAdapter = BrowseAdapter(this, object : BrowseAdapter.Callbacks {
            override fun onOpenContainer(entry: BrowseEntry) {
                autoDescendDepth = 0
                crumbs.add(Crumb(entry.id, entry.title))
                loadChildren(entry.id)
            }

            override fun onSelectionChanged(entry: BrowseEntry, selected: Boolean) {
                if (selected) selectedEntries[entry.id] = entry else selectedEntries.remove(entry.id)
                updateDownloadButton()
            }
        })
        browseList = ListView(this).apply { adapter = browseAdapter }
        browsePanel.addView(browseList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        val downloadRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        downloadButton = Button(this).apply { text = "下载选中 (0)"; textSize = 13f }
        downloadButton.setOnClickListener { downloadSelected() }
        browseHint = TextView(this).apply {
            text = "未浏览"
            textSize = 12f
            setTextColor(Color.GRAY)
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
        }
        downloadRow.addView(downloadButton)
        downloadRow.addView(browseHint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        browsePanel.addView(downloadRow, matchWrap())

        content.addView(browsePanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            movementMethod = ScrollingMovementMethod()
            text = ""
        }
        root.addView(logView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))

        setContentView(root)

        connectButton.setOnClickListener {
            val ssid = ssidEdit.text.toString().trim()
            if (ssid.isEmpty()) {
                appendLog("请先填 SSID（或用 NFC 一碰读取）")
                return@setOnClickListener
            }
            connectCamera(ssid, passEdit.text.toString().ifEmpty { null }) { onCameraConnected() }
        }
        receiveButton.setOnClickListener {
            mode = Mode.PUSH
            switchPanels()
            ensureConnected { startReceive() }
        }
        browseButton.setOnClickListener {
            mode = Mode.BROWSE
            switchPanels()
            ensureConnected { onCameraConnected() }
        }
        disconnectButton.setOnClickListener {
            mode = Mode.PUSH
            switchPanels()
            cameraWifi.disconnect()
            releaseMulticast()
            cameraBase = null
            browser = null
            crumbs.clear()
            selectedEntries.clear()
            browseAdapter.submit(emptyList(), emptySet())
            updateDownloadButton()
            browseHint.text = "已断开"
            statusView.text = "已断开相机网络"
            appendLog("已断开相机网络")
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun switchPanels() {
        pushPanel.visibility = if (mode == Mode.PUSH) View.VISIBLE else View.GONE
        browsePanel.visibility = if (mode == Mode.BROWSE) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ 权限 / NFC

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
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1001)
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
        appendLog("== NFC 标签 ==")
        info.describe().lines().filter { it.isNotBlank() }.forEach { appendLog(it) }
        statusView.text = info.describe()

        val error = SonyNfc.validate(info)
        if (error != null) {
            appendLog("NFC 不可用: $error")
            statusView.text = "NFC: $error"
            info.ssid?.let { ssidEdit.setText(it) }
            info.password?.let { passEdit.setText(it) }
            return
        }
        val ssid = info.ssid ?: return
        ssidEdit.setText(ssid)
        passEdit.setText(info.password ?: "")
        connectCamera(ssid, info.password) {
            onCameraConnected()
            if (mode == Mode.PUSH) startReceive()
        }
    }

    // ------------------------------------------------------------ 网络/连接

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

    private fun connectCamera(ssid: String, pass: String?, onConnected: () -> Unit) {
        if (busy) {
            appendLog("正在执行上一个任务，完成后再试")
            return
        }
        busy = true
        appendLog("连接相机 Wi-Fi: $ssid")
        acquireMulticast()
        cameraWifi.connect(ssid, pass) { ok, message ->
            mainHandler.post {
                busy = false
                appendLog(message)
                statusView.text = message
                if (ok) onConnected() else appendLog("连接失败，可重试或检查相机是否在传输模式")
            }
        }
    }

    private fun ensureConnected(action: () -> Unit) {
        if (cameraWifi.network != null) {
            action()
            return
        }
        val ssid = ssidEdit.text.toString().trim()
        if (ssid.isEmpty()) {
            appendLog("未连接相机 Wi-Fi：请先 NFC 一碰，或填 SSID/密码后点「连接相机」")
            return
        }
        connectCamera(ssid, passEdit.text.toString().ifEmpty { null }) { action() }
    }

    private fun onCameraConnected() {
        if (browser == null) browser = SonyCameraBrowser { line -> appendLog(line) }
        if (mode == Mode.BROWSE) {
            if (crumbs.isEmpty()) {
                loadChildren("0")
            } else {
                loadChildren(crumbs.last().id)
            }
        } else {
            appendLog("已连接相机，可点「开始接收」或「浏览相机」")
        }
    }

    private fun resolveBaseUrl(): String? {
        cameraBase?.let { return it }
        val location = Ssdp.findCameraLocation(cameraWifi.network, 6000)
        if (location != null) {
            appendLog("SSDP: $location")
            Regex("(https?://[^/]+)").find(location)?.let {
                cameraBase = it.groupValues[1]
                return cameraBase
            }
        }
        lastNfc?.ddUrl?.let { dd ->
            appendLog("NFC DD URL: $dd")
            Regex("(https?://[^/]+)").find(dd)?.let {
                cameraBase = it.groupValues[1]
                return cameraBase
            }
        }
        appendLog("SSDP 未发现相机，尝试默认地址 http://192.168.122.1:64321")
        cameraBase = "http://192.168.122.1:64321"
        return cameraBase
    }

    // ------------------------------------------------------------ 推送接收

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

    // ------------------------------------------------------------ 浏览模式

    private fun loadChildren(objectId: String) {
        if (browseBusy) return
        val browser = this.browser ?: run {
            appendLog("请先连接相机（相机需处于「在智能手机上选择」）")
            return
        }
        browseBusy = true
        Thread {
            try {
                val base = resolveBaseUrl() ?: throw IllegalStateException("找不到相机地址")
                browser.useBase(base)
                val entries = browser.browse(objectId)
                mainHandler.post {
                    browseBusy = false
                    applyEntries(entries)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    browseBusy = false
                    appendLog("浏览失败：${e.message}（相机需处于「在智能手机上选择」）")
                    browseHint.text = "浏览失败，请检查相机模式"
                }
            }
        }.start()
    }

    private fun applyEntries(entries: List<BrowseEntry>) {
        browseAdapter.submit(entries, selectedEntries.keys)
        updatePath()
        val folders = entries.count { it.isContainer }
        val files = entries.size - folders
        browseHint.text = if (entries.isEmpty()) {
            "空文件夹"
        } else {
            "共 ${entries.size} 项（文件夹 $folders / 照片 $files）"
        }
        updateDownloadButton()
        if (entries.size == 1 && entries[0].isContainer && autoDescendDepth < 4) {
            autoDescendDepth++
            crumbs.add(Crumb(entries[0].id, entries[0].title))
            loadChildren(entries[0].id)
        } else {
            autoDescendDepth = 0
        }
    }

    private fun updatePath() {
        browsePath.text = if (crumbs.isEmpty()) {
            "相机（根）"
        } else {
            "相机 / " + crumbs.joinToString(" / ") { it.title }
        }
    }

    private fun browseUp() {
        if (crumbs.isEmpty()) return
        crumbs.removeAt(crumbs.size - 1)
        autoDescendDepth = 0
        loadChildren(crumbs.lastOrNull()?.id ?: "0")
    }

    private fun updateDownloadButton() {
        downloadButton.text = "下载选中 (${selectedEntries.size})"
    }

    private fun downloadSelected() {
        val items = selectedEntries.values.toList()
        if (items.isEmpty()) {
            appendLog("先在列表里勾选照片")
            return
        }
        val browser = this.browser ?: return
        downloadButton.isEnabled = false
        Thread {
            var saved = 0
            items.forEachIndexed { index, entry ->
                appendLog("下载 ${index + 1}/${items.size}: ${entry.title}")
                runCatching { browser.download(entry) }
                    .onSuccess { data ->
                        val uri = ImageSaver.save(applicationContext, entry.title, data)
                        if (uri != null) {
                            saved++
                            appendLog(
                                String.format(
                                    Locale.US, "已保存 %s (%.1f MB)",
                                    entry.title, data.size / 1048576.0,
                                ),
                            )
                        } else {
                            appendLog("保存失败: ${entry.title}")
                        }
                    }
                    .onFailure { appendLog("下载失败 ${entry.title}: ${it.message}") }
            }
            appendLog("浏览下载完成：$saved/${items.size} 张")
            mainHandler.post { downloadButton.isEnabled = true }
        }.start()
    }

    // ------------------------------------------------------------ 日志

    private fun appendLog(line: String) {
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logView.append("[$time] $line\n")
        }
    }
}
