package com.leadaxe.lxbox.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.plugin.common.EventChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/// §migration — канал управления UI↔ядро поверх gRPC Commander'а Xray
/// (классы из app/src/main/java/com/xray, стабы сгенерированы из proto).
///
/// Раньше (`libbox`) был push-стрим UNIX-сокета (CommandClient). У Xray —
/// только blocking gRPC над `127.0.0.1:<apiPort>`: здесь данные получаем
/// ПОЛЛИНГОМ на корутинах Dispatchers.IO; EventChannel-контракт с Dart
/// и формат снапшотов сохранены побайтово. См. degradations в §migration:
///  - connections/dns-стримов нет (Xray не пушит) — продюсеры пусты;
///  - urlTest* без масс-пинга — только прочитанные из observatory задержки;
///  - closeConnection/closeConnections — недоступны.
class BoxCommandClient(
    context: Context,
    apiPort: Int,
    private val sourceConfig: String,
) {

    companion object {
        private const val TAG = "BoxCommandClient"

        /// §163 — интервал status (мс). FAST=0.1с (Stats), NORMAL=0.5с (главный).
        private const val STATUS_INTERVAL_FAST_MS = 100L
        private const val STATUS_INTERVAL_NORMAL_MS = 500L

        /// §164 — интервал экранного снапшота (groups/outbounds), мс.
        private const val SCREEN_INTERVAL_MS = 1500L

        private const val QUEUE_MAX = 4096
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val api = XrayApiClient(apiPort)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Дельты статуса: предыдущие тоталы держим на стороне клиента.
    private var prevUplinkTotal = 0L
    private var prevDownlinkTotal = 0L

    @Volatile private var tunnelAlive = false

    // ---- lifecycle-флаги (тот же контракт, что был у libbox-клиентов) ----
    @Volatile private var statusPaused = false
    @Volatile private var statusIntervalMs = STATUS_INTERVAL_NORMAL_MS
    @Volatile private var screenPaused = false
    private val screenRefs = AtomicInteger(0)
    private var statusJob: Job? = null
    private var screenJob: Job? = null

    // ---- известность источника (для простоты: конфиг не менялся после старта) ----
    private val singConfig: SingConfig = parseSingConfig(sourceConfig)

    // ═══════════════════════ Public lifecycle API ═══════════════════════

    fun startStatus() {
        tunnelAlive = true
        statusPaused = false
        connectStatus()
    }

    fun stopStatus() {
        tunnelAlive = false
        stopStatusJob()
    }

    fun setStatusFast(fast: Boolean) {
        val want = if (fast) STATUS_INTERVAL_FAST_MS else STATUS_INTERVAL_NORMAL_MS
        if (statusIntervalMs == want) return
        statusIntervalMs = want
        if (tunnelAlive && !statusPaused) connectStatus()
    }

    fun pauseStatus() {
        if (statusPaused) return
        statusPaused = true
        stopStatusJob()
    }

    fun resumeStatus() {
        if (!statusPaused) return
        statusPaused = false
        if (tunnelAlive) connectStatus()
    }

    fun connectScreen() {
        val wasZero = screenRefs.getAndIncrement() == 0
        if (wasZero && !screenPaused && screenJob == null) connectScreenClient()
    }

    fun disconnectScreen() {
        val n = screenRefs.updateAndGet { if (it > 0) it - 1 else 0 }
        if (n == 0) stopScreenJob()
    }

    fun pauseScreen() {
        if (screenPaused) return
        screenPaused = true
        stopScreenJob()
    }

    fun resumeScreen() {
        if (!screenPaused) return
        screenPaused = false
        if (tunnelAlive && screenRefs.get() > 0 && screenJob == null) connectScreenClient()
    }

    /// §185 — cold-start после swipe-keep: переподнять на свежий движок.
    fun resyncForReopen() {
        screenRefs.set(0)
        screenPaused = false
        stopScreenJob()
        statusPaused = false
        statusIntervalMs = STATUS_INTERVAL_NORMAL_MS
        if (tunnelAlive) connectStatus()
    }

    /// §048 — профайлер. Xray не стримит per-query DNS/connections: поднятие —
    /// no-op (логируем деградацию), данные будут пустыми, см. reEmitScreenConnections.
    fun connectProfiler() {
        Log.d(TAG, "connectProfiler: degraded (no connections/dns stream in Xray)")
    }

    fun disconnectProfiler() {
        // no-op
    }

    /// Полный teardown — из BoxService.closeCore.
    fun shutdown() {
        tunnelAlive = false
        screenRefs.set(0)
        screenPaused = false
        statusPaused = false
        stopStatusJob()
        stopScreenJob()
        scope.cancel()
        runCatching { api.shutdown() }
            .onFailure { Log.w(TAG, "shutdown: api.shutdown failed: ${it.message}") }
    }

    // ═══════════════════════ Poll loops ═══════════════════════

    private fun connectStatus() {
        stopStatusJob()
        statusJob = scope.launch {
            while (isActive) {
                emitStatusSnapshot()
                delay(statusIntervalMs)
            }
        }
    }

    private fun stopStatusJob() {
        statusJob?.cancel()
        statusJob = null
    }

    private fun connectScreenClient() {
        stopScreenJob()
        screenJob = scope.launch {
            while (isActive) {
                emitScreenSnapshot()
                delay(SCREEN_INTERVAL_MS)
            }
        }
    }

    private fun stopScreenJob() {
        screenJob?.cancel()
        screenJob = null
    }

    private fun emitStatusSnapshot() {
        if (BoxVpnService.ccStatusSink == null) return
        val (upTotal, downTotal) = api.queryTotals()
        val (memory, goroutines) = api.sysStats()
        val uplink = (upTotal - prevUplinkTotal).coerceAtLeast(0)
        val downlink = (downTotal - prevDownlinkTotal).coerceAtLeast(0)
        prevUplinkTotal = upTotal
        prevDownlinkTotal = downTotal
        statusEmitter.offer(mapOf(
            "uplink" to uplink,
            "downlink" to downlink,
            "uplinkTotal" to upTotal,
            "downlinkTotal" to downTotal,
            "memory" to memory,
            "goroutines" to goroutines,
            "connectionsIn" to 0,
            "connectionsOut" to 0,
        ))
    }

    private fun emitScreenSnapshot() {
        val obs = api.outboundStatus()   // OutboundStatus by outbound_tag
        if (BoxVpnService.ccOutboundsSink != null) {
            val nodes = ArrayList<Map<String, Any>>()
            for (n in singConfig.nodes) {
                val st = obs[n.tag]
                nodes.add(mapOf(
                    "tag" to n.tag,
                    "type" to n.type,
                    "urlTestDelay" to (st?.delay ?: 0),
                    // Xray не отдаёт время измерения — используем last_seen_time
                    "urlTestTime" to (st?.lastSeenTime ?: 0),
                ))
            }
            outboundsEmitter.offer(nodes)
        }
        if (BoxVpnService.ccGroupsSink != null) {
            val groups = ArrayList<Map<String, Any>>()
            for (g in singConfig.groups) {
                val items = ArrayList<Map<String, Any>>()
                for (member in g.members) {
                    val st = obs[member]
                    items.add(mapOf(
                        "tag" to member,
                        "type" to (singConfig.nodeType(member) ?: "Core"),
                        "urlTestDelay" to (st?.delay ?: 0),
                        "urlTestTime" to (st?.lastSeenTime ?: 0),
                    ))
                }
                val selected = api.balancerOverride(g.tag) ?: g.defaultTag
                groups.add(mapOf(
                    "tag" to g.tag,
                    "type" to g.type,
                    "selectable" to true,
                    "selected" to selected,
                    "isExpand" to false,
                    "items" to items,
                ))
            }
            groupsEmitter.offer(groups)
        }
    }

    // ═══════════════════════ Imperative (unary) ═══════════════════════

    /// §4.6 — per-node delay. Xray не даёт force-тест одного тега (observatory
    /// зондирует сам): отдаём ПОСЛЕДНЮЮ наблюдённую задержку; если нода ещё не
    /// зондирована — error. Контракт полей сохранён.
    fun urlTestOutbound(tag: String, link: String, timeoutMs: Int): Map<String, Any> {
        val st = api.outboundStatus()[tag] ?: return mapOf("delay" to 0, "error" to "no observatory data for '$tag'")
        return mapOf("delay" to st.delay, "error" to "")
    }

    /// §392 — диагностический HTTP GET через узел. Xray commander не имеет
    /// http-tracer: деградация с честной ошибкой.
    fun getUrlViaOutbound(
        tag: String,
        link: String,
        timeoutMs: Int,
        maxBytes: Int,
    ): Map<String, Any> = mapOf("error" to "not supported by Xray core (degraded)")

    /// §migration — сниппет тоталов трафика (uplink, downlink) для
    /// LauncherTraffic: snapshot-чтение (blocking gRPC), no-throw.
    fun trafficTotals(): Pair<Long, Long> = runCatching {
        api.queryTotals()
    }.getOrElse { 0L to 0L }

    /// §308 — групповой URLTest: Xray зондирует сам синхронно своей частотой.
    /// Команды force-теста нет — false (UI покажет честный отказ).
    fun urlTestGroup(tag: String): Boolean {
        Log.d(TAG, "urlTestGroup($tag) degraded (no force-test in Xray)")
        return false
    }

    fun cancelPing() {
        // no-op (force-пингов нет)
    }

    /// §4.7 — снапшот правил. Данные — routing-правила (tag/ruleTag), детальную
    /// структуру Xray не отдаёт. Формат Map сохранён, isDNS=false.
    fun getRules(): List<Map<String, Any>>? {
        val rules = api.listRules()
        if (rules.isEmpty() && !tunnelAlive) return null
        return rules.map { (tag, ruleTag) ->
            mapOf(
                "type" to "composite",
                "payload" to ruleTag,
                "action" to tag,
                "isDNS" to false,
            )
        }
    }

    /// §122 — unary pull-снапшот групп (тот же формат, что и push).
    /// Строится из исходного конфига + observatory.
    fun getGroups(): List<Map<String, Any>>? {
        if (!tunnelAlive) return null
        val obs = api.outboundStatus()
        val groups = ArrayList<Map<String, Any>>()
        for (g in singConfig.groups) {
            val items = ArrayList<Map<String, Any>>()
            for (member in g.members) {
                val st = obs[member]
                items.add(mapOf(
                    "tag" to member,
                    "type" to (singConfig.nodeType(member) ?: "Core"),
                    "urlTestDelay" to (st?.delay ?: 0),
                    "urlTestTime" to (st?.lastSeenTime ?: 0),
                ))
            }
            val selected = api.balancerOverride(g.tag) ?: g.defaultTag
            groups.add(mapOf(
                "tag" to g.tag,
                "type" to g.type,
                "selectable" to true,
                "selected" to selected,
                "isExpand" to false,
                "items" to items,
            ))
        }
        return groups
    }

    /// §312 — DNS-группы: Xray DNS-групп (sing group-type DNS) не имеет —
    /// пустой список (не ошибка).
    fun getDnsGroups(): List<Map<String, Any>>? = emptyList()

    /// §311 — снапшот работающего конфига. Xray не пере-отдаёт канонический
    /// конфиг: возвращаем ИСХОДНИК sing-box конфига (до переводчика) — это
    /// то, что диагностирует пользователь (деградация зафиксирована).
    fun getRunningConfig(): String? = sourceConfig.takeIf { it.isNotEmpty() }

    /// §208 — пул round_robin-группы: Xray round_robin не транслируется —
    /// пусто (не ошибка).
    fun getPool(tag: String): List<Map<String, Any>>? = emptyList()

    /// §223 Часть B — native-fallback подтекста уведомления.
    fun selectedNodeLabel(configRaw: String): String? {
        val groups = getGroups() ?: return null
        val selectors = groups.filter { (it["tag"] as? String) != "GLOBAL" }
        if (selectors.isEmpty()) return null
        val finalTag = routeFinalTag(configRaw)
        val group = selectors.firstOrNull { (it["tag"] as? String) == finalTag }
            ?: selectors.first()
        val groupTag = group["tag"] as? String ?: return null
        val node = (group["selected"] as? String).orEmpty()
        return if (node.isNotEmpty()) "$groupTag: $node" else groupTag
    }

    private fun routeFinalTag(configRaw: String): String? = runCatching {
        JSONObject(configRaw).optJSONObject("route")?.optString("final")?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /// Выбор ноды группы = OverrideBalancerTarget (commander).
    fun selectOutbound(group: String, tag: String): Boolean =
        api.setBalancerTarget(group, tag)

    /// Xray commander не отдаёт per-connection закрытие — честный false.
    fun closeConnection(id: String): Boolean {
        Log.d(TAG, "closeConnection($id) degraded (not supported)")
        return false
    }

    fun closeConnections(): Boolean {
        Log.d(TAG, "closeConnections degraded (not supported)")
        return false
    }

    /// §193 — connections пусты (Xray не стримит): отдаём пустой список, чтобы
    /// Dart-подписчик при открытии экрана получил консистентный пустой снапшот.
    fun reEmitScreenConnections() {
        connectionsEmitter.offer(emptyList<Map<String, Any>>())
    }

    // ═══════════════════════ Парсинг исходного конфига ═══════════════════════

    private data class SingNode(val tag: String, val type: String)
    private data class SingGroup(val tag: String, val type: String, val members: List<String>, val defaultTag: String?)

    private class SingConfig {
        val nodes = ArrayList<SingNode>()
        val groups = ArrayList<SingGroup>()
        private val typeByTag = HashMap<String, String>()

        fun nodeType(tag: String): String? = typeByTag[tag]

        fun addNode(tag: String, type: String) {
            typeByTag[tag] = type
        }
    }

    private fun parseSingConfig(raw: String): SingConfig {
        val cfg = SingConfig()
        runCatching {
            val root = JSONObject(raw)
            val outbounds = root.optJSONArray("outbounds") ?: JSONArray()
            for (i in 0 until outbounds.length()) {
                val o = outbounds.optJSONObject(i) ?: continue
                val tag = o.optString("tag", "node-$i")
                when (o.optString("type")) {
                    "selector", "urltest" -> {
                        val members = ArrayList<String>()
                        val list = o.optJSONArray("outbounds") ?: JSONArray()
                        for (j in 0 until list.length()) list.optString(j).takeIf { it.isNotEmpty() }?.let { members += it }
                        cfg.groups.add(SingGroup(tag, o.optString("type"), members, o.optString("default")))
                    }
                    else -> {
                        cfg.addNode(tag, o.optString("type"))
                        cfg.nodes.add(SingNode(tag, o.optString("type")))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "parseSingConfig failed: ${it.message}") }
        return cfg
    }

    // ═══════════════════════ Emitters ═══════════════════════

    private val statusEmitter = SnapshotEmitter { BoxVpnService.ccStatusSink }
    private val outboundsEmitter = SnapshotEmitter { BoxVpnService.ccOutboundsSink }
    private val groupsEmitter = SnapshotEmitter { BoxVpnService.ccGroupsSink }
    private val connectionsEmitter = SnapshotEmitter { BoxVpnService.ccConnectionsSink }
    private val dnsQueriesEmitter = EventEmitter { BoxVpnService.ccDnsQueriesSink }

    private inner class SnapshotEmitter(private val sinkProvider: () -> EventChannel.EventSink?) {
        private val queue = LinkedBlockingQueue<Any>()
        private val scheduled = AtomicBoolean(false)

        fun offer(snapshot: Any) {
            queue.clear()
            queue.offer(snapshot)
            if (scheduled.compareAndSet(false, true)) {
                mainHandler.post(drainer)
            }
        }

        private val drainer = Runnable {
            scheduled.set(false)
            val sink = sinkProvider() ?: run { queue.clear(); return@Runnable }
            val latest = queue.poll() ?: return@Runnable
            queue.clear()
            runCatching { sink.success(latest) }
                .onFailure { Log.w(TAG, "emitter sink.success failed: ${it.message}") }
        }
    }

    private inner class EventEmitter(private val sinkProvider: () -> EventChannel.EventSink?) {
        private val queue = LinkedBlockingQueue<Any>()
        private val scheduled = AtomicBoolean(false)

        fun offer(event: Any) {
            if (queue.size < QUEUE_MAX) queue.offer(event)
            if (scheduled.compareAndSet(false, true)) {
                mainHandler.post(drainer)
            }
        }

        private val drainer = Runnable {
            scheduled.set(false)
            val sink = sinkProvider() ?: run { queue.clear(); return@Runnable }
            val batch = ArrayList<Any>()
            queue.drainTo(batch)
            if (batch.isEmpty()) return@Runnable
            runCatching { sink.success(batch) }
                .onFailure { Log.w(TAG, "dns emitter sink.success failed: ${it.message}") }
        }
    }
}