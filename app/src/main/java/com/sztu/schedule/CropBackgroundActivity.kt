package com.sztu.schedule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.sztu.schedule.data.AppearanceStore
import com.sztu.schedule.data.BackgroundStore
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 按屏幕比例裁切壁纸，确认后写入 BackgroundStore。
 *
 * 画布保持黑色（看图画布，符合照片裁切习惯），但底部动作区、说明文案与系统栏
 * 使用与主界面同一套浅/深令牌 —— 外观取自 [AppearanceStore]，所以用户在 App 里
 * 选了深色时，即使系统是浅色也不会出现「浅色系统栏 + 深色底部」的割裂。
 *
 * 裁切/解码实现（含后台线程与降采样）保持不变。
 */
class CropBackgroundActivity : ComponentActivity() {
    private lateinit var cropView: CropImageView

    /** 背景图的解码/编码都走共享的单线程执行器，不占主线程（J29-01）。 */
    private val io: Executor get() = bgIo


    private fun pill(color: String, radiusDp: Float = 14f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(Color.parseColor(color))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uri = intent?.data
        if (uri == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val dark = AppearanceStore.isDark(this)
        val pageColor = Color.parseColor(if (dark) DARK_PAGE else LIGHT_PAGE)
        val cardColor = Color.parseColor(if (dark) DARK_CARD else LIGHT_CARD)
        val textColor = Color.parseColor(if (dark) DARK_TEXT else LIGHT_TEXT)
        val text2Color = Color.parseColor(if (dark) DARK_TEXT2 else LIGHT_TEXT2)
        val btnInk = Color.parseColor(if (dark) DARK_BTN_INK else LIGHT_BTN_INK)

        applyBars(pageColor, dark)

        val dm = resources.displayMetrics
        val aspect = dm.widthPixels.toFloat() / dm.heightPixels.toFloat()

        cropView = CropImageView(this).apply {
            aspectRatio = aspect
            setBackgroundColor(Color.BLACK)
        }

        val tip = TextView(this).apply {
            text = "拖动 / 双指缩放，按屏幕比例裁切"
            setTextColor(text2Color)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 10)
        }

        val cancel = Button(this).apply {
            text = "取消"
            isAllCaps = false
            setTextColor(textColor)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = pill(if (dark) DARK_BLOCK else LIGHT_BLOCK)
            minHeight = (44 * resources.displayMetrics.density).toInt()
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        val confirm = Button(this).apply {
            text = "完成"
            isAllCaps = false
            setTextColor(btnInk)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = pill(if (dark) DARK_BTN else LIGHT_BTN)
            minHeight = (44 * resources.displayMetrics.density).toInt()
            setOnClickListener { commitCrop() }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 4, 16, 14)
            addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(confirm, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * resources.displayMetrics.density).toInt()
            })
        }

        // 底部动作区：与卡片同一套圆角（20dp 上圆角）和实色底，叠在黑画布上仍可读
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    20f * resources.displayMetrics.density,
                    20f * resources.displayMetrics.density,
                    20f * resources.displayMetrics.density,
                    20f * resources.displayMetrics.density,
                    0f, 0f, 0f, 0f,
                )
                setColor(cardColor)
            }
            addView(tip)
            addView(actions)
        }

        val root = FrameLayout(this).apply {
            // 画布仍是黑色；只把系统栏后面的容器对齐主题，避免切深浅时露出旧底色
            setBackgroundColor(pageColor)
            addView(
                cropView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                bottom,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, top = bars.top)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }
        )

        // 审查增量 J29-01：解码放到后台线程。2K/4K 相册图在主线程解码会有
        // 十几到几十毫秒的掉帧，甚至 OOM；这里先后台降采样解码，再回主线程贴图。
        io.execute {
            val bmp = decodeUri(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bmp?.recycle()
                    return@runOnUiThread
                }
                if (bmp == null) {
                    setResult(RESULT_CANCELED)
                    finish()
                    return@runOnUiThread
                }
                cropView.setBitmap(bmp)
            }
        }
    }

    /** 系统栏跟着当前实际外观：浅色外观用深色图标，深色外观用浅色图标。 */
    private fun applyBars(barColor: Int, dark: Boolean) {
        runCatching { window.statusBarColor = barColor }
        runCatching { window.navigationBarColor = barColor }
        ViewCompat.getWindowInsetsController(window.decorView)?.let { controller ->
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        runCatching { window.decorView.setBackgroundColor(barColor) }
    }

    private fun commitCrop() {
        val cropped = cropView.cropBitmap() ?: run {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        // 审查增量 J29-01：降采样 + JPEG 编码 + 亮度采样都放到后台线程，
        // 只把「成功/失败」带回主线程收尾，避免点击「完成」时卡在主线程。
        io.execute {
            val ok = BackgroundStore.get(this).saveBitmap(cropped)
            cropped.recycle()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                finish()
            }
        }
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            val (bw, bh) = boundsSide(uri)
            val maxSide = 2560
            var sample = 1
            val side = max(bw, bh).coerceAtLeast(1)
            while (side / sample > maxSide) sample *= 2
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeStream(input, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun boundsSide(uri: Uri): Pair<Int, Int> {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                opts.outWidth to opts.outHeight
            } ?: (1 to 1)
        } catch (_: Exception) {
            1 to 1
        }
    }

    companion object {
        // 与 web/src/styles/tokens.css 对应的令牌（画布页只用这几个）
        private const val LIGHT_PAGE = "#F2F1F5"
        private const val LIGHT_CARD = "#FFFCFE"
        private const val LIGHT_BLOCK = "#EFEDF2"
        private const val LIGHT_TEXT = "#20232B"
        private const val LIGHT_TEXT2 = "#626674"
        private const val LIGHT_BTN = "#0066CC"
        private const val LIGHT_BTN_INK = "#FFFFFF"

        private const val DARK_PAGE = "#171411"
        private const val DARK_CARD = "#282420"
        private const val DARK_BLOCK = "#36302B"
        private const val DARK_TEXT = "#F5F1EC"
        private const val DARK_TEXT2 = "#BDAFA3"
        private const val DARK_BTN = "#FF9E45"
        private const val DARK_BTN_INK = "#2B1A08"

        /** 进程内共享一个后台线程：解码/编码背景图（不占主线程，也不随 Activity 反复建线程）。 */
        private val bgIo: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bg-image-io").apply { isDaemon = true }
        }

        fun intent(context: Context, uri: Uri): Intent {
            return Intent(context, CropBackgroundActivity::class.java).setData(uri)
        }
    }
}

