package com.nomad.droid

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nomad.droid.agent.AgentConfig
import com.nomad.droid.agent.AgentConfigStore
import com.nomad.droid.agent.AgentForegroundService
import com.nomad.droid.agent.AgentProfile
import com.nomad.droid.agent.WorkloadTracker
import com.nomad.droid.diagnostics.DiagnosticsRunner
import com.nomad.droid.log.AppLogger
import com.nomad.droid.root.RootManager
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxContract
import com.nomad.droid.termux.TermuxManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var configStore: AgentConfigStore
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    // Header Actions
    private lateinit var btnHeaderDiagnostics: MaterialButton
    private lateinit var btnHeaderProfiles: MaterialButton
    private lateinit var btnHeaderShare: MaterialButton

    // Hero Section
    private lateinit var heroCard: MaterialCardView
    private lateinit var heroStatusBadge: TextView
    private lateinit var heroDatacenterChip: TextView
    private lateinit var heroNodeChip: TextView
    private lateinit var heroDescription: TextView
    private lateinit var btnHeroToggle: MaterialButton

    // Environment & Drivers
    private lateinit var envHeaderLayout: LinearLayout
    private lateinit var envSummaryBadge: TextView
    private lateinit var envExpandArrow: ImageView
    private lateinit var envContentContainer: LinearLayout
    private var isEnvExpanded = false

    private lateinit var shizukuBadge: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var grantShizuku: MaterialButton
    private lateinit var connectBroker: MaterialButton

    private lateinit var rootBadge: TextView
    private lateinit var rootStatus: TextView
    private lateinit var grantRoot: MaterialButton

    private lateinit var termuxBadge: TextView
    private lateinit var termuxStatus: TextView
    private lateinit var grantTermux: MaterialButton
    private lateinit var testTermux: MaterialButton
    private lateinit var openTermuxSettings: MaterialButton

    private lateinit var batteryBadge: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var openBatterySettings: MaterialButton
    private lateinit var btnRunDiagnostics: MaterialButton

    // Configuration
    private lateinit var profileBadge: TextView
    private lateinit var configLockedNotice: TextView
    private lateinit var serverAddressLayout: TextInputLayout
    private lateinit var serverAddress: TextInputEditText
    private lateinit var serverAddressHint: TextView
    private lateinit var btnPingServer: MaterialButton
    private lateinit var nodeNameLayout: TextInputLayout
    private lateinit var nodeName: TextInputEditText
    private lateinit var btnAutoNodeName: MaterialButton
    private lateinit var datacenterLayout: TextInputLayout
    private lateinit var datacenter: TextInputEditText
    private lateinit var introTokenLayout: TextInputLayout
    private lateinit var introToken: TextInputEditText
    private lateinit var btnPasteToken: MaterialButton

    // Allocations & Logs
    private lateinit var allocationsBadge: TextView
    private lateinit var allocationsListContainer: LinearLayout
    private lateinit var allocationsEmptyText: TextView
    private lateinit var errorAdviceBanner: TextView
    private lateinit var logConsole: TextView
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var btnClearLogs: MaterialButton

    private val shizukuListener: (ShizukuManager.State) -> Unit = { state ->
        runOnUiThread { renderShizuku(state) }
    }
    private val rootListener: (RootManager.State) -> Unit = { state ->
        runOnUiThread { renderRoot(state) }
    }
    private val termuxListener: (TermuxManager.State) -> Unit = { state ->
        runOnUiThread { renderTermux(state) }
    }
    private val logListener: (List<AppLogger.Entry>) -> Unit = { entries ->
        runOnUiThread { renderLogs(entries) }
    }
    private val workloadListener: (List<WorkloadTracker.Workload>) -> Unit = { workloads ->
        runOnUiThread { renderWorkloads(workloads) }
    }
    private val configStatusListener: (String) -> Unit = { _ ->
        runOnUiThread { renderRuntimeState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.rootScroll).setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }

        configStore = AgentConfigStore(this)
        bindViews()
        setupListeners()

        handleDeepLink(intent)
        renderConfig(configStore.load())

        ShizukuManager.addListener(shizukuListener)
        RootManager.addListener(rootListener)
        TermuxManager.addListener(termuxListener)
        AppLogger.addListener(logListener)
        WorkloadTracker.addListener(workloadListener)
        configStore.addStatusListener(configStatusListener)

        requestNotificationPermission()
        renderBatteryState()
        renderRuntimeState()
        updateEnvironmentSummary()

        AppLogger.i("MainActivity", "Nomad Droid Material 3 UI loaded.")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun bindViews() {
        btnHeaderDiagnostics = findViewById(R.id.btnHeaderDiagnostics)
        btnHeaderProfiles = findViewById(R.id.btnHeaderProfiles)
        btnHeaderShare = findViewById(R.id.btnHeaderShare)

        heroCard = findViewById(R.id.heroCard)
        heroStatusBadge = findViewById(R.id.heroStatusBadge)
        heroDatacenterChip = findViewById(R.id.heroDatacenterChip)
        heroNodeChip = findViewById(R.id.heroNodeChip)
        heroDescription = findViewById(R.id.heroDescription)
        btnHeroToggle = findViewById(R.id.btnHeroToggle)

        envHeaderLayout = findViewById(R.id.envHeaderLayout)
        envSummaryBadge = findViewById(R.id.envSummaryBadge)
        envExpandArrow = findViewById(R.id.envExpandArrow)
        envContentContainer = findViewById(R.id.envContentContainer)

        shizukuBadge = findViewById(R.id.shizukuBadge)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        grantShizuku = findViewById(R.id.grantShizuku)
        connectBroker = findViewById(R.id.connectBroker)

        rootBadge = findViewById(R.id.rootBadge)
        rootStatus = findViewById(R.id.rootStatus)
        grantRoot = findViewById(R.id.grantRoot)

        termuxBadge = findViewById(R.id.termuxBadge)
        termuxStatus = findViewById(R.id.termuxStatus)
        grantTermux = findViewById(R.id.grantTermux)
        testTermux = findViewById(R.id.testTermux)
        openTermuxSettings = findViewById(R.id.openTermuxSettings)

        batteryBadge = findViewById(R.id.batteryBadge)
        batteryStatus = findViewById(R.id.batteryStatus)
        openBatterySettings = findViewById(R.id.openBatterySettings)
        btnRunDiagnostics = findViewById(R.id.btnRunDiagnostics)

        profileBadge = findViewById(R.id.profileBadge)
        configLockedNotice = findViewById(R.id.configLockedNotice)
        serverAddressLayout = findViewById(R.id.serverAddressLayout)
        serverAddress = findViewById(R.id.serverAddress)
        serverAddressHint = findViewById(R.id.serverAddressHint)
        btnPingServer = findViewById(R.id.btnPingServer)
        nodeNameLayout = findViewById(R.id.nodeNameLayout)
        nodeName = findViewById(R.id.nodeName)
        btnAutoNodeName = findViewById(R.id.btnAutoNodeName)
        datacenterLayout = findViewById(R.id.datacenterLayout)
        datacenter = findViewById(R.id.datacenter)
        introTokenLayout = findViewById(R.id.introTokenLayout)
        introToken = findViewById(R.id.introToken)
        btnPasteToken = findViewById(R.id.btnPasteToken)

        allocationsBadge = findViewById(R.id.allocationsBadge)
        allocationsListContainer = findViewById(R.id.allocationsListContainer)
        allocationsEmptyText = findViewById(R.id.allocationsEmptyText)
        errorAdviceBanner = findViewById(R.id.errorAdviceBanner)
        logConsole = findViewById(R.id.logConsole)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
    }

    private fun setupListeners() {
        envHeaderLayout.setOnClickListener {
            toggleEnvPanel()
        }

        btnHeroToggle.setOnClickListener {
            val status = configStore.runtimeStatus
            val isStarting = status.equals("Starting", ignoreCase = true)
            val isRunning = status.equals("Running", ignoreCase = true)
            if (configStore.desiredRunning || isStarting || isRunning) {
                stopAgent()
            } else {
                startAgent()
            }
        }

        btnHeaderDiagnostics.setOnClickListener { showDiagnosticsDialog() }
        btnRunDiagnostics.setOnClickListener { showDiagnosticsDialog() }
        btnHeaderProfiles.setOnClickListener { showProfilesDialog() }
        btnHeaderShare.setOnClickListener { showShareDialog() }

        grantShizuku.setOnClickListener {
            AppLogger.i("Shizuku", "Requesting Shizuku permission...")
            ShizukuManager.requestPermission()
        }
        connectBroker.setOnClickListener {
            AppLogger.i("Shizuku", "Binding Shizuku privilege broker...")
            ShizukuManager.bindBroker()
        }

        grantRoot.setOnClickListener {
            AppLogger.i("Root", "Checking root access...")
            RootManager.requestAccess()
        }

        grantTermux.setOnClickListener { requestTermuxPermission() }
        testTermux.setOnClickListener {
            AppLogger.i("Termux", "Dispatching setup probe...")
            runCatching { TermuxManager.startSetupProbe() }
                .onSuccess { AppLogger.ok("Termux", "Probe dispatched successfully", it.toString(2)) }
                .onFailure { AppLogger.e("Termux", "Probe failed: ${it.message}") }
        }
        openTermuxSettings.setOnClickListener { openTermuxSettings() }

        openBatterySettings.setOnClickListener { openBatterySettings() }

        serverAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateAddressLive(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnPingServer.setOnClickListener { pingServerRpc() }

        btnAutoNodeName.setOnClickListener {
            val defaultName = configStore.defaultNodeName()
            nodeName.setText(defaultName)
            Toast.makeText(this, "Set node name to $defaultName", Toast.LENGTH_SHORT).show()
            AppLogger.i("Config", "Auto-filled node name: $defaultName")
        }

        btnPasteToken.setOnClickListener { pasteTokenFromClipboard() }

        btnCopyLogs.setOnClickListener { copyLogsToClipboard() }
        btnClearLogs.setOnClickListener {
            AppLogger.clear()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleEnvPanel() {
        isEnvExpanded = !isEnvExpanded
        envContentContainer.visibility = if (isEnvExpanded) View.VISIBLE else View.GONE
        envExpandArrow.setImageResource(
            if (isEnvExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
        )
    }

    private fun pasteTokenFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                introToken.setText(text)
                Toast.makeText(this, "Token pasted from clipboard", Toast.LENGTH_SHORT).show()
                AppLogger.i("Config", "Pasted token from clipboard")
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLogsToClipboard() {
        val logs = AppLogger.exportText()
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Nomad Droid Logs", logs))
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun validateAddressLive(address: String) {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) {
            serverAddressHint.visibility = View.GONE
            return
        }
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            serverAddressHint.text = "⚠️ Nomad Client connects via TCP RPC (e.g. 10.0.0.10:4647), not HTTP."
            serverAddressHint.visibility = View.VISIBLE
            return
        }
        if (!trimmed.contains(":")) {
            serverAddressHint.text = "💡 Port is missing. Default Nomad Server RPC port is :4647."
            serverAddressHint.visibility = View.VISIBLE
            return
        }
        serverAddressHint.visibility = View.GONE
    }

    private fun pingServerRpc() {
        val address = serverAddress.text.toString().trim()
        if (address.isEmpty()) {
            Toast.makeText(this, "Please enter a Server address first", Toast.LENGTH_SHORT).show()
            return
        }

        btnPingServer.isEnabled = false
        btnPingServer.text = "Ping…"
        AppLogger.i("Network", "Testing RPC connection to $address...")

        backgroundExecutor.execute {
            val result = runCatching {
                val uri = URI("nomad://$address")
                val host = uri.host ?: error("Invalid host format in address")
                val port = if (uri.port > 0) uri.port else 4647

                val start = System.currentTimeMillis()
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 3000)
                }
                System.currentTimeMillis() - start
            }

            runOnUiThread {
                btnPingServer.isEnabled = true
                btnPingServer.text = getString(R.string.test_connection)
                result.onSuccess { latency ->
                    Toast.makeText(this, "RPC reachable (${latency}ms)", Toast.LENGTH_LONG).show()
                    AppLogger.ok("Network", "Server RPC reachable at $address (${latency}ms)")
                }.onFailure { err ->
                    val advice = AppLogger.translateError(err.message)
                    Toast.makeText(this, "Unreachable: ${err.message}", Toast.LENGTH_LONG).show()
                    AppLogger.e("Network", "Failed to reach server RPC: ${err.message}", advice)
                    errorAdviceBanner.text = advice
                    errorAdviceBanner.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showDiagnosticsDialog() {
        val currentServer = serverAddress.text.toString().trim()
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Running Diagnostics…")
            .setMessage("Checking network reachability, drivers, and background policy...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        DiagnosticsRunner.runDiagnostics(this, currentServer) { report ->
            runOnUiThread {
                progressDialog.dismiss()
                val messageBuilder = StringBuilder()
                report.items.forEach { item ->
                    val icon = when (item.status) {
                        DiagnosticsRunner.Status.PASSED -> "✅"
                        DiagnosticsRunner.Status.WARNING -> "⚠️"
                        DiagnosticsRunner.Status.FAILED -> "❌"
                        DiagnosticsRunner.Status.INFO -> "ℹ️"
                    }
                    messageBuilder.append("$icon ${item.title}\n${item.summary}\n")
                    if (!item.suggestion.isNullOrBlank()) {
                        messageBuilder.append("👉 ${item.suggestion}\n")
                    }
                    messageBuilder.append("\n")
                }

                MaterialAlertDialogBuilder(this)
                    .setTitle(if (report.allPassed) "Environment Healthy ✅" else "Diagnostics Report ⚠️")
                    .setMessage(messageBuilder.toString().trim())
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy Report") { _, _ ->
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics Report", messageBuilder.toString()))
                        Toast.makeText(this, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
        }
    }

    private fun showProfilesDialog() {
        val profiles = configStore.listProfiles()
        val items = profiles.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Configuration Profiles")
            .setItems(items) { _, which ->
                val selectedName = items[which]
                val profile = configStore.loadProfile(selectedName)
                if (profile != null) {
                    renderConfig(profile.config)
                    profileBadge.text = "Profile: $selectedName"
                    Toast.makeText(this, "Loaded profile: $selectedName", Toast.LENGTH_SHORT).show()
                    AppLogger.i("Profiles", "Switched to profile: $selectedName")
                }
            }
            .setPositiveButton("Save Current as New") { _, _ ->
                showSaveProfileDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSaveProfileDialog() {
        val input = TextInputEditText(this).apply {
            hint = "Profile Name (e.g. Home Lab)"
        }
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            addView(input)
            setPadding(48, 16, 48, 0)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save Profile")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val currentConfig = getCurrentConfig()
                    configStore.saveProfile(AgentProfile(name, currentConfig))
                    profileBadge.text = "Profile: $name"
                    Toast.makeText(this, "Saved profile: $name", Toast.LENGTH_SHORT).show()
                    AppLogger.i("Profiles", "Saved profile: $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShareDialog() {
        val currentProfile = AgentProfile(profileBadge.text.toString().removePrefix("Profile: "), getCurrentConfig())
        val options = arrayOf("Copy Config JSON", "Copy Import Link (nomad-droid://)", "Import from Clipboard")

        MaterialAlertDialogBuilder(this)
            .setTitle("Share & Import Configuration")
            .setItems(options) { _, which ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                when (which) {
                    0 -> {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Nomad Droid Config", currentProfile.toJson()))
                        Toast.makeText(this, "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Nomad Droid Link", currentProfile.toUriString()))
                        Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val clip = clipboard.primaryClip
                        val text = clip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            importConfigText(text)
                        } else {
                            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importConfigText(text: String) {
        val profile = runCatching {
            if (text.startsWith("nomad-droid://")) {
                AgentProfile.fromUri(text)
            } else {
                AgentProfile.fromJson(text)
            }
        }.getOrNull()

        if (profile != null) {
            renderConfig(profile.config)
            configStore.save(profile.config)
            profileBadge.text = "Profile: ${profile.name}"
            Toast.makeText(this, "Configuration imported successfully!", Toast.LENGTH_SHORT).show()
            AppLogger.ok("Config", "Imported configuration for node '${profile.config.nodeName}'")
        } else {
            Toast.makeText(this, "Invalid config format", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "nomad-droid") {
            val profile = AgentProfile.fromUri(data.toString())
            if (profile != null) {
                renderConfig(profile.config)
                configStore.save(profile.config)
                profileBadge.text = "Profile: ${profile.name}"
                Toast.makeText(this, "Config imported from link!", Toast.LENGTH_SHORT).show()
                AppLogger.ok("Config", "Imported config from deep link")
            }
        }
    }

    private fun getCurrentConfig(): AgentConfig = AgentConfig(
        serverAddress = serverAddress.text.toString().trim(),
        nodeName = nodeName.text.toString().trim(),
        datacenter = datacenter.text.toString().trim(),
        introToken = introToken.text.toString(),
    )

    private fun startAgent() {
        val config = getCurrentConfig()
        config.validate().onFailure {
            val msg = it.message ?: "Invalid configuration"
            AppLogger.e("Config", msg)
            errorAdviceBanner.text = "⚠️ Validation error: $msg"
            errorAdviceBanner.visibility = View.VISIBLE
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }

        errorAdviceBanner.visibility = View.GONE
        configStore.save(config)
        configStore.desiredRunning = true
        startForegroundService(AgentForegroundService.startIntent(this))
        configStore.runtimeStatus = "Starting"
        configStore.lastResult = "Agent start requested by user."
        renderRuntimeState()
        renderBatteryState()
        AppLogger.i("Agent", "Agent start requested.")
    }

    private fun stopAgent() {
        configStore.desiredRunning = false
        startService(AgentForegroundService.stopIntent(this))
        configStore.runtimeStatus = "Stopping"
        configStore.lastResult = "Agent stop requested by user."
        renderRuntimeState()
        renderBatteryState()
        AppLogger.i("Agent", "Agent stop requested.")
    }

    private fun renderConfig(config: AgentConfig) {
        serverAddress.setText(config.serverAddress)
        nodeName.setText(config.nodeName)
        datacenter.setText(config.datacenter)
        introToken.setText(config.introToken)
        heroNodeChip.text = "node: ${config.nodeName}"
        heroDatacenterChip.text = "dc: ${config.datacenter}"
    }

    private fun renderShizuku(state: ShizukuManager.State) {
        when {
            state.brokerConnected -> {
                shizukuBadge.text = "CONNECTED"
                shizukuBadge.setBackgroundResource(R.drawable.badge_green)
                shizukuBadge.setTextColor(getColor(R.color.md3_status_success_text))
            }
            state.permissionGranted -> {
                shizukuBadge.text = "PERMISSION GRANTED"
                shizukuBadge.setBackgroundResource(R.drawable.badge_amber)
                shizukuBadge.setTextColor(getColor(R.color.md3_status_warning_text))
            }
            state.binderAlive -> {
                shizukuBadge.text = "SERVICE RUNNING"
                shizukuBadge.setBackgroundResource(R.drawable.badge_neutral)
                shizukuBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
            }
            else -> {
                shizukuBadge.text = "INACTIVE"
                shizukuBadge.setBackgroundResource(R.drawable.badge_neutral)
                shizukuBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
            }
        }

        val details = buildList {
            add(state.message)
            state.shizukuUid?.let { add("Shizuku UID=$it") }
            state.brokerUid?.let { add("Broker UID=$it") }
        }
        shizukuStatus.text = details.joinToString(" · ")
        grantShizuku.isEnabled = state.binderAlive && !state.permissionGranted
        connectBroker.isEnabled = state.permissionGranted && !state.brokerConnected
        updateEnvironmentSummary()
    }

    private fun renderRoot(state: RootManager.State) {
        when {
            state.permissionGranted -> {
                rootBadge.text = "READY (UID=0)"
                rootBadge.setBackgroundResource(R.drawable.badge_green)
                rootBadge.setTextColor(getColor(R.color.md3_status_success_text))
            }
            state.suAvailable -> {
                rootBadge.text = "SU AVAILABLE"
                rootBadge.setBackgroundResource(R.drawable.badge_amber)
                rootBadge.setTextColor(getColor(R.color.md3_status_warning_text))
            }
            else -> {
                rootBadge.text = "NOT CHECKED"
                rootBadge.setBackgroundResource(R.drawable.badge_neutral)
                rootBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
            }
        }

        val details = buildList {
            add(state.message)
            if (state.suAvailable) add("su=available")
            state.uid?.let { add("uid=$it") }
        }
        rootStatus.text = details.joinToString(" · ")
        grantRoot.isEnabled = !state.checking
        updateEnvironmentSummary()
    }

    private fun renderTermux(state: TermuxManager.State) {
        when {
            state.ready -> {
                termuxBadge.text = "READY & VERIFIED"
                termuxBadge.setBackgroundResource(R.drawable.badge_green)
                termuxBadge.setTextColor(getColor(R.color.md3_status_success_text))
            }
            state.permissionGranted -> {
                termuxBadge.text = "SETUP: ${state.setupState.uppercase()}"
                termuxBadge.setBackgroundResource(R.drawable.badge_amber)
                termuxBadge.setTextColor(getColor(R.color.md3_status_warning_text))
            }
            state.installed -> {
                termuxBadge.text = "PERMISSION NEEDED"
                termuxBadge.setBackgroundResource(R.drawable.badge_amber)
                termuxBadge.setTextColor(getColor(R.color.md3_status_warning_text))
            }
            else -> {
                termuxBadge.text = "NOT INSTALLED"
                termuxBadge.setBackgroundResource(R.drawable.badge_neutral)
                termuxBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
            }
        }

        val details = buildList {
            add(state.message)
            if (state.installed) add("installed")
            if (state.serviceAvailable) add("service=available")
            add("setup=${state.setupState}")
        }
        termuxStatus.text = details.joinToString(" · ")
        grantTermux.isEnabled = state.installed && !state.permissionGranted
        testTermux.isEnabled = state.installed && state.permissionGranted && state.serviceAvailable
        updateEnvironmentSummary()
    }

    private fun renderBatteryState() {
        val exempt = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)

        if (exempt) {
            batteryBadge.text = "EXEMPT (DOZE OFF)"
            batteryBadge.setBackgroundResource(R.drawable.badge_green)
            batteryBadge.setTextColor(getColor(R.color.md3_status_success_text))
            batteryStatus.text = "Nomad Droid is exempt from battery optimizations. Background keep-alive active."
        } else {
            batteryBadge.text = "RESTRICTED"
            batteryBadge.setBackgroundResource(R.drawable.badge_amber)
            batteryBadge.setTextColor(getColor(R.color.md3_status_warning_text))
            batteryStatus.text = "Subject to Android battery optimization. Screen off may interrupt client."
        }
        updateEnvironmentSummary()
    }

    private fun updateEnvironmentSummary() {
        val shizukuReady = ShizukuManager.state().brokerConnected
        val termuxReady = TermuxManager.state().ready
        val rootReady = RootManager.state().permissionGranted
        val batteryReady = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

        val readyDrivers = listOf(shizukuReady, termuxReady, rootReady).count { it }
        val overallReady = readyDrivers > 0 && batteryReady

        if (overallReady) {
            envSummaryBadge.text = "Ready ($readyDrivers drivers · battery ok)"
            envSummaryBadge.setBackgroundResource(R.drawable.badge_green)
            envSummaryBadge.setTextColor(getColor(R.color.md3_status_success_text))
        } else {
            envSummaryBadge.text = "Setup Needed"
            envSummaryBadge.setBackgroundResource(R.drawable.badge_amber)
            envSummaryBadge.setTextColor(getColor(R.color.md3_status_warning_text))
        }
    }

    private fun renderRuntimeState() {
        val status = configStore.runtimeStatus
        val isRunning = status.equals("Running", ignoreCase = true)
        val isStarting = status.equals("Starting", ignoreCase = true)
        val isStopping = status.equals("Stopping", ignoreCase = true)
        val isFailed = status.equals("Failed", ignoreCase = true)

        when {
            isRunning -> {
                heroStatusBadge.text = getString(R.string.status_running)
                heroStatusBadge.setBackgroundResource(R.drawable.badge_green)
                heroStatusBadge.setTextColor(getColor(R.color.md3_status_success_text))
                heroDescription.text = "Agent active & connected to ${serverAddress.text}"
                btnHeroToggle.text = getString(R.string.stop_agent)
                btnHeroToggle.setIconResource(R.drawable.ic_stop)
                btnHeroToggle.backgroundTintList = ColorStateList.valueOf(getColor(R.color.md_theme_error))
                btnHeroToggle.isEnabled = true
                lockFormInputs(true)
            }
            isStarting -> {
                heroStatusBadge.text = getString(R.string.status_starting)
                heroStatusBadge.setBackgroundResource(R.drawable.badge_amber)
                heroStatusBadge.setTextColor(getColor(R.color.md3_status_warning_text))
                heroDescription.text = "Connecting to Nomad cluster at ${serverAddress.text}…"
                btnHeroToggle.text = getString(R.string.stop_agent)
                btnHeroToggle.setIconResource(R.drawable.ic_stop)
                btnHeroToggle.backgroundTintList = ColorStateList.valueOf(getColor(R.color.md_theme_error))
                btnHeroToggle.isEnabled = true
                lockFormInputs(true)
            }
            isStopping -> {
                heroStatusBadge.text = "STOPPING…"
                heroStatusBadge.setBackgroundResource(R.drawable.badge_amber)
                heroStatusBadge.setTextColor(getColor(R.color.md3_status_warning_text))
                heroDescription.text = "Gracefully shutting down Nomad client…"
                btnHeroToggle.text = "Stopping…"
                btnHeroToggle.setIconResource(R.drawable.ic_stop)
                btnHeroToggle.isEnabled = false
                lockFormInputs(true)
            }
            isFailed -> {
                heroStatusBadge.text = getString(R.string.status_failed)
                heroStatusBadge.setBackgroundResource(R.drawable.badge_red)
                heroStatusBadge.setTextColor(getColor(R.color.md3_status_error_text))
                heroDescription.text = "Agent failed to start. Review logs below for details."
                btnHeroToggle.text = getString(R.string.start_agent)
                btnHeroToggle.setIconResource(R.drawable.ic_play_arrow)
                btnHeroToggle.backgroundTintList = ColorStateList.valueOf(getColor(R.color.md_theme_primary))
                btnHeroToggle.isEnabled = true
                lockFormInputs(false)

                val advice = AppLogger.translateError(configStore.lastResult)
                errorAdviceBanner.text = advice
                errorAdviceBanner.visibility = View.VISIBLE
            }
            else -> {
                heroStatusBadge.text = getString(R.string.status_stopped)
                heroStatusBadge.setBackgroundResource(R.drawable.badge_neutral)
                heroStatusBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
                heroDescription.text = "Nomad agent is stopped. Tap Start to connect."
                btnHeroToggle.text = getString(R.string.start_agent)
                btnHeroToggle.setIconResource(R.drawable.ic_play_arrow)
                btnHeroToggle.backgroundTintList = ColorStateList.valueOf(getColor(R.color.md_theme_primary))
                btnHeroToggle.isEnabled = true
                lockFormInputs(false)
            }
        }
    }

    private fun lockFormInputs(locked: Boolean) {
        serverAddress.isEnabled = !locked
        nodeName.isEnabled = !locked
        datacenter.isEnabled = !locked
        introToken.isEnabled = !locked
        btnAutoNodeName.isEnabled = !locked
        btnPasteToken.isEnabled = !locked
        btnPingServer.isEnabled = !locked
        configLockedNotice.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun renderWorkloads(workloads: List<WorkloadTracker.Workload>) {
        val active = workloads.filter { it.status == "Running" }
        allocationsBadge.text = "${active.size} active"
        if (active.isNotEmpty()) {
            allocationsBadge.setBackgroundResource(R.drawable.badge_green)
            allocationsBadge.setTextColor(getColor(R.color.md3_status_success_text))
        } else {
            allocationsBadge.setBackgroundResource(R.drawable.badge_neutral)
            allocationsBadge.setTextColor(getColor(R.color.md3_status_neutral_text))
        }

        allocationsListContainer.removeAllViews()
        if (workloads.isEmpty()) {
            allocationsListContainer.addView(allocationsEmptyText)
            return
        }

        workloads.take(5).forEach { item ->
            val row = TextView(this).apply {
                val statusDot = if (item.status == "Running") "🟢" else "⚪"
                text = "$statusDot [${item.kind}] ${item.target} · ${item.status}"
                textSize = 12f
                setTextColor(getColor(R.color.md_theme_onSurface))
                setPadding(0, 4, 0, 4)
            }
            allocationsListContainer.addView(row)
        }
    }

    private fun renderLogs(entries: List<AppLogger.Entry>) {
        val text = entries.takeLast(50).joinToString("\n") { it.toDisplayText() }
        logConsole.text = text.ifBlank { "System ready. No operations yet." }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
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
            Toast.makeText(this, "Install Termux before granting RUN_COMMAND access.", Toast.LENGTH_SHORT).show()
            AppLogger.w("Termux", "Install Termux before granting RUN_COMMAND access.")
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
            .onFailure {
                AppLogger.e("Termux", "Unable to open Termux settings: ${it.message}")
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == TERMUX_PERMISSION_REQUEST) {
            renderTermux(TermuxManager.state())
        }
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
        AppLogger.removeListener(logListener)
        WorkloadTracker.removeListener(workloadListener)
        configStore.removeStatusListener(configStatusListener)
        backgroundExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 2001
        const val TERMUX_PERMISSION_REQUEST = 2002
    }
}
