package com.sztu.schedule.duifene

/**
 * 每一轮监听有自己的编号。旧轮结束不能把新一轮标成已停止。
 */
class WatchRunGate {
    var activeId: Long = 0L
        private set
    var phase: String = "idle"
        private set

    fun tryStart(): Long? = synchronized(this) {
        if (phase == "starting" || phase == "running" || phase == "retrying" || phase == "stopping") return null
        activeId += 1L
        phase = "starting"
        activeId
    }

    fun markRunning(id: Long): Boolean = synchronized(this) {
        if (activeId != id || phase != "starting") return false
        phase = "running"
        true
    }

    fun markRetrying(id: Long): Boolean = synchronized(this) {
        if (activeId != id) return false
        if (phase != "running" && phase != "retrying") return false
        phase = "retrying"
        true
    }

    fun markRunningAgain(id: Long): Boolean = synchronized(this) {
        if (activeId != id || phase != "retrying") return false
        phase = "running"
        true
    }

    fun requestStop(id: Long): Boolean = synchronized(this) {
        if (activeId != id) return false
        if (phase == "idle" || phase == "stopped") return false
        phase = "stopping"
        true
    }

    fun finish(id: Long, terminal: String): Boolean = synchronized(this) {
        if (activeId != id) return false
        if (phase == "idle" || phase == "stopped" || phase == terminal) return false
        phase = terminal
        true
    }

    fun isCurrent(id: Long): Boolean = synchronized(this) {
        activeId == id && phase != "idle" && phase != "stopped" && phase != "stopping"
    }

    fun canStart(): Boolean = synchronized(this) {
        phase != "starting" && phase != "running" && phase != "retrying" && phase != "stopping"
    }
}

/** 前台服务只停止仍然绑定的那一轮。 */
class RunServiceBinding {
    private var bound = 0L

    fun bind(id: Long) = synchronized(this) { bound = id }

    fun boundId(): Long = synchronized(this) { bound }

    fun stopIfBound(id: Long, stop: () -> Unit): Boolean = synchronized(this) {
        if (bound != id) return false
        bound = 0L
        stop()
        true
    }
}

/** 点击开始后立刻返回，认证放到调用方提供的执行器里。 */
class AsyncStart(
    private val executor: java.util.concurrent.Executor,
    private val gate: WatchRunGate,
) {
    fun request(work: (Long) -> Unit): String {
        val id = gate.tryStart() ?: return "busy"
        executor.execute { work(id) }
        return "starting"
    }
}
