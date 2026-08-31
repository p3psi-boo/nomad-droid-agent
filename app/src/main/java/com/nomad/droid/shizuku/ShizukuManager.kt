package com.nomad.droid.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.nomad.droid.BuildConfig
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

object ShizukuManager {
    data class State(
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val brokerConnected: Boolean,
        val shizukuUid: Int?,
        val brokerUid: Int?,
        val message: String,
    )

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private var appContext: Context? = null
    private var broker: INomadPrivilegedService? = null
    private var binding = false
    private var lastMessage = "Waiting for Shizuku"

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        lastMessage = "Shizuku binder is available"
        if (hasPermission()) bindBroker()
        publish()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        broker = null
        binding = false
        lastMessage = "Shizuku binder disconnected"
        publish()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            lastMessage = "Shizuku permission granted"
            bindBroker()
        } else {
            lastMessage = "Shizuku permission denied"
        }
        publish()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            binding = false
            broker = service?.takeIf { it.pingBinder() }?.let {
                INomadPrivilegedService.Stub.asInterface(it)
            }
            lastMessage = if (broker != null) "Privilege broker connected" else "Invalid broker binder"
            publish()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleBrokerDisconnect("Privilege broker disconnected", rebind = true)
        }

        override fun onBindingDied(name: ComponentName) {
            handleBrokerDisconnect("Privilege broker binding died", rebind = true)
        }

        override fun onNullBinding(name: ComponentName) {
            handleBrokerDisconnect("Privilege broker returned a null binding", rebind = true)
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        publish()
    }

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
        listener(state())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    fun requestPermission() {
        if (!binderAlive()) {
            lastMessage = "Start Shizuku before requesting access"
            publish()
            return
        }
        if (hasPermission()) {
            bindBroker()
            return
        }
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
            .onFailure {
                lastMessage = it.message ?: "Unable to request Shizuku permission"
                publish()
            }
    }

    @Synchronized
    fun bindBroker() {
        if (broker != null || binding) return
        if (!binderAlive() || !hasPermission()) {
            lastMessage = "Shizuku permission is required"
            publish()
            return
        }

        binding = true
        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, NomadPrivilegedService::class.java.name),
        )
            .daemon(false)
            .processNameSuffix("broker")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        runCatching { Shizuku.bindUserService(args, serviceConnection) }
            .onFailure {
                binding = false
                lastMessage = it.message ?: "Unable to bind privilege broker"
                publish()
            }
    }

    @Synchronized
    fun broker(): INomadPrivilegedService? {
        val current = broker ?: return null
        if (runCatching { current.asBinder().pingBinder() }.getOrDefault(false)) return current
        handleBrokerDisconnect("Privilege broker binder is stale", rebind = true)
        return null
    }

    fun state(): State {
        val alive = binderAlive()
        val granted = alive && hasPermission()
        val service = broker
        return State(
            binderAlive = alive,
            permissionGranted = granted,
            brokerConnected = service != null,
            shizukuUid = if (alive) runCatching { Shizuku.getUid() }.getOrNull() else null,
            brokerUid = service?.let { runCatching { it.uid }.getOrNull() },
            message = lastMessage,
        )
    }

    private fun binderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun publish() {
        val state = state()
        listeners.forEach { it(state) }
    }

    @Synchronized
    private fun handleBrokerDisconnect(message: String, rebind: Boolean) {
        binding = false
        broker = null
        lastMessage = message
        publish()
        if (rebind && binderAlive() && hasPermission()) bindBroker()
    }

    private const val PERMISSION_REQUEST = 1001
}
