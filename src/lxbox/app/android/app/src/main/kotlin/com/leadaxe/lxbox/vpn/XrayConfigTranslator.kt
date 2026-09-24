package com.leadaxe.lxbox.vpn

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/// §migration — best-effort перевод sing-box JSON (эмиттит Dart buildConfig)
/// в JSON Xray-core. НЕ полный порт движка конфигурации (это отдельный
/// workunit: Dart должен эмиттить Xray-схему нативно и translator уйдёт в
/// отставку). Здесь — минимальный срез, поддерживающий старт ядра на
/// типовых конфигах (nodes + balancer + базовый routing, как зафиксировал
/// пользователь):
///
///  - outbounds:      direct/block/vless/vmess/shadowsocks/trojan/wireguard,
///                    streamSettings (tls | ws/grpc/h2) — типовая связка.
///  - groups:         sing selector/urltest → Xray `routing.balancers`; urltest
///                    («auto») → strategy leastping (observatory сам выбирает и
///                    ПЕРЕКЛЮЧАЕТ на лучший узел); selector → strategy random
///                    (выбор из приложения через OverrideBalancerTarget).
///  - routing:        sing route.rules (domain*/ip_cidr/source_ip_cidr/port/
///                    source_port/network/inbound/protocol/tls/http) → Xray
///                    rules; селект группы → balancerTag; `final` → catch-all.
///  - inbounds:       sing tun/mixed/socks/http → Xray tun + socks/http.
///
/// Непредставимые условия (geo-rules, rule-sets, wifi_ssid, plugins, старый
/// transport и т.п.) ОТБРАСЫВАЮТСЯ с логом — ядро обязано стартовать, а не
/// падать на незнакомой схеме.
object XrayConfigTranslator {

    private const val TAG = "XrayCfg"

    private const val PIN_TIMEOUT_MS = 2500    // Таймаут cert-хендшейка (insecure-ноды)
    private const val PIN_BUDGET_MS = 8000L    // Мягкий бюджет на все fetch-пины за translate()

    data class Translated(val json: String, val apiPort: Int, val tunAddresses: List<String>, val tunMtu: Int)

    private class Ctx(val warn: (String) -> Unit) {
        val groups = LinkedHashMap<String, MutableList<String>>()   // groupTag -> nodeTags
        val groupStrategy = HashMap<String, String>()               // groupTag -> "random" | "leastping"
        val notedTags = mutableSetOf<String>()                      // теги, добавленные в outbounds
        val nodeTags = mutableSetOf<String>()                       // теги настоящих нод (не direct/block)
        val pinBudgetStart = android.os.SystemClock.elapsedRealtime() // бюджет fetch-пинов insecure-нод
    }

