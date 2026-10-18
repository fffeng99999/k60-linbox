// SPDX-License-Identifier: GPL-2.0-only
package com.k60linbox.manager.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.k60linbox.manager.MainActivity
import com.k60linbox.manager.runtime.Linbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 轮询设备端的 events.log，把容器的「退出 / 崩溃」推成系统通知。
 *
 * 只有在设置里显式开启后才运行（前台服务，常驻一条低优先级通知）。
 * 事件是运行时 `linbox` 自己写进 events.log 的，所以这里不需要轮询每个容器。
 */
class ContainerMonitorService : Service() {

    companion object {
        private const val CHANNEL_ONGOING = "linbox-monitor"
        private const val CHANNEL_EVENTS = "linbox-events"
        private const val ONGOING_ID = 1001
        private const val POLL_INTERVAL_MS = 15_000L
        private const val EVENTS = "/data/local/linbox/events.log"
        private val containerEventTypes = setOf("crash", "exit")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ContainerMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ContainerMonitorService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastCount = -1
    private var eventId = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, ongoingNotification())
        if (pollJob == null) pollJob = scope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            pollOnce()
            delay(POLL_INTERVAL_MS)
        }
    }

    /** events.log 只追加，所以按行数增量读，避免每轮都解析整个文件。 */
    private fun pollOnce() {
        val count = Linbox.shell("wc -l < $EVENTS").output.trim().toIntOrNull() ?: return
        if (count <= 0) return
        if (lastCount < 0 || count < lastCount) {
            // 第一轮或日志被清过：只记住当前位置，不补发历史通知
            lastCount = count
            return
        }
        if (count == lastCount) return
        val text = Linbox.shell("tail -n ${count - lastCount} $EVENTS").output
        lastCount = count
        text.lineSequence().forEach { handleLine(it) }
    }

    private fun handleLine(line: String) {
        val fields = line.split('\t')
        if (fields.size < 3) return
        if (fields[1].trim() != "container") return
        val tokens = fields[2].trim().split(' ')
        val action = tokens.firstOrNull() ?: return
        if (action !in containerEventTypes) return
        val name = tokens.getOrNull(1) ?: "容器"
        val code = tokens.firstOrNull { it.startsWith("rc=") }?.removePrefix("rc=") ?: "?"
        val title = if (action == "crash") "$name 异常退出" else "$name 已退出"
        notifyEvent(title, "退出码 $code")
    }

    private fun notifyEvent(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(eventId++, notification)
    }

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("k60-linbox 监控")
            .setContentText("正在监视容器退出与崩溃")
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "容器监控", NotificationManager.IMPORTANCE_MIN),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "容器事件", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}
