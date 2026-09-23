package com.leadaxe.lxbox.vpn

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import go.Seq
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import libXray.DialerController
import libXray.LibXray
import libXray.ProcessFinder

/**
 * §049 — Application class зарегистрирован в AndroidManifest как
 * `android:name=".vpn.BoxApplication"`. Android создаёт Application до
 * любого Service / Activity → `go.Seq.setContext` и регистрация
 * controller'ов гарантированно отрабатывают до первого Invoke. Слой —
 * libXray (Xray-core), исходно здесь был libbox (sing-box), см. §migration.
 *
 * `Seq.setContext(this)` обязателен: gobind-ядро в Android-режиме использует
 * context для RunOnJvm (getSystemService в провайдерах сети и т.п.).
 */
class BoxApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        runCatching { QuickShortcuts.refresh(this) }
            .onFailure { android.util.Log.w(TAG, "QuickShortcuts.refresh failed: ${it.message}") }

        // §051 Phase 3 — singleton observer. start()/stop() driven by
        // `auto_record_wifi_history` storage flag.
        wifiObserver = WifiNetworkObserver(this)

        // gotoInit асинхронно в IO. К моменту start VPN регистрация завершена.
        // `libboxReady` — sync-барьер для VPN auto-start / QS-tile (race-safe).
        @Suppress("OPT_IN_USAGE")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                initializeLibxray(this@BoxApplication)
                libboxReady.complete(Unit)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "initializeLibxray failed", t)
                libboxReady.completeExceptionally(t)
            }
        }
    }

    private fun initializeLibxray(context: Context) {
        val baseDir = context.filesDir.also { it.mkdirs() }
        val workingDir = baseDir
        val tempDir = context.cacheDir.also { it.mkdirs() }

        // §334 — событие «прошлый запуск упал»: непустой CrashReport-lxbox.log.
        // libXray флагов не пишет, но чистим кэши как прежде (безвредно) —
        // дедупа повторного триггера нет, сохранена только страховка.
        if (CrashRecovery.crashedOnPreviousLaunch(workingDir)) {
            CrashRecovery.resetKernelCaches(tempDir)
        }

        Seq.setContext(context)

        // §046 — исходящие сокеты ядра защищаются от TUN (исключение UID);
        // иначе собственный трафик Xray зацикливается через tun-интерфейс.
        val protector = object : DialerController {
            override fun protectFd(fd: Long): Boolean =
                runCatching { BoxVpnService.protectFd(fd.toInt()) }.getOrDefault(false)
        }
        LibXray.registerDialerController(protector)

        // ProcessFinder: поиск UID владельца соединения для observatory-land.
        // Минимальный срез: ядро уже знает UID самостоятельно (Android per-app
        // proxy у нас выключен) — отдаём -1 (UI рисует «unknown app»).
        LibXray.registerProcessFinder(
            object : ProcessFinder {
                override fun findProcessByConnection(
                    network: String?, srcIP: String?, srcPort: Long,
                    destIP: String?, destPort: Long,
                ): Long = -1L
            },
            Build.VERSION.SDK_INT.toLong(),
        )

        // SetDNS намеренно не зовём (минимальный срез §migration): DNS Go-резолвера
        // остаётся системным, а его сокеты защищены DialerController — петля
        // через tun исключена. Порт config-driven DNS — в след. workunit'е.
    }

    companion object {
        private const val TAG = "BoxApplication"

        @Volatile
        internal lateinit var instance: BoxApplication

        /** Готовность регистрации слоя libXray. */
        val libboxReady: CompletableDeferred<Unit> = CompletableDeferred()

        /** §051 Phase 3 — singleton WifiNetworkObserver. */
        @Volatile
        internal lateinit var wifiObserver: WifiNetworkObserver

        // -------------------------------------------------------------------
        // Backward-compat API — callsite'ы `BoxApplication.X` работают через
        // companion proxy на `instance`.
        // -------------------------------------------------------------------

        val application: Context get() = instance

        val powerManager: PowerManager
            get() = instance.getSystemService(Context.POWER_SERVICE) as PowerManager

        val connectivity: ConnectivityManager
            get() = instance.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val packageManager get() = instance.packageManager

        val notificationManager: NotificationManager
            get() = instance.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        /**
         * §049 F12.3: WifiManager через application context — на API >= R
         * Activity context может выкидывать `IllegalStateException`.
         */
        val wifiManager: WifiManager
            get() = instance.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        /**
         * Версия ядра Xray (jsonrpc `xrayVersion`). no-throw: "" на сбой.
         */
        fun coreVersion(): String = runCatching {
            val resp = LibXray.invoke("""{"apiVersion":3,"method":"xrayVersion","payload":{}}""")
            val obj = org.json.JSONObject(resp)
            obj.optJSONObject("data")?.optString("version").orEmpty()
        }.getOrElse { err ->
            android.util.Log.w(TAG, "xrayVersion failed: ${err.message}")
            ""
        }

        /**
         * No-op — backward compat. Application инициализируется Android
         * runtime'ом до Service/Activity.
         */
        @Suppress("UNUSED_PARAMETER")
        fun initialize(context: Context) {
            // No-op
        }
    }
}