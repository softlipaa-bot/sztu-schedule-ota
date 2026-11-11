package com.sztu.schedule.data

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream

/**
 * 受限背景图端点（审查增量 J29-01）。
 *
 * 背景图不再以 `data:image/jpeg;base64,...` 的形式塞进 `getState()` / `onScheduleState`
 * 的 JSON：那样每推一次状态就要跨 JsBridge 复制一遍整张图的 Base64 字符串，并且
 * WebView 侧还要额外持有一份解码后的位图。改为由 WebView 直接向本地端点取图：
 *
 * ```
 * https://appassets.androidplatform.net/bg/schedule_bg.jpg?v=<stamp>
 * ```
 *
 * 安全边界（**不得扩成任意文件读取**）：
 * 1. 文件名白名单只有一个 [ASSET_NAME]，请求路径必须与之**完全相等**
 *    （[isAllowedPath]），因此 `..`、`%2e%2e`、绝对路径、子目录、`;`/`?` 变形
 *    与大小写变体全部不会命中；
 * 2. 只从构造时给定的单一目录（`filesDir`）读取，文件名不参与路径拼接推导；
 * 3. 规范化后仍必须落在该目录内（[BackgroundImageHandler.handle] 二次校验）；
 * 4. 响应只声明 `image/jpeg`，并带 `nosniff`；
 * 5. 只有文件存在且长度 > 0 才回 200，其余一律回 null（交给 WebView 当 404）。
 *
 * URL 里的 `v=<stamp>` 是 mtime+size 派生的版本号，换图后 URL 变化，
 * 浏览器缓存自然失效；文件不变时 URL 恒定，WebView 直接命中磁盘缓存。
 */
object BackgroundImageEndpoint {
    /** WebViewAssetLoader 上挂载的路径前缀。 */
    const val PREFIX = "/bg/"

    /** 白名单文件名（BackgroundStore 写盘用的就是这个名字）。 */
    const val ASSET_NAME = "schedule_bg.jpg"

    /** 版本号查询参数名。 */
    const val VERSION_PARAM = "v"

    /**
     * 纯函数：`WebViewAssetLoader.PathHandler.handle(path)` 收到的**相对路径**
     * （已去掉 [PREFIX]）是否命中白名单。
     *
     * 只接受与 [ASSET_NAME] 完全相等的字符串；额外容忍一个前导 `/`（不同
     * androidx.webkit 版本对去掉前缀后是否保留斜杠有差异）。两种接受形式都
     * 指向同一个固定文件名，不会因为容忍斜杠而放开路径穿越。
     */
    fun isAllowedPath(path: String?): Boolean {
        val raw = path ?: return false
        val name = if (raw.startsWith("/")) raw.substring(1) else raw
        return name == ASSET_NAME
    }

    /** 带版本号的取图 URL；[stamp] <= 0（没有背景图）时返回空串。 */
    fun versionedUrl(stamp: Long): String {
        if (stamp <= 0L) return ""
        return "$PREFIX$ASSET_NAME?$VERSION_PARAM=$stamp"
    }

    /** 磁盘缓存头：URL 带版本号，文件内容与 URL 一一对应，可长期缓存。 */
    fun cacheControl(): String = "public, max-age=31536000, immutable"

    fun contentType(): String = "image/jpeg"
}

/**
 * 只服务 `schedule_bg.jpg` 一个文件的 [WebViewAssetLoader.PathHandler]。
 *
 * @param dir 只允许读取的目录（`context.filesDir`）。
 */
class BackgroundImageHandler(private val dir: File) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        if (!BackgroundImageEndpoint.isAllowedPath(path)) return null
        return try {
            val root = dir.canonicalFile
            val target = File(root, BackgroundImageEndpoint.ASSET_NAME).canonicalFile
            // 二次校验：规范化之后仍必须直接位于 root 下（防御白名单之外的任何拼接）
            if (target.parentFile != root) return null
            if (!target.isFile || target.length() <= 0L) return null
            WebResourceResponse(
                BackgroundImageEndpoint.contentType(),
                null,
                200,
                "OK",
                mapOf(
                    "Cache-Control" to BackgroundImageEndpoint.cacheControl(),
                    "X-Content-Type-Options" to "nosniff",
                ),
                FileInputStream(target),
            )
        } catch (_: Exception) {
            null
        }
    }
}
