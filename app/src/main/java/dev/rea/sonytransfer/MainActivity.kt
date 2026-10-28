package dev.rea.sonytransfer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.content.res.Configuration
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : Activity() {

    private companion object {
        private const val PREFS = "sony_camera"
        private const val KEY_BASE_URL = "camera_base_url"
    }

    private class ReceivedImage(var info: ImageInfo, val thumb: Bitmap?)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    // UI
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var rootView: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var previewImage: ImageView
    private lateinit var previewHint: TextView
    private lateinit var overallBar: ProgressBar
    private lateinit var overallText: TextView
    private lateinit var currentBar: ProgressBar
    private lateinit var currentText: TextView
    private lateinit var stripScroll: HorizontalScrollView
    private lateinit var stripRow: LinearLayout
    private lateinit var batchButton: Button

    // 状态
    private lateinit var cameraWifi: CameraWifi
    private lateinit var connectivityManager: ConnectivityManager
    private var nfcAdapter: NfcAdapter? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastNfc: CameraNfcInfo? = null
    private var busy = false
    private var cameraBase: String? = null

    // 接收/进度状态
    private val receivedImages = ArrayList<ReceivedImage>()
    private var speedIndex = -1
    private var speedLastBytes = 0L
    private var speedLastTime = 0L
    private var speedBps = 0.0
    private var receiveStartedAt = 0L
    private var receivedBytes = 0L
    private var currentImageInfo: ImageInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraWifi = CameraWifi(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        rootView = root

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
        val disconnectButton = Button(this).apply { text = "断开"; textSize = 13f }
        buttonRow.addView(connectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(receiveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttonRow.addView(disconnectButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttonRow)

        // 大图预览区
        val previewFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                currentImageInfo?.let { info ->
                    FullImageViewer(this@MainActivity, info) { updated ->
                        currentImageInfo = updated
                        receivedImages.firstOrNull { it.info.uri == updated.uri }?.info = updated
                    }.show()
                }
            }
        }
        previewFrame.addView(previewImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        previewHint = TextView(this).apply {
            text = "预览区"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
        }
        previewFrame.addView(previewHint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(previewFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(190),
        ))

        // 总进度
        val overallRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        overallBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        overallText = TextView(this).apply {
            text = "总进度 0/0"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), 0, 0, 0)
        }
        overallRow.addView(overallBar, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        overallRow.addView(overallText)
        root.addView(overallRow, matchWrap())

        // 当前图片进度 + 速度
        val currentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(4))
        }
        currentBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        currentText = TextView(this).apply {
            text = "当前：—"
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(dp(8), 0, 0, 0)
            setSingleLine()
        }
        currentRow.addView(currentBar, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
        ))
        currentRow.addView(currentText)
        root.addView(currentRow, matchWrap())

        // 已接收缩略图条（下载完成后可逐张点开预览）
        stripScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            isHorizontalScrollBarEnabled = false
        }
        stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        stripScroll.addView(stripRow)
        root.addView(stripScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(80),
        ))

        // 批量修正日期（相机没电时钟归零时，一次把本批照片日期改对）
        val batchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        batchButton = Button(this).apply { text = "批量改本次日期（0 张）"; textSize = 12f }
        batchButton.isEnabled = false
        batchButton.setOnClickListener { editAllDates() }
        batchRow.addView(batchButton, matchWrap())
        root.addView(batchRow, matchWrap())

        logView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            movementMethod = ScrollingMovementMethod()
            text = ""
        }
        root.addView(logView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150),
        ))

        // 外层滚动容器：横屏/小屏时内容可以上下滑动，不会因为高度不够被裁掉
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        scrollView = scroll
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> adjustLogHeight() }
        setContentView(scroll)

        // 首帧后让日志框占满剩余高度（竖屏铺满，横屏限高）
        scroll.post { adjustLogHeight() }

        connectButton.setOnClickListener {
            val ssid = ssidEdit.text.toString().trim()
            if (ssid.isEmpty()) {
                appendLog("请先填 SSID（或用 NFC 一碰读取）")
                return@setOnClickListener
            }
            connectCamera(ssid, passEdit.text.toString().ifEmpty { null }) { onCameraConnected() }
        }
        receiveButton.setOnClickListener { ensureConnected { startReceive() } }
        disconnectButton.setOnClickListener {
            cameraWifi.disconnect()
            releaseMulticast()
            cameraBase = null
            statusView.text = "已断开相机网络"
            appendLog("已断开相机网络")
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * 日志框高度：竖屏占满剩余屏幕空间；横屏限高（最小 96dp / 最大 220dp），
     * 高度不够时整页可上下滚动。
     */
    private fun adjustLogHeight() {
        if (!::scrollView.isInitialized || !::rootView.isInitialized || !::logView.isInitialized) return
        val viewportHeight = scrollView.height
        if (viewportHeight <= 0 || rootView.height <= 0) return

        var fixedHeight = rootView.paddingTop + rootView.paddingBottom
        for (index in 0 until rootView.childCount) {
            val child = rootView.getChildAt(index)
            if (child === logView) continue
            fixedHeight += child.height
            val params = child.layoutParams as? LinearLayout.LayoutParams
            if (params != null) fixedHeight += params.topMargin + params.bottomMargin
        }
        val available = viewportHeight - fixedHeight
        val target = if (isLandscape()) {
            available.coerceIn(dp(96), dp(220))
        } else {
            maxOf(available, dp(140))
        }
        val current = logView.layoutParams.height
        if (current != target) {
            logView.layoutParams = (logView.layoutParams as LinearLayout.LayoutParams).apply {
                height = target
            }
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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
            startReceive()
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
        acquireMulticast()
        val startedAt = SystemClock.elapsedRealtime()
        appendLog("连接相机 Wi-Fi: $ssid")

        // 复用检查要探测 dd.xml，放到后台线程，避免卡 UI。
        Thread {
            val reused = tryReuseCameraNetwork()
            mainHandler.post {
                if (reused) {
                    busy = false
                    val message = "已复用现有相机 Wi-Fi：$ssid（${SystemClock.elapsedRealtime() - startedAt}ms）"
                    appendLog(message)
                    statusView.text = message
                    onConnected()
                    return@post
                }
                cameraWifi.connect(ssid, pass) { ok, message ->
                    mainHandler.post {
                        busy = false
                        val cost = SystemClock.elapsedRealtime() - startedAt
                        val line = "$message（耗时 ${cost}ms）"
                        appendLog(line)
                        statusView.text = line
                        if (ok) onConnected() else appendLog("连接失败，可重试或检查相机是否在传输模式")
                    }
                }
            }
        }.start()
    }

    /**
     * 复用已连上的相机热点（无 Internet 的 Wi-Fi）：临时绑定该网络探测 dd.xml，
     * 命中就直接跳过 WifiNetworkSpecifier 的系统确认框和重新关联，通常可省 3–8 秒。
     */
    private fun tryReuseCameraNetwork(): Boolean {
        if (cameraWifi.network != null) return false
        val net = findCameraWifiNetwork() ?: return false
        val previous = connectivityManager.boundNetworkForProcess
        return try {
            connectivityManager.bindProcessToNetwork(net)
            val base = probeKnownBases(net, 500)
            if (base == null) {
                connectivityManager.bindProcessToNetwork(previous)
                false
            } else {
                cameraBase = base
                rememberBaseUrl(base)
                cameraWifi.attachExisting(net)
                appendLog("探测到相机: $base（复用已连接 Wi-Fi）")
                true
            }
        } catch (_: Exception) {
            connectivityManager.bindProcessToNetwork(previous)
            false
        }
    }

    /**
     * 找手机当前已连上、但没有 Internet 的 Wi-Fi 网络（相机热点特征）。
     * 正常上网 Wi-Fi 会直接跳过，不做无谓探测。
     */
    private fun findCameraWifiNetwork(): Network? {
        val networks = try {
            connectivityManager.allNetworks
        } catch (_: Exception) {
            return null
        }
        return networks.firstOrNull { net ->
            val caps = connectivityManager.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    /**
     * 依次探测已知/猜测的相机地址：上次成功的地址 → NFC 里的 DD 主机 → 默认网关 →
     * 常见固定 IP。命中任一即返回，全部失败返回 null。
     */
    private fun probeKnownBases(network: Network?, timeoutMsPerBase: Int): String? {
        val candidates = LinkedHashSet<String>()
        cachedBaseUrl()?.let { candidates += it }
        cameraBase?.let { candidates += it }
        lastNfc?.ddUrl?.let { dd ->
            Regex("(https?://[^/]+)").find(dd)?.let { candidates += it.groupValues[1] }
        }
        gatewayBaseUrls(network).forEach { candidates += it }
        candidates += "http://192.168.122.1:64321"
        for (base in candidates) {
            if (Upnp.probeCamera(base, timeoutMsPerBase)) return base
        }
        return null
    }

    /** 从网络默认网关 / DHCP 服务器推导相机地址（索尼相机通常就是网关）。 */
    private fun gatewayBaseUrls(network: Network?): List<String> {
        val net = network ?: return emptyList()
        val props = try {
            connectivityManager.getLinkProperties(net)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val hosts = LinkedHashSet<String>()
        props.routes.forEach { route ->
            if (route.isDefaultRoute) {
                route.gateway?.hostAddress?.let { host ->
                    if (!host.contains(':')) hosts += host
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            props.dhcpServerAddress?.hostAddress?.let { host ->
                if (!host.contains(':')) hosts += host
            }
        }
        return hosts.map { "http://$it:64321" }
    }

    private fun cachedBaseUrl(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_BASE_URL, null)

    private fun rememberBaseUrl(base: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_BASE_URL, base).apply()
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
        appendLog("已连接相机，可点「开始接收」（相机需处于「在本机上选择」并已选图）")
    }

    private fun resolveBaseUrl(): String? {
        cameraBase?.let { return it }
        val startedAt = SystemClock.elapsedRealtime()
        val network = cameraWifi.network

        // 先探测已知地址（上次成功地址、NFC DD、网关、常见固定 IP）：通常几十毫秒命中，
        // 比等 SSDP 广播快得多；只有全部失败才退回 SSDP。
        probeKnownBases(network, 900)?.let { base ->
            cameraBase = base
            rememberBaseUrl(base)
            appendLog("相机地址: $base（探测命中 ${SystemClock.elapsedRealtime() - startedAt}ms）")
            return base
        }

        appendLog("地址探测未命中，改用 SSDP（最多 3s）…")
        val location = Ssdp.findCameraLocation(network, 3000)
        if (location != null) {
            appendLog("SSDP: $location（${SystemClock.elapsedRealtime() - startedAt}ms）")
            val base = Regex("(https?://[^/]+)").find(location)?.groupValues?.get(1)
            if (base != null) {
                cameraBase = base
                rememberBaseUrl(base)
                return base
            }
        }
        appendLog("SSDP 未发现相机，尝试默认地址 http://192.168.122.1:64321")
        cameraBase = "http://192.168.122.1:64321"
        return cameraBase
    }

    // ------------------------------------------------------------ 接收

    private fun startReceive() {
        if (busy) return
        busy = true
        receivedBytes = 0L
        receiveStartedAt = SystemClock.elapsedRealtime()
        resetTransferUi()
        Thread {
            val base = resolveBaseUrl()
            if (base == null) {
                appendLog("找不到相机地址")
                busy = false
                return@Thread
            }
            appendLog("相机 base URL: $base")
            val client = SonyCameraClient { line -> appendLog(line) }
            val result = client.receivePush(
                baseUrl = base,
                onItems = { items -> mainHandler.post { onReceiveItems(items) } },
                onThumbnail = { bytes ->
                    val bitmap = decodePreview(bytes)
                    if (bitmap != null) {
                        mainHandler.post {
                            previewImage.setImageBitmap(bitmap)
                            previewHint.visibility = View.GONE
                        }
                    }
                },
                onProgress = { progress -> mainHandler.post { renderProgress(progress) } },
                save = { name, mime, data ->
                    val uri = ImageSaver.save(applicationContext, name, mime, data)
                    if (uri != null) {
                        receivedBytes += data.size
                        val isVideo = ImageSaver.isVideo(mime)
                        appendLog(
                            String.format(
                                Locale.US, "已保存 %s (%.1f MB)%s",
                                name, data.size / 1048576.0,
                                if (isVideo) " · 视频" else "",
                            ),
                        )
                        val thumb = if (isVideo) decodeVideoFrame(uri) else decodePreview(data, 320)
                        val big = if (isVideo) thumb else decodePreview(data)
                        val info = if (isVideo) {
                            buildVideoInfo(name, uri, mime, data.size.toLong())
                        } else {
                            buildImageInfo(name, uri, data)
                        }
                        mainHandler.post {
                            currentImageInfo = info
                            if (big != null) {
                                previewImage.setImageBitmap(big)
                                previewHint.visibility = View.GONE
                            } else if (isVideo) {
                                previewHint.visibility = View.VISIBLE
                                previewHint.text = "视频已保存：$name（点缩略图播放）"
                            }
                            addReceivedImage(info, thumb)
                        }
                    } else {
                        appendLog("保存失败: $name")
                    }
                    uri != null
                },
            )
            appendLog(result.message)
            val elapsedSeconds = (SystemClock.elapsedRealtime() - receiveStartedAt) / 1000.0
            if (result.ok && receivedBytes > 0 && elapsedSeconds > 0.1) {
                appendLog(
                    String.format(
                        Locale.US, "用时 %.1f s，平均 %.1f MB/s",
                        elapsedSeconds, receivedBytes / 1048576.0 / elapsedSeconds,
                    ),
                )
            }
            mainHandler.post {
                busy = false
                overallText.text = result.message
                if (receivedImages.isNotEmpty()) {
                    previewHint.visibility = View.VISIBLE
                    previewHint.text = "共 ${receivedImages.size} 张（点缩略图全屏查看，可双指放大）"
                }
                statusView.text = result.message
            }
        }.start()
    }

    private fun resetTransferUi() {
        mainHandler.post {
            overallBar.progress = 0
            overallText.text = "总进度 0/0"
            currentBar.progress = 0
            currentText.text = "当前：—"
            previewImage.setImageDrawable(null)
            previewHint.visibility = View.VISIBLE
            previewHint.text = "预览区"
            stripRow.removeAllViews()
            receivedImages.clear()
            currentImageInfo = null
            updateBatchButton()
            speedIndex = -1
            speedLastBytes = 0L
            speedLastTime = 0L
            speedBps = 0.0
        }
    }

    private fun onReceiveItems(items: List<CameraItem>) {
        overallBar.progress = 0
        overallText.text = "总进度 0/${items.size}"
        currentBar.progress = 0
        currentText.text = "当前：等待开始…"
        previewHint.visibility = View.VISIBLE
        previewHint.text = "共 ${items.size} 张，等待相机开始传送…"
    }

    private fun renderProgress(progress: SonyCameraClient.Progress) {
        val now = SystemClock.elapsedRealtime()
        if (progress.index != speedIndex) {
            speedIndex = progress.index
            speedLastBytes = 0L
            speedLastTime = 0L
            speedBps = 0.0
        }
        if (speedLastTime == 0L) {
            speedLastBytes = progress.downloaded
            speedLastTime = now
        } else {
            val deltaBytes = progress.downloaded - speedLastBytes
            val deltaSeconds = (now - speedLastTime) / 1000.0
            if (deltaSeconds >= 0.2) {
                if (deltaBytes >= 0) {
                    val instantBps = deltaBytes / deltaSeconds
                    speedBps = if (speedBps <= 0) instantBps else speedBps * 0.6 + instantBps * 0.4
                }
                speedLastBytes = progress.downloaded
                speedLastTime = now
            }
        }

        val perFile = if (progress.totalBytes > 0) {
            (progress.downloaded.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val overall = if (progress.total > 0) {
            ((progress.index + perFile) / progress.total).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        overallBar.progress = (overall * 1000).toInt().coerceIn(0, 1000)
        overallText.text = String.format(
            Locale.US, "总进度 %d/%d · %d%%",
            progress.index + 1, progress.total, (overall * 100).toInt(),
        )

        currentBar.progress = (perFile * 1000).toInt().coerceIn(0, 1000)
        val speedText = when {
            speedBps >= 1048576 -> String.format(Locale.US, "%.1f MB/s", speedBps / 1048576.0)
            speedBps >= 1024 -> String.format(Locale.US, "%.0f KB/s", speedBps / 1024.0)
            else -> "—"
        }
        currentText.text = String.format(
            Locale.US, "%s · %d%% · %s",
            progress.title, (perFile * 100).toInt(), speedText,
        )
        previewHint.text = "${progress.title}（第 ${progress.index + 1}/${progress.total} 张）"
    }

    private fun addReceivedImage(info: ImageInfo, thumb: Bitmap?) {
        val image = ReceivedImage(info, thumb)
        receivedImages.add(image)
        val click = View.OnClickListener {
            FullImageViewer(this@MainActivity, image.info) { updated ->
                image.info = updated
                if (currentImageInfo?.uri == updated.uri) currentImageInfo = updated
            }.show()
        }
        val cell = FrameLayout(this).apply {
            if (thumb != null) {
                addView(
                    ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(thumb)
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            } else {
                addView(
                    TextView(this@MainActivity).apply {
                        text = if (info.isVideo) "视频" else "图片"
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            if (info.isVideo) {
                addView(
                    TextView(this@MainActivity).apply {
                        text = "▶"
                        textSize = 18f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        setBackgroundColor(Color.parseColor("#55000000"))
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            setOnClickListener(click)
        }
        val params = LinearLayout.LayoutParams(dp(64), dp(64))
        params.setMargins(dp(2), 0, dp(2), 0)
        cell.layoutParams = params
        stripRow.addView(cell)
        stripScroll.post { stripScroll.fullScroll(View.FOCUS_RIGHT) }
        updateBatchButton()
    }

    /** 视频取首帧作为预览图 / 缩略图。 */
    private fun decodeVideoFrame(uri: Uri?): Bitmap? {
        if (uri == null) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun buildVideoInfo(name: String, uri: Uri?, mime: String, bytes: Long): ImageInfo {
        var width = 0
        var height = 0
        var durationMs = 0L
        var takenAt: String? = null
        if (uri != null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                takenAt = formatMp4Date(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                )
            } catch (_: Exception) {
            } finally {
                runCatching { retriever.release() }
            }
        }
        return ImageInfo(
            name = name,
            uri = uri,
            bytes = bytes,
            width = width,
            height = height,
            takenAt = takenAt,
            camera = null,
            exposure = null,
            aperture = null,
            iso = null,
            focalLength = null,
            isVideo = true,
            durationMs = durationMs,
            mime = mime,
        )
    }

    /** MP4 里常见的几种日期格式 → "yyyy-MM-dd HH:mm:ss"。 */
    private fun formatMp4Date(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyyMMdd'T'HHmmss",
            "yyyy:MM:dd HH:mm:ss",
        )
        for (pattern in patterns) {
            val date = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value)
            }.getOrNull()
            if (date != null) {
                return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
            }
        }
        return null
    }

    private fun updateBatchButton() {
        batchButton.text = "按偏移量改日期（${receivedImages.size} 张）"
        batchButton.isEnabled = receivedImages.isNotEmpty()
    }

    /** 按偏移量整体平移本批照片/视频的日期（保留彼此之间的时间差）。 */
    private fun editAllDates() {
        val images = receivedImages.toList()
        if (images.isEmpty()) {
            appendLog("还没有已接收的图片")
            return
        }
        val labels = listOf("年", "月", "日", "时", "分")
        val edits = labels.map {
            EditText(this).apply {
                hint = "偏移$it（正数往后，负数往前）"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                setSingleLine()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        edits.forEach { layout.addView(it, matchWrap()) }
        AlertDialog.Builder(this)
            .setTitle("按偏移量平移日期（${images.size} 张）")
            .setMessage("每张按各自原拍摄时间加上偏移量，照片之间的相对时间差保持不变。例如全部 +10 年。")
            .setView(layout)
            .setPositiveButton("应用") { _, _ ->
                val delta = edits.map { it.text.toString().trim().toIntOrNull() ?: 0 }
                applyDateOffset(images, delta)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyDateOffset(images: List<ReceivedImage>, delta: List<Int>) {
        val years = delta.getOrElse(0) { 0 }
        val months = delta.getOrElse(1) { 0 }
        val days = delta.getOrElse(2) { 0 }
        val hours = delta.getOrElse(3) { 0 }
        val minutes = delta.getOrElse(4) { 0 }
        if (years == 0 && months == 0 && days == 0 && hours == 0 && minutes == 0) {
            appendLog("偏移量全为 0，未修改")
            return
        }
        appendLog(
            "按偏移量平移日期：${years}年 ${months}月 ${days}日 ${hours}时 ${minutes}分（${images.size} 张）",
        )
        val editor = PhotoDateEditor(this)
        Thread {
            var ok = 0
            images.forEach { image ->
                val base = PhotoDates.parseDisplay(image.info.takenAt)
                if (base == null) {
                    appendLog("跳过 ${image.info.name}：没有可用的拍摄时间")
                    return@forEach
                }
                val calendar = Calendar.getInstance().apply {
                    time = base
                    add(Calendar.YEAR, years)
                    add(Calendar.MONTH, months)
                    add(Calendar.DAY_OF_MONTH, days)
                    add(Calendar.HOUR_OF_DAY, hours)
                    add(Calendar.MINUTE, minutes)
                }
                val result = editor.updateDate(image.info.uri, calendar, image.info.isVideo)
                if (result.ok) {
                    ok++
                    image.info = image.info.copy(takenAt = PhotoDates.displayString(calendar))
                } else {
                    appendLog("改日期失败 ${image.info.name}: ${result.message}")
                }
            }
            appendLog("偏移完成：$ok/${images.size} 张")
            mainHandler.post {
                if (currentImageInfo != null) {
                    receivedImages.firstOrNull { it.info.uri == currentImageInfo?.uri }
                        ?.let { currentImageInfo = it.info }
                }
                updateBatchButton()
            }
        }.start()
    }

    /** 保存时从 JPEG 读取 EXIF，构建图片详情（供侧栏显示）。 */
    private fun buildImageInfo(name: String, uri: Uri?, data: ByteArray): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val exif = runCatching { ExifInterface(ByteArrayInputStream(data)) }.getOrNull()
        fun attr(tag: String): String? =
            exif?.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() }

        val make = attr(ExifInterface.TAG_MAKE).orEmpty()
        val model = attr(ExifInterface.TAG_MODEL).orEmpty()
        val camera = when {
            make.isNotEmpty() && model.isNotEmpty() && !model.startsWith(make, true) -> "$make $model"
            model.isNotEmpty() -> model
            make.isNotEmpty() -> make
            else -> null
        }
        return ImageInfo(
            name = name,
            uri = uri,
            bytes = data.size.toLong(),
            width = bounds.outWidth,
            height = bounds.outHeight,
            takenAt = formatExifDateTime(
                attr(ExifInterface.TAG_DATETIME_ORIGINAL) ?: attr(ExifInterface.TAG_DATETIME),
            ),
            camera = camera,
            exposure = formatExposure(attr(ExifInterface.TAG_EXPOSURE_TIME)),
            aperture = attr(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
            iso = attr(ExifInterface.TAG_ISO_SPEED_RATINGS)?.let { "ISO $it" }
                ?: attr("PhotographicSensitivity")?.let { "ISO $it" },
            focalLength = formatFocalLength(attr(ExifInterface.TAG_FOCAL_LENGTH)),
        )
    }

    /** "2016:03:26 10:31:00" → "2016-03-26 10:31:00" */
    private fun formatExifDateTime(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return raw.replaceFirst(":", "-").replaceFirst(":", "-")
    }

    /** 曝光时间：优先保持 "1/250" 形式，小数秒换算成 1/x。 */
    private fun formatExposure(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.contains('/')) return "$raw s"
        val seconds = raw.toDoubleOrNull() ?: return "$raw s"
        if (seconds <= 0.0) return "$raw s"
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            String.format(Locale.US, "1/%d s", Math.round(1.0 / seconds))
        }
    }

    /** 焦距："350/10" → "35mm" */
    private fun formatFocalLength(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val millimeters = if (raw.contains('/')) {
            val parts = raw.split('/')
            val numerator = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val denominator = parts.getOrNull(1)?.toDoubleOrNull()?.takeIf { it != 0.0 }
                ?: return null
            numerator / denominator
        } else {
            raw.toDoubleOrNull() ?: return null
        }
        return String.format(Locale.US, "%.0fmm", millimeters)
    }

    /** 解码 JPEG 预览：先算采样率（避免 24MP 原图 OOM），再按 EXIF 旋转。 */
    private fun decodePreview(data: ByteArray, maxDimension: Int = 1280): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimension) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
            val degrees = try {
                val orientation = ExifInterface(ByteArrayInputStream(data))
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) {
                0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ 日志

    private fun appendLog(line: String) {
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logView.append("[$time] $line\n")
            // 日志框铺满后自动滚到最新一行
            logView.post logScroll@{
                val layout = logView.layout ?: return@logScroll
                if (layout.lineCount > 0) {
                    val bottom = layout.getLineBottom(layout.lineCount - 1)
                    logView.scrollTo(0, (bottom - logView.height).coerceAtLeast(0))
                }
            }
        }
    }
}
