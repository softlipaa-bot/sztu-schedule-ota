package com.sztu.schedule.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.isVisible
import com.sztu.schedule.data.JwxtUrls
import com.sztu.schedule.data.ScheduleRepository

/**
 * 只负责统一认证登录。成功拿到教务 Cookie 后立即返回，
 * 课表抓取由 MainActivity 后台进行并显示「更新中」。
 */
class LoginActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var repo: ScheduleRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = ScheduleRepository.get(this)

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
        }
        val banner = TextView(this).apply {
            text = intent.getStringExtra("banner") ?: "教务课表登录"
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

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.isVisible = newProgress < 100
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                maybeFinish(url.orEmpty())
            }
        }
        webView.loadUrl(JwxtUrls.SSO)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                }
            }
        )
    }

    private fun maybeFinish(url: String) {
        if (finished) return
        if (!url.contains("jwxt.sztu.edu.cn")) return
        if (url.contains("LoginToXk", ignoreCase = true)) return
        if (url.contains("auth.sztu.edu.cn")) return
        val okPath = url.contains("jsxsd") || url.contains("xsMain") ||
            url.contains("framework") || url.contains("xskb")
        if (!okPath) return

        CookieManager.getInstance().flush()
        repo.persistWebViewCookies()
        val cookie = repo.savedCookieHeader()
        if (!cookie.contains("JSESSIONID", ignoreCase = true)) return

        finished = true
        // 稍留一点时间让 Cookie flush 落盘
        mainHandler.postDelayed({
            setResult(RESULT_OK, Intent().putExtra("ok", true).putExtra("needSync", true))
            finish()
        }, 200)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            webView.destroy()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
