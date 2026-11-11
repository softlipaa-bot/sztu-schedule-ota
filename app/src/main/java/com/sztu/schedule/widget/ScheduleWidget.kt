package com.sztu.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sztu.schedule.MainActivity
import com.sztu.schedule.R
import com.sztu.schedule.data.Course
import com.sztu.schedule.data.ScheduleClock
import com.sztu.schedule.data.ScheduleRepository
import com.sztu.schedule.data.TeachingWeek

/** 系统放置 / 用户点刷新 / 节次闹钟 / 打开 App 时重绘 */
class ScheduleWidgetReceiver : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        ScheduleWidgetUpdater.updateAll(context)
        WidgetRefreshScheduler.scheduleNext(context)
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshScheduler.scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshScheduler.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            ScheduleWidgetUpdater.updateAll(context)
            WidgetRefreshScheduler.scheduleNext(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.sztu.schedule.ACTION_WIDGET_REFRESH"
    }
}

object ScheduleWidgetUpdater {
    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, ScheduleWidgetReceiver::class.java))
        if (ids.isEmpty()) {
            WidgetRefreshScheduler.cancel(context)
            return
        }
        val snap = ScheduleRepository.get(context).reloadFromDisk()
        val week = TeachingWeek.current(context, snap.term)
        val view = ScheduleClock.buildTodayView(snap.courses, week)
        val weekLabel = "第 $week 周"
        val dayNames = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val day = dayNames.getOrElse(ScheduleClock.todayWeekday()) { "今日" }

        for (id in ids) {
            val rv = RemoteViews(context.packageName, R.layout.widget_schedule)
            rv.setTextViewText(R.id.w_day, "$day · 今日")
            rv.setTextViewText(R.id.w_week, weekLabel)

            bindSlot(rv, R.id.prev_tag, R.id.prev_name, R.id.prev_info, "上节课", view.previous)
            val currentOrFocus = view.current ?: view.next
            val currentTag = if (view.current != null) "这节课" else if (view.next != null) "下一节" else "这节课"
            bindSlot(rv, R.id.now_tag, R.id.now_name, R.id.now_info, currentTag, currentOrFocus, empty = "今日暂无课程")
            val nextForBelow = when {
                view.current != null -> view.next
                view.next != null -> view.today.dropWhile { it.id != view.next.id }.drop(1).firstOrNull()
                else -> null
            }
            bindSlot(rv, R.id.next_tag, R.id.next_name, R.id.next_info, "下节课", nextForBelow)

            val rowIds = listOf(
                Triple(R.id.row1, R.id.row1_time, R.id.row1_name),
                Triple(R.id.row2, R.id.row2_time, R.id.row2_name),
                Triple(R.id.row3, R.id.row3_time, R.id.row3_name),
                Triple(R.id.row4, R.id.row4_time, R.id.row4_name),
            )
            rowIds.forEachIndexed { idx, (row, timeId, nameId) ->
                val c = view.today.getOrNull(idx)
                if (c == null) {
                    rv.setViewVisibility(row, android.view.View.GONE)
                } else {
                    rv.setViewVisibility(row, android.view.View.VISIBLE)
                    rv.setTextViewText(timeId, "${c.startTime}\n${c.endTime}")
                    rv.setTextViewText(nameId, "${c.name}\n${c.place} · ${c.teacher}")
                }
            }

            val open = PendingIntent.getActivity(
                context, id, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.widget_root, open)

            val refresh = PendingIntent.getBroadcast(
                context,
                3000 + id,
                Intent(context, ScheduleWidgetReceiver::class.java).setAction(ScheduleWidgetReceiver.ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.btn_refresh, refresh)

            mgr.updateAppWidget(id, rv)
        }
        WidgetRefreshScheduler.scheduleNext(context)
    }

    private fun bindSlot(
        rv: RemoteViews,
        tagId: Int,
        nameId: Int,
        infoId: Int,
        tag: String,
        course: Course?,
        empty: String = "暂无",
    ) {
        rv.setTextViewText(tagId, tag)
        if (course == null) {
            rv.setTextViewText(nameId, empty)
            rv.setTextViewText(infoId, "")
        } else {
            rv.setTextViewText(nameId, course.name)
            rv.setTextViewText(infoId, "${course.timeLabel} · ${course.place}\n${course.teacher}")
        }
    }
}
