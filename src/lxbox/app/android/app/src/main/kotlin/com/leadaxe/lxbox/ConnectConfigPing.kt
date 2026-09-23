package com.leadaxe.lxbox

import android.util.Log
import com.leadaxe.lxbox.vpn.BoxCommandClient
import com.leadaxe.lxbox.vpn.BoxService
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/// DeltaRay: мониторинг фазы «конфиг пингуется» для лаунчера.
///
/// Вместо разбора log'ов (у libbox core-лог идёт только в Flutter — после
/// закрытия MainActivity движка нет, а файла-журнала пингов нет) используем
/// штатный ping-механизм самого приложения: unary RPC `urlTestOutbound`
/// против живого ядра. §migration (libxray): живого ядра как command.sock нет —
/// все unary-вызовы — это gRPC на api-порт Xray через `BoxService.commandClient`
/// (BoxCommandClient), см. [PingClient]. План чтения — из актуального
/// `singbox_config.json`: url-test/selector-группы направлений (`vpn-N`) +
/// их тест-URL. Как только хоть один конфиг ответил (`error == ""`) — связка
/// считается подключённой.
object ConnectConfigPing {

    private const val TAG = "ConnectConfigPing"

    /// Дефолтный тест-URL приложения (wizard_template ping_options.url).
    const val DEFAULT_PING_URL = "https://www.gstatic.com/generate_204"

    const val DEFAULT_ATTEMPT_TIMEOUT_MS = 4_000
    const val MAX_ROUNDS = 8
    const val ROUND_INTERVAL_MS = 3_000L

    /// Потолок ожидания первого ответа от конфигов (pollAppPings).
    const val POLL_MAX_WAIT_MS = 30_000L

    data class PingPlan(
        val tags: List<String>,
        val url: String,
    )

    /// Одна попытка замера ноды: тег + результат по §4.6-инварианту
    /// (`error` — единственный признак провала) + задержка в мс.
    data class PingDelay(
        val tag: String,
        val ok: Boolean,
        val delayMs: Int,
    )

    /// Префиксы/теги, которые НЕ являются VPN-конфигами (замер через них
    /// может «ответить» реальной сетью, минуя туннель — ложный Connected).
    private fun looksIndirect(tag: String): Boolean {
        val t = tag.lowercase()
        return t.startsWith("direct") ||
            t.startsWith("bypass") ||
            t.startsWith("block") ||
            t.startsWith("dns_") ||
            t == "dns"
    }

