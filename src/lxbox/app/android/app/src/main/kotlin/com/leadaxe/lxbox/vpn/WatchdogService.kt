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
/// (`127.0.0.1:2080`, vpn_proxy §119) с твёрдым таймаутом 10с.
///
/// ТЗ переключения: когда ТЕКУЩИЙ конфиг накопил [TIMEOUTS_BEFORE_SWITCH]
/// таймаутов подряд — замеряются ВСЕ конфиги (полный список результатов в
/// лог), и селектор `route.final` переключается на конфиг с наименьшим пингом
/// (не текущий, не из чёрного списка). Умерший конфиг попадает в ПОСЕССИОННЫЙ
/// чёрный список [defectiveTags] — на него больше не переключаемся. Список
/// живёт в памяти сервиса: остановка VPN/приложения (stopSelf) или новый запуск
/// цикла его сжигают.
///
/// Каждый СТАРТ цикла возвращает Направление в auto (`selectOutbound` на
/// `<tag>-auto`): ручной/вачдоговый выбор переживает перезапуск лаунчера при
/// живом ядре, а ТЗ требует стартовать всегда на auto.
///
/// Живёт пока VPN запущен: receiver на BROADCAST_STATUS (Started → старт
/// цикла, Stopped → stopSelf). Лаунчер стартует сервис обычным `startService`
/// в момент успешного коннекта; на провале фазы коннекта — останавливает
/// явно. UI-статистика — из [WatchdogStats], лаунчер тикает (3с). Строки
/// журнала — только English (конвенция launcher-строк).
class WatchdogService : Service() {

    private val TAG = "WatchdogService"
    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    @Volatile
    private var lastSwitchAttemptMs: Long = 0L

    /// Таймауты подряд по КАЖДОМУ узлу (ключ — активный узел, не селектор).
    /// Успешная проба сбрасывает весь счётчик; узел, не определившийся при
    /// пробах, копится под ключом [UNKNOWN_NODE].
    private val timeoutStreak = mutableMapOf<String, Int>()

