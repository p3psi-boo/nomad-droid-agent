package com.nomad.droid.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nomad.droid.termux.TermuxManager

class AgentRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TermuxManager.initialize(context)
            TermuxManager.markInterruptedByReboot()
        }

        val store = AgentConfigStore(context)
        if (!store.desiredRunning) return

        runCatching {
            context.startForegroundService(AgentForegroundService.startIntent(context))
        }.onFailure {
            store.runtimeStatus = "Restore pending"
            store.lastResult = it.message ?: it.javaClass.simpleName
        }
    }

    private companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
