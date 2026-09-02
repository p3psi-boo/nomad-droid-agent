package com.nomad.droid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.nomad.droid.agent.AgentConfig
import com.nomad.droid.agent.AgentConfigStore
import com.nomad.droid.agent.AgentForegroundService
import com.nomad.droid.root.RootManager
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxContract
import com.nomad.droid.termux.TermuxManager

class MainActivity : Activity() {
    private lateinit var configStore: AgentConfigStore
    private lateinit var serverAddress: EditText
    private lateinit var nodeName: EditText
    private lateinit var datacenter: EditText
    private lateinit var introToken: EditText
    private lateinit var shizukuStatus: TextView
    private lateinit var rootStatus: TextView
    private lateinit var termuxStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var agentStatus: TextView
    private lateinit var lastResult: TextView

    private val shizukuListener: (ShizukuManager.State) -> Unit = { state ->
        runOnUiThread { renderShizuku(state) }
    }
    private val rootListener: (RootManager.State) -> Unit = { state ->
        runOnUiThread { renderRoot(state) }
    }
    private val termuxListener: (TermuxManager.State) -> Unit = { state ->
        runOnUiThread { renderTermux(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.rootScroll).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        configStore = AgentConfigStore(this)
        serverAddress = findViewById(R.id.serverAddress)
        nodeName = findViewById(R.id.nodeName)
        datacenter = findViewById(R.id.datacenter)
        introToken = findViewById(R.id.introToken)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        rootStatus = findViewById(R.id.rootStatus)
        termuxStatus = findViewById(R.id.termuxStatus)
        batteryStatus = findViewById(R.id.batteryStatus)
        agentStatus = findViewById(R.id.agentStatus)
        lastResult = findViewById(R.id.lastResult)

        renderConfig(configStore.load())
        findViewById<Button>(R.id.grantShizuku).setOnClickListener {
            ShizukuManager.requestPermission()
        }
        findViewById<Button>(R.id.connectBroker).setOnClickListener {
            ShizukuManager.bindBroker()
        }
        findViewById<Button>(R.id.grantRoot).setOnClickListener {
            RootManager.requestAccess()
        }
        findViewById<Button>(R.id.grantTermux).setOnClickListener {
            requestTermuxPermission()
        }
        findViewById<Button>(R.id.testTermux).setOnClickListener {
            runCatching { TermuxManager.startSetupProbe() }
                .onSuccess { lastResult.text = it.toString(2) }
                .onFailure { lastResult.text = it.message ?: it.javaClass.simpleName }
        }
        findViewById<Button>(R.id.openTermuxSettings).setOnClickListener {
            openTermuxSettings()
        }
        findViewById<Button>(R.id.openBatterySettings).setOnClickListener {
            openBatterySettings()
        }
        findViewById<Button>(R.id.startAgent).setOnClickListener { startAgent() }
        findViewById<Button>(R.id.stopAgent).setOnClickListener { stopAgent() }

        ShizukuManager.addListener(shizukuListener)
        RootManager.addListener(rootListener)
        TermuxManager.addListener(termuxListener)
        requestNotificationPermission()
        renderBatteryState()
        renderRuntimeState()
    }

    override fun onResume() {
        super.onResume()
        renderRuntimeState()
        renderShizuku(ShizukuManager.state())
        renderRoot(RootManager.state())
        renderTermux(TermuxManager.state())
        renderBatteryState()
    }

    override fun onDestroy() {
        ShizukuManager.removeListener(shizukuListener)
        RootManager.removeListener(rootListener)
        TermuxManager.removeListener(termuxListener)
        super.onDestroy()
    }

    private fun startAgent() {
        val config = AgentConfig(
            serverAddress = serverAddress.text.toString(),
            nodeName = nodeName.text.toString(),
            datacenter = datacenter.text.toString(),
            introToken = introToken.text.toString(),
        )
        config.validate().onFailure {
            lastResult.text = it.message
            return
        }

        configStore.save(config)
        configStore.desiredRunning = true
        startForegroundService(AgentForegroundService.startIntent(this))
        configStore.runtimeStatus = "Starting"
        configStore.lastResult = "Agent start requested by user."
        renderRuntimeState()
        renderBatteryState()
    }

    private fun stopAgent() {
        configStore.desiredRunning = false
        startService(AgentForegroundService.stopIntent(this))
        configStore.runtimeStatus = "Stopping"
        configStore.lastResult = "Agent stop requested by user."
        renderRuntimeState()
        renderBatteryState()
    }

    private fun renderConfig(config: AgentConfig) {
        serverAddress.setText(config.serverAddress)
        nodeName.setText(config.nodeName)
        datacenter.setText(config.datacenter)
        introToken.setText(config.introToken)
    }

    private fun renderShizuku(state: ShizukuManager.State) {
        val details = buildList {
            add(state.message)
            state.shizukuUid?.let { add("Shizuku uid=$it") }
            state.brokerUid?.let { add("Broker uid=$it") }
            if (state.permissionGranted) add("permission=granted")
            if (state.brokerConnected) add("broker=connected")
        }
        shizukuStatus.text = details.joinToString(" · ")
        findViewById<Button>(R.id.grantShizuku).isEnabled = state.binderAlive && !state.permissionGranted
        findViewById<Button>(R.id.connectBroker).isEnabled =
            state.permissionGranted && !state.brokerConnected
    }

    private fun renderRoot(state: RootManager.State) {
        val details = buildList {
            add(state.message)
            if (state.suAvailable) add("su=available")
            state.uid?.let { add("uid=$it") }
            if (state.permissionGranted) add("permission=granted")
        }
        rootStatus.text = details.joinToString(" · ")
        findViewById<Button>(R.id.grantRoot).isEnabled = !state.checking
    }

    private fun renderTermux(state: TermuxManager.State) {
        val details = buildList {
            add(state.message)
            if (state.installed) add("installed")
            if (state.permissionGranted) add("permission=granted")
            if (state.serviceAvailable) add("service=available")
            add("setup=${state.setupState}")
        }
        termuxStatus.text = details.joinToString(" · ")
        findViewById<Button>(R.id.grantTermux).isEnabled =
            state.installed && !state.permissionGranted
        findViewById<Button>(R.id.testTermux).isEnabled =
            state.installed && state.permissionGranted && state.serviceAvailable
    }

    private fun renderRuntimeState() {
        agentStatus.text = configStore.runtimeStatus
        lastResult.text = configStore.lastResult
    }

    private fun renderBatteryState() {
        val exempt = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)
        val restore = if (configStore.desiredRunning) "restore=enabled" else "restore=off"
        val optimization = if (exempt) "Doze exemption=granted" else "Doze exemption=not granted"
        batteryStatus.text = "$optimization · $restore"
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        // A Nomad client must maintain its RPC session and cannot replace it
        // with push delivery, so exemption is an explicit user choice here.
        val powerManager = getSystemService(PowerManager::class.java)
        val intent = if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        }
        runCatching { startActivity(intent) }
            .onFailure {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun requestTermuxPermission() {
        if (!TermuxManager.state().installed) {
            lastResult.text = "Install Termux before granting RUN_COMMAND access."
            return
        }
        requestPermissions(arrayOf(TermuxContract.RUN_COMMAND_PERMISSION), TERMUX_PERMISSION_REQUEST)
    }

    private fun openTermuxSettings() {
        val intent = if (TermuxManager.state().installed) {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${TermuxContract.PACKAGE_NAME}"),
            )
        } else {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://f-droid.org/packages/${TermuxContract.PACKAGE_NAME}/"),
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { lastResult.text = it.message ?: it.javaClass.simpleName }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TERMUX_PERMISSION_REQUEST) renderTermux(TermuxManager.state())
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 2001
        const val TERMUX_PERMISSION_REQUEST = 2002
    }
}
