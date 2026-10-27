package dev.rea.sonytransfer

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.ByteArrayInputStream
import java.util.Locale

/** 一张已接收文件（图片或视频）的详细信息。 */
data class ImageInfo(
    val name: String,
    val uri: Uri?,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val takenAt: String?,
    val camera: String?,
    val exposure: String?,
    val aperture: String?,
    val iso: String?,
    val focalLength: String?,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val mime: String = "image/jpeg",
) {
    val sizeText: String
        get() = String.format(Locale.US, "%.1f MB（%d 字节）", bytes / 1048576.0, bytes)

    val resolutionText: String
        get() = if (width > 0 && height > 0) {
            if (isVideo) {
                String.format(Locale.US, "%d × %d", width, height)
            } else {
                String.format(
                    Locale.US, "%d × %d（%.1f MP）",
                    width, height, width.toDouble() * height / 1_000_000.0,
                )
            }
        } else {
            "—"
        }

    val durationText: String
        get() {
            if (durationMs <= 0) return "—"
            val totalSeconds = durationMs / 1000
            return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }

    val takenText: String get() = takenAt ?: "—"

    val cameraText: String get() = camera ?: "—"

    val exposureText: String
        get() = listOfNotNull(exposure, aperture, iso, focalLength)
            .joinToString(" · ")
            .ifEmpty { "—" }

    val locationText: String
        get() = if (isVideo) "Movies/SonyTransfer" else "Pictures/SonyTransfer"
}

/**
 * 全屏查看器：
 *  - 图片：双指缩放（1x~8x）、单指拖动、双击缩放；
 *  - 视频：VideoView + 系统播放控制条；
 *  - 右侧信息侧栏为半透明浮层（覆盖在图片上，不挤压图片），可收起/展开。
 */
