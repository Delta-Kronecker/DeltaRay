package com.leadaxe.lxbox.vpn

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

/// Читатель объёма трафика ядра для главной страницы лаунчера: отдельный
/// `CommandClient` с подпиской `CommandStatus` — те же uplinkTotal/downlinkTotal,
/// что кормят Stats-экран приложения (Dart s.uplinkTotal/downlinkTotal).
/// Складывает суммы в [WatchdogStats] — UI-тикер лаунчера (3с) читает снапшот.
/// Живёт, пока туннель Started (стартует вместе с вачдог-циклом, гасится в
/// onDestroy сервиса).
///
/// JNI-no-throw: тело каждого колбэка обёрнуто в runCatching.
object LauncherTraffic {

    private const val TAG = "LauncherTraffic"

    /// Интервал status-стрима в наносекундах: 1с — для главной страницы
    /// достаточно (приложение для Stats даже 0.1с делает).
    private const val STATUS_INTERVAL_NS = 1_000_000_000L

    @Volatile
    private var client: CommandClient? = null

    /// Поколение, как в BoxCommandClient: снапшоты устаревшего клиента
    /// игнорируются (гонка stop→start).
    @Volatile
    private var generation = 0

    /// Поднять подписку (идемпотентно: старый клиент гасится и поднимается
    /// новый с инкрементом поколения). Без клиента ядро подписать статус
    /// нечем — просто лог.
    fun start() {
        val gen = generation + 1
        stopQuiet()
        runCatching {
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                setStatusInterval(STATUS_INTERVAL_NS)
            }
            val c = CommandClient(StatusHandler(gen), options)
            c.connect()
            generation = gen
            client = c
            Log.d(TAG, "traffic subscription up (gen=$gen)")
        }.onFailure {
            Log.w(TAG, "subscribe failed: ${it.message}")
        }
    }

    /// Погасить подписку и инвалидировать поколение.
    fun stop() {
        generation++
        client?.runCatching { disconnect() }
        client = null
    }

    private fun stopQuiet() {
        generation++
        runCatching { client?.disconnect() }
        client = null
    }

    private inner class StatusHandler(private val gen: Int) : CommandClientHandler {
        override fun connected() {
            runCatching { Log.d(TAG, "connected gen=$gen") }
        }

        override fun disconnected(message: String) {
            runCatching { Log.d(TAG, "disconnected gen=$gen: $message") }
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

        override fun writeGroups(groups: OutboundGroupIterator?) {
            runCatching { }
        }

        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {
            runCatching { }
        }

        override fun writeConnectionEvents(message: ConnectionEvents?) {
            runCatching { }
        }

        override fun writeDNSQuery(query: DnsQuery?) {
            runCatching { }
        }

        override fun writeStatus(message: StatusMessage?) {
            runCatching {
                if (gen != generation) return  // устаревший клиент
                val m = message ?: return
                WatchdogStats.instance.setTraffic(m.getUplinkTotal(), m.getDownlinkTotal())
            }.onFailure { Log.w(TAG, "writeStatus failed: ${it.message}") }
        }
    }
}