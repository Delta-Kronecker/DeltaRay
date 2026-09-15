package com.leadaxe.lxbox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.leadaxe.lxbox.vpn.WatchdogLog

/// DeltaRay: экран журнала вачдога — пункт «Watchdog log» гамбургер-меню.
/// Показывает последние [WatchdogLog.VISIBLE_LINES] строк кольцевого буфера
/// (пробы OK/FAIL с активным конфигом, серии таймаутов, sweep-замеры всех
/// конфигов с пингами, чёрный список, сами смены, фазы connect-all) и кнопку
/// «Copy» — копирует ВЕСЬ буфер (до [WatchdogLog.MAX_LINES] строк) в
/// буфер обмена. Прокрутка вниз, к самым свежим строкам.
class WatchdogLogActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchdog_log)

        val scroll = findViewById<ScrollView>(R.id.log_scroll)
        val text = findViewById<TextView>(R.id.log_text)
        text.text = WatchdogLog.tail()
        // К самым свежим — как только layout измерится.
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        val copy = findViewById<TextView>(R.id.log_copy)
        copy.setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(
                ClipData.newPlainText("DeltaRay watchdog log", WatchdogLog.tail(WatchdogLog.MAX_LINES)),
            )
            copy.setText(R.string.log_copied)
        }
        findViewById<TextView>(R.id.log_close).setOnClickListener { finish() }
    }
}
