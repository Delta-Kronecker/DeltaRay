package com.leadaxe.lxbox.vpn

import android.os.SystemClock
import android.util.Log
import com.leadaxe.lxbox.ConnectConfigPing
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/// Вачдог туннеля: механика проверки реального выхода и завершение на живом
/// ядре. Через ЛОКАЛЬНЫЙ mixed/socks-инбаунд (`127.0.0.1:<port>`, режим
/// vpn_proxy §119) гоняем SOCKS5-CONNECT до `www.google.com:80` + GET
/// `generate_204` — трафик идёт в ТОТ ЖЕ выход, что у юзера (route.final).
/// Ответ = туннель жив замером «как пользователь видит сеть».
///
/// При отказе — RPC на ядро (одно ядро, unary через свой CommandClient):
///  - `getGroups()`           → `selected` текущей группы (текущий конфиг);
///  - `urlTestOutbound(tag)`   → свежий замер каждого конфига;
///  - `selectOutbound(group,tag)` → переключение на «вторую с наименьшим
///    пингом» (текущий исключается).
///
/// Никакого приложения/Flutter не трогаем — всё нативно, как и монитор фазы
/// коннекта (`ConnectConfigPing`).
object TunnelWatchdog {
    private const val TAG = "TunnelWatchdog"

    /// Тест-цель вачдога (SPEC оператора): через SOCKS уходим на
    /// `http://www.google.com/generate_204`. Любой HTTP-ответ (не только 204)
    /// доказывает, что туннель работает.
    const val TEST_URL_HOST = "www.google.com"
    const val TEST_URL_PATH = "/generate_204"
    const val TEST_URL_PORT = 80

    /// Таймаут одного провайдинг-замера — ЖЁСТКИЙ дедлайн (мс).
    const val PROXY_TIMEOUT_MS = 10_000L
    /// Период вачдога: каждый [PROBE_INTERVAL_MS] — один замер.
    const val PROBE_INTERVAL_MS = 3_000L
    /// Минимальная пауза между ПОПЫТКАМИ переключения ноды: туннель умер
    /// надолго (все узлы легли) — не молотим urlTest каждые 3с, раз в 15с.
    const val SWITCH_COOLDOWN_MS = 15_000L
    /// Таймаут `urlTestOutbound` одной ноды при переключении.
    const val NODE_TEST_TIMEOUT_MS = 4_000

    data class ProbeResult(
        val ok: Boolean,
        val rttMs: Long,
        val error: String,
    )

    /// Локальный прокси-инбаунд из конфига. [listen] — адрес для коннекта
    /// (всегда 127.0.0.1: loopback/0.0.0.0 на нём слышны; LAN-listen вачдог
    /// не трогает). [socksCapable] = false для `type: http` (SOCKS-шапки нет).
    data class LocalProxy(
        val listen: String,
        val port: Int,
        val authEnabled: Boolean,
        val socksCapable: Boolean,
    )

    /// Активная группа роутинга (`route.final`) + её ноды-члены + тег
    /// auto-двойника (`<tag>-auto`, urltest). Двойник в members НЕ входит.
    data class DirectionInfo(
        val groupTag: String,
        val members: List<String>,
        val autoTag: String,
    ) {
        constructor(groupTag: String, members: List<String>) :
            this(groupTag, members, "$groupTag-auto")
    }

    /// Найти локальный прокси: первый inbound типа mixed/socks/http с
    /// не-пустым портом (в режиме vpn_proxy их ровно один). Loopback/0.0.0.0
    /// достижимы по 127.0.0.1; конкретный LAN-listen — нет (чужая история).
    fun localProxy(configJson: String): LocalProxy? {
        if (configJson.isBlank() || configJson == "{}") return null
        return try {
            val root = JSONObject(configJson)
            val inbounds = root.optJSONArray("inbounds") ?: return null
            for (i in 0 until inbounds.length()) {
                val ob = inbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type", "")
                if (type != "mixed" && type != "socks" && type != "http") continue
                val listen = ob.optString("listen", "127.0.0.1")
                if (!listen.startsWith("127.") && listen != "0.0.0.0") continue
                val port = ob.optInt("listen_port", 0).takeIf { it > 0 }
                    ?: ob.optInt("port", 0)
                if (port <= 0) continue
                val users = ob.optJSONArray("users")
                return LocalProxy(
                    listen = "127.0.0.1",
                    port = port,
                    authEnabled = users != null && users.length() > 0,
                    socksCapable = type != "http",
                )
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "localProxy failed: ${e.message}")
            null
        }
    }

