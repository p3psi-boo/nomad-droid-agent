package com.nomad.droid.agent

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.nomad.droid.MainActivity
import com.nomad.droid.R
import com.nomad.droid.runtime.NomadRuntime
import com.nomad.droid.shizuku.ShizukuManager
import org.json.JSONObject
import java.util.concurrent.Executors

class AgentForegroundService : Service() {
    private val runtimeExecutor = Executors.newSingleThreadExecutor()
    private lateinit var store: AgentConfigStore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        store = AgentConfigStore(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.agent_notification_connecting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAgent()
            ACTION_START -> startAgent()
            null -> if (store.desiredRunning) startAgent() else stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        runtimeExecutor.execute { runCatching { NomadRuntime.stop() } }
        runtimeExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgent() {
        store.desiredRunning = true
        store.runtimeStatus = "Starting"
        acquireWakeLock()
        ShizukuManager.bindBroker()

        runtimeExecutor.execute {
            val result = runCatching {
                val current = JSONObject(NomadRuntime.status())
                val currentState = current.optString("state")
                if (currentState == "running" || currentState == "starting") {
                    store.runtimeStatus = currentState.replaceFirstChar { it.uppercase() }
                    store.lastResult = current.toString(2)
                    updateNotification("Nomad client: $currentState")
                    return@runCatching
                }

                val config = store.load()
                config.validate().getOrThrow()
                val startCode = NomadRuntime.start(this, config)
                if (startCode != 0) {
                    val failedStatus = JSONObject(NomadRuntime.status())
                    error(
                        failedStatus.optString(
                            "error",
                            "Native Nomad start failed with code $startCode",
                        ),
                    )
                }
                val status = JSONObject(NomadRuntime.status())
                val state = status.optString("state", "running")
                store.runtimeStatus = state.replaceFirstChar { it.uppercase() }
                store.lastResult = status.toString(2)
                updateNotification("Nomad client: $state")
            }
            result.onFailure {
                store.desiredRunning = false
                store.runtimeStatus = "Failed"
                store.lastResult = it.message ?: it.javaClass.simpleName
                updateNotification("Nomad client failed")
                releaseWakeLock()
                stopSelf()
            }
        }
    }

    private fun stopAgent() {
        store.desiredRunning = false
        runtimeExecutor.execute {
            runCatching { NomadRuntime.stop() }
            store.runtimeStatus = "Stopped"
            store.lastResult = "Nomad agent stopped."
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun acquireWakeLock() {
        // The user-selected agent lifecycle has no fixed completion time.
        // Every stop, failure, and destruction path releases this lock.
        val current = wakeLock
        if (current?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:NomadAgent")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nomad_droid)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_agent), stop).build())
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.agent_channel_description) },
        )
    }

    companion object {
        private const val ACTION_START = "com.nomad.droid.action.START_AGENT"
        private const val ACTION_STOP = "com.nomad.droid.action.STOP_AGENT"
        private const val CHANNEL_ID = "nomad-agent"
        private const val NOTIFICATION_ID = 100

        fun startIntent(context: Context): Intent =
            Intent(context, AgentForegroundService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AgentForegroundService::class.java).setAction(ACTION_STOP)
    }
}
