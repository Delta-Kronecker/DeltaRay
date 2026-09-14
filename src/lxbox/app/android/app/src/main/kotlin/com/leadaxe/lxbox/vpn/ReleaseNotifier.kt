package com.leadaxe.lxbox.vpn

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/// Оповещение о новом релизе: при запуске лаунчера читаем latest-релиз из
/// GitHub API (tag_name вида vX.Y.Z), сравниваем с версией установленного
/// APK (versionName) и, если новее и ещё не предлагалось, лаунчер показывает
/// диалог с источниками загрузки: страница релиза на GitHub или канал в
/// Telegram.
///
/// Оффлайн-безопасно: любая ошибка сети/парсинга — тихий пропуск (лог).
/// Повтор не назойливый: после первого показа запоминаем предложенную версию
/// и для неё больше не спрашиваем.
object ReleaseNotifier {

    private const val TAG = "ReleaseNotifier"

    private const val LATEST_API_URL =
        "https://api.github.com/repos/Delta-Kronecker/DeltaRay/releases/latest"

    private const val REPO_PAGE_PREFIX = "https://github.com/Delta-Kronecker/DeltaRay/releases/tag/"

    /// Страница конкретного релиза: .../releases/tag/<tag>.
    fun releasePageUrl(tag: String): String = "$REPO_PAGE_PREFIX$tag"

    /// Канал релизов в Telegram.
    const val TELEGRAM_URL = "https://t.me/DeltaRayReleases"

    private const val PREFS_NAME = "release_notify"
    private const val KEY_LAST_NOTIFIED = "lastNotifiedVersion"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        val code: Long get() = major * 1_000_000L + minor * 1_000L + patch

        override fun compareTo(other: Version): Int = code.compareTo(other.code)

        override fun toString(): String = "$major.$minor.$patch"
    }

    data class LatestRelease(val version: Version, val tag: String)

    /** Версия установленного APK; неразбираемая (dev-сборка) — 0.0.0. */
    private fun currentVersion(app: Context): Version =
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()?.let { parseVersion(it) } ?: Version(0, 0, 0)

    /// Последний доступный релиз, если он новее установленной версии и ещё
    /// не предлагался пользователю; иначе — null.
    suspend fun check(app: Context): LatestRelease? {
        val context = app.applicationContext
        return withContext(Dispatchers.IO) {
            val remote = fetchLatest() ?: run {
                Log.w(TAG, "latest-release probe failed; skip")
                return@withContext null
            }
            val local = currentVersion(context)
            if (remote.version <= local) {
                Log.d(TAG, "up to date (installed $local; latest ${remote.tag})")
                return@withContext null
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastNotified = prefs.getString(KEY_LAST_NOTIFIED, null)
                ?.let { parseVersion(it) }
            if (lastNotified != null && remote.version <= lastNotified) {
                Log.d(TAG, "latest ${remote.tag} was already suggested")
                return@withContext null
            }
            remote
        }
    }

    /// Пометить, что релиз предложен (вызывается после показа диалога), —
    /// чтобы повторов для одной версии не было.
    fun markNotified(app: Context, version: Version) {
        app.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_NOTIFIED, version.toString())
            .apply()
    }

    private fun parseVersion(text: String): Version? {
        val parts = text.trim().removePrefix("v").split('.')
        if (parts.size != 3) return null
        val (major, minor, patch) = parts.map { it.toIntOrNull() }
        if (major == null || minor == null || patch == null) return null
        return Version(major, minor, patch)
    }

    private fun fetchLatest(): LatestRelease? =
        runCatching {
            val conn = URL(LATEST_API_URL).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "DeltaRay-Launcher")
                val status = conn.responseCode
                if (status !in 200..299) {
                    Log.w(TAG, "GET $LATEST_API_URL -> HTTP $status")
                    null
                } else {
                    val body = conn.inputStream
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                    val tag = JSONObject(body).optString("tag_name")
                        .takeIf { it.isNotBlank() } ?: return@runCatching null
                    val version = parseVersion(tag) ?: return@runCatching null
                    LatestRelease(version, tag)
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
}