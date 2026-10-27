package dev.rea.sonytransfer

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale

/**
 * 视频播放器：TextureView + MediaPlayer + 自绘控制条。
 * 视频按比例居中显示（不变形），支持双指缩放（1x~8x）、拖动、双击缩放、点击暂停/播放。
 */
class VideoPlayerView(context: Context) : FrameLayout(context) {

    private val textureView = ZoomTextureView(context)
    private val mediaPlayer = MediaPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val playButton = Button(context)
    private val seekBar = SeekBar(context)
    private val timeText = TextView(context)

    private var pendingUri: Uri? = null
    private var prepared = false
    private var durationMs = 0
    private var userSeeking = false
    private val density = context.resources.displayMetrics.density

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        playButton.apply {
            text = "▶"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { togglePlay() }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeText.text = "${formatTime(progress.toLong())} / ${formatTime(durationMs.toLong())}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (prepared) mediaPlayer.seekTo(seekBar?.progress ?: 0)
                userSeeking = false
            }
        })
        timeText.apply {
            text = "0:00 / 0:00"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, 0, 0)
        }
        bar.addView(
            playButton,
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        bar.addView(
            seekBar,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            timeText,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        textureView.onSurfaceReady = { surfaceTexture ->
            mediaPlayer.setSurface(Surface(surfaceTexture))
            pendingUri?.let { prepare(it) }
        }
        textureView.onTap = { togglePlay() }

        mediaPlayer.setOnPreparedListener { mp ->
            prepared = true
            durationMs = mp.duration.coerceAtLeast(0)
            seekBar.max = durationMs
            textureView.setVideoSize(mp.videoWidth, mp.videoHeight)
            mp.start()
            updatePlayButton()
            updateProgressLoop()
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            textureView.setVideoSize(width, height)
        }
        mediaPlayer.setOnCompletionListener {
            updatePlayButton()
            seekBar.progress = 0
            timeText.text = "0:00 / ${formatTime(durationMs.toLong())}"
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            playButton.text = "!"
            true
        }
    }

    fun setVideo(uri: Uri) {
        pendingUri = uri
        val surfaceTexture = textureView.surfaceTexture
        if (surfaceTexture != null) {
            mediaPlayer.setSurface(Surface(surfaceTexture))
            prepare(uri)
        }
    }

    /** 预览旋转 90°（只转显示，不改原文件）。 */
    fun rotateView() {
        textureView.rotate90()
    }

    private fun prepare(uri: Uri) {
        if (prepared) return
        try {
            mediaPlayer.reset()
            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture != null) {
                mediaPlayer.setSurface(Surface(surfaceTexture))
            }
            mediaPlayer.setDataSource(context, uri)
            mediaPlayer.prepareAsync()
        } catch (_: Exception) {
            playButton.text = "!"
        }
    }

    private fun togglePlay() {
        if (!prepared) return
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.start()
        updatePlayButton()
    }

    private fun updatePlayButton() {
        playButton.text = if (prepared && mediaPlayer.isPlaying) "⏸" else "▶"
    }

    private fun updateProgressLoop() {
        handler.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                if (prepared && !userSeeking) {
                    seekBar.progress = mediaPlayer.currentPosition
                    timeText.text = "${formatTime(mediaPlayer.currentPosition.toLong())} / " +
                        formatTime(durationMs.toLong())
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        runCatching { mediaPlayer.release() }
        textureView.onSurfaceReady = null
        textureView.onTap = null
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

/** TextureView 缩放层：视频按比例居中，双指缩放 1x~8x、拖动、双击缩放。 */
class ZoomTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    var onSurfaceReady: ((SurfaceTexture) -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var videoWidth = 0
    private var videoHeight = 0
    private var rotationDegrees = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor).coerceIn(1f, 8f)
                if (current <= 0f || target / current == 1f) return true
                suppMatrix.postScale(target / current, target / current, detector.focusX, detector.focusY)
                applyTransform()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (currentScale() > 1.01f) {
                    suppMatrix.postTranslate(-distanceX, -distanceY)
                    applyTransform()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale() > 1.01f) {
                    suppMatrix.reset()
                } else {
                    suppMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                }
                applyTransform()
                clampTransform()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }
        },
    )

    init {
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        onSurfaceReady?.invoke(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        resetBase()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
        post { resetBase() }
    }

    /** 旋转 90°（只影响预览显示）。 */
    fun rotate90() {
        rotationDegrees = (rotationDegrees + 90) % 360
        resetBase()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetBase()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            clampTransform()
        }
        return true
    }

    private fun currentScale(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun applyTransform() {
        val display = Matrix().apply {
            set(suppMatrix)
            postConcat(baseMatrix)
        }
        setTransform(display)
    }

    /**
     * 视频按比例居中。
     *
     * 注意：TextureView 的默认变换是「把视频拉伸铺满整个视图」，所以不能直接套等比缩放；
     * 要用非等比缩放（scaleX/scaleY）把默认拉伸还原，再平移到居中位置。
     */
    private fun resetBase() {
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        // 旋转 90/270 时宽高互换，按旋转后的有效尺寸做适配
        val rotated = rotationDegrees % 180 != 0
        val effectiveWidth = if (rotated) videoHeight else videoWidth
        val effectiveHeight = if (rotated) videoWidth else videoHeight
        val fitScale = minOf(viewWidth / effectiveWidth, viewHeight / effectiveHeight)
        // 撤销 TextureView 默认拉伸：缩放后内容尺寸 = fitScale * 视频尺寸
        val scaleX = fitScale * videoWidth / viewWidth
        val scaleY = fitScale * videoHeight / viewHeight
        // postRotate 绕原点旋转，旋转后的矩形中心不在原点，需要按旋转后的中心重新居中
        val scaledWidth = fitScale * videoWidth
        val scaledHeight = fitScale * videoHeight
        val rotatedCenterX: Float
        val rotatedCenterY: Float
        when (rotationDegrees) {
            90 -> {
                rotatedCenterX = -scaledHeight / 2f
                rotatedCenterY = scaledWidth / 2f
            }
            180 -> {
                rotatedCenterX = -scaledWidth / 2f
                rotatedCenterY = -scaledHeight / 2f
            }
            270 -> {
                rotatedCenterX = scaledHeight / 2f
                rotatedCenterY = -scaledWidth / 2f
            }
            else -> {
                rotatedCenterX = scaledWidth / 2f
                rotatedCenterY = scaledHeight / 2f
            }
        }
        val translateX = viewWidth / 2f - rotatedCenterX
        val translateY = viewHeight / 2f - rotatedCenterY
        baseMatrix.reset()
        baseMatrix.postScale(scaleX, scaleY)
        baseMatrix.postRotate(rotationDegrees.toFloat())
        baseMatrix.postTranslate(translateX, translateY)
        suppMatrix.reset()
        applyTransform()
    }

    private fun clampTransform() {
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
        val display = Matrix().apply {
            set(suppMatrix)
            postConcat(baseMatrix)
        }
        // 视频在视图里实际占用的矩形：默认铺满的视图矩形经过 base 变换后的范围
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        display.mapRect(rect)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        var dx = 0f
        var dy = 0f
        if (rect.width() <= viewWidth) {
            dx = (viewWidth - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) dx = -rect.left
            if (rect.right < viewWidth) dx = viewWidth - rect.right
        }
        if (rect.height() <= viewHeight) {
            dy = (viewHeight - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) dy = -rect.top
            if (rect.bottom < viewHeight) dy = viewHeight - rect.bottom
        }
        if (dx != 0f || dy != 0f) {
            suppMatrix.postTranslate(dx, dy)
            applyTransform()
        }
    }
}