    /// Найти группу `route.final` и её члены-ноды (без вложенных групп и
    /// direct/bypass/block-like). Пусто/расхождение → null (переключать некуда).
    fun directionOf(configJson: String): DirectionInfo? {
        if (configJson.isBlank() || configJson == "{}") return null
        return try {
            val root = JSONObject(configJson)
            val finalTag = root.optJSONObject("route")
                ?.optString("final")?.takeIf { it.isNotEmpty() } ?: return null
            val outbounds = root.optJSONArray("outbounds") ?: return null

            val groupTags = mutableSetOf<String>()
            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type", "")
                if (type != "url-test" && type != "selector" && type != "urltest") continue
                val tag = ob.optString("tag", "")
                if (tag.isNotEmpty()) groupTags.add(tag)
            }

            for (i in 0 until outbounds.length()) {
                val ob = outbounds.optJSONObject(i) ?: continue
                val type = ob.optString("type", "")
                if (type != "url-test" && type != "selector" && type != "urltest") continue
                if (ob.optString("tag", "") != finalTag) continue
                val members = mutableListOf<String>()
                val mems = ob.optJSONArray("outbounds")
                if (mems != null) {
                    for (j in 0 until mems.length()) {
                        val m = mems.optString(j)
                        if (m.isEmpty() || m in groupTags || looksIndirect(m)) continue
                        members.add(m)
                    }
                }
                if (members.isEmpty()) return null
                return DirectionInfo(finalTag, members.distinct())
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "directionOf failed: ${e.message}")
            null
        }
    }

    /// Собственно проба выхода: SOCKS5 (no-auth) CONNECT на тест-цель + HTTP
    /// GET. Жёсткий дедлайн [PROXY_TIMEOUT_MS] на ВЕСЬ обмен через сброс
    /// `soTimeout` перед каждым блокирующим чтением.
    fun probeViaProxy(proxy: LocalProxy): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val deadline = start + PROXY_TIMEOUT_MS
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(proxy.listen, proxy.port),
                remaining(deadline).coerceAtLeast(1).toInt(),
            )
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            applyDeadline(socket, deadline)

            // SOCKS5 handshake: greeting, метод 0x00 (no auth).
            out.write(byteArrayOf(0x05.toByte(), 0x01, 0x00))
            out.flush()
            if (readInt(input, socket, deadline) != 0x05) {
                return fail("socks5 greeting", start)
            }
            val method = readInt(input, socket, deadline)
            if (method != 0x00) {
                return fail(if (method == 0x02) "socks5 auth required" else "socks5 method $method", start)
            }

            // CONNECT www.google.com:80 (домен резолвит ЯДРО внутри туннеля).
            val hostBytes = TEST_URL_HOST.toByteArray(Charsets.US_ASCII)
            val req = ByteArrayOutputStream()
            req.write(0x05); req.write(0x01); req.write(0x00)
            req.write(0x03); req.write(hostBytes.size)
            req.write(hostBytes)
            req.write(TEST_URL_PORT ushr 8); req.write(TEST_URL_PORT and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            if (readInt(input, socket, deadline) != 0x05) {
                return fail("socks5 connect version", start)
            }
            val rep = readInt(input, socket, deadline)
            if (rep != 0x00) {
                return fail("socks5 connect rejected ($rep)", start)
            }
            // RFC 1928: VER REP RSV ATYP — после REP обязательно RSV-байт (0x00)
            // (без него первый байт адреса читается как ATYP). Затем ATYP+адрес.
            readInt(input, socket, deadline) // RSV
            when (readInt(input, socket, deadline)) {
                0x01 -> skip(input, socket, deadline, 4)
                0x03 -> skip(input, socket, deadline, readInt(input, socket, deadline) + 1)
                0x04 -> skip(input, socket, deadline, 16)
                else -> return fail("socks5 bind addr", start)
            }
            skip(input, socket, deadline, 2)

            // HTTP GET через установленный туннель.
            val http = "GET $TEST_URL_PATH HTTP/1.1\r\n" +
                "Host: $TEST_URL_HOST\r\n" +
                "User-Agent: DeltaRay-Watchdog\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            out.write(http.toByteArray(Charsets.US_ASCII))
            out.flush()

            val line = readLine(input, socket, deadline, maxLen = 256)
            if (!line.startsWith("HTTP/")) {
                return fail("bad response '$line'", start)
            }
            Log.d(TAG, "probe ok: ${SystemClock.elapsedRealtime() - start}ms ($line)")
            return ProbeResult(ok = true, rttMs = SystemClock.elapsedRealtime() - start, error = "")
        } catch (e: Exception) {
            val err = when (e) {
                is SocketTimeoutException -> "timeout"
                is IOException -> e.message ?: "io"
                else -> e.message ?: e.javaClass.simpleName
            }
            return fail(err, start)
        } finally {
            runCatching { socket.close() }
        }
    }

    // ══════════════════ RPC на живое ядро (unary, свой клиент) ══════════════════

    /// Текущий выбранный узел группы [groupTag] (`selected` из getGroups()).
    fun currentSelectedNode(groupTag: String): String? {
        val client = ConnectConfigPing.openClient() ?: return null
        return try {
            val it = client.getGroups()
            while (it.hasNext()) {
                val g = it.next()
                if (g.tag == groupTag) {
                    return g.selected?.takeIf { s -> s.isNotEmpty() }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "getGroups failed: ${e.message}")
            null
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /// Переключить группу [groupTag] на узел [tag] (`selectOutbound`).
    fun switchNode(groupTag: String, tag: String): Boolean {
        val client = ConnectConfigPing.openClient() ?: return false
        return try {
            client.selectOutbound(groupTag, tag)
            true
        } catch (e: Exception) {
            Log.w(TAG, "selectOutbound($groupTag, $tag) failed: ${e.message}")
            false
        } finally {
            runCatching { client.disconnect() }
        }
    }

    /// Вернуть Направление в режим auto: селектор выбирает свой urltest-двойник
    /// `<tag>-auto` (§141 default). Best-effort: если двойника нет (auto off) или
    /// RPC не ответил — просто лог, не фатал. Нужно на КАЖДОМ старте: ручной /
    /// вачдоговый выбор переживает перезапуск (ядро живёт, selection в памяти).
    fun selectAuto(direction: DirectionInfo?): Boolean {
        if (direction == null) return false
        if (!switchNode(direction.groupTag, direction.autoTag)) {
            Log.w(TAG, "selectAuto(${direction.groupTag} → ${direction.autoTag}) failed")
            return false
        }
        Log.d(TAG, "direction ${direction.groupTag} back to auto (${direction.autoTag})")
        return true
    }

    /// Свежий замер нод (параллельно, один клиент — как монитор коннекта).
    /// Возвращает tag→delayMs только для ответивших. Вачдог НЕпользует —
    /// выбирает из кеша задержек ядра (ConnectConfigPing.appPings), чтобы не
    /// забивать тест-цель сотнями одновременных urlTest.
    suspend fun testNodes(candidates: List<String>): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val client = ConnectConfigPing.openClient() ?: return@withContext emptyMap()
            try {
                coroutineScope {
                    candidates.map { tag ->
                        async {
                            runCatching {
                                val r = client.urlTestOutbound(
                                    tag,
                                    ConnectConfigPing.DEFAULT_PING_URL,
                                    NODE_TEST_TIMEOUT_MS,
                                )
                                val err = r.getError() ?: ""
                                if (err.isEmpty()) tag to r.getDelay() else null
                            }.getOrNull()
                        }
                    }.awaitAll()
                }.filterNotNull().toMap()
            } catch (e: Exception) {
                Log.w(TAG, "testNodes failed: ${e.message}")
                emptyMap()
            } finally {
                runCatching { client.disconnect() }
            }
        }

    // ───────────────────────── helpers ─────────────────────────

    /// Префиксы/теги, которые НЕ являются VPN-нодами (см. ConnectConfigPing).
    private fun looksIndirect(tag: String): Boolean {
        val t = tag.lowercase()
        return t.startsWith("direct") ||
            t.startsWith("bypass") ||
            t.startsWith("block") ||
            t.startsWith("dns_") ||
            t == "dns"
    }

    private fun remaining(deadline: Long): Long =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private fun applyDeadline(socket: Socket, deadline: Long) {
        socket.soTimeout = remaining(deadline).coerceAtLeast(1).toInt()
    }

    private fun readInt(input: InputStream, socket: Socket, deadline: Long): Int {
        applyDeadline(socket, deadline)
        val b = input.read()
        if (b < 0) throw SocketTimeoutException("eof")
        return b
    }

    private fun skip(input: InputStream, socket: Socket, deadline: Long, count: Int) {
        var left = count
        while (left > 0) {
            readInt(input, socket, deadline)
            left--
        }
    }

    /// Читает строку до `\n` (но не длиннее [maxLen]); `\r` обрезается.
    private fun readLine(
        input: InputStream,
        socket: Socket,
        deadline: Long,
        maxLen: Int,
    ): String {
        val sb = StringBuilder()
        while (sb.length < maxLen) {
            val b = readInt(input, socket, deadline)
            if (b == '\n'.code) break
            if (b == '\r'.code) continue
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun fail(error: String, start: Long): ProbeResult {
        Log.w(TAG, "probe failed: $error (${SystemClock.elapsedRealtime() - start}ms)")
        return ProbeResult(ok = false, rttMs = 0L, error = error)
    }

    /// §428 — پروب از طریق urlTestOutbound هسته: خود sing-box درخواست HTTP
    /// از طریق outbound مشخص‌شده می‌زند و تاخیر را برمی‌گرداند. ساده‌تر و
    /// قابل اطمینان‌تر از probeViaProxy چون نیازی به SOCKS5 نیست.
    fun probeViaOutbound(tag: String): ProbeResult {
        val start = SystemClock.elapsedRealtime()
        val client = ConnectConfigPing.openClient()
            ?: return fail("command client not connected", start)
        return try {
            val r = client.urlTestOutbound(
                tag,
                ConnectConfigPing.DEFAULT_PING_URL,
                PROXY_TIMEOUT_MS.toInt(),
            )
            val err = r.getError() ?: ""
            if (err.isEmpty()) {
                val delay = r.getDelay()
                Log.d(TAG, "probeViaOutbound ok: ${delay}ms (tag=$tag)")
                ProbeResult(ok = true, rttMs = delay.toLong(), error = "")
            } else {
                fail(err, start)
            }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName, start)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}