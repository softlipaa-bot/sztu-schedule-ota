package com.sztu.schedule.data.hwsys

import android.os.Looper
import android.os.NetworkOnMainThreadException

/** 真实网络调用前记下线程。跑在主线程就直接失败，不用 StrictMode 把问题藏起来。 */
object NetworkThreadGuard {
    @Volatile var lastThreadName: String = ""
    @Volatile var lastWasMain: Boolean = false
    @Volatile var lastStage: String = ""

    fun mark(stage: String) {
        lastStage = stage
        lastThreadName = Thread.currentThread().name
        val main = try {
            Looper.getMainLooper()?.thread
        } catch (_: Throwable) {
            null
        }
        val onMain = main != null && main == Thread.currentThread()
        lastWasMain = onMain
        if (onMain) throw NetworkOnMainThreadException()
    }
}