    fun translate(singboxJson: String, baseDir: String): Translated {
        val root = JSONObject(singboxJson)
        val out = JSONObject()
        val warn: (String) -> Unit = { Log.w(TAG, it) }
        val ctx = Ctx(warn)

        // ---- log -----------------------------------------------------------------
        val log = JSONObject()
        log.put("loglevel", root.optJSONObject("log")?.optString("level", "info") ?: "info")
        log.put("access", "$baseDir/access.log")
        log.put("error", "$baseDir/error.log")
        out.put("log", log)

        // ---- dns: минимальный — серверы наверх, правила §migration. --------------
        val dnsRoot = root.optJSONObject("dns")
        if (dnsRoot != null) {
            val dnsOut = JSONObject()
            val serversArr = dnsRoot.optJSONArray("servers")
            val xrayServers = JSONArray()
            if (serversArr != null) {
                for (i in 0 until serversArr.length()) {
                    val s = serversArr.opt(i)
                    val addr = when (s) {
                        is String -> s
                        is JSONObject -> s.optString("address")
                        else -> null
                    } ?: continue
                    xrayServers.put(JSONObject().put("address", addr))
                }
            }
            if (xrayServers.length() > 0) dnsOut.put("servers", xrayServers)
            when (dnsRoot.optString("strategy")) {
                "ipv4_only" -> dnsOut.put("queryStrategy", "UseIPv4")
                "ipv6_only" -> dnsOut.put("queryStrategy", "UseIPv6")
            }
            if (dnsOut.length() > 0) out.put("dns", dnsOut)
        }

        // ---- inbounds -------------------------------------------------------------
        val inbounds = JSONArray()
        var tunAddresses: List<String> = listOf("10.0.0.1", "fdfe:dcba:9876::1")
        var tunMtu = 9000
        var hasTun = false
        val srcInbounds = root.optJSONArray("inbounds") ?: JSONArray()
        for (i in 0 until srcInbounds.length()) {
            val inb = srcInbounds.optJSONObject(i) ?: continue
            when (inb.optString("type")) {
                "tun" -> {
                    hasTun = true
                    val settings = inb.optJSONObject("inbound_options") ?: inb
                    tunMtu = settings.optInt("mtu", 9000).coerceIn(68, 65535)
                    val addrs = settings.optJSONArray("address")
                    if (addrs != null && addrs.length() > 0) {
                        val list = mutableListOf<String>()
                        for (j in 0 until addrs.length()) addrs.optString(j).takeIf { it.isNotEmpty() }?.let { list += it }
                        if (list.isNotEmpty()) tunAddresses = list.toList()
                    }
                    inbounds.put(
                        JSONObject().apply {
                            put("tag", inb.optString("tag", "tun"))
                            put("protocol", "tun")
                            put("listen", "127.0.0.1")
                            put("port", 0)
                            // Xray TunConfig читается из `settings` с КЛЮЧАМИ Xray
                            // (name/mtu/gateway/...), а не sing-box (stack/address).
                            // `name` обязателен: пустой → conf.GetAvailableTunName()
                            // перечисляет интерфейсы через netlink → Android SELinux
                            // запрещает (permission denied). Имя на Android не
                            // валидируется (fd берётся из env.xray.tun.fd) — "tun0".
                            // autoOutboundsInterface НЕ ставим: Index()→InterfaceByName
                            // тоже упирается в netlink.
                            put(
                                "settings",
                                JSONObject().apply {
                                    put("name", "tun0")
                                    put("mtu", tunMtu)
                                    put(
                                        "gateway",
                                        JSONArray().apply { tunAddresses.forEach { put(it.substringBefore('/')) } },
                                    )
                                    put("userLevel", 0)
                                },
                            )
                        },
                    )
                }
                "mixed", "socks", "http" -> {
                    val protocol = if (inb.optString("type") == "http") "http" else "socks"
                    val tag = inb.optString("tag", protocol)
                    val listen = inb.optString("listen", "127.0.0.1")
                    val port = inb.optInt("listen_port", inb.optInt("port", 1080))
                    val users = inb.optJSONArray("users")
                    inbounds.put(
                        JSONObject().apply {
                            put("tag", tag)
                            put("protocol", protocol)
                            put("listen", listen)
                            put("port", port)
                            put(
                                "settings",
                                if (protocol == "socks") {
                                    JSONObject().apply {
                                        put("udp", true)
                                        put("allowTransparent", false)
                                        if (users != null) put("auth", "password")
                                    }
                                } else {
                                    JSONObject().apply {
                                        if (users != null) {
                                            val accs = JSONArray()
                                            for (k in 0 until users.length()) {
                                                val u = users.optJSONObject(k) ?: continue
                                                accs.put(
                                                    JSONObject(
                                                        mapOf(
                                                            "user" to u.optString("username", ""),
                                                            "pass" to u.optString("password", ""),
                                                        ),
                                                    ),
                                                )
                                            }
                                            put("accounts", accs)
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
                else -> warn("[$TAG] inbound ${inb.optString("type")} skipped (unsupported)")
            }
        }
        if (!hasTun) {
            inbounds.put(
                JSONObject().apply {
                    put("tag", "tun")
                    put("protocol", "tun")
                    put("listen", "127.0.0.1")
                    put("port", 0)
                    put("settings", JSONObject().put("name", "tun0").put("mtu", 9000))
                },
            )
            hasTun = true
        }
        out.put("inbounds", inbounds)

        // ---- outbounds ------------------------------------------------------------
        val outbounds = JSONArray()
        val srcOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
        var index = 0
        for (i in 0 until srcOutbounds.length()) {
            val o = srcOutbounds.optJSONObject(i) ?: continue
            val tag = o.optString("tag").ifEmpty { "node-$i" }
            when (o.optString("type")) {
                "selector", "urltest" -> {
                    val list = o.optJSONArray("outbounds") ?: JSONArray()
                    val tags = mutableListOf<String>()
                    for (j in 0 until list.length()) list.optString(j).takeIf { it.isNotEmpty() }?.let { tags += it }
                    if (tags.isEmpty()) continue
                    // групповые теги в outbound-список не попадают (Dart ждёт плоский список нод)
                    if (ctx.groups.putIfAbsent(tag, tags.toMutableList()) != null) {
                        warn("[$TAG] group $tag duplicated — merged")
                    }
                    // urltest = «auto»: Xray сам переключается на лучший узел (leastping
                    // через observatory). selector = ручной выбор (random + override из приложения).
                    ctx.groupStrategy[tag] = if (o.optString("type") == "urltest") "leastping" else "random"
                }
                "direct", "dns" -> {
                    outbounds.put(freedom(tag))
                    ctx.notedTags.add(tag)
                }
                "block" -> {
                    outbounds.put(blackhole(tag))
                    ctx.notedTags.add(tag)
                }
                else -> {
                    val x = translateOutbound(o, tag, warn, ctx) ?: run {
                        warn("[$TAG] outbound $tag (${o.optString("type")}) unsupported — skipped")
                        null
                    }
                    if (x != null) {
                        outbounds.put(x)
                        ctx.notedTags.add(tag)
                        ctx.nodeTags.add(tag)
                    }
                }
            }
        }
        if (outbounds.length() == 0) {
            outbounds.put(freedom("direct"))
            ctx.notedTags.add("direct")
        }
        out.put("outbounds", outbounds)

        // ---- routing ----------------------------------------------------------------
        val routing = JSONObject()
        routing.put("domainStrategy", "AsIs")
        val balancers = JSONArray()
        ctx.groups.forEach { (tag, members) ->
            val live = members.filter { it in ctx.notedTags }
            if (live.isEmpty()) return@forEach
            balancers.put(
                JSONObject().apply {
                    put("tag", tag)
                    put("selector", JSONArray().apply { live.forEach { put(it) } })
                    // urltest → «leastping» (observatory сам выбирает лучший узел и
                    // переключается); selector → «random» (выбор из приложения через override).
                    put("strategy", JSONObject().put("type", ctx.groupStrategy[tag] ?: "random"))
                },
            )
        }
        if (balancers.length() > 0) routing.put("balancers", balancers)

        val rules = JSONArray()
        val route = root.optJSONObject("route")
        val routeRulesIn = route?.optJSONArray("rules")
        if (routeRulesIn != null) {
            for (i in 0 until routeRulesIn.length()) {
                val r = routeRulesIn.optJSONObject(i) ?: continue
                translateRule(r, ctx)?.let { rules.put(it) }
            }
        }
        val finalOut = route?.optString("final")?.takeIf { it.isNotEmpty() } ?: "direct"
        // §v26 — если final указывает на пропущенную ноду/группу — фоллбэк на
        // живой outbound, иначе ядро упадёт с "outbound not found".
        val safeFinal = when {
            ctx.groups[finalOut]?.any { it in ctx.notedTags } == true -> finalOut
            finalOut in ctx.notedTags -> finalOut
            "direct" in ctx.notedTags -> "direct"
            ctx.notedTags.isNotEmpty() -> ctx.notedTags.first()
            else -> null
        }
        if (safeFinal != null) {
            rules.put(
                JSONObject().apply {
                    put("type", "field")
                    // Xray cтакает условие обязательно (без него — "this rule has no
                    // effective fields"): network=tcp,udp покрывает весь трафик tun.
                    put("network", "tcp,udp")
                    if (ctx.groups.containsKey(safeFinal)) put("balancerTag", safeFinal) else put("outboundTag", safeFinal)
                },
            )
        } else {
            warn("[$TAG] no usable outbound/group left — config has no routing target")
        }
        routing.put("rules", rules)
        out.put("routing", routing)

        // ---- api (commander) ---------------------------------------------------------
        val api = JSONObject()
        api.put("tag", "api")
        api.put("listen", "127.0.0.1:$API_PORT")
        api.put(
            "services",
            JSONArray().apply {
                put("ObservatoryService")
                put("HandlerService")
                put("StatsService")
                put("LoggerService")
                put("RoutingService")
            },
        )
        out.put("api", api)

        // ---- observatory ---------------------------------------------------------------
        // БЕЗ блока `observatory` api-сервис ObservatoryService делает
        // RequireFeatures(Observatory, false) → зависимость не резолвится →
        // core: "not all dependencies are resolved" на старте. Плюс это и есть
        // источник статуса/задержек нод для приложения (GetOutboundStatus).
        val observatory = JSONObject()
        val nodeTags = ctx.nodeTags.toList().distinct()
        if (nodeTags.isNotEmpty()) {
            observatory.put("subjectSelector", JSONArray().apply { nodeTags.forEach { put(it) } })
        }
        // duration.Duration требует СТРОКУ (time.ParseDuration), число → "invalid duration"
        observatory.put("probeInterval", "1m")
        observatory.put("enableConcurrency", true)
        out.put("observatory", observatory)

        // ---- stats/policy: без них StatsService.QueryStats(>>>traffic>>>) не
        // соберёт ни одного счётчика — статус/трафик в приложении мертвы.
        out.put(
            "policy",
            JSONObject().apply {
                put(
                    "levels",
                    JSONObject().put(
                        "0",
                        JSONObject().apply {
                            put("statsUserUplink", true)
                            put("statsUserDownlink", true)
                            put("statsUserConnection", true)
                        },
                    ),
                )
                put(
                    "system",
                    JSONObject().apply {
                        put("statsInboundUplink", true)
                        put("statsInboundDownlink", true)
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    },
                )
            },
        )
        out.put("stats", JSONObject())

        return Translated(out.toString(), API_PORT, tunAddresses, tunMtu)
    }

    private fun freedom(tag: String) = JSONObject().apply {
        put("protocol", "freedom")
        put("tag", tag)
        put("settings", JSONObject())
    }

    private fun blackhole(tag: String) = JSONObject().apply {
        put("protocol", "blackhole")
        put("tag", tag)
        put("settings", JSONObject())
    }

    private fun translateOutbound(o: JSONObject, tag: String, warn: (String) -> Unit, ctx: Ctx): JSONObject? {
        val server = o.optString("server")
        val port = o.optInt("server_port", o.optInt("port", 0))
        if (server.isEmpty() || port == 0) {
            warn("[$TAG] outbound $tag: no server/port")
            return null
        }
        val base = JSONObject().apply { put("tag", tag) }
        try {
            when (o.optString("type")) {
                "vless" -> {
                    base.put("protocol", "vless")
                    base.put(
                        "settings",
                        JSONObject().put(
                            "vnext",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("address", server)
                                    put("port", port)
                                    put(
                                        "users",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("id", o.optString("uuid"))
                                                val flow = o.optString("flow", "").ifEmpty {
                                                    o.optJSONObject("transport")?.optString("flow", "").orEmpty()
                                                }
                                                put("flow", flow)
                                                put("encryption", "none")
                                            },
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                }
                "vmess" -> {
                    base.put("protocol", "vmess")
                    base.put(
                        "settings",
                        JSONObject().put(
                            "vnext",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("address", server)
                                    put("port", port)
                                    put(
                                        "users",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("id", o.optString("uuid"))
                                                put("security", o.optString("security", "auto"))
                                                put("alterId", o.optInt("alter_id", 0))
                                            },
                                        ),
                                    )
                                },
                            ),
                        ),
                    )
                }
                "shadowsocks" -> {
                    base.put("protocol", "shadowsocks")
                    base.put(
                        "settings",
                        JSONObject().put(
                            "servers",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("address", server)
                                    put("port", port)
                                    put("method", o.optString("method", "aes-128-gcm"))
                                    put("password", o.optString("password"))
                                    o.optString("plugin").takeIf { it.isNotEmpty() }?.let {
                                        warn("[$TAG] shadowsocks $tag: plugin '$it' dropped (Xray не поддерживает sing-plugin)")
                                    }
                                },
                            ),
                        ),
                    )
                }
                "trojan" -> {
                    base.put("protocol", "trojan")
                    base.put(
                        "settings",
                        JSONObject().put(
                            "servers",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("address", server)
                                    put("port", port)
                                    put("password", o.optString("password"))
                                },
                            ),
                        ),
                    )
                }
                "wireguard" -> {
                    base.put("protocol", "wireguard")
                    val local = o.optJSONArray("local_address") ?: JSONArray().put("10.0.0.2/32")
                    val addrList = mutableListOf<String>()
                    for (i in 0 until local.length()) local.optString(i).takeIf { it.isNotEmpty() }?.let { addrList += it }
                    val peer = JSONObject().apply {
                        put("publicKey", o.optString("peer_public_key"))
                        o.optString("preshared_key").takeIf { it.isNotEmpty() }?.let { put("preSharedKey", it) }
                        put("allowedIPs", JSONArray().apply { put("0.0.0.0/0"); put("::/0") })
                    }
                    base.put(
                        "settings",
                        JSONObject().apply {
                            put("secretKey", o.optString("private_key"))
                            put("address", JSONArray().apply { addrList.forEach { put(it.substringBefore('/')) } })
                            put("mtu", o.optInt("mtu", 1420))
                            put("peers", JSONArray().put(peer))
                        },
                    )
                }
                else -> return null
            }
        } catch (t: Throwable) {
            warn("[$TAG] outbound $tag (${o.optString("type")}) malformed: ${t.message}")
            return null
        }

        // ---- streamSettings (tls + network) --------------------------------------
        val stream = JSONObject()
        stream.put("security", "none")
        val tls = o.optJSONObject("tls")
        val ssl = tls?.optJSONObject("ssl") ?: tls
        val transport = o.optJSONObject("transport")
        if (tls != null) {
            stream.put("security", "tls")
            stream.put(
                "tlsSettings",
                JSONObject().apply {
                    ssl?.optString("server_name").takeIf { !it.isNullOrEmpty() }?.let { put("serverName", it) }
                    // §v26: "allowInsecure" УДАЛЁН (PrintRemovedFeatureError при старте).
                    // Для insecure-нод пиннуем leaf-серт (SHA-256 DER) прямо при
                    // трансляции — это единственный эквивалент старого поведения
                    // (skip-verification). Не удалось достать cert → warn, нода остаётся
                    // без пина (обычная верификация).
                    if (ssl != null && ssl.optBoolean("insecure", false)) {
                        val sni = ssl.optString("server_name").takeIf { !it.isNullOrEmpty() } ?: server
                        val pin = fetchLeafPin(server, port, sni, ctx, warn)
                        if (pin != null) {
                            put("pinnedPeerCertSha256", pin)
                        } else {
                            warn("[$TAG] outbound $tag: allowInsecure удалён в Xray v26, серт-пин недоступен — будет обычная верификация TLS (нода может не подключиться)")
                        }
                    }
                    val alpn = ssl?.optJSONArray("alpn")
                    if (alpn != null && alpn.length() > 0) {
                        put("alpn", JSONArray().apply { for (k in 0 until alpn.length()) put(alpn.optString(k)) })
                    }
                    put("fingerprint", "chrome")
                    // Xray `tlsSettings.cipherSuites` (vless `cs=` param).
                    ssl?.optString("cipher_suites").takeIf { !it.isNullOrEmpty() }
                        ?.let { put("cipherSuites", it) }
                },
            )
        }
        if (transport != null) {
            when (transport.optString("type", "tcp")) {
                "ws" -> {
                    stream.put("network", "ws")
                    val ws = JSONObject()
                    // Xray ED = хвост пути `?ed=N` (sing-box: `max_early_data` без
                    // `early_data_header_name`; референс: path "/?ed=2560").
                    var path = transport.optString("path", "/")
                    val maxEd = transport.optInt("max_early_data", 0)
                    val ehHeader = transport.optString("early_data_header_name")
                    if (maxEd > 0 && !path.contains("?ed=")) {
                        if (ehHeader.isEmpty()) {
                            path = if (path.contains('?')) "$path&ed=$maxEd" else "$path?ed=$maxEd"
                        } else {
                            warn("[$TAG] ws early-data header mode не перенесён в Xray — ed отброшен, путь без ED")
                        }
                    }
                    ws.put("path", path.ifEmpty { "/" })
                    // host: из явного host либо из заголовка Host (паритет с reference).
                    val headers = transport.optJSONObject("headers")
                    val hostFromHeaders = headers?.remove("Host")?.toString()
                    val host = transport.optString("host").takeIf { it.isNotEmpty() } ?: hostFromHeaders
                    if (!host.isNullOrEmpty()) ws.put("host", host)
                    if (headers != null && headers.length() > 0) ws.put("headers", headers)
                    stream.put("wsSettings", ws)
                }
                "grpc" -> {
                    stream.put("network", "grpc")
                    stream.put(
                        "grpcSettings",
                        JSONObject().apply {
                            put("serviceName", transport.optString("service_name", ""))
                        },
                    )
                }
                "h2", "http" -> {
                    stream.put("network", "h2")
                    val h = JSONObject()
                    transport.optString("path").takeIf { it.isNotEmpty() }?.let { h.put("path", it) }
                    transport.optString("host").takeIf { it.isNotEmpty() }?.let { h.put("host", JSONArray().put(it)) }
                    stream.put("h2Settings", h)
                }
                "quic", "httpupgrade", "xhttp", "splithttp" -> {
                    warn("[$TAG] $tag: transport '${transport.optString("type")}' не перенесён, использую tcp")
                    stream.put("network", "tcp")
                }
                else -> stream.put("network", "tcp")
            }
        }
        // §X — Xray `streamSettings.finalmask` (A/B-фрагментация) прокидывается
        // из sing-JSON служебным ключом `xray_finalmask` (не sing-box поле).
        o.optJSONObject("xray_finalmask")?.let { stream.put("finalmask", it) }

        // §v26.7 — Xray запрещает VLESS/Trojan БЕЗ TLS/REALITY/шифрования на
        // публичный адрес (infra/conf/xray.go requiresTransportSecurity).
        // Повторяем guard, чтобы НЕ ронять весь конфиг на старте: блокируемую
        // ноду пропускаем, остальные живы. Private-IP/домен (localhost/LAN)
        // Xray пропускает — их не трогаем.
        if (stream.optString("security", "none") == "none") {
            val proto = o.optString("type")
            if ((proto == "vless" || proto == "trojan") &&
                o.optString("encryption", "none").ifEmpty { "none" } == "none" &&
                requiresTransportSecurity(server)
            ) {
                warn("[$TAG] outbound $tag ($proto) пропущен: plaintext на публичный адрес запрещён Xray (security:none без TLS/REALITY/encryption)")
                return null
            }
        }

        base.put("streamSettings", stream)
        return base
    }

    private val XRAY_PROTOCOLS = mapOf(
        "http" to "http",
        "tls" to "tls",
        "quic" to "quic",
        "bittorrent" to "bittorrent",
    )

    private fun translateRule(r: JSONObject, ctx: Ctx): JSONObject? {
        val out = JSONObject()
        out.put("type", "field")

        val action = r.optJSONObject("action") ?: JSONObject()
        val targetOutbound = action.optString("outbound").ifEmpty { r.optString("outbound") }
        val groupLive = ctx.groups[targetOutbound]?.any { it in ctx.notedTags } == true
        when {
            targetOutbound.isNotEmpty() && groupLive ->
                out.put("balancerTag", targetOutbound)
            targetOutbound.isNotEmpty() && targetOutbound in ctx.notedTags ->
                out.put("outboundTag", targetOutbound)
            targetOutbound.isNotEmpty() ->
                ctx.warn("[$TAG] rule target '$targetOutbound' отсутствует в итоговом конфиге (нода пропущена) — правило дропнуто")
        }

        var matched = false
        r.optString("network").takeIf { it.isNotEmpty() }?.let {
            out.put("network", it)
            matched = true
        }
        val proto = r.optString("protocol")
        if (proto.isNotEmpty()) {
            val p = XRAY_PROTOCOLS[proto]
            if (p != null) {
                out.put("protocol", JSONArray().put(p))
                matched = true
            } else {
                ctx.warn("[$TAG] rule protocol '$proto' dropped")
            }
        }

        val domains = JSONArray()
        r.optJSONArray("domain")?.forEachArr { domains.put(it) }
        r.optJSONArray("domain_suffix")?.forEachArr { domains.put(it) }
        r.optJSONArray("domain_keyword")?.forEachArr { d ->
            domains.put(if (d.startsWith(".")) "keyword:${d.substring(1)}" else "keyword:$d")
        }
        r.optJSONArray("domain_regex")?.forEachArr { domains.put("regexp:$it") }
        if (domains.length() > 0) {
            out.put("domain", domains)
            matched = true
        }

        if (r.has("geoip") || r.has("geosite") || r.has("ip_is_private")) {
            ctx.warn("[$TAG] geo-condition dropped (минимальный срез)")
        }
        r.optJSONArray("ip_cidr")?.let { out.put("ip", it); matched = true }
        r.optJSONArray("source_ip_cidr")?.let { out.put("source", it); matched = true }
        // port в Xray — СТРОКА "500,4500" или число; sing-box емиттит массив
        // [500,4500] → PortList.UnmarshalJSON падает («invalid port: [...]»).
        portList(r, "port")?.let { out.put("port", it); matched = true }
        portList(r, "source_port")?.let { out.put("sourcePort", it); matched = true }
        r.optString("inbound").takeIf { it.isNotEmpty() }?.let {
            out.put("inboundTag", JSONArray().put(it))
            matched = true
        }

        if (!matched || !out.has("outboundTag") && !out.has("balancerTag")) {
            if (!matched) {
                ctx.warn("[$TAG] rule dropped (no representable condition): ${r.toString().take(120)}")
            } else {
                ctx.warn("[$TAG] rule dropped (no target): ${r.toString().take(120)}")
            }
            return null
        }
        return out
    }

    private fun JSONArray.forEachArr(block: (String) -> Unit) {
        for (i in 0 until this.length()) {
            val v = this.opt(i)
            if (v is String) block(v)
        }
    }

    /// sing-box `port` → Xray-строка. Массив [500,4500] → "500,4500";
    /// элемент-"500-4500" сохраняется как диапазон. Одиночный string/число — копируется.
    private fun portList(r: JSONObject, key: String): String? {
        if (r.has(key)) {
            val arr = r.optJSONArray(key)
            if (arr != null) {
                val parts = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    when (v) {
                        is String -> parts += v
                        is Number -> parts += v.toString()
                        else -> {}
                    }
                }
                return parts.joinToString(",").takeIf { it.isNotEmpty() }
            }
            return r.optString(key).takeIf { it.isNotEmpty() }
        }
        return null
    }

