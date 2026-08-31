package com.nomad.droid.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TermuxManager.ACTION_RESULT) return
        TermuxManager.initialize(context)
        val taskID = intent.getStringExtra(TermuxManager.EXTRA_TASK_ID) ?: return
        val kind = intent.getStringExtra(TermuxManager.EXTRA_RESULT_KIND) ?: return
        runCatching {
            TermuxManager.handleResult(
                taskID,
                kind,
                intent.getBundleExtra(TermuxContract.EXTRA_RESULT_BUNDLE),
            )
        }
    }
}
