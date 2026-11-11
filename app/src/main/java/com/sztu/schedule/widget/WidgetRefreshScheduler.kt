package com.sztu.schedule.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sztu.schedule.data.ScheduleClock
import com.sztu.schedule.data.ScheduleRepository
import com.sztu.schedule.data.SectionTimes
import com.sztu.schedule.data.TeachingWeek
import java.util.Calendar

/**
 * 按深技大节次边界唤醒重绘小组件（上节/这节/下节）。
 * AppWidget updatePeriodMillis=0 时系统不会自动刷新，跨日/上课点会卡住。
 *
 * 必须覆盖全部单节/连堂起止（含「1-3 节」结束 11:00），并叠加今日实际课程时刻。
 */
object WidgetRefreshScheduler {
    private const val REQ = 71001

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pending(context)
        val triggerAt = nextBoundaryMillis(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleWidgetReceiver::class.java)
            .setAction(ScheduleWidgetReceiver.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextBoundaryMillis(context: Context): Long {
        val now = Calendar.getInstance()
        val candidates = ArrayList<Long>()
        for ((h, m) in SectionTimes.allBoundaryHm()) {
            addCandidate(now, candidates, h, m)
        }
        // 今日真实课程起止（解析异常表也覆盖）
        try {
            val snap = ScheduleRepository.get(context).reloadFromDisk()
            val week = TeachingWeek.current(context, snap.term)
            val today = ScheduleClock.buildTodayView(snap.courses, week).today
            for (c in today) {
                parseHmPair(c.startTime)?.let { (h, m) -> addCandidate(now, candidates, h, m) }
                parseHmPair(c.endTime)?.let { (h, m) -> addCandidate(now, candidates, h, m) }
            }
        } catch (_: Exception) {
        }
        // 兜底：最多 12 分钟后再刷（Doze 下也别拖太久）
        candidates.add(now.timeInMillis + 12 * 60_000L)
        return candidates.minOrNull() ?: (now.timeInMillis + 12 * 60_000L)
    }

    private fun parseHmPair(hm: String): Pair<Int, Int>? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(hm.trim()) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun addCandidate(now: Calendar, out: ArrayList<Long>, h: Int, minute: Int) {
        val c = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        out.add(c.timeInMillis)
    }
}

/** 开机后重新挂上节次闹钟 */
class WidgetBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_DATE_CHANGED
        ) {
            ScheduleWidgetUpdater.updateAll(context)
            WidgetRefreshScheduler.scheduleNext(context)
        }
    }
}