    private val leafPinCache = ConcurrentHashMap<String, String>()  // "host:port#sni" -> sha256 hex

    /// ready-made replacement for old `allowInsecure`: fetch the peer leaf cert
    /// (DIRECT TLS handshake, trust-all) and return its SHA-256 as Xray pin.
    /// Bounded: ~2.5s per handshake, hard budget 8s per config-build.
    private fun fetchLeafPin(
        host: String,
        port: Int,
        sni: String,
        ctx: Ctx,
        warn: (String) -> Unit,
    ): String? {
        val key = "$host:$port#${sni.ifEmpty { host }}"
        leafPinCache[key]?.let { return it }
        if (android.os.SystemClock.elapsedRealtime() - ctx.pinBudgetStart > PIN_BUDGET_MS) {
            warn("[$TAG] pin budget exceeded — skip cert fetch for $key")
            return null
        }
        return try {
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, TRUST_ALL, java.security.SecureRandom())
            java.net.Socket().use { raw ->
                raw.connect(java.net.InetSocketAddress(host, port), PIN_TIMEOUT_MS)
                val ssl = sslCtx.socketFactory.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
                ssl.use {
                    if (!isIpLiteral(sni.ifEmpty { host })) {
                        it.sslParameters = it.sslParameters.apply {
                            serverNames = listOf(javax.net.ssl.SNIHostName(sni.ifEmpty { host }))
                        }
                    }
                    it.soTimeout = PIN_TIMEOUT_MS
                    it.startHandshake()
                    val cert = it.session.peerCertificates.firstOrNull()
                    (cert as? java.security.cert.X509Certificate)?.let { c ->
                        val pin = sha256Hex(c.encoded)
                        leafPinCache[key] = pin
                        pin
                    } ?: run {
                        warn("[$TAG] no peer cert ($key)")
                        null
                    }
                }
            }
        } catch (t: Throwable) {
            warn("[$TAG] cert fetch failed for $key: ${t.message}")
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return StringBuilder(d.size * 2).run {
            for (b in d) append("%02x".format(b.toInt() and 0xFF))
            toString()
        }
    }

