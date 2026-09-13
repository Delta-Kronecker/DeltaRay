package com.leadaxe.lxbox

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/// DeltaRay: мониторинг фазы «конфиг пингуется» для лаунчера.
///
/// Вместо разбора log'ов (у libbox core-лог идёт только в Flutter — после
/// закрытия MainActivity движка нет, а файла-журнала пингов нет) используем
/// штатный ping-механизм самого приложения: unary RPC `urlTestOutbound`
/// против живого ядра (command.sock). План чтения — из актуального
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

    data class PingPlan(
        val tags: List<String>,
        val url: String,
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
    ): Boolean {
        if (plan.tags.isEmpty()) return false
        val client = newClient() ?: return false
        try {
            var round = 0
            while (round < maxRounds) {
                round++
                if (!isCoreAlive()) return false
                val results = parallelPing(client, plan.tags, plan.url, attemptTimeoutMs)
                if (results.any { it }) return true
                if (round >= maxRounds) break
                delay(roundIntervalMs)
            }
        } finally {
            runCatching { client.disconnect() }
        }
        return false
    }

    private suspend fun parallelPing(
        client: CommandClient,
        tags: List<String>,
        url: String,
        timeoutMs: Int,
    ): List<Boolean> = coroutineScope {
        tags.map { tag ->
            async(Dispatchers.IO) { pingOnce(client, tag, url, timeoutMs) }
        }.awaitAll()
    }

    /// §4.6 инвариант: `error` — единственный признак провала; `error == ""`
    /// = успех (delay может быть 0мс).
    private fun pingOnce(client: CommandClient, tag: String, url: String, timeoutMs: Int): Boolean =
        runCatching {
            val r = client.urlTestOutbound(tag, url, timeoutMs)
            (r.getError() ?: "").isEmpty()
        }.getOrDefault(false)

    private fun newClient(): CommandClient? = runCatching {
        val client = CommandClient(PingClientHandler, CommandClientOptions())
        client.connect()
        client
    }.onFailure { Log.w(TAG, "command client connect failed: ${it.message}") }.getOrNull()

    /// Клиент без подписок — только unary RPC (аналог ProbeClientHandler).
    private object PingClientHandler : CommandClientHandler {
        override fun connected() {
            runCatching { Log.d(TAG, "client connected") }
        }

        override fun disconnected(message: String) {
            runCatching { Log.d(TAG, "client disconnected: $message") }
        }

        override fun clearLogs() {
            runCatching { }
        }

        override fun setDefaultLogLevel(level: Int) {
            runCatching { }
        }

        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {
            runCatching { }
        }

        override fun updateClashMode(newMode: String?) {
            runCatching { }
        }

        override fun writeLogs(messageList: LogIterator?) {
            runCatching { }
        }

        override fun writeStatus(message: StatusMessage?) {
            runCatching { }
        }

        override fun writeGroups(groups: OutboundGroupIterator?) {
            runCatching { }
        }

        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {
            runCatching { }
        }

        override fun writeConnectionEvents(message: ConnectionEvents?) {
            runCatching { }
        }

        // §261 — CommandClientHandler расширен writeDNSQuery. Пинги DNS не слушают.
        override fun writeDNSQuery(query: DnsQuery?) {
            runCatching { }
        }
    }
}