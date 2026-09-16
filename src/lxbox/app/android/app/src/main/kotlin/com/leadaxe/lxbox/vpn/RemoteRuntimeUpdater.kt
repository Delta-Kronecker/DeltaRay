package com.leadaxe.lxbox.vpn

import android.content.Context
import android.util.Log
import dev.zerodpi.android.profile.ZeroDpiProfile
import dev.zerodpi.android.service.ZeroDpiRuntimeStateStore
import dev.zerodpi.android.storage.RuntimeStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/// Обновление runtime-ресурсов ZeroDPI (config.toml, sni_list.txt,
/// ip_list.txt) прямо из репозитория DeltaRay, БЕЗ переустановки APK:
///
/// 1. читаем удалённый `version.txt` (github raw);
/// 2. если он отличается от применяемой версии (shared prefs) или вшитой в
///    APK (assets/version.txt) — скачиваем три файла и кладём в профиль
///    ZeroDPI штатным путём (`RuntimeStorage.saveAll`, тот же атомарный
///    write с .bak, что у ручного редактирования);
/// 3. запоминаем применённую версию, чтобы каждый запуск не качал заново.
///
/// Идемпотентно и оффлайн-безопасно: любая ошибка сети/парсинга — тихий
/// пропуск (только лог). Прогресс запущенного туннеля не трогаем — правки
/// подхватятся следующим запуском ZeroDPI.
object RemoteRuntimeUpdater {

    private const val TAG = "RemoteRuntimeUpdater"

    private const val BASE_URL =
        "https://github.com/Delta-Kronecker/DeltaRayConfig/raw/refs/heads/main"
    private const val VERSION_URL = "$BASE_URL/version.txt"
    private const val CONFIG_URL = "$BASE_URL/config.txt"
    private const val SNI_LIST_URL = "$BASE_URL/sni_list.txt"
    private const val IP_LIST_URL = "$BASE_URL/ip_list.txt"

    /// Версия ресурсов, вшитая в APK (android/app/src/main/assets/version.txt).
    private const val ASSET_VERSION_PATH = "version.txt"

    private const val PREFS_NAME = "remote_runtime_updates"
    private const val KEY_APPLIED_VERSION = "appliedVersion"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    @Volatile
    private var checkStarted = false

    /// Один прогон на процесс: предзагруженные re-launch не дублируют качание.
    suspend fun checkAndUpdate(context: Context) = withContext(Dispatchers.IO) {
        if (checkStarted) return@withContext
        checkStarted = true
        val app = context.applicationContext
        try {
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Какую версию ресурсов «имеет» приложение: последнюю применённую,
            // иначе — вшитую в APK при первой установке.
            val bundled = bundledVersion(app)
            val applied = prefs.getInt(KEY_APPLIED_VERSION, bundled)
            val remote = fetch(VERSION_URL)?.trim()?.toIntOrNull()
            if (remote == null) {
                Log.w(TAG, "version probe failed; skip")
                return@withContext
            }
            if (remote == applied) {
                Log.d(TAG, "runtime resources already at v$remote")
                return@withContext
            }

            val configText = fetch(CONFIG_URL)
            val sniText = fetch(SNI_LIST_URL)
            val ipText = fetch(IP_LIST_URL)
            if (configText == null || sniText == null || ipText == null) {
                Log.w(
                    TAG,
                    "download failed; skip (config=${configText != null}, " +
                        "sni=${sniText != null}, ip=${ipText != null})",
                )
                return@withContext
            }

            // Профиль, которым пользуется лаунчер (маркер последнего запуска
            // ZeroDPI), иначе default — он бутстрапится автоматически.
            val marker = ZeroDpiRuntimeStateStore.runtimeMarker(app)
            val profileId = marker.profileId?.takeIf { it.isNotBlank() }
                ?: ZeroDpiProfile.DEFAULT_PROFILE_ID
            RuntimeStorage(app).saveAll(profileId, configText, sniText, ipText)
            prefs.edit().putInt(KEY_APPLIED_VERSION, remote).apply()
            Log.i(TAG, "runtime resources updated to v$remote (profile=$profileId)")
        } catch (e: Exception) {
            Log.w(TAG, "update failed: ${e.message}")
        }
    }

    private fun bundledVersion(app: Context): Int =
        runCatching {
            app.assets.open(ASSET_VERSION_PATH)
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText().trim().toInt() }
        }.getOrDefault(0)

    private fun fetch(urlText: String): String? =
        runCatching {
            val conn = URL(urlText).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "text/plain, */*")
                val status = conn.responseCode
                if (status in 200..299) {
                    BufferedReader(
                        InputStreamReader(conn.inputStream, StandardCharsets.UTF_8),
                    ).use { it.readText() }
                } else {
                    Log.w(TAG, "GET $urlText -> HTTP $status")
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
}