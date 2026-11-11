package com.sztu.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拉取 GitHub 上的 update.json，对比 versionCode 后下载 APK 并安装。
 * GitHub Releases 会 302 到 release-assets；国内直连常卡在 0%，需手动跟跳转 + 镜像兜底。
 */
object OtaUpdater {
    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val force: Boolean,
        val changelog: String,
        /** 可选：包大小（字节）。update.json 里有就校验。 */
        val sizeBytes: Long = 0L,
        /** 可选：APK 的 SHA-256（小写十六进制）。update.json 里有就校验。 */
        val sha256: String = "",
    )

    data class State(
        val checking: Boolean = false,
        val downloading: Boolean = false,
        val progress: Int = 0,
        val hasUpdate: Boolean = false,
        val error: String = "",
        val manifest: Manifest? = null,
        val localVersionName: String = BuildConfig.VERSION_NAME,
        val localVersionCode: Int = BuildConfig.VERSION_CODE,
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)

    /** 国内可达的 GitHub 加速前缀（按顺序试） */
    private val GH_MIRRORS = listOf(
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://mirror.ghproxy.com/",
    )

    @Volatile
    private var state = State()

    @Volatile
    private var listeners = mutableSetOf<(State) -> Unit>()

    fun addListener(cb: (State) -> Unit) {
        listeners.add(cb)
        cb(state)
    }

    fun removeListener(cb: (State) -> Unit) {
        listeners.remove(cb)
    }

    fun check(ctx: Context, silent: Boolean = true) {
        val url = resolveManifestUrl(ctx)
        if (url.isEmpty()) {
            if (!silent) {
                emit(state.copy(error = "未配置更新地址", checking = false, hasUpdate = false))
            }
            return
        }
        if (!busy.compareAndSet(false, true)) return
        emit(state.copy(checking = true, error = "", downloading = false, progress = 0))
        io.execute {
            try {
                val raw = httpGet(url)
                val o = JSONObject(raw)
                val m = Manifest(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.optString("versionName", ""),
                    apkUrl = o.getString("apkUrl"),
                    force = o.optBoolean("force", false),
                    changelog = o.optString("changelog", ""),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    sha256 = o.optString("sha256", "").trim().lowercase(),
                )
                val has = m.versionCode > BuildConfig.VERSION_CODE && m.apkUrl.isNotBlank()
                emit(
                    state.copy(
                        checking = false,
                        hasUpdate = has,
                        manifest = m,
                        error = when {
                            has -> ""
                            silent -> ""
                            else -> "已是最新版本"
                        },
                    ),
                )
            } catch (e: Exception) {
                emit(
                    state.copy(
                        checking = false,
                        hasUpdate = false,
                        error = if (silent) "" else ("检查失败：" + (e.message ?: "网络错误")),
                    ),
                )
            } finally {
                busy.set(false)
            }
        }
    }

    fun resolveManifestUrl(ctx: Context): String {
        val saved = ctx.getSharedPreferences("sztu_ota", Context.MODE_PRIVATE)
            .getString("manifestUrl", "")
            ?.trim()
            .orEmpty()
        if (saved.isNotEmpty()) return saved
        return BuildConfig.OTA_MANIFEST_URL.trim()
    }

    fun setManifestUrl(ctx: Context, url: String) {
        ctx.getSharedPreferences("sztu_ota", Context.MODE_PRIVATE)
            .edit()
            .putString("manifestUrl", url.trim())
            .apply()
    }

    fun snapshot(ctx: Context? = null): JSONObject {
        val m = state.manifest
        val url = ctx?.let { resolveManifestUrl(it) } ?: BuildConfig.OTA_MANIFEST_URL
        return JSONObject()
            .put("checking", state.checking)
            .put("downloading", state.downloading)
            .put("progress", state.progress)
            .put("hasUpdate", state.hasUpdate)
            .put("error", state.error)
            .put("localVersionName", state.localVersionName)
            .put("localVersionCode", state.localVersionCode)
            .put("remoteVersionName", m?.versionName ?: "")
            .put("remoteVersionCode", m?.versionCode ?: 0)
            .put("force", m?.force == true)
            .put("changelog", m?.changelog ?: "")
            .put("apkUrl", m?.apkUrl ?: "")
            .put("manifestUrl", url)
    }

    private fun emit(next: State) {
        state = next
        val snap = next
        main.post {
            listeners.toList().forEach { it(snap) }
        }
    }

    fun downloadAndInstall(activity: Activity): String {
        val m = state.manifest ?: return "没有可用更新"
        if (m.apkUrl.isBlank()) return "更新包地址无效"
        if (!busy.compareAndSet(false, true)) return "正在处理中"
        emit(state.copy(downloading = true, progress = 1, error = ""))
        io.execute {
            try {
                val dir = File(activity.cacheDir, "ota").apply { mkdirs() }
                val out = File(dir, "update.apk")
                if (out.exists()) out.delete()
                var lastErr: Exception? = null
                var ok = false
                for (url in downloadCandidates(m.apkUrl)) {
                    try {
                        emit(state.copy(downloading = true, progress = 1, error = ""))
                        httpDownload(url, out) { p ->
                            emit(state.copy(downloading = true, progress = p.coerceIn(1, 99)))
                        }
                        if (out.length() < 100_000L) {
                            throw IllegalStateException("下载文件过小（${out.length()}）")
                        }
                        ok = true
                        break
                    } catch (e: Exception) {
                        lastErr = e
                        if (out.exists()) out.delete()
                    }
                }
                if (!ok) {
                    throw lastErr ?: IllegalStateException("所有下载源均失败")
                }
                // update.json 里给了 sizeBytes/sha256 就必须对得上，才允许调起安装。
                // 缺这两个字段时行为与本轮之前一致（不做额外校验）。
                verifyDownloaded(out, m)
                main.post {
                    busy.set(false)
                    emit(state.copy(downloading = false, progress = 100, error = ""))
                    installApk(activity, out)
                }
            } catch (e: Exception) {
                busy.set(false)
                val tip = "下载失败：" + (e.message ?: "") + "。可点「浏览器下载」"
                emit(state.copy(downloading = false, progress = 0, error = tip))
            }
        }
        return ""
    }

    /** 系统浏览器打开 APK 链接（OTA 卡住时的兜底） */
    fun openApkInBrowser(activity: Activity): String {
        val url = state.manifest?.apkUrl?.takeIf { it.isNotBlank() }
            ?: return "没有可用更新地址"
        return try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ""
        } catch (e: Exception) {
            "无法打开浏览器：" + (e.message ?: "")
        }
    }

    fun openUnknownAppSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
        }
    }

    /** 校验下载产物与 update.json 的 sizeBytes / sha256 是否一致（字段缺失则跳过）。 */
    private fun verifyDownloaded(file: File, m: Manifest) {
        if (m.sizeBytes > 0L && file.length() != m.sizeBytes) {
            file.delete()
            throw IllegalStateException("安装包大小不符（期望 ${m.sizeBytes}，实际 ${file.length()}）")
        }
        val want = m.sha256
        if (want.isNotEmpty()) {
            val actual = sha256Of(file)
            if (!actual.equals(want, ignoreCase = true)) {
                file.delete()
                throw IllegalStateException("安装包校验失败（SHA-256 不一致）")
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadCandidates(apkUrl: String): List<String> {
        val list = linkedSetOf(apkUrl)
        if (apkUrl.contains("github.com") || apkUrl.contains("githubusercontent.com")) {
            for (prefix in GH_MIRRORS) {
                list += prefix + apkUrl
            }
        }
        return list.toList()
    }

    private fun installApk(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            emit(state.copy(error = "请先允许「安装未知应用」"))
            openUnknownAppSettings(activity)
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            emit(state.copy(error = "无法调起安装：" + (e.message ?: "")))
        }
    }

    private fun ua(): String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 SztuSchedule/${BuildConfig.VERSION_NAME}"

    private fun openConn(url: String, method: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = false // 手动跟跳转，避免跨域丢 Content-Length / 卡死
            requestMethod = method
            setRequestProperty("User-Agent", ua())
            setRequestProperty("Accept", "*/*")
        }
    }

    private fun httpGet(url: String): String {
        openFollowed(url).use { conn ->
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
    }

    private fun httpDownload(url: String, dest: File, onProgress: (Int) -> Unit) {
        openFollowed(url).use { conn ->
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                copyWithProgress(input, dest, total, onProgress)
            }
        }
        onProgress(100)
    }

    private fun copyWithProgress(
        input: InputStream,
        dest: File,
        total: Long,
        onProgress: (Int) -> Unit,
    ) {
        FileOutputStream(dest).use { output ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            var done = 0L
            var lastEmit = -1
            onProgress(1)
            while (input.read(buf).also { read = it } != -1) {
                output.write(buf, 0, read)
                done += read
                val p = if (total > 0) {
                    ((done * 100) / total).toInt().coerceIn(1, 99)
                } else {
                    // 无 Content-Length：按约 45MB 估算，避免一直停在 0%
                    ((done * 100) / (45L * 1024 * 1024)).toInt().coerceIn(1, 95)
                }
                if (p != lastEmit) {
                    lastEmit = p
                    onProgress(p)
                }
            }
            output.flush()
        }
    }

    /** 手动跟随 301/302/303/307/308 */
    private fun openFollowed(startUrl: String): HttpURLConnection {
        var current = startUrl
        for (i in 0 until 8) {
            val conn = openConn(current, "GET")
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            if (code in 301..303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("重定向无 Location ($code)")
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("重定向过多")
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }
}
