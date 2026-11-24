package com.sztu.schedule.duifene

import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 一次监听或一次独立请求的取消范围。
 * 停掉某个范围只断开它自己的连接，不会封死之后的课程刷新。
 */
class RequestScope {
    private val cancelled = AtomicBoolean(false)
    private val live = ConcurrentHashMap<Long, HttpURLConnection>()
    private val ids = AtomicLong()

    fun isCancelled(): Boolean = cancelled.get()

    fun inflight(): Int = live.size

    fun cancel() {
        cancelled.set(true)
        live.values.toList().forEach { connection ->
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun open(connection: HttpURLConnection): Long {
        if (cancelled.get()) {
            connection.disconnect()
            throw TransportException("请求已取消", uncertain = false, sent = false)
        }
        val id = ids.incrementAndGet()
        live[id] = connection
        if (cancelled.get()) {
            live.remove(id)
            connection.disconnect()
            throw TransportException("请求已取消", uncertain = false, sent = false)
        }
        return id
    }

    fun close(id: Long) {
        live.remove(id)?.let { connection ->
            try {
                connection.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}

/** POST 在可能已经发出之后失败，才算提交结果未知。查询和未发出的取消都不是。 */
fun submissionUncertain(method: String, sent: Boolean, cancelled: Boolean): Boolean {
    if (!method.equals("POST", ignoreCase = true)) return false
    if (cancelled && !sent) return false
    return sent
}
