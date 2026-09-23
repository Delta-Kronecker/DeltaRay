package com.leadaxe.lxbox.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.leadaxe.lxbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import libXray.LibXray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/// §migration — BoxService для слоя libXray (Xray-core).
///
/// Владеет state'ом и lifecycle'ом Xray-runtime'а:
///  - конфиг sing-box из ConfigManager → `XrayConfigTranslator` (best-effort);
///  - app-driven TUN (BoxVpnService.establishTun) → fd в `env.xray.tun.fd`;
///  - `runXray`/`stopXray` через JSON-RPC invoke;
///  - командный gRPC-слой (`XrayApiClient` + `BoxCommandClient`).
///
/// Чего уже НЕТ (было в sing-box слое): CommandServer/gRPC-слушатель внутри
/// ядра, pause/wake (idle/screen), rebindStaleEndpoints, core log steam
/// (writeDebugMessage) и platform-notifications из ядра — см. §migration,
/// деградации задокументированы на Dart-стороне.
class BoxService(private val service: Service) {

    companion object {
        private const val TAG = "BoxService"

        /// §223 Часть B (#23) — задержка перед native-snapshot'ом подтекста
        /// уведомления при старте без UI.
        private const val NOTIFICATION_SNAPSHOT_DELAY_MS = 3000L

        /// §122 Фаза 0 — статическая ссылка на активный `BoxCommandClient`.
        /// @Volatile: пишется из service-потока, читается из Flutter MethodChannel-потока.
        @Volatile
        var commandClient: BoxCommandClient? = null
            private set

        /// §345 — live-режим verbose core-логов. Xray не стримит логи в Java-слой,
        /// поэтому здесь оставлено флагом совместимости с VpnPlugin (self-coreLogs).
        @Volatile
        @JvmStatic
        var coreLogsVerbose: Boolean = false

        /// JSON-RPC invoke c проверкой success-флага обёртки.
        /// При success=false бросает с текстом error/message.
        fun invokeXray(method: String, payload: JSONObject = JSONObject()): JSONObject {
            val req = JSONObject()
            req.put("apiVersion", 3)
            req.put("method", method)
            req.put("payload", payload)
            val respText = LibXray.invoke(req.toString())
            val resp = JSONObject(respText)
            val ok = resp.optBoolean("success", false)
            if (!ok) {
                val err = resp.optString("error").ifEmpty { resp.optString("message") }
                    .ifEmpty { resp.optString("data") }
                throw IllegalStateException("$method: $err")
            }
            return resp.optJSONObject("data") ?: JSONObject()
        }
    }

    /// Scoped to service lifetime — all child coroutines cancelled in onDestroy / doStop.
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: kotlinx.coroutines.Job? = null

