package com.nomad.droid.diagnostics

import android.content.Context
import android.os.PowerManager
import com.nomad.droid.log.AppLogger
import com.nomad.droid.root.RootManager
import com.nomad.droid.runtime.NomadNative
import com.nomad.droid.shizuku.ShizukuManager
import com.nomad.droid.termux.TermuxManager
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors

object DiagnosticsRunner {
    enum class Status {
        PASSED, WARNING, FAILED, INFO
    }

    data class Item(
        val category: String,
        val title: String,
        val status: Status,
        val summary: String,
        val suggestion: String? = null,
    )

    data class Report(
        val timestamp: Long,
        val items: List<Item>,
        val allPassed: Boolean,
    )

    private val executor = Executors.newSingleThreadExecutor()

    fun runDiagnostics(
        context: Context,
        serverAddress: String,
        onComplete: (Report) -> Unit,
    ) {
        executor.execute {
            AppLogger.i("Diagnostics", "Starting full environment diagnostics...")
            val items = mutableListOf<Item>()

            // 1. Server RPC TCP Reachability
            items += checkServerRpc(serverAddress)

            // 2. Shizuku Driver
            items += checkShizuku()

            // 3. Root Driver
            items += checkRoot()

            // 4. Termux Driver
            items += checkTermux()

            // 5. Battery Optimization (Doze)
            items += checkBattery(context)

            // 6. Native Nomad Core
            items += checkNativeCore()

            val allPassed = items.none { it.status == Status.FAILED }
            val report = Report(System.currentTimeMillis(), items, allPassed)

            if (allPassed) {
                AppLogger.ok("Diagnostics", "Diagnostics complete: All critical checks passed!")
            } else {
                AppLogger.w("Diagnostics", "Diagnostics complete: Issues detected.")
            }

            onComplete(report)
        }
    }

    private fun checkServerRpc(serverAddress: String): Item {
        val trimmed = serverAddress.trim()
        if (trimmed.isEmpty()) {
            return Item(
                category = "Network",
                title = "Server RPC Reachability",
                status = Status.FAILED,
                summary = "Server address is not configured",
                suggestion = "Enter the Nomad Server RPC address (e.g. 10.0.0.10:4647) in Cluster Configuration.",
            )
        }

        return try {
            val uri = URI("nomad://$trimmed")
            val host = uri.host ?: return Item(
                category = "Network",
                title = "Server RPC Reachability",
                status = Status.FAILED,
                summary = "Invalid server host format: $trimmed",
                suggestion = "Use host:port or [IPv6]:port (e.g. 10.0.0.10:4647).",
            )
            val port = if (uri.port > 0) uri.port else 4647

            val startTime = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 3500)
            }
            val latency = System.currentTimeMillis() - startTime
            Item(
                category = "Network",
                title = "Server RPC Reachability",
                status = Status.PASSED,
                summary = "Successfully connected to $host:$port (latency: ${latency}ms)",
            )
        } catch (e: Throwable) {
            val translated = AppLogger.translateError(e.message ?: e.javaClass.simpleName)
            Item(
                category = "Network",
                title = "Server RPC Reachability",
                status = Status.FAILED,
                summary = "Cannot reach $trimmed: ${e.message}",
                suggestion = translated,
            )
        }
    }

    private fun checkShizuku(): Item {
        val state = ShizukuManager.state()
        return when {
            state.brokerConnected -> Item(
                category = "Drivers",
                title = "Shizuku Driver",
                status = Status.PASSED,
                summary = "Broker connected (Shizuku UID=${state.shizukuUid ?: "unknown"}, Broker UID=${state.brokerUid ?: "unknown"})",
            )
            state.permissionGranted -> Item(
                category = "Drivers",
                title = "Shizuku Driver",
                status = Status.WARNING,
                summary = "Permission granted, but broker is not connected yet",
                suggestion = "Click 'Connect broker' to initialize the privileged service.",
            )
            state.binderAlive -> Item(
                category = "Drivers",
                title = "Shizuku Driver",
                status = Status.WARNING,
                summary = "Shizuku service is running, but permission is not granted",
                suggestion = "Click 'Grant access' to allow Nomad Droid to communicate with Shizuku.",
            )
            else -> Item(
                category = "Drivers",
                title = "Shizuku Driver",
                status = Status.INFO,
                summary = "Shizuku service is not active",
                suggestion = "If you plan to run Android Service jobs, start Shizuku. Otherwise, this is optional.",
            )
        }
    }

    private fun checkRoot(): Item {
        val state = RootManager.state()
        return when {
            state.permissionGranted -> Item(
                category = "Drivers",
                title = "Root Driver",
                status = Status.PASSED,
                summary = "Root access verified (UID=0, su available)",
            )
            state.suAvailable -> Item(
                category = "Drivers",
                title = "Root Driver",
                status = Status.INFO,
                summary = "su binary detected, but root permission not verified yet",
                suggestion = "Click 'Check / grant root access' if you want to run root-privileged jobs.",
            )
            else -> Item(
                category = "Drivers",
                title = "Root Driver",
                status = Status.INFO,
                summary = "No su binary found (device is unrooted)",
                suggestion = "Root is optional. Standard jobs can run via Shizuku or Termux.",
            )
        }
    }

    private fun checkTermux(): Item {
        val state = TermuxManager.state()
        return when {
            state.ready -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.PASSED,
                summary = "Termux installed, RUN_COMMAND permission granted, probe verified",
            )
            !state.installed -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.INFO,
                summary = "Termux is not installed",
                suggestion = "Install Termux from F-Droid if you want to execute shell workloads.",
            )
            !state.permissionGranted -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.WARNING,
                summary = "Termux is installed, but RUN_COMMAND permission is missing",
                suggestion = "Click 'Grant access' in Termux card or allow in Android App Info -> Other permissions.",
            )
            !state.serviceAvailable -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.FAILED,
                summary = "Termux RunCommandService is not responding",
                suggestion = "Make sure Termux version is >= 0.109.",
            )
            state.setupState != "ready" -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.WARNING,
                summary = "Termux setup probe unverified (${state.setupState})",
                suggestion = "Ensure allow-external-apps=true is in ~/.termux/termux.properties, then click 'Test setup'.",
            )
            else -> Item(
                category = "Drivers",
                title = "Termux Shell Driver",
                status = Status.PASSED,
                summary = state.message,
            )
        }
    }

    private fun checkBattery(context: Context): Item {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val isExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        return if (isExempt) {
            Item(
                category = "System",
                title = "Battery & Doze Policy",
                status = Status.PASSED,
                summary = "Battery optimization ignored (Nomad Droid is exempt from Doze mode)",
            )
        } else {
            Item(
                category = "System",
                title = "Battery & Doze Policy",
                status = Status.WARNING,
                summary = "Nomad Droid is subject to Android battery optimization",
                suggestion = "Click 'Battery settings' and allow unrestricted background execution to prevent node disconnections when screen is off.",
            )
        }
    }

    private fun checkNativeCore(): Item {
        val status = runCatching { NomadNative.status() }.getOrNull()
        return if (status != null && !status.contains("unavailable")) {
            Item(
                category = "System",
                title = "Embedded Nomad Core",
                status = Status.PASSED,
                summary = "Native library (libnomad_android.so) loaded and healthy",
            )
        } else {
            Item(
                category = "System",
                title = "Embedded Nomad Core",
                status = Status.FAILED,
                summary = "Native Nomad library load issue",
                suggestion = "Ensure ARM64 ABI is supported and APK was built correctly.",
            )
        }
    }
}
