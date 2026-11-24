package com.sztu.schedule.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 在共享登录会话的后台 WebView 中读取课表。 */
object ScheduleScraper {
    private const val SESSION_EXPIRED = "会话已过期，请重新登录"

    suspend fun scrape(context: Context): Result<ScheduleSnapshot> {
        val repo = ScheduleRepository.get(context)
        return try {
            val html = readPage(context)
            if (ScheduleRepository.looksLikeLoginPage(html)) throw SessionExpired()
            Result.success(repo.saveTimetablePage(html))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpired) {
            repo.markSessionExpired()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readPage(context: Context): String = withContext(Dispatchers.Main.immediate) {
        val webView = WebView(context)
        val main = Handler(Looper.getMainLooper())
        try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = userAgentString.replace("; wv", "")
            }

            withTimeoutOrNull(25_000) {
                suspendCancellableCoroutine { continuation ->
                    fun fail(error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }

                    var navigation = 0
                    var retriedLanding = false
                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            // 教务会把过期会话重定向到明文 /Logon.do，直接交回官方登录入口。
                            if (request.isForMainFrame && url.scheme == "http" && JwxtUrls.isSignInPage(url.toString())) {
                                fail(SessionExpired())
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            navigation++
                            main.removeCallbacksAndMessages(null)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (!continuation.isActive || view.url != url) return
                            main.removeCallbacksAndMessages(null)
                            val pageNavigation = navigation
                            main.postDelayed({
                                if (!continuation.isActive || navigation != pageNavigation || view.url != url) {
                                    return@postDelayed
                                }
                                when {
                                    JwxtUrls.isSignInPage(url) -> fail(SessionExpired())
                                    CookieJar.hostOf(url) != CookieJar.HOST_JWXT ->
                                        fail(IllegalStateException("教务跳转到了非预期页面"))
                                    !CookieJar.pathOf(url).startsWith("/jsxsd/xskb/") && !retriedLanding -> {
                                        retriedLanding = true
                                        view.loadUrl(JwxtUrls.SCHEDULE)
                                    }
                                    else -> view.evaluateJavascript("document.documentElement.outerHTML") { value ->
                                        if (continuation.isActive && navigation == pageNavigation && view.url == url) {
                                            val html = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
                                            if (html.isNullOrBlank()) {
                                                fail(IllegalStateException("无法读取教务课表页面"))
                                            } else {
                                                continuation.resume(html)
                                            }
                                        }
                                    }
                                }
                            }, 900)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (!request.isForMainFrame) return
                            if (JwxtUrls.isSignInPage(request.url.toString()) && request.url.scheme == "http") {
                                fail(SessionExpired())
                            } else {
                                fail(IllegalStateException("无法连接教务系统（WebView 错误 ${error.errorCode}）"))
                            }
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (!request.isForMainFrame) return
                            if (response.statusCode == 401) {
                                fail(SessionExpired())
                            } else {
                                fail(IllegalStateException("教务系统请求失败（HTTP ${response.statusCode}）"))
                            }
                        }
                    }
                    webView.loadUrl(JwxtUrls.SCHEDULE)
                }
            } ?: throw IllegalStateException("教务课表加载超时，请检查网络后重试")
        } finally {
            main.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private class SessionExpired : IllegalStateException(SESSION_EXPIRED)
}