    /// «Сломанные» конфиги этой сессии: два таймаута подряд на ноде → в чёрный
    /// список, на неё больше не переключаемся. Чистится на старте цикла и
    /// сгорает вместе с сервисом (остановка VPN/приложения) — ТЗ оператора.
    private val defectiveTags = mutableSetOf<String>()

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
        // Трафик лаунчера живёт ровно с сервисом (туннель Started дольше не бывает).
        LauncherTraffic.stop()
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
        WatchdogLog.add("═══ new watchdog session: streaks and defective list cleared")
        // Объём трафика (↑/↓) на главной странице — подписка на status ядра.
        LauncherTraffic.start()
        // ТЗ: старт ВСЕГДА на auto. Каждый запуск цикла (Started) возвращает
        // Направление в auto-двойник — выбор ручной/с прошлого запуска не живёт.
        // Новая сессия = новая жизнь: streak-и и чёрный список сгорают (ТЗ:
        // «остановил приложение — список сломанных конфигов очищен»; список
        // живёт в памяти сервиса, сервис при этом умирает вместе с туннелем).
        timeoutStreak.clear()
        defectiveTags.clear()
        val relit = TunnelWatchdog.selectAuto(TunnelWatchdog.directionOf(ConfigManager.load()))
        WatchdogLog.add(
            if (relit) "start: direction → auto"
            else "start: auto-relight failed (auto off or RPC silent)",
        )
        // ТЗ: статистика ресетится КАЖДУЮ сессию (новый запуск цикла = новая
        // сессия), а не только на переключение.
        WatchdogStats.instance.resetRun()
        while (coroutineContext.isActive) {
            if (BoxVpnService.currentStatus != VpnStatus.Started) break
            val stats = WatchdogStats.instance
            val config = ConfigManager.load()
            val proxy = TunnelWatchdog.localProxy(config)
            if (proxy == null || proxy.authEnabled || !proxy.socksCapable) {
                // Нет HTTP-прокси из конфига (не vpn_proxy) — мониторить нечем
                // и не на что переключать: состояние disabled, интервал выжидаем.
                stats.recordDisabled()
                WatchdogLog.add("DISABLED: no local socks proxy without auth (not vpn_proxy) — probe skipped")
                delay(TunnelWatchdog.PROBE_INTERVAL_MS)
                continue
            }
            val result = TunnelWatchdog.probeViaProxy(proxy)
            val current = TunnelWatchdog.directionOf(config)
                ?.let { activeConfigOf(it) } ?: UNKNOWN_NODE
            if (result.ok) {
                stats.recordOk(result.rttMs)
                // Выход живой — серия неудач текущего конфига обнуляется.
                timeoutStreak.clear()
                WatchdogLog.add("PROBE OK  ${result.rttMs}ms  config=$current")
            } else {
                stats.recordTimeout()
                WatchdogLog.add("PROBE FAIL  config=$current  error=${result.error}")
                onProbeFailed(config)
            }
            delay(TunnelWatchdog.PROBE_INTERVAL_MS)
        }
        Log.d(TAG, "watchdog loop finished")
        WatchdogLog.add("═══ watchdog loop finished (tunnel stopped)")
    }

    /// ТЗ: переключение — когда ТЕКУЩИЙ конфиг накопил
    /// [TIMEOUTS_BEFORE_SWITCH] таймаутов ПОДРЯД. Счётчик ведётся по активному
    /// узлу (см. [activeConfigOf]): успешная проба обнуляет серию. До порога —
    /// просто копим.
    private suspend fun onProbeFailed(config: String) {
        val direction = TunnelWatchdog.directionOf(config)
        if (direction == null) {
            WatchdogLog.add("SWITCH: no direction (route.final/group not found) — cannot switch")
            return
        }
        val current = activeConfigOf(direction)
        val streak = (timeoutStreak[current] ?: 0) + 1
        timeoutStreak[current] = streak
        if (current in defectiveTags) {
            // Порог пройден раньше, переключение не удалось (не было живых /
            // cooldown / RPC молчал) — продолжаем пробовать; полный замер
            // гейтит cooldown внутри attemptSwitch, не каждые 3с.
            WatchdogLog.add("FAIL streak=$streak  config=$current (already defective) → retry switch")
            attemptSwitch(direction, current)
            return
        }
        if (streak < TIMEOUTS_BEFORE_SWITCH) {
            WatchdogLog.add("FAIL streak=$streak/$TIMEOUTS_BEFORE_SWITCH  config=$current — waiting for more timeouts")
            return
        }
        timeoutStreak.remove(current)
        // Порог достигнут: конфиг — в чёрный список сессии СРАЗУ, независимо
        // от того, состоится ли замер в этом же проходе и найдётся ли замена.
        // «unknown» (узел не определился) не записываем.
        if (current != UNKNOWN_NODE) {
            defectiveTags.add(current)
            Log.w(TAG, "$current: $streak timeouts in a row → defective (session list: $defectiveTags)")
            WatchdogLog.add("⚠ $current: $streak timeouts in a row → defective; list=$defectiveTags")
        } else {
            WatchdogLog.add("⚠ threshold reached on “$UNKNOWN_NODE” — not added to defective list")
        }
        attemptSwitch(direction, current)
    }

    /// Активный узел-КОНФИГ, несущий трафик: selected селектора route.final;
    /// когда селектор стоит на auto-двойнике `<tag>-auto` (ТЗ: старт всегда
    /// на auto) — фактический узел берётся из selected этого urltest-двойника.
    /// Итог — конкретный член группы либо [UNKNOWN_NODE].
    private fun activeConfigOf(direction: TunnelWatchdog.DirectionInfo): String {
        val sel = TunnelWatchdog.currentSelectedNode(direction.groupTag)
            ?.takeIf { it.isNotEmpty() } ?: return UNKNOWN_NODE
        if (sel == direction.autoTag) {
            return TunnelWatchdog.currentSelectedNode(direction.autoTag)
                ?.takeIf { it in direction.members } ?: UNKNOWN_NODE
        }
        return sel.takeIf { it in direction.members } ?: UNKNOWN_NODE
    }

    /// Полный замер ВСЕХ конфигов (полный результат — в лог), выбор лучшего
    /// пинга среди НЕ сломанных, переключение текущей серии неудач в чёрный
    /// список. Cooldown между ПОЛНЫМИ замерами: туннель может лежать целиком —
    /// не молотим urlTest каждые 3с.
    private suspend fun attemptSwitch(direction: TunnelWatchdog.DirectionInfo, current: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchAttemptMs < TunnelWatchdog.SWITCH_COOLDOWN_MS) {
            val leftS = (TunnelWatchdog.SWITCH_COOLDOWN_MS - (now - lastSwitchAttemptMs)) / 1000
            WatchdogLog.add("SWITCH: cooldown — retry in ${leftS}s")
            return
        }
        lastSwitchAttemptMs = now

        // ТЗ: «список конфигов и результат пинга каждого» —
        // меряем ВСЕХ членов направления и логируем каждый исход.
        WatchdogLog.add("── SWEEP: pinging ${direction.members.size} configs of group ${direction.groupTag} ──")
        val results = TunnelWatchdog.testNodesDetailed(direction.members)
        if (results.isEmpty()) {
            WatchdogLog.add("SWEEP: core returned nothing — falling back to connect pings")
        }
        for (r in results) {
            val line = if (r.ok) "  ping ${r.tag} = ${r.delayMs}ms" else "  ping ${r.tag} ✗ ${r.error}"
            Log.i(TAG, line.trim())
            WatchdogLog.add(line)
        }
        var live = results.filter { it.ok }.associate { it.tag to it.delayMs }
        if (live.isEmpty()) {
            // Свежий замер не дал живых — fallback на замеры теста при коннекте
            // (только по актуальным членам группы!).
            live = WatchdogStats.instance.initialPings()
                .filterKeys { it in direction.members }
            if (live.isNotEmpty()) WatchdogLog.add("SWEEP: none alive → connect-ping fallback: $live")
        }

        // current уже в defectiveTags (добавлен в onProbeFailed на пороге).
        val free = live.filterKeys { it !in defectiveTags }
        val freeText = if (free.isEmpty()) "—"
            else free.entries.joinToString(" · ") { "${it.key}=${it.value}ms" }
        WatchdogLog.add("candidates (minus defective $defectiveTags): $freeText")
        val target = free.minByOrNull { it.value }?.key
            // Ни свежего замера, ни коннект-пинов (реле лёг — urlTest у всех
            // в ошибку) — ТЗ: всё равно уйти на СЛЕДУЮЩИЙ конфиг по порядку,
            // пропуская сломанные. Текущий уже в defective, не вернёмся.
            ?: direction.members.firstOrNull { it !in defectiveTags }
        if (target == null) {
            Log.w(
                TAG,
                "no switch target: all defective (current=$current defective=$defectiveTags)",
            )
            WatchdogLog.add("✗ all configs are defective — nothing to switch to, staying on $current")
            return
        }
        val reason = if (target in live) "${live[target]}ms ping" else "no live ping — next config by order"

        if (TunnelWatchdog.switchNode(direction.groupTag, target)) {
            Log.w(TAG, "switched ${direction.groupTag}: $current -> $target ($reason, defective=$defectiveTags)")
            WatchdogLog.add("⇄ SWITCH  $current → $target  ($reason)")
            WatchdogStats.instance.recordSwitch(current, target)
        } else {
            WatchdogLog.add("✗ selectOutbound(${direction.groupTag}, $target) — core rejected the switch")
        }
    }

    private companion object {
        /// Ключ для серий таймаутов, когда активный узел не определился
        /// (getGroups молчит / selection вне списка).
        const val UNKNOWN_NODE = "<unknown>"

        /// ТЗ оператора: таймаутов подряд на одном конфиге до переключения.
        const val TIMEOUTS_BEFORE_SWITCH = 2
    }
}
