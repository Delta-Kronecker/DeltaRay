package com.leadaxe.lxbox.vpn

import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/// §236 — headless probe-сессия (Xray): проверка нод папки ПОКА VPN ВЫКЛЮЧЕН.
///
/// libbox-версия поднимала ВТОРОЙ sing-box (CommandServer + command.sock) и
/// гоняла urlTestOutbound против него. libXray держит ОДИН managed-core на
/// процесс (`runXray`), поэтому живой второй инстанции нет: probe-сессия
/// хранит переведённый в Xray конфиг, а каждый `urlTest` — это `pingBatch`:
/// libXray поднимает транзиентный Xray для переданного конфига, тестит
/// outbound и гасит — managed-core (боевой VPN) при этом не трогается.
///
/// Ограничения, диктующие модель:
///  - транзиентный Xray в `pingBatch` разделяет глобальные структуры ядра с
///    `runXray` → gate ТОТ ЖЕ, что был: probe стартует ТОЛЬКО при выключенном
///    VPN (`BoxService.commandClient == null`);
///  - тело HTTP-ответа `getURLViaOutbound` pingBatch не возвращает (только
///    задержку/ошибку) → `getUrl` деградирован с честной ошибкой.
///
/// НЕ Android-сервис: VpnStatus-broadcast, уведомления и tun не затрагиваются.
object ProbeSession {
    private const val TAG = "ProbeSession"

    private val probeXray = AtomicReference<String?>(null)
    private val probeTags = AtomicReference<Set<String>>(emptySet())

    val active: Boolean get() = probeXray.get() != null

    /// Запуск сессии с готовым sing-box probe-конфигом (без tun). Переводим в
    /// Xray и запоминаем; до первого urlTest живой инстанции нет. Возвращает ''
    /// при успехе, иначе текст ошибки. Повторный вызов поверх живой сессии —
    /// рестарт (старое содержимое стирается).
    @Synchronized
    fun start(config: String): String {
        if (BoxService.commandClient != null) {
            return "VPN is running — test uses the live tunnel instead"
        }
        stopInternal()
        return runCatching {
            val translated = XrayConfigTranslator.translate(
                config, BoxApplication.application.filesDir.absolutePath
            )
            val tags = parseOutboundTags(translated.json)
            if (tags.isEmpty()) {
                throw IllegalArgumentException("no outbounds in translated probe config")
            }
            probeXray.set(translated.json)
            probeTags.set(tags)
            Log.d(TAG, "probe session ready (${tags.size} outbounds)")
            ""
        }.getOrElse {
            Log.e(TAG, "probe start failed", it)
            stopInternal()
            it.message ?: "probe start failed"
        }
    }

    /// Синхронный тест одной ноды (SPEC 014, Variant B: провал — в `error`
    /// результата, не в исключении). Механика — `pingBatch` с одним конфигом.
    /// Конкурентные вызовы допустимы (инвок-обёртка ядра сериализует).
    fun urlTest(tag: String, link: String, timeoutMs: Int): Map<String, Any> {
        val xray = probeXray.get()
            ?: return mapOf("delay" to 0, "error" to "probe session not running")
        if (tag !in probeTags.get()) {
            return mapOf("delay" to 0, "error" to "tag not in probe config: $tag")
        }
        return runCatching {
            val item = JSONObject()
                .put("xrayJson", xray)
                .put("outboundTag", tag)
            val payload = JSONObject()
                .put("configs", JSONArray().put(item))
                .put("timeout", timeoutMs)
                .put("url", link)
            val data = BoxService.invokeXray("pingBatch", payload)
            val results = data.optJSONArray("results") ?: JSONArray()
            val r = if (results.length() > 0) results.optJSONObject(0) else null
            if (r == null) {
                mapOf("delay" to 0, "error" to "empty pingBatch response")
            } else {
                val err = r.optString("error")
                mapOf(
                    "delay" to r.optLong("delay", 0L).toInt(),
                    "error" to (if (r.optBoolean("success")) "" else err),
                )
            }
        }.getOrElse {
            mapOf("delay" to 0, "error" to (it.message ?: "pingBatch failed"))
        }
    }

    /// §392 — диагностический HTTP GET через узел. pingBatch возвращает только
    /// задержку/ошибку, тела ответа нет → честная деградация (контракт Map
    /// сохранён; Dart разберёт `error` как недоступность метода).
    fun getUrl(tag: String, link: String, timeoutMs: Int, maxBytes: Int): Map<String, Any> {
        Log.d(TAG, "getUrl(tag=$tag) degraded (pingBatch has no response body)")
        return mapOf("error" to "getURLViaOutbound not supported by Xray probe")
    }

    @Synchronized
    fun stop() = stopInternal()

    private fun stopInternal() {
        probeXray.set(null)
        probeTags.set(emptySet())
        Log.d(TAG, "probe session stopped")
    }

    private fun parseOutboundTags(xrayJson: String): Set<String> = runCatching {
        val outbounds = JSONObject(xrayJson).optJSONArray("outbounds") ?: JSONArray()
        (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .mapNotNull { it.optString("tag").takeIf { t -> t.isNotEmpty() } }
            .toSet()
    }.getOrDefault(emptySet())
}