package com.sztu.schedule

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.sztu.schedule.widget.ScheduleWidgetUpdater
import com.sztu.schedule.widget.WidgetRefreshScheduler

class ScheduleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 进程起来就挂上节次闹钟，并立刻重绘一次小组件
        try {
            ScheduleWidgetUpdater.updateAll(this)
            WidgetRefreshScheduler.scheduleNext(this)
        } catch (_: Exception) {
        }
        LegacyAttendanceCleanup.runOnce(this)
    }
}

/**
 * 2.1.0 起应用不再包含考勤（对分易）功能，对应的前台服务、通知渠道与 Python 引擎
 * 都已经从代码里删除。老版本升级上来时，系统侧可能还留着旧的通知渠道/通知：
 * 这里在升级后的首次进程启动做一次清理 —— **不新增任何后台服务**，
 * 只取消已知的通知 id 并删除已知渠道。
 */
internal object LegacyAttendanceCleanup {
    private const val TAG = "LegacyAttendanceCleanup"
    private const val PREFS = "sztu_cleanup"
    private const val KEY_DONE = "attendance_removed_v210"

    /** 旧版 WatchForegroundService 的常量（2.0.x 历史值，不要复用给新功能）。 */
    private const val LEGACY_CHANNEL_ID = "sztu_watch"
    private const val LEGACY_NOTIFICATION_ID = 1001

    fun runOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.cancel(LEGACY_NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager?.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                manager?.deleteNotificationChannelGroup(LEGACY_CHANNEL_ID)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "legacy attendance notification cleanup failed: ${t.message}")
        }
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }
}
