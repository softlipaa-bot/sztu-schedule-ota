package com.sztu.schedule.duifene

/** 提交结果未知的活动。重新开始监听不能把它忘掉再直接重发。 */
class PendingLedger {
    private val keys = linkedSetOf<String>()

    fun mark(key: String) = synchronized(this) { keys += key }

    fun remove(key: String) = synchronized(this) { keys -= key }

    fun snapshot(): Set<String> = synchronized(this) { keys.toSet() }

    fun clear() = synchronized(this) { keys.clear() }
}
