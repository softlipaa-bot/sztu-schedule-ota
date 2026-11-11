package com.sztu.schedule

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sztu.schedule.data.AppearanceStore
import com.sztu.schedule.data.BackgroundImageEndpoint
import com.sztu.schedule.data.BackgroundImageHandler
import com.sztu.schedule.data.BackgroundStore
import com.sztu.schedule.data.CookieJar
import com.sztu.schedule.data.JwxtUrls
import com.sztu.schedule.data.ScheduleRepository
import com.sztu.schedule.data.ScheduleScraper
import com.sztu.schedule.data.SessionState
import com.sztu.schedule.data.TeachingWeek
import com.sztu.schedule.data.hwsys.AttendanceRepository
import com.sztu.schedule.data.hwsys.SyncHooks
import com.sztu.schedule.data.hwsys.SyncOrigin
import com.sztu.schedule.data.hwsys.SyncText
import com.sztu.schedule.data.hwsys.claimSync
import com.sztu.schedule.data.hwsys.releaseSync
import com.sztu.schedule.data.hwsys.runUnified
import com.sztu.schedule.login.HwsysLoginActivity
import com.sztu.schedule.login.LoginActivity
import com.sztu.schedule.widget.ScheduleWidgetUpdater
import com.sztu.schedule.widget.WidgetPinHelper
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var repo: ScheduleRepository
    private lateinit var attendance: AttendanceRepository
    private lateinit var bgStore: BackgroundStore
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private var syncing = false

    /**
     * 系统栏后面的实际容器。系统栏是透明的，切深浅色时必须同时改它的底色，
     * 否则深色外观会从栏后露出旧的浅色底（同时和栏图标撞色）。
     */
    private var rootView: FrameLayout? = null

    /** 最近一次同步失败的原文，供首页区分「同步失败」与「未登录／缓存可看」（J29-02）。 */
    @Volatile private var lastSyncError = ""
    @Volatile private var lastUnifiedMessage = ""
    @Volatile private var syncStage = ""
    private val syncRunning = AtomicBoolean(false)
    private var jwxtLoginSlot: CancellableContinuation<Boolean>? = null
    private var hwsysLoginSlot: CancellableContinuation<Boolean>? = null

    private companion object {
        const val TAG = "MainActivity"
        /** 界面底色：与 web/src/styles/tokens.css 的 --page 保持一致（浅/深）。 */
        const val PAGE_LIGHT = "#F2F1F5"
        const val PAGE_DARK = "#171411"
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val slot = jwxtLoginSlot
        jwxtLoginSlot = null
        if (slot != null && slot.isActive) {
            slot.resume(result.resultCode == RESULT_OK)
        } else if (result.resultCode != RESULT_OK) {
            pushMessage(result.data?.getStringExtra("error") ?: "已取消登录")
        }
    }

    private val hwsysLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val slot = hwsysLoginSlot
        hwsysLoginSlot = null
        if (slot != null && slot.isActive) slot.resume(result.resultCode == RESULT_OK)
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) {
            pushMessage("已取消选择背景")
            return@registerForActivityResult
        }
        cropLauncher.launch(CropBackgroundActivity.intent(this, uri))
    }

    private val cropLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pushState()
            pushMessage("壁纸已更新")
        } else {
            pushMessage("已取消裁切")
        }
    }

    private val otaListener: (OtaUpdater.State) -> Unit = {
        pushOtaState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repo = ScheduleRepository.get(this)
        attendance = AttendanceRepository.get(this)
        bgStore = BackgroundStore.get(this)
        OtaUpdater.addListener(otaListener)

        val dark = AppearanceStore.isDark(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(if (dark) PAGE_DARK else PAGE_LIGHT))
        }
        webView = WebView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor(if (dark) PAGE_DARK else PAGE_LIGHT))
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        errorView = TextView(this).apply {
            setTextColor(Color.parseColor("#626674"))
            textSize = 14f
            setPadding(48, 48, 48, 48)
            isVisible = false
        }
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            progress,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        )
        root.addView(
            errorView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootView = root
        setContentView(root)

        // 系统栏 + 刘海只在这里避让一次：两类取并集再 padding，避免重复计算。
        // 已处理的类型在返回值里置零（官方推荐做法），IME 等未处理类型原样下发，
        // 不能整体 CONSUMED —— 那会破坏现代 WebView 的键盘 viewport 调整。
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.NONE)
                .build()
        }

        applySystemBars(dark)

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            // 审查增量 J29-01：背景图走受限端点，不再把整张图 Base64 塞进状态 JSON。
            // 该端点只服务 filesDir 下的 schedule_bg.jpg 一个文件（白名单 + 目录校验）。
            .addPathHandler(
                BackgroundImageEndpoint.PREFIX,
                BackgroundImageHandler(bgStore.imageDir())
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            offscreenPreRaster = true
            // 高刷机优先流畅滑动；禁用不必要缩放开销
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // 系统深浅色要在页面脚本之前就知道，避免首屏闪一次旧主题
        installSystemThemeScript()
        webView.clearCache(false)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.isVisible = newProgress < 100
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                return request?.url?.let { assetLoader.shouldInterceptRequest(it) }
            }

            /**
             * 主导航白名单（报告 J08）：带原生桥的页面只允许本地 app assets 源。
             * 其它 URL 一律不在本 WebView 打开 —— 否则外部页面可以拿到
             * ScheduleBridge 的能力。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return true
                if (isAllowedLocalUrl(url)) return false
                Log.w(TAG, "blocked navigation from bridged WebView: ${url.scheme}://${url.host}")
                // 外部 http(s) 交给系统浏览器；其它 scheme 直接丢弃
                if (url.scheme == "http" || url.scheme == "https") {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    }
                }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.isVisible = false
                pushState()
                pushAttendance()
                pushSystemTheme()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    errorView.isVisible = true
                    errorView.text = "页面加载失败：${error?.description}\n请重新安装最新 APK"
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "ScheduleBridge")

        // https domain required for ES modules; served from android_asset/www via AssetLoader
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!::webView.isInitialized) {
                        showExitConfirm()
                        return
                    }
                    webView.evaluateJavascript(
                        "(function(){try{return !!(window.__handleAndroidBack&&window.__handleAndroidBack());}catch(e){return false;}})()"
                    ) { raw ->
                        val handled = raw == "true" || raw == "\"true\""
                        if (!handled) {
                            runOnUiThread { showExitConfirm() }
                        }
                    }
                }
            }
        )
    }

    /**
     * 把「系统当前是否深色」注入页面上下文（真实 UA 之外的一个只读布尔）。
     * 页面里 src/lib/theme.ts 用它代替 matchMedia 之外的信号源；
     * 跟随系统模式在系统变化时由 [onConfigurationChanged] 继续推送。
     */
    private fun installSystemThemeScript() {
        val darkNow = systemDark()
        val script = "(function(){window.__sztuSystemDark=$darkNow;})();"
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                return
            }
        } catch (t: Throwable) {
            Log.w(TAG, "document start script unavailable: ${t.message}")
        }
        // 老 WebView 降级：onPageFinished 时补一次
        webView.webViewClient?.let { /* 已在 onPageFinished 调 pushSystemTheme */ }
    }

    private fun systemDark(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun pushSystemTheme() {
        if (!::webView.isInitialized) return
        val dark = systemDark()
        webView.post {
            webView.evaluateJavascript(
                "window.onSztuSystemTheme&&window.onSztuSystemTheme($dark)",
                null
            )
        }
    }

    /** 系统栏 / 窗口底色跟着当前实际外观走（浅色外观 = 深色图标）。 */
    private fun applySystemBars(dark: Boolean) {
        val barColor = Color.parseColor(if (dark) PAGE_DARK else PAGE_LIGHT)
        runCatching { window.statusBarColor = barColor }
        runCatching { window.navigationBarColor = barColor }
        ViewCompat.getWindowInsetsController(window.decorView)?.let { controller ->
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
        runCatching { window.decorView.setBackgroundColor(barColor) }
        // 透明系统栏 + 主题切换：容器与 WebView 底色都要一起走，不能只改 decorView
        rootView?.setBackgroundColor(barColor)
        if (::webView.isInitialized) webView.setBackgroundColor(barColor)
    }

    /** 自有原生弹窗统一用「当前实际外观」，不跟随系统反色的 Light 主题。 */
    private fun themedDialog(): AlertDialog.Builder {
        val style = if (AppearanceStore.isDark(this)) {
            R.style.Theme_SztuSchedule_Dialog_Dark
        } else {
            R.style.Theme_SztuSchedule_Dialog
        }
        return AlertDialog.Builder(this, style)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 已声明 uiMode，这里不重建 Activity，只把新系统深浅推给页面。
        val dark = systemDark()
        if (!::webView.isInitialized) {
            applySystemBars(AppearanceStore.isDark(this))
            return
        }
        webView.post {
            webView.evaluateJavascript(
                "window.onSztuSystemTheme&&window.onSztuSystemTheme($dark)",
                null
            )
        }
    }

    private fun showExitConfirm() {
        themedDialog()
            .setTitle("退出应用")
            .setMessage("确定要返回桌面吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ -> finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 本地重算今日上节/这节/下节（不强制联网）；避免小组件跨日卡住
        ScheduleWidgetUpdater.updateAll(this)
        if (::webView.isInitialized) {
            pushState()
            pushAttendance()
            pushOtaState()
            pushSystemTheme()
        }
        OtaUpdater.check(this, silent = true)
    }

    override fun onDestroy() {
        OtaUpdater.removeListener(otaListener)
        super.onDestroy()
    }

    private fun pushOtaState() {
        if (!::webView.isInitialized) return
        val json = JSONObject.quote(OtaUpdater.snapshot(this).toString())
        webView.post {
            webView.evaluateJavascript("window.onOtaState && window.onOtaState($json)", null)
        }
    }

    private fun snapshotJson(): String {
        // 用内存快照，不再每次都 reloadFromDisk + 重新序列化（报告 J05）。
        // 磁盘读取只在启动/显式刷新时发生。
        val s = repo.snapshot.value
        val arr = JSONArray()
        s.courses.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("place", c.place)
                    .put("weeks", c.weeks)
                    .put("weekday", c.weekday)
                    .put("startSection", c.startSection)
                    .put("endSection", c.endSection)
                    .put("startTime", c.startTime)
                    .put("endTime", c.endTime)
                    .put("sectionLabel", c.sectionLabel)
                    .put("timeLabel", c.timeLabel)
            )
        }
        return JSONObject()
            .put("courses", arr)
            .put("term", s.term)
            .put("teachingWeek", TeachingWeek.current(this, s.term))
            .put("termStartMs", TeachingWeek.effectiveWeek1Monday(this, s.term).timeInMillis)
            .put("termStartSource", TeachingWeek.startSource(this, s.term).name)
            .put("syncedAt", s.syncedAt)
            .put("loggedIn", repo.isLoggedInHint() || s.courses.isNotEmpty())
            // 会话三态（报告 J03）：有缓存不等于认证仍然有效
            .put("sessionState", repo.sessionState().name)
            .put("needsResync", repo.needsResync())
            .put("emptyTerm", repo.isEmptyTerm())
            .put("version", BuildConfig.VERSION_NAME)
            .put("syncing", syncing)
            .put("bgOpacity", bgStore.getOpacity().toDouble())
            .put("bgLuminance", bgStore.getLuminance().toDouble())
            // 只发受限端点地址（十几到几十字节），不再发整张图的 Base64 data URL
            .put("bgImageUrl", bgStore.webImagePath())
            // 首页错误态分流：未登录 / 缓存可看 / 同步失败 / 解析需核对（J29-02）
            .put("lastSyncError", lastSyncError)
            .put("syncButtonLabel", syncButtonLabel())
            .put("jwxtDetail", jwxtDetail())
            .put("hwsysDetail", attendance.hwsysStatus())
            .put("syncDetail", lastUnifiedMessage)
            .put("syncStage", syncStage)
            .toString()
    }

    /** 带原生桥的 WebView 只允许本地 app assets（报告 J08）。 */
    private fun isAllowedLocalUrl(url: android.net.Uri): Boolean {
        val host = url.host.orEmpty()
        if (url.scheme == "https" && host == "appassets.androidplatform.net") return true
        if (url.scheme == "file" && url.path.orEmpty().startsWith("/android_asset/")) return true
        return false
    }

    private fun pushState() {
        val json = JSONObject.quote(snapshotJson())
        webView.post {
            webView.evaluateJavascript("window.onScheduleState && window.onScheduleState($json)", null)
        }
    }

    private fun pushAttendance() {
        if (!::webView.isInitialized || !::attendance.isInitialized) return
        val json = JSONObject.quote(attendance.snapshot(repo.snapshot.value.term, lastUnifiedMessage))
        webView.post {
            webView.evaluateJavascript("window.onAttendanceState && window.onAttendanceState($json)", null)
        }
    }

    private fun pushMessage(msg: String) {
        val q = JSONObject.quote(msg)
        webView.post {
            webView.evaluateJavascript("window.onScheduleMessage && window.onScheduleMessage($q)", null)
        }
    }

    private fun jwxtReady(): Boolean = CookieJar.hasSessionId(repo.cookieFor(JwxtUrls.SCHEDULE))

    private fun jwxtDetail(): String = when (repo.sessionState()) {
        SessionState.SERVER_VERIFIED -> "会话已验证"
        SessionState.CACHED_ONLY -> "有本地课表，会话未验证"
        SessionState.NONE -> if (jwxtReady()) "有会话，尚未验证" else "未登录"
    }

    private fun syncButtonLabel(): String = SyncText.buttonLabel(
        syncing = syncing,
        jwxtReady = jwxtReady(),
        hwsysReady = attendance.hwsysReady(),
        lastFailed = lastUnifiedMessage.contains("失败") || lastUnifiedMessage.contains("需重新登录"),
    )

    private fun startUnifiedSync(origin: SyncOrigin = SyncOrigin.FULL) {
        if (!claimSync(syncRunning)) return
        lifecycleScope.launch {
            try {
                runUnifiedSync(origin)
            } finally {
                releaseSync(syncRunning)
                syncing = false
                syncStage = ""
                pushState()
                pushAttendance()
            }
        }
    }

    private suspend fun promptJwxt(banner: String): Boolean = suspendCancellableCoroutine { cont ->
        runOnUiThread {
            if (!cont.isActive) return@runOnUiThread
            if (jwxtLoginSlot != null) {
                cont.resume(false)
                return@runOnUiThread
            }
            jwxtLoginSlot = cont
            loginLauncher.launch(Intent(this, LoginActivity::class.java).putExtra("banner", banner))
        }
    }

    private suspend fun promptHwsys(banner: String): Boolean = suspendCancellableCoroutine { cont ->
        runOnUiThread {
            if (!cont.isActive) return@runOnUiThread
            if (hwsysLoginSlot != null) {
                cont.resume(false)
                return@runOnUiThread
            }
            hwsysLoginSlot = cont
            hwsysLoginLauncher.launch(
                Intent(this, HwsysLoginActivity::class.java).putExtra(HwsysLoginActivity.EXTRA_BANNER, banner)
            )
        }
    }

    private fun authFailure(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("未登录") || text.contains("重新登录") || text.contains("会话已过期")
    }

    /**
     * 界面和登录页留在主线程。出勤请求在 Repository 里切到 IO。
     * 教务和教学辅助系统各走各的，一边取消或失败不会抹掉另一边。
     */
    private suspend fun runUnifiedSync(origin: SyncOrigin) {
        syncing = true
        val report = runUnified(
            origin,
            SyncHooks(
                jwxtHasSession = { jwxtReady() },
                scheduleErrorLooksLikeAuth = { authFailure(it) },
                promptJwxt = { promptJwxt("教务课表登录") },
                promptHwsys = { promptHwsys("教学辅助系统登录") },
                prepareAccess = { attendance.prepareAccess() },
                syncSchedule = { syncScheduleOnce() },
                syncAttendance = { attendance.sync(repo.snapshot.value.term) },
                onStage = { stage ->
                    syncStage = stage
                    pushState()
                    pushMessage(stage)
                },
            )
        )
        lastUnifiedMessage = report.message
        if (report.schedule.ok) lastSyncError = ""
        pushMessage(report.message)
    }

    private suspend fun syncScheduleOnce(): Pair<Boolean, String> {
        val result = scrapeInBackground()
        return result.fold(
            onSuccess = {
                lastSyncError = ""
                ScheduleWidgetUpdater.updateAll(this)
                true to ""
            },
            onFailure = {
                val message = it.message?.ifBlank { null } ?: "课表同步失败"
                lastSyncError = message
                false to "课表同步失败：$message"
            }
        )
    }

    private suspend fun scrapeInBackground() = suspendCancellableCoroutine { cont ->
        ScheduleScraper.scrape(this@MainActivity) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun getState(): String = snapshotJson()

        @JavascriptInterface
        fun login() {
            startUnifiedSync()
        }

        @JavascriptInterface
        fun refresh() {
            startUnifiedSync()
        }

        @JavascriptInterface
        fun syncData() {
            startUnifiedSync(SyncOrigin.FULL)
        }

        @JavascriptInterface
        fun syncData(origin: String) {
            startUnifiedSync(if (origin == "attendance") SyncOrigin.ATTENDANCE else SyncOrigin.FULL)
        }

        @JavascriptInterface
        fun getAttendanceState(): String {
            if (!::attendance.isInitialized) return "{}"
            return attendance.snapshot(repo.snapshot.value.term, lastUnifiedMessage)
        }

        @JavascriptInterface
        fun setAttendanceTerm(termId: String): String {
            if (!::attendance.isInitialized) return "{}"
            attendance.selectTerm(termId)
            val json = attendance.snapshot(repo.snapshot.value.term, lastUnifiedMessage)
            runOnUiThread { pushAttendance() }
            return json
        }

        @JavascriptInterface
        fun confirmAttendanceRange(termId: String, start: String, end: String): String {
            if (!::attendance.isInitialized) return "{}"
            attendance.confirmRange(termId, start, end)
            val json = attendance.snapshot(repo.snapshot.value.term, lastUnifiedMessage)
            runOnUiThread { pushAttendance() }
            return json
        }

        @JavascriptInterface
        fun pinWidget() {
            runOnUiThread { showWidgetGuide() }
        }

        @JavascriptInterface
        fun openWidgetHelp() {
            runOnUiThread { showWidgetGuide() }
        }

        @JavascriptInterface
        fun pickBackground() {
            runOnUiThread { imagePicker.launch("image/*") }
        }

        @JavascriptInterface
        fun clearBackground() {
            bgStore.clear()
            runOnUiThread {
                pushState()
                pushMessage("已清除背景")
            }
        }

        @JavascriptInterface
        fun setBackgroundOpacity(value: String) {
            val v = value.toFloatOrNull() ?: return
            bgStore.setOpacity(v)
            runOnUiThread { pushState() }
        }

        /**
         * 仅主题用途的受限桥（2.1.0）：前端报告「当前实际显示是深色吗」，
         * 原生据此调整系统栏图标明暗，并保存给裁切页这类自有原生界面复用。
         * 不做任何业务动作，也不改变任何课表/会话状态。
         */
        @JavascriptInterface
        fun setSystemBars(dark: Boolean) {
            AppearanceStore.setDark(this@MainActivity, dark)
            runOnUiThread { applySystemBars(dark) }
        }

        @JavascriptInterface
        fun getOtaState(): String = OtaUpdater.snapshot(this@MainActivity).toString()

        @JavascriptInterface
        fun checkUpdate() {
            OtaUpdater.check(this@MainActivity, silent = false)
        }

        @JavascriptInterface
        fun downloadUpdate(): String {
            return OtaUpdater.downloadAndInstall(this@MainActivity)
        }

        @JavascriptInterface
        fun openApkInBrowser(): String {
            return OtaUpdater.openApkInBrowser(this@MainActivity)
        }

        @JavascriptInterface
        fun openUnknownAppSettings() {
            OtaUpdater.openUnknownAppSettings(this@MainActivity)
        }

        /**
         * 校历校准（报告 J07）：传 "yyyy-MM-dd" 保存本学期第 1 周周一（任意一天会归一到周一）。
         * 返回最新快照 JSON，供前端立即刷新教学周。
         */
        @JavascriptInterface
        fun setTermStart(dateIso: String): String {
            val parsed = try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                fmt.isLenient = false
                fmt.parse(dateIso.trim())
            } catch (_: Exception) {
                null
            }
            if (parsed == null) return snapshotJson()
            val cal = java.util.Calendar.getInstance().apply { time = parsed }
            TeachingWeek.setCalibratedStart(this@MainActivity, repo.snapshot.value.term, cal)
            runOnUiThread { pushState() }
            return snapshotJson()
        }

        @JavascriptInterface
        fun clearTermStart(): String {
            TeachingWeek.clearCalibration(this@MainActivity)
            runOnUiThread { pushState() }
            return snapshotJson()
        }
    }

    private fun showWidgetGuide() {
        val result = WidgetPinHelper.requestPin(this)
        // 非小米机型仍尝试一键；小米直接给清晰手动步骤，避免假弹窗提示
        themedDialog()
            .setTitle("添加桌面小部件")
            .setMessage(
                """
                ${result.message}
                
                手动添加步骤（推荐）：
                1. 回到手机桌面，双指捏合（或长按空白处）
                2. 点「小部件」
                3. 搜索「SZTU教务」或「课表」
                4. 若搜不到：点「安卓小部件 / 系统小工具」，在应用列表里找「SZTU教务」拖到桌面
                
                小部件右上角「刷新」才会更新显示，不会后台耗电。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .setNeutralButton("打开应用信息") { _, _ ->
                WidgetPinHelper.openAppDetails(this)
            }
            .show()
        pushMessage(if (result.needManual) "请按弹窗步骤手动添加小部件" else result.message)
    }
}
