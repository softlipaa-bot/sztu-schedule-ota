package com.sztu.schedule.duifene

import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL

/** App 接口走 okhttp 标识，网页接口不跟随系统代理，也不跟随重定向。 */
class AndroidDfTransport(
    private val settings: DuifeneSettings,
) : DfTransport {
    val idleScope = RequestScope()
    private val threadScope = ThreadLocal<RequestScope>()

    fun newScope(): RequestScope = RequestScope()

    fun <T> inScope(scope: RequestScope, block: () -> T): T {
        val previous = threadScope.get()
        threadScope.set(scope)
        try {
            return block()
        } finally {
            if (previous == null) threadScope.remove() else threadScope.set(previous)
        }
    }

    override fun exchange(call: DfCall): DfReply = exchange(call, threadScope.get() ?: idleScope)

    fun exchange(call: DfCall, scope: RequestScope): DfReply {
        if (!allowed(call.url)) throw TransportException("只接受 HTTPS", uncertain = false, sent = false)
        if (scope.isCancelled()) throw TransportException("请求已取消", uncertain = false, sent = false)
        val connection = (URL(call.url).openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
            requestMethod = call.method
            connectTimeout = settings.requestTimeoutMs
            readTimeout = settings.requestTimeoutMs
            instanceFollowRedirects = call.followRedirects
            call.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (call.body != null) doOutput = true
        }
        val id = scope.open(connection)
        var sent = false
        try {
            if (scope.isCancelled()) throw TransportException("请求已取消", uncertain = false, sent = false)
            if (call.body != null) {
                sent = call.method.equals("POST", ignoreCase = true)
                connection.outputStream.use { it.write(call.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status == -1 && call.method.equals("POST", ignoreCase = true)) {
                throw TransportException("没有拿到提交结果", uncertain = true, sent = true)
            }
            val headers = mutableListOf<Pair<String, String>>()
            connection.headerFields?.forEach { (key, values) ->
                if (key != null) values.forEach { headers += key to it }
            }
            return DfReply(status, text, headers)
        } catch (error: TransportException) {
            throw error
        } catch (error: SocketTimeoutException) {
            val uncertain = submissionUncertain(call.method, sent, scope.isCancelled())
            throw TransportException("网络超时", uncertain = uncertain, sent = sent, cause = error)
        } catch (error: IOException) {
            val uncertain = submissionUncertain(call.method, sent, scope.isCancelled())
            throw TransportException(
                if (scope.isCancelled() && !sent) "请求已取消" else "网络请求失败",
                uncertain = uncertain,
                sent = sent,
                cause = error,
            )
        } finally {
            scope.close(id)
        }
    }

    private fun allowed(url: String): Boolean {
        if (url.startsWith("https://")) return true
        return url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
    }
}
