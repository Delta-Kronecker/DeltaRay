package com.leadaxe.lxbox.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/// §049 F1 split (mirror reference SagerNet/Bailu style).
///
/// `BoxVpnService` — Android `VpnService`. В отличие от sing-box-слоя
/// (ядро звало `openTun` через PlatformInterface), здесь **app-driven TUN**:
/// сам сервис строит `VpnService.Builder` из настроек tun-inbound конфига
/// Xray и прокидывает fd ядру через `env.xray.tun.fd` (см. BoxService).
class BoxVpnService : VpnService() {

    companion object {
        private const val TAG = "BoxVpnService"
        const val ACTION_START = "com.leadaxe.lxbox.ACTION_START"
        const val ACTION_STOP = "com.leadaxe.lxbox.ACTION_STOP"
        /// §129 — force-stop при зависшем-вхолостую ядре (см. BoxService.doForceStop).
        const val ACTION_FORCE_STOP = "com.leadaxe.lxbox.ACTION_FORCE_STOP"
        const val ACTION_RELOAD = "com.leadaxe.lxbox.ACTION_RELOAD"
        const val ACTION_RESET_NETWORK = "com.leadaxe.lxbox.ACTION_RESET_NETWORK"
        /// §263 — сброс DNS-кэша (удалить cache.db + reload).
        const val ACTION_CLEAR_DNS_CACHE = "com.leadaxe.lxbox.ACTION_CLEAR_DNS_CACHE"
        /// §182 — кнопка Reconnect в foreground-уведомлении: native-side reconnect.
        const val ACTION_RECONNECT = "com.leadaxe.lxbox.ACTION_RECONNECT"
        /// §223 — live-перерисовка лейблов уведомления при смене ноды (#20).
        const val ACTION_UPDATE_NOTIFICATION = "com.leadaxe.lxbox.ACTION_UPDATE_NOTIFICATION"
        const val BROADCAST_STATUS = "com.leadaxe.lxbox.BROADCAST_STATUS"
        const val EXTRA_STATUS = "status"

        /// §415 — бюджет ожидания штатной остановки (`stopAwait`).
        /// Должен быть строго МЕНЬШЕ Dart `_Timeouts.stopVpn` (10с, зазор 1с), иначе
        /// внешний слой объявит таймаут раньше и вернёт ложную ошибку (см. §415).
        const val STOP_AWAIT_TIMEOUT_MS = 9_000L

        /// §276 — признак «туннель отобрало другое VPN-приложение».
        const val EXTRA_REVOKED = "revoked"

        @Volatile
        var currentStatus: VpnStatus = VpnStatus.Stopped
            private set

        /// §069: snapshot `allow_bypass` при последнем `establishTun()`.
        @Volatile
        var currentSessionAllowBypass: Boolean = false
            private set

        /// §187 — время старта туннеля (monotonic).
        @Volatile
        var tunnelStartedElapsedMs: Long = 0L
            private set

        /// §276 — зеркало «последний Stopped пришёл из onRevoke».
        @Volatile
        var currentRevoked: Boolean = false
            private set

        internal fun setCurrentStatus(s: VpnStatus, revoked: Boolean = false) {
            currentStatus = s
            currentRevoked = when (s) {
                VpnStatus.Starting, VpnStatus.Started -> false
                else -> revoked
            }
            when (s) {
                VpnStatus.Started ->
                    if (tunnelStartedElapsedMs == 0L) {
                        tunnelStartedElapsedMs = SystemClock.elapsedRealtime()
                    }
                VpnStatus.Stopped -> tunnelStartedElapsedMs = 0L
                else -> { /* Starting/Stopping — не трогаем */ }
            }
        }

        /// §361 — жив ли ACTION_STOP-приёмник.
        @Volatile
        var stopReceiverAlive: Boolean = false
            private set

        internal fun setStopReceiverAlive(alive: Boolean) {
            stopReceiverAlive = alive
        }

        @Volatile
        private var stopCompleter: CompletableDeferred<Unit>? = null

        internal fun completeStopIfWaiting() {
            stopCompleter?.complete(Unit)
            stopCompleter = null
        }

        /// §043: Sink для core logs (здесь — деградировано: Xray не стримит логи;
        /// продюсер отсутствует, поле сохранено для совместимости EventChannel).
        @Volatile
        var coreLogSink: io.flutter.plugin.common.EventChannel.EventSink? = null

        /// §122 Фаза 0 — sink'и new CommandClient-канала (`BoxCommandClient`).
        @Volatile
        var ccStatusSink: io.flutter.plugin.common.EventChannel.EventSink? = null
        @Volatile
        var ccOutboundsSink: io.flutter.plugin.common.EventChannel.EventSink? = null
        @Volatile
        var ccGroupsSink: io.flutter.plugin.common.EventChannel.EventSink? = null
        @Volatile
        var ccConnectionsSink: io.flutter.plugin.common.EventChannel.EventSink? = null
        /// §180 — DNS-журнал из ядра (SPEC 018). Деградировано: Xray не отдаёт
        /// per-query журнал, продюсер пуст (см. BoxCommandClient).
        @Volatile
        var ccDnsQueriesSink: io.flutter.plugin.common.EventChannel.EventSink? = null

        /// Текущий активный инстанс VpnService — для `protectFd` (DialerController)
        /// и обращения к VpnService-скоупу из фоновых корутин ядра.
        @Volatile
        private var activeService: BoxVpnService? = null

        /// §046 — protect исходящего сокета (ядро зовёт из DialerController).
        fun protectFd(fd: Int): Boolean =
            activeService?.runCatching { protect(fd) }?.getOrDefault(false) ?: false

        fun start(context: Context) {
            Log.d(TAG, "[vpn] companion.start() → startForegroundService, current status=${currentStatus.name}")
            val intent = Intent(context, BoxVpnService::class.java).apply { action = ACTION_START }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            Log.d(TAG, "[vpn] companion.stop() → sendBroadcast(ACTION_STOP), current status=${currentStatus.name}")
            context.sendBroadcast(
                Intent(ACTION_STOP).setPackage(context.packageName)
            )
        }

        /// §129 — fire-and-forget force-stop.
        fun forceStop(context: Context) {
            Log.w(TAG, "[vpn] companion.forceStop() → sendBroadcast(ACTION_FORCE_STOP), current status=${currentStatus.name}")
            context.sendBroadcast(
                Intent(ACTION_FORCE_STOP).setPackage(context.packageName)
            )
        }

        fun reload(context: Context) {
            Log.d(TAG, "[vpn] companion.reload() current status=${currentStatus.name}")
            context.sendBroadcast(
                Intent(ACTION_RELOAD).setPackage(context.packageName)
            )
        }

        fun resetNetwork(context: Context) {
            Log.d(TAG, "[vpn] companion.resetNetwork() current status=${currentStatus.name}")
            context.sendBroadcast(
                Intent(ACTION_RESET_NETWORK).setPackage(context.packageName)
            )
        }

        /// §263 — сброс DNS-кэша (degraded: Xray кэша cache.db не ведёт, на
        /// совместимость чистим ведущиеся ядром файлы, если они появятся).
        fun clearDnsCache(context: Context) {
            Log.d(TAG, "[vpn] companion.clearDnsCache() current status=${currentStatus.name}")
            if (currentStatus == VpnStatus.Started ||
                currentStatus == VpnStatus.Starting
            ) {
                context.sendBroadcast(
                    Intent(ACTION_CLEAR_DNS_CACHE).setPackage(context.packageName)
                )
            } else {
                deleteCacheDbFile()
            }
        }

        internal fun deleteCacheDbFile() {
            val f = File(BoxApplication.application.filesDir, "cache.db")
            if (!f.exists()) {
                Log.i(TAG, "[dns] cache.db absent — nothing to clear")
                return
            }
            val ok = runCatching { f.delete() }.getOrDefault(false)
            Log.i(TAG, "[dns] cache.db delete=$ok (${f.absolutePath})")
        }

        /// §223 — попросить работающий сервис перерисовать foreground-уведомление.
        fun updateNotification(context: Context) {
            context.sendBroadcast(
                Intent(ACTION_UPDATE_NOTIFICATION).setPackage(context.packageName)
            )
        }

        fun stopAwait(context: Context): Deferred<Unit> {
            Log.d(TAG, "[vpn] companion.stopAwait() current status=${currentStatus.name}")
            if (currentStatus == VpnStatus.Stopped) {
                return CompletableDeferred(Unit)
            }
            if (!stopReceiverAlive) {
                Log.w(TAG, "[vpn §361] stopAwait: no live receiver (status=${currentStatus.name}) — force Stopped")
                setCurrentStatus(VpnStatus.Stopped)
                runCatching {
                    context.sendBroadcast(
                        Intent(BROADCAST_STATUS)
                            .setPackage(context.packageName)
                            .putExtra(EXTRA_STATUS, VpnStatus.Stopped.name)
                    )
                }
                completeStopIfWaiting()
                return CompletableDeferred(Unit)
            }
            val completer = CompletableDeferred<Unit>()
            stopCompleter?.cancel()
            stopCompleter = completer
            context.sendBroadcast(
                Intent(ACTION_STOP).setPackage(context.packageName)
            )
            return completer
        }

        private val reconnectScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Volatile
        private var reconnecting: Boolean = false

        /// §182 — native-side reconnect: stopAwait() → start().
        fun reconnect(context: Context) {
            Log.d(TAG, "[vpn] companion.reconnect() current status=${currentStatus.name}")
            if (reconnecting) {
                Log.w(TAG, "[vpn] reconnect already in progress — ignore")
                return
            }
            if (currentStatus == VpnStatus.Stopped) {
                start(context)   // нечего останавливать — просто старт
                return
            }
            reconnecting = true
            reconnectScope.launch {
                val stopped = try {
                    withTimeout(STOP_AWAIT_TIMEOUT_MS) { stopAwait(context).await(); true }
                } catch (t: Throwable) {
                    Log.w(TAG, "[vpn] reconnect: stop phase failed/timeout: ${t.message}")
                    false
                }
                if (stopped) {
                    start(context)   // startForegroundService(ACTION_START)
                } else {
                    Log.w(TAG, "[vpn] reconnect aborted — stop not confirmed")
                }
                reconnecting = false
            }
        }
    }

