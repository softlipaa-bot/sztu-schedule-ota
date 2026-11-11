package com.sztu.schedule.login

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import com.sztu.schedule.data.hwsys.HwsysCallException
import com.sztu.schedule.data.hwsys.HwsysHttp
import com.sztu.schedule.data.hwsys.HwsysSessionStore
import com.sztu.schedule.data.hwsys.HwsysUrls
import com.sztu.schedule.data.hwsys.TokenBundle
import com.sztu.schedule.data.hwsys.allowedSchoolHost
import com.sztu.schedule.data.hwsys.extractSsoTokenPlain
import com.sztu.schedule.data.hwsys.isAcmTokenCarrier
import com.sztu.schedule.data.hwsys.isHwsysAppUrl
import com.sztu.schedule.data.hwsys.isJwxtPage
import com.sztu.schedule.data.hwsys.jwtExpiryEpochMs
import com.sztu.schedule.data.hwsys.safeFailure
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 教学辅助系统官网登录。这个 WebView 没有本地桥，页面拿不到课表能力。
 * 令牌只在内存里交给 Keystore，不写日志、不放进结果 Intent。
 */
class HwsysLoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var banner: TextView
    private val http = HwsysHttp()
    private val busy = AtomicBoolean(false)
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
        }
        banner = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_BANNER) ?: "教学辅助系统登录"
            setTextColor(Color.parseColor("#20232B"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(48, 36, 48, 24)
        }
        webView = WebView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        clearAcmCookies()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                if (!allowed(uri)) {
                    banner.text = "已拦下非学校域名"
                    return true
                }
                noteUrl(uri.toString())
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                noteUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                noteUrl(url)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                banner.text = "SSO交换：证书校验失败"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.isVisible = false
                noteUrl(url)
            }
        }
        webView.loadUrl(HwsysUrls.SSO_START)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!finished && ::webView.isInitialized && webView.canGoBack()) webView.goBack()
                else cancel()
            }
        })
    }

    private fun noteUrl(url: String?) {
        if (finished || url.isNullOrBlank()) return
        if (isJwxtPage(url)) {
            banner.text = "当前打开的是教务系统，还不能当作教学辅助系统已登录"
            return
        }
        if (!isAcmTokenCarrier(url)) return
        val sso = extractSsoToken(url)
        if (sso != null) {
            exchange(sso)
            return
        }
        if (isHwsysAppUrl(url)) readStoredSession()
    }

    private fun exchange(ssoToken: String) {
        if (finished || !busy.compareAndSet(false, true)) return
        banner.text = "正在登录教学辅助系统"
        thread {
            val outcome = try {
                val bundle = http.exchangeSso(ssoToken)
                confirmWithServer(bundle)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                busy.set(false)
                outcome.fold(
                    onSuccess = { finishVerified(it) },
                    onFailure = { banner.text = failureText("SSO交换", it) },
                )
            }
        }
    }

    private fun readStoredSession() {
        if (finished || busy.get()) return
        webView.evaluateJavascript(READ_SESSION) { raw ->
            if (finished || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            val bundle = decodeStored(raw) ?: return@evaluateJavascript
            webView.evaluateJavascript(CLEAR_TOKENS, null)
            confirmStored(bundle)
        }
    }

    private fun confirmStored(bundle: TokenBundle) {
        if (finished || !busy.compareAndSet(false, true)) return
        if (jwtExpiryEpochMs(bundle.accessToken) == null || bundle.userKey.isBlank()) {
            busy.set(false)
            banner.text = "SSO交换：响应格式变化"
            return
        }
        banner.text = "正在向教学辅助系统确认会话"
        thread {
            val outcome = confirmWithServer(bundle)
            runOnUiThread {
                busy.set(false)
                outcome.fold(
                    onSuccess = { finishVerified(it) },
                    onFailure = { banner.text = failureText("出勤查询失败", it) },
                )
            }
        }
    }

    private fun confirmWithServer(bundle: TokenBundle): Result<TokenBundle> {
        if (jwtExpiryEpochMs(bundle.accessToken) == null || bundle.userKey.isBlank()) {
            return Result.failure(HwsysCallException("SSO交换", 0, null, "parse", "SSO交换：响应格式变化"))
        }
        val envelope = http.verifyAccess(bundle.accessToken)
        if (envelope.authRequired) {
            return Result.failure(
                HwsysCallException(
                    "出勤查询失败",
                    envelope.statusCode,
                    envelope.statusCode,
                    "auth",
                    "出勤查询失败：会话已失效（HTTP ${envelope.statusCode}）",
                )
            )
        }
        if (envelope.statusCode != 200 || !envelope.shapeOk) {
            return Result.failure(
                HwsysCallException(
                    "出勤查询失败",
                    envelope.statusCode,
                    envelope.statusCode,
                    "parse",
                    "出勤查询失败：响应格式变化（HTTP ${envelope.statusCode}）",
                )
            )
        }
        return Result.success(bundle)
    }

    private fun finishVerified(bundle: TokenBundle) {
        if (finished) return
        val store = HwsysSessionStore(this)
        store.save(bundle)
        store.markServerVerified()
        finished = true
        setResult(RESULT_OK)
        finish()
    }

    private fun failureText(stage: String, error: Throwable): String {
        val text = when (error) {
            is HwsysCallException -> error.message
            else -> safeFailure(stage, error)
        }
        return text?.ifBlank { "$stage：请求失败" } ?: "$stage：请求失败"
    }

    private fun cancel() {
        if (finished) return
        finished = true
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            try {
                webView.destroy()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BANNER = "banner"

        private val READ_SESSION = """
            (function(){
              try {
                var raw = localStorage.getItem('persistedStore');
                if (!raw) return null;
                var data = (JSON.parse(raw).store_data) || {};
                function val(key){
                  var v = data[key];
                  if (!v) return '';
                  if (typeof v === 'string') return v;
                  if (typeof v.data === 'string') return v.data;
                  return '';
                }
                var user = data['hwsys_user'] || null;
                if (user && user.data) user = user.data;
                var access = val('hwsys_access_token');
                if (!access) return null;
                return {
                  access: access,
                  refresh: val('hwsys_refresh_token'),
                  userKey: user && user.userx_key ? String(user.userx_key) : '',
                  userName: user && user.name ? String(user.name) : ''
                };
              } catch (e) { return null; }
            })()
        """.trimIndent()

        private const val CLEAR_TOKENS = """
            (function(){
              try {
                var raw = localStorage.getItem('persistedStore');
                if (!raw) return;
                var obj = JSON.parse(raw);
                var data = obj.store_data || {};
                delete data['hwsys_access_token'];
                delete data['hwsys_refresh_token'];
                obj.store_data = data;
                localStorage.setItem('persistedStore', JSON.stringify(obj));
              } catch (e) {}
            })()
        """

        fun allowed(uri: Uri): Boolean = allowedSchoolHost(uri.scheme, uri.host)

        fun extractSsoToken(url: String): String? = extractSsoTokenPlain(url)

        fun decodeStored(raw: String): TokenBundle? {
            val text = raw.trim()
            if (!text.startsWith("{")) return null
            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            val access = json.optString("access")
            val key = json.optString("userKey")
            if (jwtExpiryEpochMs(access) == null || key.isBlank()) return null
            return TokenBundle(access, json.optString("refresh"), key, json.optString("userName"))
        }

        private fun clearAcmCookies() {
            val manager = CookieManager.getInstance()
            for (url in listOf("https://acm.sztu.edu.cn", "https://acm.sztu.edu.cn:40130")) {
                val header = manager.getCookie(url) ?: continue
                for (part in header.split(";")) {
                    val name = part.substringBefore("=").trim()
                    if (name.isEmpty()) continue
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/")
                }
            }
            manager.flush()
        }
    }
}
