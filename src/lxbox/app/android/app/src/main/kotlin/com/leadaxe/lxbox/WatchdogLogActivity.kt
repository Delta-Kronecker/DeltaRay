package com.leadaxe.lxbox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.leadaxe.lxbox.vpn.WatchdogLog

/// DeltaRay: watchdog log screen — «Watchdog log» item in the hamburger menu.
/// Shows the last [WatchdogLog.VISIBLE_LINES] lines of the ring buffer and
/// refreshes live ([REFRESH_MS]): while the user is scrolled to the bottom we
/// stick to the newest line; once they scroll up to read history, the view
/// stays put until they return to the bottom. «Copy» copies the whole buffer
/// (up to [WatchdogLog.MAX_LINES] lines) to the clipboard.
class WatchdogLogActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var text: TextView
    private var lastText = ""

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watchdog_log)

        scroll = findViewById(R.id.log_scroll)
        text = findViewById(R.id.log_text)
        refresh()
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

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun refresh() {
        val tail = WatchdogLog.tail()
        if (tail == lastText) return
        lastText = tail
        // Пользователь у самого низа — значит следит за лентой: прижимаем вниз
        // после перерисовки. Скроллит вверх (читает историю) — не трогаем.
        val stickToBottom = !scroll.canScrollVertically(1)
        text.text = tail
        if (stickToBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