class FullImageViewer(
    private val context: Context,
    info: ImageInfo,
    private val onInfoUpdated: (ImageInfo) -> Unit = {},
) {

    private var imageInfo = info
    private var takenRow: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

    fun show() {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val imageContainer = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        var zoomView: ZoomImageView? = null
        var loading: TextView? = null
        if (imageInfo.isVideo) {
            setupVideo(imageContainer)
        } else {
            zoomView = ZoomImageView(context)
            imageContainer.addView(zoomView, FrameLayout.LayoutParams(MATCH, MATCH))
            loading = TextView(context).apply {
                text = "加载中…"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            imageContainer.addView(loading, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(imageContainer, FrameLayout.LayoutParams(MATCH, MATCH))

        // 信息侧栏：半透明浮层，覆盖在图片右侧（不挤压图片）
        val sidebar = buildSidebar()
        root.addView(
            sidebar,
            FrameLayout.LayoutParams(dp(190), MATCH).apply { gravity = Gravity.END },
        )

        val closeButton = Button(context).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
        }
        root.addView(
            closeButton,
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(10), dp(10), 0, 0) },
        )
        val toggleButton = Button(context).apply {
            text = "信息"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
        }
        root.addView(
            toggleButton,
            FrameLayout.LayoutParams(dp(58), dp(44), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, dp(10), dp(10), 0) },
        )
        toggleButton.setOnClickListener {
            sidebar.visibility = if (sidebar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        closeButton.setOnClickListener { dialog.dismiss() }

        dialog.setContentView(root)
        dialog.show()

        val view = zoomView
        val hint = loading
        if (view != null) {
            Thread {
                val bitmap = decodeImage(2048)
                mainHandler.post {
                    if (bitmap != null) {
                        view.setImage(bitmap)
                        hint?.visibility = View.GONE
                    } else {
                        hint?.text = "图片加载失败"
                    }
                }
            }.start()
        }
    }

    // ------------------------------------------------------------ 视频播放

    private fun setupVideo(container: FrameLayout) {
        val uri = imageInfo.uri ?: return
        val videoView = VideoView(context)
        container.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH))
        val controller = MediaController(context)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener {
            it.isLooping = false
            videoView.start()
        }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, "视频播放失败", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // ------------------------------------------------------------ 信息侧栏

    private fun buildSidebar(): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 半透明覆盖层（80% 不透明）
            setBackgroundColor(Color.parseColor("#CC1E1E1E"))
            setPadding(dp(14), dp(58), dp(14), dp(14))
        }
        panel.addView(
            TextView(context).apply {
                text = if (imageInfo.isVideo) "视频信息" else "图片信息"
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) },
        )
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addRow(list, "文件名", imageInfo.name)
        addRow(list, "大小", imageInfo.sizeText)
        addRow(list, "分辨率", imageInfo.resolutionText)
        if (imageInfo.isVideo) {
            addRow(list, "时长", imageInfo.durationText)
        }
        takenRow = addRow(list, "拍摄时间", imageInfo.takenText)
        addRow(list, "相机", imageInfo.cameraText)
        if (!imageInfo.isVideo) {
            addRow(list, "曝光参数", imageInfo.exposureText)
        }
        addRow(list, "保存位置", imageInfo.locationText)
        val scroll = ScrollView(context).apply { addView(list) }
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val dateButton = Button(context).apply {
            text = "修改日期"
            textSize = 13f
        }
        dateButton.setOnClickListener { openDatePicker() }
        panel.addView(
            dateButton,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) },
        )
        return panel
    }

    private fun addRow(parent: LinearLayout, label: String, value: String): TextView {
        parent.addView(
            TextView(context).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#9A9A9A"))
                setPadding(0, 0, 0, dp(2))
            },
        )
        val valueView = TextView(context).apply {
            text = value
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        }
        parent.addView(valueView)
        return valueView
    }

    /** 修改当前文件的拍摄日期（图片改 EXIF + 相册；视频仅改相册日期）。 */
    private fun openDatePicker() {
        val activity = context as? Activity ?: return
        PhotoDates.showPicker(activity, imageInfo.takenAt) { calendar ->
            Thread {
                val result = PhotoDateEditor(context)
                    .updateDate(imageInfo.uri, calendar, imageInfo.isVideo)
                mainHandler.post {
                    if (result.ok) {
                        imageInfo = imageInfo.copy(takenAt = PhotoDates.displayString(calendar))
                        takenRow?.text = imageInfo.takenText
                        onInfoUpdated(imageInfo)
                    }
                    Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }
    }

    // ------------------------------------------------------------ 解码

    private fun decodeImage(maxDimension: Int): Bitmap? {
        val uri = imageInfo.uri ?: return null
        return runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDimension) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return null
            val degrees = runCatching {
                when (
                    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }.getOrDefault(0f)
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                bitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
                )
            }
            bitmap
        }.getOrNull()
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(value: Int): Int = (value * density).toInt()
}

/** 支持双指缩放 / 拖动 / 双击缩放（相对"适应屏幕"的 1x~8x）的 ImageView。 */
class ZoomImageView(context: Context) : ImageView(context) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor).coerceIn(1f, 8f)
                if (current <= 0f || target / current == 1f) return true
                suppMatrix.postScale(target / current, target / current, detector.focusX, detector.focusY)
                if (currentScale() < 1f) suppMatrix.reset()
                applyMatrix()
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
                    applyMatrix()
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
                applyMatrix()
                clamp()
                return true
            }
        },
    )

    init {
        setScaleType(ScaleType.MATRIX)
    }

    fun setImage(bitmap: Bitmap) {
        setImageBitmap(bitmap)
        post { resetBase() }
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
            clamp()
        }
        return true
    }

    private fun currentScale(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    private fun applyMatrix() {
        val display = Matrix().apply {
            set(suppMatrix)
            postConcat(baseMatrix)
        }
        imageMatrix = display
    }

    /** 计算"适应屏幕居中"的基准矩阵，缩放/拖动都相对它叠加。 */
    private fun resetBase() {
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f || width == 0 || height == 0) return
        val scale = minOf(width / drawableWidth, height / drawableHeight)
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(
            (width - drawableWidth * scale) / 2f,
            (height - drawableHeight * scale) / 2f,
        )
        suppMatrix.reset()
        applyMatrix()
    }

    /** 拖动/缩放后把图片约束在可视区域内，避免整张飞出屏幕。 */
    private fun clamp() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0) return
        val display = Matrix().apply {
            set(suppMatrix)
            postConcat(baseMatrix)
        }
        val rect = RectF(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat(),
        )
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
            applyMatrix()
        }
    }
}
