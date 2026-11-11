package com.sztu.schedule.data

import java.net.URI

/**
 * Cookie 作用域工具（报告 J03 / 复核报告 R07）。
 *
 * 规则：
 * - 只有在**同一个 host** 内才合并多个 path 的 cookie 字符串；
 * - SSO（auth.sztu.edu.cn）与教务（jwxt.sztu.edu.cn）的会话分开保存，
 *   绝不把另一个域的凭证拼进教务请求；
 * - 同名 cookie 不再被压成"全域一个 header"：按 path 作用域分别保存，
 *   请求时取**最具体的匹配路径**（R07）；
 * - host 判断使用精确已知域名，不用 endsWith（避免相似前缀域被当成受信域）。
 */
object CookieJar {
    /** 已知受信 host（精确匹配，不做后缀匹配）。 */
    const val HOST_JWXT = "jwxt.sztu.edu.cn"
    const val HOST_AUTH = "auth.sztu.edu.cn"

    fun isKnownHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase().orEmpty()
        return h == HOST_JWXT || h == HOST_AUTH
    }

    /**
     * 解析 "a=1; b=2" → 有序 name/value 对。
     * 同名多次出现时保留**第一次**（CookieManager.getCookie(url) 已按更具体 path 优先返回）。
     */
    fun parse(header: String?): List<Pair<String, String>> {
        if (header.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, String>>()
        for (raw in header.split(";")) {
            val kv = raw.trim()
            if (kv.isEmpty() || !kv.contains("=")) continue
            val name = kv.substringBefore("=").trim()
            val value = kv.substringAfter("=").trim()
            if (name.isEmpty()) continue
            if (seen.add(name)) out += name to value
        }
        return out
    }

    /** 合并同一 host、同一 path 下的 cookie 结果；不跨 host 使用。 */
    fun headerForSameHost(parts: List<String?>): String {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (part in parts) {
            for ((name, value) in parse(part)) {
                if (seen.add(name)) out += "$name=$value"
            }
        }
        return out.joinToString("; ")
    }

    // ---- R07：按 path 作用域保存与选择 ----

    /** path → Cookie header（同一 host 内，按 path 作用域分开）。 */
    fun encodeScopes(scopes: Map<String, String>): String {
        if (scopes.isEmpty()) return ""
        val sb = StringBuilder()
        for ((path, header) in scopes) {
            if (header.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(path.replace("\n", "")).append('\t').append(header.replace("\n", ""))
        }
        return sb.toString()
    }

    fun decodeScopes(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (line in raw.split('\n')) {
            if (line.isBlank() || !line.contains('\t')) continue
            val path = line.substringBefore('\t').trim()
            val header = line.substringAfter('\t').trim()
            if (path.isEmpty() || header.isEmpty()) continue
            out += path to header
        }
        return out
    }

    /**
     * 按请求路径选择 Cookie：取"是请求路径前缀"的作用域中**最长**的那个
     * （与浏览器的 path 匹配规则一致：/jsxsd/ 优先于 /）。
     */
    fun pickForPath(scopes: List<Pair<String, String>>, requestPath: String?): String {
        val path = requestPath?.takeIf { it.isNotBlank() } ?: "/"
        var best = ""
        var bestLen = -1
        for ((scopePath, header) in scopes) {
            if (header.isBlank()) continue
            val matches = when {
                scopePath == "/" -> true
                path == scopePath -> true
                path.startsWith(if (scopePath.endsWith("/")) scopePath else "$scopePath/") -> true
                else -> false
            }
            if (matches && scopePath.length > bestLen) {
                bestLen = scopePath.length
                best = header
            }
        }
        return best
    }

    /** 教务请求是否带上了会话 id。 */
    fun hasSessionId(header: String?): Boolean {
        return parse(header).any { it.first.equals("JSESSIONID", ignoreCase = true) && it.second.isNotBlank() }
    }

    fun hostOf(url: String): String {
        return try {
            URI(url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun pathOf(url: String): String {
        return try {
            URI(url).path?.takeIf { it.isNotBlank() } ?: "/"
        } catch (_: Exception) {
            "/"
        }
    }
}

/** 会话状态：有缓存不等于认证仍然有效（报告 J03）。 */
enum class SessionState { NONE, CACHED_ONLY, SERVER_VERIFIED }

object SessionStateResolver {
    fun resolve(
        nowMs: Long,
        verifiedAtMs: Long,
        verifiedTtlMs: Long,
        hasCookie: Boolean,
        hasCachedCourses: Boolean,
    ): SessionState {
        val fresh = verifiedAtMs > 0 && nowMs >= verifiedAtMs && nowMs - verifiedAtMs <= verifiedTtlMs
        return when {
            hasCookie && fresh -> SessionState.SERVER_VERIFIED
            hasCachedCourses -> SessionState.CACHED_ONLY
            else -> SessionState.NONE
        }
    }
}
