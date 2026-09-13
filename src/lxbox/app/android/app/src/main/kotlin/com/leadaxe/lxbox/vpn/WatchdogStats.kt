package com.leadaxe.lxbox.vpn

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/// Хранилище статистики вачдога. Обновляется только из сервиса вачдога;
/// лаунчер читает снапшот (`snapshot()`) тикером (3с) для страницы UI.
/// Сброс по операторскому ТЗ: при переключении ноды СЧИТЧИКИ (включая
/// «всего тестов/ок») обнуляются, состояние уходит в «starting»; маркеры
/// последнего переключения и замеры теста при коннекте сохраняются.
class WatchdogStats private constructor() {

    private val prefs: SharedPreferences by lazy {
        ctx!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "WatchdogStats"
        private const val PREFS_NAME = "deltaray_watchdog"

        // Ключи prefs.
        private const val K_TESTS = "tests"            // всего замеров
        private const val K_OK = "ok"                  // успешных
        private const val K_TIMEOUTS = "timeouts"      // неуспешных
        private const val K_SWITCHES = "switches"      // переключений (после сброса — 0)
        private const val K_STREAK = "streak"          // подряд успешных
        private const val K_LAST_OK = "lastOk"         // эпоха последнего успеха
        private const val K_LAST_RTT = "lastRttMs"     // мс последнего успеха
        private const val K_STATE = "state"            // id текущего состояния
        private const val K_LAST_SWITCH_FROM = "lastSwitchFrom"
        private const val K_LAST_SWITCH_TO = "lastSwitchTo"
        private const val K_INITIAL_PINGS = "initialPings" // JSON tag→delayMs

        // Разрешили приложение; подтягиваем Context отсюда (singleton-inject).
        private var ctx: Context? = null
        @Volatile
        private var instance: WatchdogStats? = null

        @Synchronized
        fun init(context: Context): WatchdogStats {
            ctx = context.applicationContext
            if (instance == null) instance = WatchdogStats()
            return instance!!
        }

        val instance: WatchdogStats get() = checkNotNull(instance) { "WatchdogStats not init" }
    }

    enum class State(val value: Int) {
        Idle(0),
        Starting(1),
        Ok(2),
        Fail(3),
        Disabled(4),
    }

    /// Атом, чтобы сервис (IO-воркерами) и UI-тикер читали согласованно.
    data class Snapshot(
        val tests: Int,
        val ok: Int,
        val timeouts: Int,
        val switches: Int,
        val streak: Int,
        val state: State,
        val lastRttMs: Long,
        val lastOkEpochMs: Long,
        val lastSwitchFrom: String?,
        val lastSwitchTo: String?,
    )

    fun snapshot(): Snapshot = Snapshot(
        tests = prefs.getInt(K_TESTS, 0),
        ok = prefs.getInt(K_OK, 0),
        timeouts = prefs.getInt(K_TIMEOUTS, 0),
        switches = prefs.getInt(K_SWITCHES, 0),
        streak = prefs.getInt(K_STREAK, 0),
        state = State.values().getOrElse(prefs.getInt(K_STATE, State.Starting.value))
            { State.Starting },
        lastRttMs = prefs.getLong(K_LAST_RTT, 0L),
        lastOkEpochMs = prefs.getLong(K_LAST_OK, 0L),
        lastSwitchFrom = prefs.getString(K_LAST_SWITCH_FROM, null),
        lastSwitchTo = prefs.getString(K_LAST_SWITCH_TO, null),
    )

    fun setState(state: State) {
        prefs.edit().putInt(K_STATE, state.value).apply()
    }

    fun recordOk(rttMs: Long) {
        prefs.edit()
            .putInt(K_TESTS, prefs.getInt(K_TESTS, 0) + 1)
            .putInt(K_OK, prefs.getInt(K_OK, 0) + 1)
            .putInt(K_STREAK, prefs.getInt(K_STREAK, 0) + 1)
            .putLong(K_LAST_RTT, rttMs)
            .putLong(K_LAST_OK, System.currentTimeMillis())
            .putInt(K_STATE, State.Ok.value)
            .apply()
    }

    fun recordTimeout() {
        prefs.edit()
            .putInt(K_TESTS, prefs.getInt(K_TESTS, 0) + 1)
            .putInt(K_TIMEOUTS, prefs.getInt(K_TIMEOUTS, 0) + 1)
            .putInt(K_STREAK, 0)
            .putInt(K_STATE, State.Fail.value)
            .apply()
    }

    fun recordDisabled() {
        prefs.edit().putInt(K_STATE, State.Disabled.value).apply()
    }

    /// Переключение ноды: всё (включая счётчики «всего») сбрасывается в ноль,
    /// state=Starting; маркеры переключения и замеры коннекта остаются.
    fun recordSwitch(from: String?, to: String) {
        prefs.edit()
            .putInt(K_TESTS, 0)
            .putInt(K_OK, 0)
            .putInt(K_TIMEOUTS, 0)
            .putInt(K_SWITCHES, prefs.getInt(K_SWITCHES, 0) + 1)
            .putInt(K_STREAK, 0)
            .putLong(K_LAST_RTT, 0L)
            .putString(K_LAST_SWITCH_FROM, from)
            .putString(K_LAST_SWITCH_TO, to)
            .putInt(K_STATE, State.Starting.value)
            .apply()
    }

    fun setInitialPings(pings: Map<String, Int>) {
        if (pings.isEmpty()) return
        val json = JSONObject()
        pings.forEach { (tag, delay) -> json.put(tag, delay) }
        prefs.edit().putString(K_INITIAL_PINGS, json.toString()).apply()
    }

    /// Замеры теста при коннекте (fallback, если свежий замер не удался).
    fun initialPings(): Map<String, Int> {
        val raw = prefs.getString(K_INITIAL_PINGS, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, Int>()
            json.keys().forEach { k ->
                val v = json.optInt(k, 0)
                if (v > 0) result[k] = v
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}