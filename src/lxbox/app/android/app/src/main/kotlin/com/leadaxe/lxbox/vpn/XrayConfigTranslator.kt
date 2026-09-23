package com.leadaxe.lxbox.vpn

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/// §migration — best-effort перевод sing-box JSON (эмиттит Dart buildConfig)
/// в JSON Xray-core. НЕ полный порт движка конфигурации (это отдельный
/// workunit: Dart должен эмиттить Xray-схему нативно и translator уйдёт в
/// отставку). Здесь — минимальный срез, поддерживающий старт ядра на
/// типовых конфигах (nodes + balancer + базовый routing, как зафиксировал
/// пользователь):
///
///  - outbounds:      direct/block/vless/vmess/shadowsocks/trojan/wireguard,
///                    streamSettings (tls | ws/grpc/h2) — типовая связка.
///  - groups:         sing selector/urltest → Xray `routing.balancers`
///                    (strategy random; выбор ноды — через OverrideBalancerTarget,
///                    наблюдаемость — ObservatoryService.GetOutboundStatus).
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

    data class Translated(val json: String, val apiPort: Int, val tunAddresses: List<String>, val tunMtu: Int)

    private class Ctx(val warn: (String) -> Unit) {
        val groups = LinkedHashMap<String, MutableList<String>>()   // groupTag -> nodeTags
        val notedTags = mutableSetOf<String>()                      // теги, добавленные в outbounds
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
                    val x = translateOutbound(o, tag, warn) ?: run {
                        warn("[$TAG] outbound $tag (${o.optString("type")}) unsupported — skipped")
                        null
                    }
                    if (x != null) {
                        outbounds.put(x)
                        ctx.notedTags.add(tag)
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
            balancers.put(
                JSONObject().apply {
                    put("tag", tag)
                    put("selector", JSONArray().apply { members.forEach { put(it) } })
                    put("strategy", JSONObject().put("type", "random"))
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
        rules.put(
            JSONObject().apply {
                put("type", "field")
                // Xray cтакает условие обязательно (без него — "this rule has no
                // effective fields"): network=tcp,udp покрывает весь трафик tun.
                put("network", "tcp,udp")
                if (ctx.groups.containsKey(finalOut)) put("balancerTag", finalOut) else put("outboundTag", finalOut)
            },
        )
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

    private fun translateOutbound(o: JSONObject, tag: String, warn: (String) -> Unit): JSONObject? {
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
                    if (ssl != null && ssl.optBoolean("insecure", false)) put("allowInsecure", true)
                    val alpn = ssl?.optJSONArray("alpn")
                    if (alpn != null && alpn.length() > 0) {
                        put("alpn", JSONArray().apply { for (k in 0 until alpn.length()) put(alpn.optString(k)) })
                    }
                    put("fingerprint", "chrome")
                },
            )
        }
        if (transport != null) {
            when (transport.optString("type", "tcp")) {
                "ws" -> {
                    stream.put("network", "ws")
                    val ws = JSONObject().apply {
                        put("path", transport.optString("path", "/"))
                        transport.optJSONObject("headers")?.let { put("headers", it) }
                    }
                    val host = transport.optString("host")
                    if (host.isNotEmpty()) {
                        val h = ws.optJSONObject("headers") ?: JSONObject()
                        h.put("Host", host)
                        ws.put("headers", h)
                    }
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
        when {
            targetOutbound.isNotEmpty() && ctx.groups.containsKey(targetOutbound) ->
                out.put("balancerTag", targetOutbound)
            targetOutbound.isNotEmpty() ->
                out.put("outboundTag", targetOutbound)
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
        r.optJSONArray("port")?.let { out.put("port", it); matched = true }
        r.optJSONArray("source_port")?.let { out.put("sourcePort", it); matched = true }
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

    const val API_PORT = 16531
}