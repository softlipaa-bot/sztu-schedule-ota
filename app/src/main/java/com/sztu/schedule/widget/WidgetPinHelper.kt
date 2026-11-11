package com.sztu.schedule.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings

object WidgetPinHelper {
    data class PinResult(
        val requested: Boolean,
        val message: String,
        val needManual: Boolean,
    )

    fun isXiaomi(): Boolean {
        val brand = Build.MANUFACTURER.lowercase()
        val model = Build.BRAND.lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") ||
            model.contains("xiaomi") || model.contains("redmi") ||
            brand.contains("poco") || Build.DISPLAY.contains("HyperOS", ignoreCase = true) ||
            Build.DISPLAY.contains("MIUI", ignoreCase = true)
    }

    fun requestPin(context: Context): PinResult {
        if (Build.VERSION.SDK_INT < 26) {
            return PinResult(false, "系统版本过低，请手动添加小部件", true)
        }
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) {
            return PinResult(false, "当前桌面不支持一键添加，请按说明手动添加", true)
        }
        // 澎湃/小米经常返回 true 但不弹窗，直接引导手动添加更靠谱
        if (isXiaomi()) {
            return PinResult(false, "澎湃系统需手动添加小部件（系统常拦截一键添加）", true)
        }
        val provider = ComponentName(context, ScheduleWidgetReceiver::class.java)
        val success = PendingIntent.getBroadcast(
            context,
            2001,
            Intent(context, ScheduleWidgetReceiver::class.java).setAction(ScheduleWidgetReceiver.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return try {
            val ok = mgr.requestPinAppWidget(provider, Bundle(), success)
            if (ok) PinResult(true, "若出现系统弹窗请点确认；若无弹窗请手动添加", true)
            else PinResult(false, "一键添加失败，请手动添加", true)
        } catch (_: Exception) {
            PinResult(false, "一键添加失败，请手动添加", true)
        }
    }

    /** 尝试打开应用详情，便于用户从桌面小部件入口找本应用 */
    fun openAppDetails(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }
}
