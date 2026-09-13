package com.leadaxe.lxbox.vpn

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/// Вачдог туннеля (ТЗ оператора): фоновая проверка реального интернет-выхода
/// каждые [TunnelWatchdog.PROBE_INTERVAL_MS] через ЛОКАЛЬНЫЙ прокси
/// (`127.0.0.1:2080`, vpn_proxy §119) с твёрдым таймаутом 10с. При отказе —
/// ищем живые конфиги RPC'ом на ядро и переключаем `route.final` на конфиг
/// с наименьшим пингом (минуя текущий). После переключения статистика
/// сбрасывается (ТЗ оператора: обнуляются и «всего»-счётчики), состояние —
/// starting.
///
/// Живёт пока VPN запущен: receiver на BROADCAST_STATUS (Started → старт
/// цикла, Stopped → stopSelf). Лаунчер стартует сервис обычным `startService`
/// в момент успешного коннекта; на провале фазы коннекта — останавливает
/// явно. UI-статистика — из [WatchdogStats], лаунчер тикает (3с).
class WatchdogService : Service() {

    private val TAG = "WatchdogService"
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    @Volatile
    private var lastSwitchAttemptMs: Long = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BoxVpnService.BROADCAST_STATUS) return
            val name = intent.getStringExtra(BoxVpnService.EXTRA_STATUS) ?: return
            Log.d(TAG, "status broadcast: $name")
            when (name) {
                VpnStatus.Started.name -> ensureLoop()
                VpnStatus.Stopped.name -> stopSelf()
                else -> {}
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchdogStats.init(applicationContext)
        registerReceiver(
            statusReceiver,
            IntentFilter(BoxVpnService.BROADCAST_STATUS),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Если VPN уже поднят (сервис стартовали на phase-connect) — цикл
        // сразу; иначе statusReceiver поднимет при переходах.
        if (BoxVpnService.currentStatus == VpnStatus.Started) ensureLoop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        scope?.cancel()
        scope = null
        loopJob = null
        super.onDestroy()
    }

    private fun ensureLoop() {
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (loopJob?.isActive == true) return
        loopJob = scope?.launch { loop() }
    }

    private suspend fun loop() {
        Log.d(TAG, "watchdog loop started")
        WatchdogStats.instance.setState(WatchdogStats.State.Starting)
        while (coroutineContext.isActive) {
            if (BoxVpnService.currentStatus != VpnStatus.Started) break
            val stats = WatchdogStats.instance
            val config = ConfigManager.load()
            val proxy = TunnelWatchdog.localProxy(config)
            if (proxy == null || proxy.authEnabled || !proxy.socksCapable) {
                // Нет HTTP-прокси из конфига (не vpn_proxy) — мониторить нечем
                // и не на что переключать: состояние disabled, интервал выжидаем.
                stats.recordDisabled()
                delay(TunnelWatchdog.PROBE_INTERVAL_MS)
                continue
            }
            val result = TunnelWatchdog.probeViaProxy(proxy)
            if (result.ok) {
                stats.recordOk(result.rttMs)
            } else {
                stats.recordTimeout()
                handleFailure(config)
            }
            delay(TunnelWatchdog.PROBE_INTERVAL_MS)
        }
        Log.d(TAG, "watchdog loop finished")
    }

    /// Переключение на здоровый конфиг при отказе. Cooldown между ПОПЫТКАМИ
    /// (туннель может лежать целиком — не молотим urlTest каждые 3с).
    private suspend fun handleFailure(config: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchAttemptMs < TunnelWatchdog.SWITCH_COOLDOWN_MS) return
        lastSwitchAttemptMs = now

        val direction = TunnelWatchdog.directionOf(config) ?: return
        val current = TunnelWatchdog.currentSelectedNode(direction.groupTag)
            ?.takeIf { it in direction.members }

        var live = TunnelWatchdog.testNodes(direction.members)
        if (live.isEmpty()) {
            // Свежий замер не дал живых — fallback на замеры теста при коннекте
            // (только по актуальным членам группы!).
            live = WatchdogStats.instance.initialPings()
                .filterKeys { it in direction.members }
        }

        val target = live
            .filterKeys { it != current }
            .minByOrNull { it.value }
            ?.key ?: return

        if (target == current) return
        if (TunnelWatchdog.switchNode(direction.groupTag, target)) {
            Log.w(TAG, "switched ${direction.groupTag}: $current -> $target")
            WatchdogStats.instance.recordSwitch(current, target)
        }
    }
}