    /// Какие конфиги пинговать и каким URL:
    ///  - кэнд idаты: члены ВСЕХ групп направлений (приоритет — группа
    ///    `route.final`), минус direct/bypass/block-like;
    ///  - URL: тест-URL группы `route.final`, иначе тест-URL любой группы,
    ///    иначе дефолтный.
    fun plan(configJson: String): PingPlan {
        val fallback = PingPlan(emptyList(), DEFAULT_PING_URL)
        try {
            val root = JSONObject(configJson)
            val outbounds = root.optJSONArray("outbounds") ?: return fallback
            val routeFinal =
                root.optJSONObject("route")?.optString("final")?.takeIf { it.isNotEmpty() } ?: ""

            data class Group(
                val tag: String,
                val url: String?,
                val members: List<String>,
            )

            val groups = mutableListOf<Group>()
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type", "")
                if (type != "url-test" && type != "selector" && type != "urltest") continue
                val tag = ob.optString("tag", "")
                if (tag.isEmpty()) continue
                val members = mutableListOf<String>()
                val mems = ob.optJSONArray("outbounds")
                if (mems != null) {
                    for (j in 0 until mems.length()) {
                        val m = mems.optString(j)
                        if (m.isNotEmpty()) members.add(m)
                    }
                }
                groups.add(Group(tag, ob.optString("url").takeIf { it.isNotEmpty() }, members))
            }

            if (groups.isEmpty()) {
                // Групп нет — пингуем то, что в route.final (корневой предел),
                // URL делаем дефолтным.
                return if (routeFinal.isEmpty()) {
                    fallback
                } else {
                    PingPlan(listOf(routeFinal), DEFAULT_PING_URL)
                }
            }

            // Порядок: маршрутизируемая группа первой, потом остальные.
            val ordered = mutableListOf<Group>()
            ordered.addAll(groups.filter { it.tag == routeFinal })
            ordered.addAll(groups.filter { it.tag != routeFinal })

            val candidates = mutableListOf<String>()
            for (g in ordered) {
                candidates.addAll(g.members)
                candidates.add(g.tag)
            }
            val filtered = candidates.filterNot { looksIndirect(it) }.distinct()
            val effective = if (filtered.isEmpty()) candidates.distinct() else filtered

            val url = ordered.firstNotNullOfOrNull { it.url } ?: DEFAULT_PING_URL
            return PingPlan(effective, url)
        } catch (e: Exception) {
            Log.w(TAG, "plan failed: ${e.message}")
            return fallback
        }
    }

    /// Прогон: параллельный ping по кэндидатам до первого успеха, пауза между
    /// раундами. [isCoreAlive] — сторож: если ядро упало — прекращаем.
    suspend fun probeUntilSuccess(
        plan: PingPlan,
        attemptTimeoutMs: Int = DEFAULT_ATTEMPT_TIMEOUT_MS,
        maxRounds: Int = MAX_ROUNDS,
        roundIntervalMs: Long = ROUND_INTERVAL_MS,
        isCoreAlive: () -> Boolean,
    ): Boolean = probeWithResults(
        plan,
        attemptTimeoutMs,
        maxRounds,
        roundIntervalMs,
        isCoreAlive,
    ).first

    /// То же, что [probeUntilSuccess], но возвращает `(успех, замеры успешного
    /// раунда tag→delayMs)`. Замеры сохраняет лаунчер как «конфиги, ответившие
    /// в тесте при коннекте» — опорный список для вачдога (fallback, если
    /// свежий замер в момент отказа недоступен).
    suspend fun probeWithResults(
        plan: PingPlan,
        attemptTimeoutMs: Int = DEFAULT_ATTEMPT_TIMEOUT_MS,
        maxRounds: Int = MAX_ROUNDS,
        roundIntervalMs: Long = ROUND_INTERVAL_MS,
        isCoreAlive: () -> Boolean,
    ): Pair<Boolean, Map<String, Int>> {
        if (plan.tags.isEmpty()) return false to emptyMap()
        val client = openClient() ?: return false to emptyMap()
        try {
            var round = 0
            while (round < maxRounds) {
                round++
                if (!isCoreAlive()) return false to emptyMap()
                val results = parallelPing(client, plan.tags, plan.url, attemptTimeoutMs)
                val okDelays = results.filter { it.ok }
                    .associate { it.tag to it.delayMs }
                if (okDelays.isNotEmpty()) return true to okDelays
                if (round >= maxRounds) break
                delay(roundIntervalMs)
            }
        } finally {
            runCatching { client.disconnect() }
        }
        return false to emptyMap()
    }

    /// Читаем пинги, которые УЖЕ наколотил сам апп: unary `getGroups` раз в
    /// [pollIntervalMs] — задержки узлов, что приложение замерило через свой
    /// mass ping (urlTestOutbound обновляет кеш задержек ядра). Первый узел с
    /// delay>0 = конфиг ответил. Читаем, а не пингуем повторно: у лаунчера
    /// URL может не совпасть с ping_options приложения.
    /// null = ядро отвалилось / клиент не поднялся; [] = за таймаут никто не ответил.
    suspend fun pollAppPings(
        pollIntervalMs: Long = 1_000,
        maxWaitMs: Long = POLL_MAX_WAIT_MS,
        isCoreAlive: () -> Boolean,
    ): Map<String, Int>? {
        if (!isCoreAlive()) return null
        val client = openClient() ?: return null
        try {
            val deadline = System.currentTimeMillis() + maxWaitMs
            while (isCoreAlive() && System.currentTimeMillis() < deadline) {
                val delays = withContext(Dispatchers.IO) { readGroupDelays(client) }
                if (delays.isNotEmpty()) return delays
                delay(pollIntervalMs)
            }
        } finally {
            runCatching { client.disconnect() }
        }
        return null
    }

    private fun readGroupDelays(client: PingClient): Map<String, Int> = runCatching {
        val delays = mutableMapOf<String, Int>()
        val groups = client.getGroups()
        while (groups.hasNext()) {
            val items = groups.next().items
            while (items.hasNext()) {
                val item = items.next()
                val tag = item.tag
                val d = item.urlTestDelay
                if (d > 0 && !looksIndirect(tag)) delays[tag] = d.toInt()
            }
        }
        delays
    }.getOrDefault(emptyMap())

    /// Однократное чтение кеша задержек ядра (пинги, которые само приложение
    /// уже наколотило штатным mass ping). null = command-клиент не поднялся.
    suspend fun appPings(): Map<String, Int>? {
        val client = openClient() ?: return null
        return try {
            withContext(Dispatchers.IO) { readGroupDelays(client) }
        } finally {
            runCatching { client.disconnect() }
        }
    }

    private suspend fun parallelPing(
        client: PingClient,
        tags: List<String>,
        url: String,
        timeoutMs: Int,
    ): List<PingDelay> = coroutineScope {
        tags.map { tag ->
            async(Dispatchers.IO) { pingOnce(client, tag, url, timeoutMs) }
        }.awaitAll()
    }

    /// §4.6 инвариант: `error` — единственный признак провала; `error == ""`
    /// = успех (delay может быть 0мс).
    private fun pingOnce(
        client: PingClient,
        tag: String,
        url: String,
        timeoutMs: Int,
    ): PingDelay = runCatching {
        val r = client.urlTestOutbound(tag, url, timeoutMs)
        val error = r.getError() ?: ""
        PingDelay(tag, error.isEmpty(), if (error.isEmpty()) r.getDelay() else 0)
    }.getOrDefault(PingDelay(tag, ok = false, delayMs = 0))

    /// "Клиент" поверх живого `BoxService.commandClient` (BoxCommandClient).
    /// Собственного сокета/gRPC-канала нет — это лёгкая обёртка, которая
    /// проектирует unary-методы обёртки ядра в тот же фасад, что был у raw
    /// CommandClient libbox (getGroups/selectOutbound/urlTestOutbound/
    /// disconnect — disconnect теперь no-op: клиент один на процесс). null =
    /// command-клиент не поднялся (ядро не стартовало / упало / ещё не
    /// перешло в Started).
    fun openClient(): PingClient? = runCatching {
        val cc = BoxService.commandClient
        if (cc == null) {
            com.leadaxe.lxbox.vpn.WatchdogLog.add("openClient: commandClient unavailable (core not running?)")
            null
        } else {
            PingClient(cc)
        }
    }.onFailure {
        Log.w(TAG, "openClient failed: ${it.message}")
        com.leadaxe.lxbox.vpn.WatchdogLog.add("openClient failed — ${it.message}")
    }.getOrNull()

    /// Экранные снапшоты групп (тот же формат, что у BoxCommandClient.getGroups):
    /// [selected] — текущий узел (balancerOverride), [items] — члены с задержкой.
    class ObjItem(val tag: String, val urlTestDelay: Long)
    class ObjGroup(val tag: String, val selected: String?, val items: List<ObjItem>)

    /// Итератор так же, как в libbox-контракте: `hasNext()/next()`, см.
    /// TunnelWatchdog.currentSelectedNode и ConnectConfigPing.readGroupDelays.
    class OutboundGroupIterator(private val groups: List<ObjGroup>) : Iterator<ObjGroup> {
        private var idx = 0
        override fun hasNext(): Boolean = idx < groups.size
        override fun next(): ObjGroup { if (idx >= groups.size) throw NoSuchElementException(); return groups[idx++] }
    }

    class ObjGroupItemIterator(private val items: List<ObjItem>) : Iterator<ObjItem> {
        private var idx = 0
        override fun hasNext(): Boolean = idx < items.size
        override fun next(): ObjItem { if (idx >= items.size) throw NoSuchElementException(); return items[idx++] }
    }

    /// Результат urlTest: §4.6-инвариант (error == "" = успех) + задержка.
    class ObjUrlTest(private val delayMs: Int, private val errorMsg: String) {
        fun getDelay(): Int = delayMs
        fun getError(): String = errorMsg
    }

    class PingClient(private val cc: BoxCommandClient?) {
        fun getGroups(): OutboundGroupIterator {
            val raw = cc?.getGroups() ?: return OutboundGroupIterator(emptyList())
            val groups = raw.mapNotNull { g ->
                val tag = g["tag"] as? String ?: return@mapNotNull null
                val items = (g["items"] as? List<*>)?.mapNotNull { m ->
                    val mm = m as? Map<*, *> ?: return@mapNotNull null
                    val itag = mm["tag"] as? String ?: return@mapNotNull null
                    ObjItem(itag, (mm["urlTestDelay"] as? Number)?.toLong() ?: 0L)
                } ?: emptyList<ObjItem>()
                ObjGroup(tag, g["selected"] as? String, items)
            }
            return OutboundGroupIterator(groups)
        }

        fun selectOutbound(group: String, tag: String): Boolean =
            cc?.selectOutbound(group, tag) ?: false

        fun urlTestOutbound(tag: String, link: String, timeoutMs: Int): ObjUrlTest {
            val m = cc?.urlTestOutbound(tag, link, timeoutMs)
                ?: return ObjUrlTest(0, "command client unavailable")
            return ObjUrlTest(
                (m["delay"] as? Number)?.toInt() ?: 0,
                (m["error"] as? String) ?: "",
            )
        }

        /// no-op: клиент один на процесс, владеет им BoxService.
        fun disconnect() {}
    }
}