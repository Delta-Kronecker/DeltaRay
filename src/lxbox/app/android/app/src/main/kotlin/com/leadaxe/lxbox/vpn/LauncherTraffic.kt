package com.leadaxe.lxbox.vpn

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// Читатель объёма трафика ядра для главной страницы лаунчера: поллит
/// тоталы (uplink/downlink) у живого `BoxService.commandClient` (BoxCommandClient,
/// Xray StatsService) и складывает их в [WatchdogStats] — UI-тикер лаунчера
/// (3с) читает снапшот. Живёт, пока туннель Started (стартует вместе с
/// вачдог-циклом, гасится в onDestroy сервиса).
///
/// §migration: libbox-версия держала собственный CommandClient с подпиской
/// CommandStatus; для Xray подписки нет — лёгкий poll раз в секунду, тот же
/// снапшот тоталов (per-client gRPC мультиплексируется, блокирующий вызов в
/// IO-скоупе). commandClient может быть null (core не в Started) — пропускаем
/// итерацию.
object LauncherTraffic {

    private const val TAG = "LauncherTraffic"

    /// Интервал поллинга: 1с — для главной страницы достаточно.
    private const val STATUS_INTERVAL_MS = 1_000L

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    /// Поднять поллер (идемпотентно: старый гасится и поднимается новый).
    fun start() {
        stop()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (isActive) {
                val cc = BoxService.commandClient
                if (cc != null) {
                    val (up, down) = cc.trafficTotals()
                    WatchdogStats.instance.setTraffic(up, down)
                }
                delay(STATUS_INTERVAL_MS)
            }
        }
        Log.d(TAG, "traffic poller up")
    }

    /// Погасить поллер.
    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
    }
}