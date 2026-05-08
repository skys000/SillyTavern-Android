package com.jiuguan.sillytavern

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * Foreground Service that keeps the Node.js SillyTavern server running.
 * Shows a persistent notification while active.
 */
class NodeService : Service() {

    companion object {
        private const val TAG = "NodeService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sillytavern_service"

        private const val ACTION_START = "com.jiuguan.sillytavern.START"
        private const val ACTION_STOP = "com.jiuguan.sillytavern.STOP"

        /** Convenience: start the service */
        fun start(context: Context) {
            val intent = Intent(context, NodeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Convenience: stop the service */
        fun stop(context: Context) {
            val intent = Intent(context, NodeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Check if the service is currently running */
        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var nodeManager: NodeManager
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watchdogJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        nodeManager = NodeManager(applicationContext)
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("正在启动酒馆..."))
                startNodeServer()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        watchdogJob?.cancel()
        serviceScope.cancel()
        nodeManager.stopServer()
        Log.i(TAG, "Service destroyed, server stopped")
        super.onDestroy()
    }

    private fun startNodeServer() {
        serviceScope.launch {
            try {
                if (!nodeManager.isReady()) {
                    updateNotification("Node.js 或 SillyTavern 未就绪")
                    Log.e(TAG, "Cannot start: not ready")
                    return@launch
                }

                val error = nodeManager.startServer()
                if (error == null) {
                    isRunning = true
                    updateNotification("SillyTavern 运行中 · ${nodeManager.serverUrl}")
                    startWatchdog()
                    Log.i(TAG, "Server started successfully")
                } else {
                    updateNotification("启动失败")
                    Log.e(TAG, "Failed to start server: $error")
                }
            } catch (e: Exception) {
                updateNotification("启动出错: ${e.message}")
                Log.e(TAG, "Error starting server", e)
            }
        }
    }

    /** Monitor the Node.js process and restart if it crashes */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (isActive) {
                delay(5000) // Check every 5 seconds
                if (!nodeManager.isProcessAlive()) {
                    Log.w(TAG, "Node.js process died, restarting...")
                    updateNotification("正在重启服务...")
                    delay(1000)
                    val restartError = nodeManager.startServer()
                    if (restartError == null) {
                        updateNotification("SillyTavern 运行中 · ${nodeManager.serverUrl}")
                    } else {
                        updateNotification("重启失败，请重新打开APP")
                        isRunning = false
                        break
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SillyTavern 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SillyTavern 后台运行通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        // Clicking notification opens the app
        val openIntent = Intent(this, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop action
        val stopIntent = Intent(this, NodeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SillyTavern 酒馆")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }
}
