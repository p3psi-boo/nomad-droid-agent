package com.nomad.droid.runtime

object NomadNative {
    private val loadError: Throwable? = runCatching {
        System.loadLibrary("nomad_android")
    }.exceptionOrNull()

    fun start(configJson: String): Int {
        loadError?.let { throw IllegalStateException("Native Nomad library is unavailable", it) }
        return nativeStart(configJson)
    }

    fun stop() {
        loadError?.let { return }
        nativeStop()
    }

    fun status(): String {
        loadError?.let { return "{\"state\":\"unavailable\",\"error\":${jsonQuote(it.message)}}" }
        return nativeStatus()
    }

    private external fun nativeStart(configJson: String): Int
    private external fun nativeStop()
    private external fun nativeStatus(): String

    private fun jsonQuote(value: String?): String =
        org.json.JSONObject.quote(value ?: "unknown native load error")
}