@SuppressLint("ViewConstructor")
private class CropImageView(context: Context) : View(context) {
    var aspectRatio: Float = 9f / 16f
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val cropRect = RectF()
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }

    private var lastX = 0f
    private var lastY = 0f
    private var minScale = 1f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bmp = bitmap ?: return false
                val px = detector.focusX
                val py = detector.focusY
                matrix.postScale(detector.scaleFactor, detector.scaleFactor, px, py)
                clamp(bmp)
                invalidate()
                return true
            }
        }
    )

    fun setBitmap(bmp: Bitmap) {
        bitmap?.takeIf { it !== bmp && !it.isRecycled }?.recycle()
        bitmap = bmp
        post { fitBitmap(bmp) }
    }

    private fun fitBitmap(bmp: Bitmap) {
        if (width == 0 || height == 0) return
        updateCropRect()
        val scale = max(cropRect.width() / bmp.width, cropRect.height() / bmp.height)
        minScale = scale
        matrix.reset()
        matrix.postScale(scale, scale)
        val dx = cropRect.centerX() - bmp.width * scale / 2f
        val dy = cropRect.centerY() - bmp.height * scale / 2f
        matrix.postTranslate(dx, dy)
        clamp(bmp)
        invalidate()
    }

    private fun updateCropRect() {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val pad = 20f * resources.displayMetrics.density
        var cw = viewW - pad * 2
        var ch = cw / aspectRatio
        if (ch > viewH - pad * 2 - 120f * resources.displayMetrics.density) {
            ch = viewH - pad * 2 - 120f * resources.displayMetrics.density
            cw = ch * aspectRatio
        }
        val left = (viewW - cw) / 2f
        val top = (viewH - ch) / 2f - 24f * resources.displayMetrics.density
        cropRect.set(left, top, left + cw, top + ch)
    }

    private fun clamp(bmp: Bitmap) {
        val values = FloatArray(9)
        matrix.getValues(values)
        var scale = values[Matrix.MSCALE_X]
        if (scale < minScale) {
            val factor = minScale / scale
            matrix.postScale(factor, factor, cropRect.centerX(), cropRect.centerY())
            matrix.getValues(values)
            scale = values[Matrix.MSCALE_X]
        }
        val scaledW = bmp.width * scale
        val scaledH = bmp.height * scale
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        if (scaledW <= cropRect.width()) {
            tx = cropRect.centerX() - scaledW / 2f
        } else {
            tx = min(tx, cropRect.left)
            tx = max(tx, cropRect.right - scaledW)
        }
        if (scaledH <= cropRect.height()) {
            ty = cropRect.centerY() - scaledH / 2f
        } else {
            ty = min(ty, cropRect.top)
            ty = max(ty, cropRect.bottom - scaledH)
        }
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        matrix.setValues(values)
    }

    fun cropBitmap(): Bitmap? {
        val bmp = bitmap ?: return null
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return null
        val outW = 1080
        val outH = max(1, (outW / aspectRatio).toInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val m = Matrix(matrix)
        val scaleX = outW / cropRect.width()
        val scaleY = outH / cropRect.height()
        m.postTranslate(-cropRect.left, -cropRect.top)
        m.postScale(scaleX, scaleY)
        canvas.drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bitmap?.let { fitBitmap(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, null)
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(cropRect, clearPaint)
        canvas.restoreToCount(saved)
        canvas.drawRect(cropRect, borderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrix.postTranslate(dx, dy)
                    clamp(bmp)
                    invalidate()
                    lastX = event.x
                    lastY = event.y
                }
            }
        }
        return true
    }
}
