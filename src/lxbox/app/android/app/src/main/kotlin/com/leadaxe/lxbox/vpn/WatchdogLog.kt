package com.leadaxe.lxbox.vpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/// Кольцевой буфер журнала вачдога: последние [MAX_LINES] строк с отметками
/// времени, живёт в памяти процесса (не на диске — вместе с сессией:
/// остановка приложения очищает его, как и чёрный список вачдога).
///
/// Пишут: [WatchdogService] (прообы, таймауты, порог, sweep-пинги, смена
/// нод), [LauncherActivity] (фазы connect-all), [ConnectConfigPing]
/// (ошибки command-клиента), [LauncherTraffic]. Читает WatchdogLogActivity —
/// пункт «Watchdog log» гамбургер-меню: последние [VISIBLE_LINES] строк +
/// кнопка копирования всего буфера.
object WatchdogLog {

    const val MAX_LINES = 2000
    const val VISIBLE_LINES = 1000

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(msg: String) {
        lines.addLast("${stamp.format(Date())}  $msg")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /// [maxLines] последних строк через перевод строки.
    @Synchronized
    fun tail(maxLines: Int = VISIBLE_LINES): String =
        lines.takeLast(maxLines.coerceIn(1, MAX_LINES)).joinToString("\n")
}
