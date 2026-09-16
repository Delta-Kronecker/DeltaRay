package com.leadaxe.lxbox.vpn

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.leadaxe.lxbox.ConnectConfigPing
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
/// таймаутов подряд — цель выбирается из УЖЕ ГОТОВЫХ замеров приложения
/// (кеш задержек ядра, один getGroups — без повторного пинга 145 нод, он
/// лишь забивает тест-цель 429-ми): селектор `route.final` переключается на
/// конфиг с наименьшим пингом (не текущий, не из чёрного списка). Умерший
/// конфиг попадает в ПОСЕССИОННЫЙ чёрный список [defectiveTags] — на него
/// больше не переключаемся. Список живёт в памяти сервиса: остановка
/// VPN/приложения (stopSelf) или новый запуск цикла его сжигают.
///
/// Каждый СТАРТ ЦИКЛА (новая сессия) возвращает Направление в auto
/// (`selectOutbound` на `<tag>-auto`). ВАЖНО: релайт — только на старте
/// цикла; onResume лаунчера больше не трогает выбор — иначе auto/urltest
/// перебирает ноды между пробами, streak никогда не накапливается на одном
/// конфиге и смена не наступает.
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

    /// Пауза между ПОЛНЫМИ попытками смены (SELECT по кешу + log-блок на
    /// каждую). Порог streak (~26с) сам по себе медленнее кулдауна; гейт
    /// нужен, когда текущий узел уже defective и пробы валятся каждые 3с, —
    /// иначе журнал захлёбывается от SELECT-блоков.
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

    /// Выбор цели из УЖЕ ГОТОВЫХ замеров: кеш задержек ядра (пинги, которые
    /// само приложение копит штатным mass ping — `getGroups`/urlTestDelay).
    /// Никаких повторных urlTest на 145 нод — это и трафик лишнее, и 429 от
    /// тест-цели. Мёртвые/незамеренные ноды в кеше имеют delay<=0 — не
    /// кандидаты. SELECT гейтится [TunnelWatchdog.SWITCH_COOLDOWN_MS]: сам
    /// порог streak медленнее кулдауна, но ветка «уже defective» дёргается
    /// каждые ~3с и без гейта залила бы журнал SELECT-блоками.
    private suspend fun attemptSwitch(direction: TunnelWatchdog.DirectionInfo, current: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSwitchAttemptMs < TunnelWatchdog.SWITCH_COOLDOWN_MS) return
        lastSwitchAttemptMs = now

        // ТЗ: «результаты пинга каждого конфига» — берём из кеша ядра, одним
        // дешёвым getGroups, и логируем каждый член группы.
        val cached = ConnectConfigPing.appPings() ?: emptyMap()
        WatchdogLog.add("── SELECT: app ping cache (getGroups) for ${direction.members.size} configs of ${direction.groupTag} ──")
        for (tag in direction.members) {
            val d = cached[tag]
            WatchdogLog.add(if (d != null) "  ping $tag = ${d}ms (cached)" else "  ping $tag — no cached ping")
        }
        var live = cached.filterKeys { it in direction.members }
        if (live.isEmpty()) {
            // Кеш пуст (ядро ещё не меряло / только после рестарта) — fallback
            // на замеры теста при коннекте.
            live = WatchdogStats.instance.initialPings()
                .filterKeys { it in direction.members }
            if (live.isNotEmpty()) WatchdogLog.add("cache empty → connect-ping fallback: $live")
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
        const val TIMEOUTS_BEFORE_SWITCH = 3
    }
}
