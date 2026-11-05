package com.seb.ebikecanmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.seb.ebikecanmonitor.ble.BleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while a BLE connection is active
 * (connecting or connected). It owns nothing: the connection lives in [Monitor].
 * It stops itself, and its notification disappears, as soon as the link is down.
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.seb.ebikecanmonitor.action.STOP"

        /** Starts the service. Never throws: without it the connection simply may not survive in background. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start foreground service from here", e)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monitor = Monitor.get(this)

        if (intent?.action == ACTION_STOP) {
            monitor.disconnect()
            if (monitor.connectionState.value == BleState.Disconnected) stopSelf()
            return START_NOT_STICKY
        }

        // Must be called within a few seconds of startForegroundService()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(monitor.statusMessage.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        if (observeJob == null) {
            observeJob = scope.launch {
                combine(monitor.connectionState, monitor.statusMessage) { state, status -> state to status }
                    .collect { (state, status) ->
                        if (state == BleState.Disconnected) {
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(status))
                        }
                    }
            }
        }
        // Not sticky: if the system kills us, the companion service wakes the app up again
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(status: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(status)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            0,
            getString(R.string.app_disconnect),
            PendingIntent.getService(
                this,
                1,
                Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}