    private fun isIpLiteral(h: String): Boolean =
        Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h) ||
                (h.contains(':') && Regex("""^[0-9a-f:.%]+$""").matches(h))

    private val TRUST_ALL = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

/// Зеркало Xray infra/conf/xray.go requiresTransportSecurity(address):
    /// TRUE = публичный address (Xray запрещает plaintext VLESS/Trojan на него).
    /// IPv4/IPv6 литерелы — без DNS; домен — приватный только для reserved-TLD.
    private fun requiresTransportSecurity(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h.isEmpty()) return false

        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").matchEntire(h)
        if (v4 != null) {
            val b = v4.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (b.any { it !in 0..255 }) return false
            val ip = (((b[0].toLong()) shl 24) or
                    ((b[1].toLong()) shl 16) or
                    ((b[2].toLong()) shl 8) or
                    b[3].toLong()) and 0xFFFFFFFFL
            return !privateV4(ip)
        }

        // IPv6-литерел: только hex:цифры и ':' — getByName в этом случае НЕ
        // делает DNS, парсит адрес. Прочее (домен с буквами за пределами a-f) → false.
        if (h.contains(':') && Regex("""^[0-9a-f:.]+$""").matches(h)) {
            val addr = try { java.net.InetAddress.getByName(h) } catch (_: Throwable) { return false }
            val priv = addr.isLoopbackAddress || addr.isSiteLocalAddress ||
                    addr.isLinkLocalAddress || addr.isMulticastAddress
            return !priv
        }

        // Домен: приватный = reserved/zone TLD (геосайт "private" резервирует
        // localhost/.local/.lan/.internal/.home.arpa/.test/.invalid и т.д.).
        val privDom = h == "localhost" ||
                PRIVATE_DOMAINS.any { h == it || h.endsWith(".$it") }
        return !privDom
    }

    private fun privateV4(ip: Long): Boolean = when {
        (ip ushr 24) == 0L -> true                       // 0.0.0.0/8
        (ip ushr 24) == 10L -> true                      // 10.0.0.0/8
        (ip ushr 24) == 127L -> true                     // 127.0.0.0/8 (loopback)
        (ip ushr 16) in 0x6440..0x647F -> true           // 100.64.0.0/10 (CGNAT)
        (ip ushr 16) == 0xA9FEL -> true                  // 169.254.0.0/16 (link-local)
        (ip ushr 16) in 0xAC10..0xAC1F -> true           // 172.16.0.0/12
        (ip ushr 16) == 0xC0A8L -> true                  // 192.168.0.0/16
        (ip ushr 16) in 0xC000..0xC07F -> true           // 192.0.0.0/24, 192.0.2.0/24
        (ip ushr 16) in 0xC612..0xC7FF -> true           // 198.18.0.0/15 (benchmark)
        (ip ushr 16) == 0xC633L -> true                  // 198.51.100.0/24 (docs)
        (ip ushr 16) in 0xCB00..0xCBFF -> true           // 203.0.113.0/24 (docs)
        else -> false
    }

    const val API_PORT = 16531

    private val PRIVATE_DOMAINS = setOf(
        "local", "localhost", "lan", "internal", "test", "invalid", "localdomain",
        "home.arpa", "corp", "intranet", "private",
    )
}