    /// §140 — отдельный scope ТОЛЬКО для `doForceStop`-teardown'а (onDestroy не отменяет).
    private var forceStopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun resetScope() {
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        forceStopScope.cancel()
        forceStopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /// TUN parcel, открытый нами (app-driven). Закрывается при stop/reload/revoke.
    private val fileDescriptor = AtomicReference<ParcelFileDescriptor?>(null)

    /// Идёт ли Xray-инстанс (после runXray, до stopXray). Решение для reload.
    @Volatile
    private var coreRunning = false

    /// Текущий api-порт переведённого конфига.
    private var apiPort = XrayConfigTranslator.API_PORT

    @Volatile
    private var receiverRegistered = false
        set(value) {
            field = value
            BoxVpnService.setStopReceiverAlive(value)
        }
    private var status = VpnStatus.Stopped

    private val notification: ServiceNotification by lazy { ServiceNotification(service) }

    private val receiver = object : BroadcastReceiver() {
        private fun offloadFromMain(action: String, block: () -> Unit) {
            val pending = goAsync()
            serviceScope.launch {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "$action failed", t)
                } finally {
                    runCatching { pending.finish() }
                        .onFailure { Log.e(TAG, "$action: finish() failed", it) }
                }
            }
        }

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "[vpn] receiver.onReceive action=${intent.action} status=${status.name}")
            when (intent.action) {
                BoxVpnService.ACTION_STOP -> doStop()
                BoxVpnService.ACTION_FORCE_STOP -> doForceStop()
                BoxVpnService.ACTION_RECONNECT -> {
                    Log.d(TAG, "[vpn] receiver: ACTION_RECONNECT → BoxVpnService.reconnect()")
                    runCatching { BoxVpnService.reconnect(service.applicationContext) }
                        .onFailure { Log.e(TAG, "ACTION_RECONNECT failed", it) }
                }
                BoxVpnService.ACTION_RELOAD -> {
                    Log.d(TAG, "[vpn] receiver: ACTION_RELOAD → serviceReload() (off-main)")
                    offloadFromMain("ACTION_RELOAD") { serviceReload() }
                }
                BoxVpnService.ACTION_RESET_NETWORK -> {
                    // §migration — resetNetwork из ядра sing-box исчез; деградация:
                    // пересборка инстанса (drop+rebind сокетов) через reload.
                    Log.d(TAG, "[vpn] receiver: ACTION_RESET_NETWORK → serviceReload() (off-main, degraded)")
                    offloadFromMain("ACTION_RESET_NETWORK") { serviceReload() }
                }
                BoxVpnService.ACTION_CLEAR_DNS_CACHE -> {
                    Log.d(TAG, "[vpn] receiver: ACTION_CLEAR_DNS_CACHE → delete cache.db + serviceReload() (off-main)")
                    offloadFromMain("ACTION_CLEAR_DNS_CACHE") {
                        BoxVpnService.deleteCacheDbFile()
                        serviceReload()
                    }
                }
                BoxVpnService.ACTION_UPDATE_NOTIFICATION -> {
                    if (status == VpnStatus.Started) {
                        runCatching {
                            notification.show(
                                ConfigManager.notificationTitle,
                                ConfigManager.notificationText.ifEmpty {
                                    L10n.str(service, R.string.status_connected)
                                },
                            )
                        }.onFailure { Log.e(TAG, "ACTION_UPDATE_NOTIFICATION failed", it) }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Android lifecycle (forwarded from BoxVpnService)
    // -------------------------------------------------------------------------

    fun onCreate() {
        // Сервис может стартануть в свежем процессе без UI (QS-tile).
        BoxApplication.initialize(service.applicationContext)
    }

    /// §428 — START_STICKY из обоих выходов (см. историю §428; логика копирована
    /// из sing-box слоя, предохранитель sticky-шторма сохранён).
    fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[vpn] onStartCommand action=${intent?.action} status=${status.name} startId=$startId")
        if (intent == null) {
            val n = BootReceiver.noteStickyRestart(service)
            Log.w(TAG, "[vpn §428] sticky restart #$n (limit=${BootReceiver.STICKY_RESTART_LIMIT} per ${BootReceiver.STICKY_RESTART_WINDOW_MS / 1000}s)")
            if (n >= BootReceiver.STICKY_RESTART_LIMIT) {
                notification.showAlert(
                    L10n.str(service, R.string.sticky_restart_storm_title),
                    L10n.str(service, R.string.sticky_restart_storm_text),
                )
                service.stopSelf()
                return Service.START_NOT_STICKY
            }
        }
        try {
            notification.show(
                ConfigManager.notificationTitle,
                L10n.str(service, R.string.notification_status_starting),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "[vpn §428] startForeground failed — stopSelf", t)
            service.stopSelf()
            return Service.START_NOT_STICKY
        }

        if (status != VpnStatus.Stopped) {
            Log.w(TAG, "[vpn] onStartCommand GUARD — status=${status.name} != Stopped, silent return")
            return Service.START_STICKY
        }
        resetScope()
        setStatus(VpnStatus.Starting)

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(service, receiver, IntentFilter().apply {
                addAction(BoxVpnService.ACTION_STOP)
                addAction(BoxVpnService.ACTION_FORCE_STOP)
                addAction(BoxVpnService.ACTION_RECONNECT)
                addAction(BoxVpnService.ACTION_RELOAD)
                addAction(BoxVpnService.ACTION_RESET_NETWORK)
                addAction(BoxVpnService.ACTION_CLEAR_DNS_CACHE)
                addAction(BoxVpnService.ACTION_UPDATE_NOTIFICATION)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }

        serviceScope.launch {
            try {
                BoxApplication.libboxReady.await()
                startCore()
            } catch (t: Throwable) {
                Log.e(TAG, "Start failed", t)
                stopAndAlert(t.message
                    ?: L10n.str(service, R.string.stop_alert_unknown_error))
            }
        }
        return Service.START_STICKY
    }

    fun onDestroy() {
        Log.d(TAG, "[vpn] onDestroy status=${status.name}")
        serviceScope.cancel()
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (BoxVpnService.currentStatus != VpnStatus.Stopped) {
            BoxVpnService.setCurrentStatus(VpnStatus.Stopped)
            runCatching { LxBoxTileService.refreshTile(service.applicationContext) }
                .onFailure { Log.w(TAG, "refreshTile in onDestroy failed: ${it.message}") }
        }
    }

    fun onTaskRemoved(rootIntent: Intent?) {
        if (!BootReceiver.isKeepOnExit(service)) {
            Log.d(TAG, "App removed from recents — stopping VPN")
            doStop()
        }
    }

    fun onRevoke() {
        Log.d(TAG, "onRevoke — VPN taken by another app")
        closeTun("onRevoke")
        closeCore("revoke")

        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        notification.stop()
        setStatus(
            VpnStatus.Stopped,
            error = "Another VPN app took the system VPN slot " +
                "(e.g. an always-on VPN). Start again to reconnect.",
            revoked = true,
        )
        serviceScope.cancel()
        service.stopSelf()
    }

    // -------------------------------------------------------------------------
    // Xray lifecycle
    // -------------------------------------------------------------------------

    private fun closeTun(reason: String) {
        val pfd = fileDescriptor.getAndSet(null)
        val fd = pfd?.runCatching { fd }?.getOrNull()
        Log.w(TAG, "[fd] close($reason) fd=${fd ?: "already-closed"}")
        pfd?.runCatching { close() }
            ?.onFailure { Log.w(TAG, "closeTun($reason): close failed: ${it.message}") }
    }

    /// Остановка Xray-инстанса и командного канала. Идемпотентно.
    private fun closeCore(reason: String) {
        runCatching { commandClient?.shutdown() }
            .onFailure { Log.w(TAG, "closeCore($reason): commandClient shutdown failed: ${it.message}") }
        commandClient = null
        if (coreRunning) {
            runCatching { invokeXray("stopXray") }
                .onFailure { Log.w(TAG, "closeCore($reason): stopXray failed: ${it.message}") }
            coreRunning = false
        }
    }

    private suspend fun startCore() {
        try {
            BoxApplication.libboxReady.await()
        } catch (t: Throwable) {
            stopAndAlert(L10n.str(
                service, R.string.stop_alert_libbox_init_failed, t.message ?: ""))
            return
        }

        val config = ConfigManager.load()
        if (config.isBlank() || config == "{}") {
            stopAndAlert(L10n.str(service, R.string.stop_alert_empty_config))
            return
        }

        // sing-box → Xray (best-effort, см. translator).
        val baseDir = service.applicationContext.filesDir.path
        val translated = runCatching {
            XrayConfigTranslator.translate(config, baseDir)
        }.getOrElse { t ->
            Log.e(TAG, "config translate failed", t)
            stopAndAlert("Section error: ${t.message}")
            return
        }
        apiPort = translated.apiPort

        // app-driven TUN: открываем сами, отдаём ядру fd.
        val vpn = service as? BoxVpnService
        if (vpn == null) {
            stopAndAlert("no BoxVpnService instance")
            return
        }
        val tunFd = vpn.establishTun(translated.json) ?: run {
            stopAndAlert("establishTun failed")
            return
        }
        fileDescriptor.set(vpn.tun)

        // Пропустить fd в env корня конфига (libXray Android: env.xray.tun.fd).
        val patched = JSONObject(translated.json)
        val env = patched.optJSONObject("env") ?: JSONObject()
        env.put("xray.tun.fd", tunFd.toString())
        patched.put("env", env)
        val xrayJson = patched.toString()

        // §087 — на genuine смену интерфейса пересобираем ядро (деградация
        // resetNetwork из sing-box слоя): закрывает стейл-сокеты на мёртвом NIC.
        DefaultNetworkMonitor.start(serviceScope) {
            Log.d(TAG, "[vpn] interface switch → serviceReload() (degraded resetNetwork)")
            runCatching { serviceReload() }
                .onFailure { Log.e(TAG, "auto serviceReload failed", it) }
        }

        // runXray: JSON-RPC через invoke.
        val payload = JSONObject()
        payload.put("xrayJson", xrayJson)
        val data = runCatching { invokeXray("runXray", payload) }
            .getOrElse { t ->
                Log.e(TAG, "runXray failed", t)
                stopAndAlert(L10n.str(
                    service, R.string.stop_alert_start_failed, t.message ?: ""))
                return
            }
        Log.i(TAG, "runXray ok, configPath=${data.optString("configPath")}")
        coreRunning = true

        // §361/§387 — старт мог быть отменён, пока висели в блокирующем invoke.
        if (!currentCoroutineContext().isActive || status != VpnStatus.Starting) {
            Log.w(TAG, "[vpn §361/§387] start cancelled while blocked — teardown")
            closeTun("late-start-cancel")
            closeCore("late-start-cancel")
            return
        }
        setStatus(VpnStatus.Started)

        // Поднять командный gRPC-канал к commander (api блок).
        runCatching {
            val cc = BoxCommandClient(service.applicationContext, apiPort, config)
            commandClient = cc
            cc.startStatus()
        }.onFailure { Log.w(TAG, "BoxCommandClient.startStatus failed: ${it.message}") }

        // §223 Часть B — если UI не открывался, рисуем подтекст «Connected».
        withContext(Dispatchers.Main) {
            notification.show(
                ConfigManager.notificationTitle,
                L10n.str(service, R.string.status_connected),
            )
        }
    }

    /// Пересборка инстанса (stopXray → runXray). Деградация resetNetwork/pause
    /// для событий, где sing-box ядро вызывало reload само. no-throw: reload —
    /// rеаctируется на сбои изнутри startCore (stopAndAlert).
    fun serviceReload() {
        if (status != VpnStatus.Started) {
            Log.w(TAG, "serviceReload: status=${status.name} — skip")
            return
        }
        runCatching {
            closeTun("reload")
            closeCore("reload")
        }.onFailure { Log.e(TAG, "serviceReload teardown failed", it) }
        delay(150)
        serviceScope.launch {
            try {
                startCore()
            } catch (t: Throwable) {
                Log.e(TAG, "serviceReload start failed", t)
                stopAndAlert(t.message
                    ?: L10n.str(service, R.string.stop_alert_unknown_error))
            }
        }
    }

    private fun doStop() {
        Log.d(TAG, "[vpn] doStop ENTER status=${status.name}")
        if (status == VpnStatus.Stopped || status == VpnStatus.Stopping) {
            Log.w(TAG, "[vpn] doStop GUARD — already ${status.name}, return")
            return
        }
        setStatus(VpnStatus.Stopping)

        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        notification.stop()

        serviceScope.launch {
            closeTun("doStop")
            DefaultNetworkMonitor.stop()
            closeCore("doStop")

            withContext(Dispatchers.Main) {
                Log.d(TAG, "[vpn] doStop cleanup done → setStatus(Stopped) + stopSelf()")
                setStatus(VpnStatus.Stopped)
                service.stopSelf()
            }
        }
    }

    /// §129 — жёсткая остановка при зависшем ядре: UI гасим сразу на main,
    /// teardown — на forceStopScope (onDestroy его НЕ отменяет).
    private fun doForceStop() {
        Log.w(TAG, "[vpn] doForceStop ENTER status=${status.name}")
        if (status == VpnStatus.Stopped) {
            Log.d(TAG, "[vpn] doForceStop — already Stopped, no-op")
            return
        }
        if (receiverRegistered) {
            runCatching { service.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        notification.stop()
        setStatus(VpnStatus.Stopped)
        Log.w(TAG, "[vpn] doForceStop — UI/notification stopped, teardown+stopSelf on forceStopScope")

        forceStopScope.launch {
            runCatching { withTimeout(2_000) { closeTun("doForceStop") } }
                .onFailure { Log.w(TAG, "doForceStop: closeTun timeout/fail: ${it.message}") }
            runCatching { withTimeout(2_000) { DefaultNetworkMonitor.stop() } }
                .onFailure { Log.w(TAG, "doForceStop: DefaultNetworkMonitor.stop timeout/fail: ${it.message}") }
            runCatching { withTimeout(2_000) { closeCore("doForceStop") } }
                .onFailure { Log.w(TAG, "doForceStop: closeCore timeout/fail: ${it.message}") }
            Log.d(TAG, "[vpn] doForceStop — teardown done → stopSelf()")
            withContext(Dispatchers.Main) { service.stopSelf() }
        }
    }

    private suspend fun stopAndAlert(message: String) {
        Log.e(TAG, "stopAndAlert: $message")
        closeTun("stopAndAlert")
        DefaultNetworkMonitor.stop()
        closeCore("stopAndAlert: $message")

        withContext(Dispatchers.Main) {
            notification.show(
                L10n.str(service, R.string.notification_error_title), message)
            if (receiverRegistered) {
                runCatching { service.unregisterReceiver(receiver) }
                receiverRegistered = false
            }
            notification.stop()
            setStatus(VpnStatus.Stopped, error = message)
            service.stopSelf()
        }
    }

    /// §276 — [revoked] = «Stopped пришёл из onRevoke».
    private fun setStatus(
        newStatus: VpnStatus,
        error: String? = null,
        revoked: Boolean = false,
    ) {
        if (status == newStatus && error == null) {
            Log.d(TAG, "[vpn] setStatus(${newStatus.name}) — same status, dedup")
            return
        }
        Log.d(TAG, "[vpn] setStatus(${newStatus.name})${if (error != null) " error=$error" else ""} — sendBroadcast")
        status = newStatus
        BoxVpnService.setCurrentStatus(newStatus, revoked)
        val appCtx = service.applicationContext
        when (newStatus) {
            VpnStatus.Started -> {
                BootReceiver.resetStickyRestarts(appCtx)
                BootReceiver.setVpnDesired(appCtx, true)
                watchdogJob?.cancel()
                watchdogJob = VpnWatchdog.startTicker(appCtx, serviceScope) {
                    status == VpnStatus.Started
                }
            }
            VpnStatus.Stopped -> {
                BootReceiver.setVpnDesired(appCtx, false)
                watchdogJob?.cancel()
                watchdogJob = null
                VpnWatchdog.disarm(appCtx)
            }
            else -> {}
        }
        if (newStatus == VpnStatus.Stopped) {
            BoxVpnService.completeStopIfWaiting()
        }
        service.sendBroadcast(
            Intent(BoxVpnService.BROADCAST_STATUS).apply {
                `package` = service.packageName
                putExtra(BoxVpnService.EXTRA_STATUS, newStatus.name)
                if (error != null) putExtra("error", error)
                if (revoked) putExtra(BoxVpnService.EXTRA_REVOKED, true)
            }
        )
        runCatching { LxBoxTileService.refreshTile(service.applicationContext) }
            .onFailure { Log.w(TAG, "refreshTile failed: ${it.message}") }
        runCatching { QuickShortcuts.refresh(service.applicationContext) }
            .onFailure { Log.w(TAG, "QuickShortcuts.refresh failed: ${it.message}") }
    }
}