    /// §049 F1 — field initializer: инстанс создаётся при создании Android Service,
    /// до onCreate(). Strong-ref на BoxService живёт всё время жизни сервиса.
    private val service = BoxService(this)

    /// Открытый нами tun-parcel. Закрывается BoxService при stop/reload.
    @Volatile
    var tun: ParcelFileDescriptor? = null

    // -------------------------------------------------------------------------
    // Android lifecycle — forward в BoxService
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        activeService = this
        service.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return service.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent) ?: android.os.Binder()

    override fun onDestroy() {
        service.onDestroy()
        currentSessionAllowBypass = false
        activeService = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        service.onTaskRemoved(rootIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onRevoke() {
        service.onRevoke()
        super.onRevoke()
    }

    // -------------------------------------------------------------------------
    // app-driven TUN (Xray-слой)
    // -------------------------------------------------------------------------

    /// Открывает TUN по настройкам tun-inbound из конфига Xray. Возвращает fd
    /// (ядро получит его через `xray.tun.fd`) или null на ошибку (лог без броска).
    /// Вызывается из BoxService на Dispatchers.IO.
    fun establishTun(configJson: String): Int? {
        if (prepare(this) != null) {
            Log.e(TAG, "establishTun: missing vpn permission")
            return null
        }
        val cfg = runCatching { parseTunConfig(configJson) }.getOrElse { err ->
            Log.e(TAG, "establishTun: bad config: ${err.message}")
            return null
        }

        val builder = Builder()
            .setSession("lxbox")
            .setMtu(cfg.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val allowBypass = BootReceiver.isAllowBypass(this)
        currentSessionAllowBypass = allowBypass
        if (allowBypass) {
            builder.allowBypass()
        }

        cfg.addresses.forEach { addr ->
            runCatching { builder.addAddress(addr.ip(), addr.prefix()) }
                .onFailure { Log.w(TAG, "establishTun: bad address $addr: ${it.message}") }
        }

        cfg.dnsServers.forEach { builder.addDnsServer(it) }

        // §migration (минимальный срез): full-header route как у sing-box
        // autoRoute. Xray на Android не маршрутизирует сам (маршруты ставит
        // VpnService.Builder) — целимся в 0/0, маски точных IP из допустимых
        // связок config/zones — следующий workunit.
        builder.addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.addRoute("::", 0)
        }

        val pfd = builder.establish()
            ?: run {
                Log.e(TAG, "establishTun: not prepared or revoked")
                return null
            }
        tun?.runCatching { close() }.let { /* orphaned previous — закрыт */ }
        tun = pfd
        Log.w(TAG, "[fd §329] establishTun fd=${pfd.fd} at=${SystemClock.elapsedRealtime()}ms")
        return pfd.fd
    }

    private data class TunConfig(
        val mtu: Int,
        val addresses: List<String>,
        val dnsServers: List<String>,
    )

    private fun parseTunConfig(configJson: String): TunConfig {
        val root = JSONObject(configJson)
        var mtu = 9000
        val addresses = mutableListOf<String>()
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (i in 0 until inbounds.length()) {
                val inb = inbounds.optJSONObject(i) ?: continue
                if (inb.optString("protocol") != "tun") continue
                mtu = inb.optJSONObject("settings")?.optInt("mtu")?.takeIf { it in 68..65535 } ?: mtu
                val addrArr = inb.optJSONObject("settings")?.optJSONArray("address") ?: continue
                for (j in 0 until addrArr.length()) addrArr.optString(j).takeIf { it.isNotEmpty() }?.let { addresses += it }
            }
        }
        // DNS-сервера для Android (Xray сам ходит в DNS своими сокетами; сюда —
        // только чтобы VpnService.Builder знал резолверы tun).
        val dnsServers = mutableListOf<String>()
        val dnsRoot = root.optJSONObject("dns")
        val srvArr = dnsRoot?.optJSONArray("servers")
        if (srvArr != null) {
            for (i in 0 until srvArr.length()) {
                val s = srvArr.opt(i)
                when (s) {
                    is String -> dnsServers += s
                    is JSONObject -> s.optString("address").takeIf { it.isNotEmpty() }?.let { dnsServers += it }
                }
            }
        }
        if (dnsServers.isEmpty()) dnsServers += "8.8.8.8"
        return TunConfig(mtu, addresses.ifEmpty { listOf("10.0.0.1", "fdfe:dcba:9876::1") }, dnsServers)
    }
}

private fun String.ip(): String = substringBefore('/')
private fun String.prefix(): Int = substringAfter('/', "24").toIntOrNull() ?: 24