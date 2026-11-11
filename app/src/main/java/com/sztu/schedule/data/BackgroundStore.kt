package com.sztu.schedule.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import java.io.File
import java.io.InputStream

/** 课表背景图：相册选择、透明度、亮度采样（供前端配色） */
class BackgroundStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sztu_schedule", Context.MODE_PRIVATE)

    /** 落盘文件名与受限端点白名单必须一致（审查增量 J29-01）。 */
    private val file = File(context.filesDir, BackgroundImageEndpoint.ASSET_NAME)

    fun hasImage(): Boolean = file.exists() && file.length() > 0

    /** 限制端点的读取目录（只放这一个文件，不给别的路径）。 */
    fun imageDir(): File = context.filesDir

    /**
     * 背景图版本标识（mtime + 大小）。用于给受限端点拼 `?v=` 版本号；
     * 换图后版本号变化，URL 变化，WebView 缓存自动失效（报告 J05 / J29-01）。
     */
    fun stamp(): Long {
        if (!file.exists()) return 0L
        return file.lastModified() * 31L + file.length()
    }

    fun getOpacity(): Float {
        // 旧默认 0.55 偏灰，未手动改过的一次性抬到更清晰的默认值
        if (!prefs.contains("bg_opacity_v2") && prefs.getFloat("bg_opacity", 0.55f) == 0.55f) {
            prefs.edit().putFloat("bg_opacity", 0.88f).putBoolean("bg_opacity_v2", true).apply()
        }
        return prefs.getFloat("bg_opacity", 0.88f).coerceIn(0.2f, 0.98f)
    }

    fun setOpacity(value: Float) {
        prefs.edit()
            .putFloat("bg_opacity", value.coerceIn(0.2f, 0.98f))
            .putBoolean("bg_opacity_v2", true)
            .apply()
    }

    fun clear() {
        if (file.exists()) file.delete()
        prefs.edit().remove("bg_luminance").apply()
    }

    fun saveFromUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 有界解码：先读尺寸再按 inSampleSize 降采样，避免相册大图
                // 直接按原始分辨率解码到内存（审查增量 J29-01「后台下采样」）
                val decoded = decodeBounded(input, SCALE_MAX_SIDE * 2) ?: return false
                val ok = saveBitmap(decoded)
                decoded.recycle()
                ok
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun saveBitmap(src: Bitmap): Boolean {
        return try {
            val scaled = scaleDown(src, SCALE_MAX_SIDE)
            val luminance = sampleLuminance(scaled)
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (scaled !== src) scaled.recycle()
            prefs.edit()
                .putFloat("bg_luminance", luminance)
                .apply()
            if (!prefs.contains("bg_opacity")) {
                prefs.edit().putFloat("bg_opacity", 0.88f).putBoolean("bg_opacity_v2", true).apply()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getLuminance(): Float {
        if (prefs.contains("bg_luminance")) return prefs.getFloat("bg_luminance", 0.55f)
        if (!hasImage()) return 0.92f
        return try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return 0.55f
            val l = sampleLuminance(bmp)
            bmp.recycle()
            prefs.edit().putFloat("bg_luminance", l).apply()
            l
        } catch (_: Exception) {
            0.55f
        }
    }

    /**
     * 背景图在 WebView 里的取图地址（带版本号），交给受限端点
     * [BackgroundImageEndpoint] 服务。没有背景图时返回空串。
     *
     * 前端据此走 `url(<地址>)` 且图片就绪后再渐显；不再有 data URL，
     * 因此每次状态推送跨桥的只有这几十个字符（审查增量 J29-01）。
     */
    fun webImagePath(): String {
        if (!hasImage()) return ""
        return BackgroundImageEndpoint.versionedUrl(stamp())
    }

    /** 有界解码：只按 [maxSide] 量级解码，避免把原图整张读进内存。 */
    private fun decodeBounded(input: InputStream, maxSide: Int): Bitmap? {
        val buffer = input.readBytes()
        if (buffer.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(buffer, 0, buffer.size, bounds)
        var sample = 1
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side > 0) {
            while (side / (sample * 2) >= maxSide) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(buffer, 0, buffer.size, opts)
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        val side = maxOf(w, h)
        if (side <= maxSide) return src
        val scale = maxSide.toFloat() / side
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun sampleLuminance(bmp: Bitmap): Float {
        val stepX = (bmp.width / 12).coerceAtLeast(1)
        val stepY = (bmp.height / 12).coerceAtLeast(1)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c) / 255.0
                val g = Color.green(c) / 255.0
                val b = Color.blue(c) / 255.0
                // perceived luminance
                sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.55f else (sum / n).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** 落盘最长边：屏宽/屏高都在这个量级以下，再大只是白占解码内存。 */
        private const val SCALE_MAX_SIDE = 1600

        @Volatile private var instance: BackgroundStore? = null
        fun get(context: Context): BackgroundStore {
            return instance ?: synchronized(this) {
                instance ?: BackgroundStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
