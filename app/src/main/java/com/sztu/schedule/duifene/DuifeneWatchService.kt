package com.sztu.schedule.duifene

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 用户点「开始监听」后才启动。不随开机复活。
 * dataSync 在系统超时时立刻停止；有定位权限时同时声明 location，便于退到后台仍读取真实位置。
 */
class DuifeneWatchService : Service() {
    private var boundRun = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getLongExtra(EXTRA_RUN, boundRun) ?: boundRun
        if (intent?.action == ACTION_STOP) {
            DuifeneRuntime.peek()?.stopFromService(runId, "stopped")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (runId != 0L) boundRun = runId
        ensureChannel(this)
        val runtime = DuifeneRuntime.peek()
        val notification = runtime?.buildNotification(this)
            ?: androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.sztu.schedule.R.drawable.ic_stat_duifene)
                .setContentTitle("对分易正在监听")
                .setContentText("正在准备")
                .build()
        val type = runtime?.serviceTypes() ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (error: Exception) {
            runtime?.onServiceStartFailed(boundRun)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DuifeneRuntime.peek()?.onServiceDestroyed(boundRun)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int) {
        timeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        timeout()
    }

    private fun timeout() {
        DuifeneRuntime.peek()?.onSystemTimeout(boundRun)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "sztu_duifene_listen"
        const val NOTIFICATION_ID = 2102
        const val ACTION_STOP = "com.sztu.schedule.duifene.STOP"
        const val EXTRA_RUN = "runId"

        fun start(context: Context, runId: Long) {
            val intent = Intent(context, DuifeneWatchService::class.java).putExtra(EXTRA_RUN, runId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DuifeneWatchService::class.java))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, "对分易监听", NotificationManager.IMPORTANCE_LOW)
            channel.description = "显示正在监听的课程，并可以停止监听"
            manager.createNotificationChannel(channel)
        }
    }
}
