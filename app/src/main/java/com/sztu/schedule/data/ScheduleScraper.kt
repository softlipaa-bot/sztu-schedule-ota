package com.sztu.schedule.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * 后台 WebView 打开课表页并抓 HTML（不展示给用户）。
 */
object ScheduleScraper {
    @SuppressLint("SetJavaScriptEnabled")
    fun scrape(
        context: Context,
        onDone: (Result<ScheduleSnapshot>) -> Unit,
    ) {
        val activityContext = context
        val repo = ScheduleRepository.get(activityContext)
        val main = Handler(Looper.getMainLooper())

        main.post {
            val webView = WebView(activityContext)
            var done = false
            fun finishOnce(r: Result<ScheduleSnapshot>) {
                if (done) return
                done = true
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (_: Exception) {
                }
                onDone(r)
            }

            CookieManager.getInstance().setAcceptCookie(true)
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgentString.replace("; wv", "")
            }

            val bridge = object {
                @JavascriptInterface
                fun onHtml(html: String?) {
                    val body = html.orEmpty()
                    thread {
                        try {
                            CookieManager.getInstance().flush()
                            repo.persistWebViewCookies()
                            if (body.length < 80 || ScheduleRepository.looksLikeLoginPage(body)) {
                                val http = runBlocking { repo.refreshFromJwxt() }
                                main.post { finishOnce(http) }
                                return@thread
                            }
                            val parsed = ScheduleHtmlParser.parse(body)
                            if (parsed.courses.isEmpty()) {
                                val http = runBlocking { repo.refreshFromJwxt() }
                                main.post { finishOnce(http) }
                            } else {
                                repo.save(parsed)
                                main.post { finishOnce(Result.success(parsed)) }
                            }
                        } catch (e: Exception) {
                            main.post { finishOnce(Result.failure(e)) }
                        }
                    }
                }
            }
            webView.addJavascriptInterface(bridge, "SztuCapture")

            var attempts = 0
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (done) return
                    CookieManager.getInstance().flush()
                    val u = url.orEmpty()
                    if (!u.contains("jwxt.sztu.edu.cn")) return
                    if (u.contains("LoginToXk", ignoreCase = true)) {
                        finishOnce(Result.failure(IllegalStateException("会话已过期，请重新登录")))
                        return
                    }
                    if (!u.contains("xskb") && attempts < 2) {
                        attempts++
                        view?.loadUrl(JwxtUrls.SCHEDULE)
                        return
                    }
                    main.postDelayed({
                        if (done) return@postDelayed
                        view?.evaluateJavascript(
                            """
                            (function(){
                              try {
                                SztuCapture.onHtml(document.documentElement.outerHTML);
                              } catch(e) { SztuCapture.onHtml(''); }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }, 900)
                }
            }

            main.postDelayed({
                if (!done) {
                    finishOnce(Result.failure(IllegalStateException("同步超时，请重试")))
                }
            }, 25000)

            webView.loadUrl(JwxtUrls.SCHEDULE)
        }
    }
}
