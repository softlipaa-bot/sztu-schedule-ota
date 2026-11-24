package com.sztu.schedule.duifene

import java.net.URI
import java.util.Base64

fun interface QrImageDecoder {
    /** 只返回图中的文本，不访问这些文本里的链接。 */
    fun read(bytes: ByteArray): List<String>
}

private val QR_TOKEN = Regex("[0-9A-Fa-f]{48}")

fun decodeQrToken(encoded: String?, decoder: QrImageDecoder): String {
    if (encoded == null) throw AppApiError("网页二维码图片数据异常")
    if (encoded.length > DuifenePolicy.MAX_QR_TEXT_CHARS) throw AppApiError("网页二维码图片数据异常")
    val bytes = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        throw AppApiError("网页二维码图片无法解析")
    }
    val texts = try {
        decoder.read(bytes)
    } catch (error: AppApiError) {
        throw error
    } catch (_: Exception) {
        throw AppApiError("网页二维码图片无法解析")
    }
    if (texts.size != 1) throw AppApiError("网页二维码未识别到唯一有效内容")
    return acceptQrText(texts[0])
}

fun acceptQrText(text: String): String {
    val uri = try {
        URI(text)
    } catch (_: Exception) {
        throw AppApiError("网页二维码格式不受支持，未提交")
    }
    val query = splitQuery(uri.rawQuery)
    val token = query["e"].orEmpty()
    val onlyId = query["OnlyId"].orEmpty()
    if (uri.scheme != "https" ||
        uri.host != "www.duifene.com" ||
        uri.path != "/G.aspx" ||
        !uri.rawFragment.isNullOrEmpty() ||
        query.keys != setOf("OnlyId", "e") ||
        onlyId != listOf("2") ||
        token.size != 1 ||
        !QR_TOKEN.matches(token[0])
    ) {
        throw AppApiError("网页二维码格式不受支持，未提交")
    }
    return token[0]
}

fun splitQuery(raw: String?): LinkedHashMap<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    if (raw.isNullOrEmpty()) return LinkedHashMap()
    for (part in raw.split("&")) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        val key = urlDecode(if (eq >= 0) part.substring(0, eq) else part)
        val value = urlDecode(if (eq >= 0) part.substring(eq + 1) else "")
        out.getOrPut(key) { mutableListOf() }.add(value)
    }
    return LinkedHashMap(out.mapValues { it.value.toList() })
}

private fun urlDecode(raw: String): String =
    java.net.URLDecoder.decode(raw, Charsets.UTF_8)
