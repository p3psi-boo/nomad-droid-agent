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
import com.nomad.droid.log.AppLogger
import com.nomad.droid.runtime.NomadRuntime
import com.nomad.droid.shizuku.ShizukuManager
import org.json.JSONObject
import java.util.concurrent.Executors

class AgentForegroundService : Service() {
    private val startExecutor = Executors.newSingleThreadExecutor()
    private val stopExecutor = Executors.newSingleThreadExecutor()
    private lateinit var store: AgentConfigStore
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        store = AgentConfigStore(this)
        createNotificationChannel()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification(getString(R.string.agent_notification_connecting)),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.agent_notification_connecting)))
        }
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
        stopExecutor.execute { runCatching { NomadRuntime.stop() } }
        startExecutor.shutdown()
        stopExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAgent() {
        store.desiredRunning = true
        store.runtimeStatus = "Starting"
        NomadTileService.requestTileUpdate(this)
        acquireWakeLock()
        ShizukuManager.bindBroker()
        AppLogger.i("AgentService", "Starting Nomad Agent service...")

        startExecutor.execute {
            if (!store.desiredRunning) {
                AppLogger.i("AgentService", "Startup aborted: Stop was requested.")
                return@execute
            }

            val result = runCatching {
                val current = JSONObject(NomadRuntime.status())
                val currentState = current.optString("state")
                if (currentState == "running" || currentState == "starting") {
                    val statusText = currentState.replaceFirstChar { it.uppercase() }
                    store.runtimeStatus = statusText
                    store.lastResult = current.toString(2)
                    updateNotification("Nomad client: $currentState")
                    NomadTileService.requestTileUpdate(this)
                    AppLogger.ok("AgentService", "Nomad runtime is $currentState", current.toString(2))
                    return@runCatching
                }

                val config = store.load()
                config.validate().getOrThrow()
                AppLogger.i("AgentService", "Connecting to ${config.serverAddress} as node '${config.nodeName}'...")
                val startCode = NomadRuntime.start(this, config)

                if (!store.desiredRunning) {
                    AppLogger.i("AgentService", "Startup cancelled by user while connecting.")
                    NomadRuntime.stop()
                    return@runCatching
                }

                if (startCode != 0) {
                    val failedStatus = JSONObject(NomadRuntime.status())
                    val errMsg = failedStatus.optString("error", "Native Nomad start failed with code $startCode")
                    AppLogger.e("AgentService", "Start failed: $errMsg")
                    error(errMsg)
                }
                val status = JSONObject(NomadRuntime.status())
                val state = status.optString("state", "running")
                val statusText = state.replaceFirstChar { it.uppercase() }
                store.runtimeStatus = statusText
                store.lastResult = status.toString(2)
                updateNotification("Connected to ${config.serverAddress} (${config.nodeName})")
                NomadTileService.requestTileUpdate(this)
                AppLogger.ok("AgentService", "Agent successfully started and joined cluster!", status.toString(2))
            }
            result.onFailure {
                if (!store.desiredRunning) {
                    store.runtimeStatus = "Stopped"
                    store.lastResult = "Nomad agent startup was cancelled."
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@onFailure
                }

                store.desiredRunning = false
                store.runtimeStatus = "Failed"
                val err = it.message ?: it.javaClass.simpleName
                store.lastResult = err
                updateNotification("Nomad client failed: $err")
                NomadTileService.requestTileUpdate(this)
                AppLogger.e("AgentService", "Agent encountered error: $err")
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopAgent() {
        store.desiredRunning = false
        store.runtimeStatus = "Stopping"
        NomadTileService.requestTileUpdate(this)
        AppLogger.i("AgentService", "Stopping Nomad Agent service immediately...")

        stopExecutor.execute {
            runCatching { NomadRuntime.stop() }
            store.runtimeStatus = "Stopped"
            store.lastResult = "Nomad agent stopped."
            NomadTileService.requestTileUpdate(this)
            AppLogger.i("AgentService", "Nomad agent stopped.")
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("WakelockTimeout")
    @Synchronized
    private fun acquireWakeLock() {
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
