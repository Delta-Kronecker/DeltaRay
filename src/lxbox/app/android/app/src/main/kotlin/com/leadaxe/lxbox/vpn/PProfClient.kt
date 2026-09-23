package com.leadaxe.lxbox.vpn

import android.util.Log

/// §207 — диагностические pprof-слепки. §migration: libbox давал встроенный
/// `PProfServer` (Go net/http/pprof); у libXray такого сервиса нет (pprof
/// включается в конфиге Xray опцией `pprof.listen`, в минимальном срезе не
/// поднимается) → все методы деградированы: честный throw, caller (VpnPlugin
/// `pprofProfile`) обернёт в error-ответ канала.
object PProfClient {
    private const val TAG = "PProfClient"

    private const val DEGRADED = "pprof not available (Xray core, degraded)"

    fun goroutineDump(): String =
        throw UnsupportedOperationException(DEGRADED)

    fun cpuProfile(seconds: Int, headroomMs: Int = 5000): ByteArray =
        throw UnsupportedOperationException(DEGRADED)

    fun fetch(pathAndQuery: String, readTimeoutMs: Int): ByteArray {
        Log.d(TAG, "fetch($pathAndQuery) degraded: $DEGRADED")
        throw UnsupportedOperationException(DEGRADED)
    }
}