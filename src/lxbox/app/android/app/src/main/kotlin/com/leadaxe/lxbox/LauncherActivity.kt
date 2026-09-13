package com.leadaxe.lxbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.leadaxe.lxbox.vpn.BoxVpnService
import com.leadaxe.lxbox.vpn.VpnStatus
import dev.zerodpi.android.profile.ZeroDpiProfile
import dev.zerodpi.android.service.RuntimeStatus
import dev.zerodpi.android.service.ZeroDpiRuntimeStateStore
import dev.zerodpi.android.service.ZeroDpiService
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/// DeltaRay: главная страница лаунчера (MAIN + LAUNCHER в манифесте).
///
/// Меню-гамбургер (три линии) открывает штатный DrawerLayout. Пункт «About»
/// открывает репозиторий проекта; долгое удержание (10 с) ведёт на скрытый
/// экран AppChooserActivity («Choose an app»).
///
/// Кнопка «Connect all» — команда-переключатель с монитором коннекта:
///   1. старт ZeroDPI, на экран выводится сканирование («Connecting…
///      ZeroDPI scan 45/120», скан идёт из потока событий службы);
///   2. когда скан завершён и ZeroDPI активен — стартует L×Box
///      (quick-action: обновление подписок + consent + start, MainActivity
///      сама закрывается — мы возвращаемся в onResume);
///   3. «Connecting… testing configs» — ждём, пока хоть один конфиг ответит
///      на штатный urlTest тем же RPC, что и приложение в UI;
///   4. ответил — «Connected». Всё это время — «Connecting…».
///
/// Повторное открытие лаунчера при уже работающих сервисах («verified»-флаг
/// в SharedPreferences) показывает Connected без перезапуска чего-либо.
class LauncherActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiHandler = Handler(Looper.getMainLooper())

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var zeroDpiBound = false
    private var connectAllRunning = false
    private var expectingConnectReturn = false
    private var zdpiRunningNotified = false
    private var aboutHoldFired = false
    private var zeroDpiMonitorJob: Job? = null
    private var pingJob: Job? = null

    private val openChooserRunnable = Runnable {
        aboutHoldFired = true
        uiHandler.removeCallbacksAndMessages(null)
        closeDrawer()
        startActivity(Intent(this@LauncherActivity, AppChooserActivity::class.java))
    }

    /// Bind для СТАРТА + мониторинга ZeroDPI: onServiceConnected вызывает
    /// `startZeroDpi` (как кнопка Start) и поднимает коллектор `state()`.
    private val zeroDpiConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            zeroDpiBound = true
            val service = (binder as ZeroDpiService.LocalBinder).service()
            if (connectAllRunning) {
                service.startZeroDpi(profileId = zeroDpiProfileId())
                startZeroDpiMonitor(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            zeroDpiBound = false
        }
    }

    /// Остановка ZeroDPI — через bind + stopZeroDpi (тот же путь, что
    /// MainViewModel.stop). startService(ACTION_STOP) не используем: это FGS
    /// с foregroundServiceType, а штатный путь старта здесь — bind/foreground.
    private val zeroDpiStopConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as ZeroDpiService.LocalBinder).service()
            service.stopZeroDpi()
            runCatching { unbindService(this) }
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<ImageButton>(R.id.btn_drawer_toggle).setOnClickListener { toggleDrawer() }
        findViewById<View>(R.id.launcher_main).setOnClickListener {
            if (isDrawerOpen()) closeDrawer()
        }
        findViewById<Button>(R.id.btn_connect_all).setOnClickListener {
            onConnectButtonPressed()
        }

        val about = findViewById<TextView>(R.id.drawer_about)
        about.setOnClickListener {
            if (aboutHoldFired) {
                aboutHoldFired = false
                return@setOnClickListener
            }
            openGithubRepo()
        }
        about.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    aboutHoldFired = false
                    uiHandler.postDelayed(openChooserRunnable, ABOUT_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    uiHandler.removeCallbacksAndMessages(null)
                else -> Unit
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (expectingConnectReturn) {
            // MainActivity (quick-action connect-all) закрылась — вернулись.
            expectingConnectReturn = false
            startPingStage()
        }
        refreshConnectState()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        zeroDpiMonitorJob?.cancel()
        pingJob?.cancel()
        scope.cancel()
        unbindZeroDpi()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isDrawerOpen()) closeDrawer() else super.onBackPressed()
    }

    // -- Drawer ---------------------------------------------------------------

    private fun isDrawerOpen(): Boolean =
        findViewById<DrawerLayout>(R.id.launcher_root)
            .isDrawerOpen(GravityCompat.START)

    private fun toggleDrawer() {
        val drawer = findViewById<DrawerLayout>(R.id.launcher_root)
        if (drawer.isDrawerOpen(GravityCompat.START)) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        findViewById<DrawerLayout>(R.id.launcher_root).openDrawer(GravityCompat.START)
    }

    private fun closeDrawer() {
        findViewById<DrawerLayout>(R.id.launcher_root).closeDrawer(GravityCompat.START)
    }

    private fun openGithubRepo() {
        val uri = Uri.parse(GITHUB_REPO_URL)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // -- Connect all / Disconnect all ----------------------------------------

    private fun onConnectButtonPressed() {
        if (connectAllRunning) return
        // Короткая проверка активности (с suspension): клик не блокирует UI.
        findViewById<Button>(R.id.btn_connect_all).isEnabled = false
        scope.launch {
            val anyActive =
                BoxVpnService.currentStatus != VpnStatus.Stopped || zeroDpiCurrentlyActive()
            if (anyActive) {
                stopAll()
                findViewById<Button>(R.id.btn_connect_all).isEnabled = true
            } else {
                connectAll()
            }
        }
    }

    /// Стадия 1 — ZeroDPI: foreground service + bind → startZeroDpi. Дальше
    /// мониторит коллектор state(): сканирование на экран, Running → стадия 2.
    private fun connectAll() {
        if (connectAllRunning) return
        connectAllRunning = true
        expectingConnectReturn = false
        zdpiRunningNotified = false
        prefs.edit().putBoolean(KEY_CONNECT_VERIFIED, false).apply()
        findViewById<Button>(R.id.btn_connect_all).isEnabled = false
        setConnectingStatus(R.string.launcher_status_zdpi_starting)

        val serviceIntent = Intent(this, ZeroDpiService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        zeroDpiBound = bindService(serviceIntent, zeroDpiConnection, Context.BIND_AUTO_CREATE)
        if (!zeroDpiBound) {
            Log.e(TAG, "Failed to bind ZeroDpiService")
        }
    }

    /// Мониторинг скана ZeroDPI «из лога»: поток runner-событий службы уже
    /// превращён в state() (scan_started/scan_progress/scan_completed →
    /// RuntimeStatus.Scanning + ScanProgressInfo). Показываем прогресс скана,
    /// на Running — уходим на стадию 2.
    private fun startZeroDpiMonitor(service: ZeroDpiService) {
        zeroDpiMonitorJob?.cancel()
        zeroDpiMonitorJob = scope.launch {
            service.state().collect { s ->
                if (!connectAllRunning) return@collect
                when (s.status) {
                    RuntimeStatus.Scanning -> {
                        val p = s.scanProgress
                        if (p != null && p.total != null && p.total > 0 && p.completed != null) {
                            setConnectingStatus(
                                R.string.launcher_status_zdpi_scanning,
                                p.completed.coerceAtLeast(0),
                                p.total,
                            )
                        } else {
                            setConnectingStatus(R.string.launcher_status_zdpi_starting)
                        }
                    }
                    RuntimeStatus.Starting,
                    RuntimeStatus.Choosing,
                    RuntimeStatus.Restarting -> {
                        setConnectingStatus(R.string.launcher_status_zdpi_starting)
                    }
                    RuntimeStatus.Running -> {
                        if (!zdpiRunningNotified) {
                            zdpiRunningNotified = true
                            zeroDpiMonitorJob?.cancel()
                            launchLxBoxStage()
                        }
                    }
                    RuntimeStatus.Failed -> {
                        onConnectFlowFailed(
                            R.string.launcher_status_zdpi_failed,
                            s.lastError ?: s.status.name,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    /// Стадия 2 — L×Box: запуск через quick-action MainActivity (обновление
    /// подписок без ручного открытия + consent + старт VPN). Activity сама
    /// закрывается (finishAfterConsent) — мы возвращаемся в onResume.
    private fun launchLxBoxStage() {
        setConnectingStatus(R.string.launcher_status_zdpi_active)
        expectingConnectReturn = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_CONNECT_ALL_REFRESH)
            },
        )
    }

    /// Стадия 3 — «a config pings»: ждём поднятия ядра L×Box и гоним штатный
    /// urlTest по конфигам, пока хоть один не ответит. До этого — Connecting.
    /// [verify] = пассивная проверка при уже работающих сервисах (перезапуск
    /// лаунчера): провал не роняет флоу, статус остаётся Connecting.
    private fun startPingStage(verify: Boolean = false) {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            setConnectingStatus(R.string.launcher_status_lxbox_starting)
            val coreUp = withTimeoutOrNull(CORE_UP_TIMEOUT_MS) {
                while (BoxVpnService.currentStatus != VpnStatus.Started) {
                    delay(CORE_POLL_MS)
                    if (BoxVpnService.currentStatus == VpnStatus.Stopped) break
                }
                BoxVpnService.currentStatus == VpnStatus.Started
            } ?: false

            if (!coreUp) {
                if (!verify) {
                    if (BoxVpnService.currentStatus == VpnStatus.Stopped) {
                        onConnectFlowFailed(R.string.qc_consent_denied)
                    } else {
                        onConnectFlowFailed(
                            R.string.launcher_status_connect_failed,
                            BoxVpnService.currentStatus.name,
                        )
                    }
                }
                return@launch
            }

            setConnectingStatus(R.string.launcher_status_ping_configs)
            val plan = ConnectConfigPing.plan(ConfigManager.load())
            val ok = ConnectConfigPing.probeUntilSuccess(
                plan,
                isCoreAlive = { BoxVpnService.currentStatus == VpnStatus.Started },
            )

            if (ok) {
                onConnectSucceeded()
            } else if (!verify) {
                onConnectFlowFailed(R.string.launcher_status_connect_failed, "no config answered")
            } else {
                setConnectingStatus(R.string.launcher_status_ping_no_reply)
            }
        }
    }

    /// Текущее состояние связки. L×Box — по нативному статусу сервиса;
    /// ZeroDPI — короткий peek статуса через bind (маркер рантайма честно
    /// отвечает на «сервис хоть запущен?», а реле ли = по StateFlow).
    private fun refreshConnectState() {
        val lxActive = BoxVpnService.currentStatus != VpnStatus.Stopped
        scope.launch {
            val zActive = zeroDpiCurrentlyActive()
            when {
                connectAllRunning -> Unit // статусы ведут стадии флоу
                lxActive -> {
                    findViewById<Button>(R.id.btn_connect_all)
                        .setText(R.string.app_chooser_disconnect_all)
                    if (prefs.getBoolean(KEY_CONNECT_VERIFIED, false)) {
                        showConnectedStatus()
                    } else if (pingJob?.isActive != true) {
                        startPingStage(verify = true)
                    }
                }
                zActive -> {
                    findViewById<Button>(R.id.btn_connect_all)
                        .setText(R.string.app_chooser_disconnect_all)
                    setConnectingStatus(R.string.launcher_status_zdpi_active)
                }
                else -> {
                    findViewById<Button>(R.id.btn_connect_all)
                        .setText(R.string.app_chooser_connect_all)
                    showIdleStatus()
                }
            }
        }
    }

    private fun stopAll() {
        connectAllRunning = false
        expectingConnectReturn = false
        zeroDpiMonitorJob?.cancel()
        pingJob?.cancel()
        prefs.edit().putBoolean(KEY_CONNECT_VERIFIED, false).apply()
        // L×Box — штатная остановка VPN.
        BoxVpnService.stop(applicationContext)
        // ZeroDPI — bind + stopZeroDpi.
        runCatching {
            bindService(
                Intent(this, ZeroDpiService::class.java),
                zeroDpiStopConnection,
                Context.BIND_AUTO_CREATE,
            )
        }.onFailure {
            Log.e(TAG, "Failed to bind ZeroDPI for stop: ${it.message}", it)
        }
        uiHandler.postDelayed({ refreshConnectState() }, STOP_SETTLE_DELAY_MS)
    }

    private fun onConnectSucceeded() {
        prefs.edit().putBoolean(KEY_CONNECT_VERIFIED, true).apply()
        connectAllRunning = false
        zeroDpiMonitorJob?.cancel()
        findViewById<Button>(R.id.btn_connect_all).isEnabled = true
        findViewById<Button>(R.id.btn_connect_all)
            .setText(R.string.app_chooser_disconnect_all)
        showConnectedStatus()
    }

    private fun onConnectFlowFailed(@StringRes messageRes: Int, arg: String? = null) {
        Log.w(TAG, "connect flow failed: $messageRes $arg")
        connectAllRunning = false
        expectingConnectReturn = false
        zdpiRunningNotified = false
        zeroDpiMonitorJob?.cancel()
        findViewById<Button>(R.id.btn_connect_all).isEnabled = true
        setConnectingStatus(messageRes, arg ?: "", isError = true)
    }

    // -- Status UI ------------------------------------------------------------

    private fun setConnectingStatus(
        @StringRes resId: Int,
        vararg args: Any,
        isError: Boolean = false,
    ) {
        val status = findViewById<TextView>(R.id.launcher_connect_status)
        status.visibility = View.VISIBLE
        status.text = if (args.isEmpty()) getString(resId) else getString(resId, *args)
        status.setTextColor(if (isError) COLOR_ERROR else COLOR_MUTED)
    }

    private fun showConnectedStatus() {
        val status = findViewById<TextView>(R.id.launcher_connect_status)
        status.visibility = View.VISIBLE
        status.setTextColor(COLOR_OK)
        status.text = getString(R.string.launcher_status_connected)
    }

    private fun showIdleStatus() {
        findViewById<TextView>(R.id.launcher_connect_status).visibility = View.GONE
    }

    // -- ZeroDPI helpers ------------------------------------------------------

    private fun zeroDpiProfileId(): String {
        val marker = ZeroDpiRuntimeStateStore.runtimeMarker(applicationContext)
        return marker.profileId?.takeIf { it.isNotBlank() } ?: ZeroDpiProfile.DEFAULT_PROFILE_ID
    }

    private suspend fun zeroDpiCurrentlyActive(): Boolean {
        if (!ZeroDpiRuntimeStateStore.isRuntimeActive(applicationContext)) return false
        return withTimeoutOrNull(ZERO_PEEK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val service = (binder as ZeroDpiService.LocalBinder).service()
                        val active = service.state().value.status in ACTIVE_ZERO_STATUSES
                        runCatching { unbindService(this) }
                        if (cont.isActive) cont.resume(active)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val bound = bindService(
                    Intent(this@LauncherActivity, ZeroDpiService::class.java),
                    conn,
                    Context.BIND_AUTO_CREATE,
                )
                if (!bound) {
                    if (cont.isActive) cont.resume(false)
                } else {
                    cont.invokeOnCancellation { runCatching { unbindService(conn) } }
                }
            }
        } ?: false
    }

    private fun unbindZeroDpi() {
        if (zeroDpiBound) {
            runCatching { unbindService(zeroDpiConnection) }
            zeroDpiBound = false
        }
    }

    private companion object {
        val TAG: String = LauncherActivity::class.java.simpleName
        const val GITHUB_REPO_URL = "https://github.com/Delta-Kronecker/DeltaRay"
        const val ABOUT_HOLD_MS = 10_000L
        const val STOP_SETTLE_DELAY_MS = 700L
        const val ZERO_PEEK_TIMEOUT_MS = 1_500L
        const val CORE_UP_TIMEOUT_MS = 25_000L
        const val CORE_POLL_MS = 200L

        const val PREFS_NAME = "deltaray_monitor"
        const val KEY_CONNECT_VERIFIED = "connect_all_verified"

        val ACTIVE_ZERO_STATUSES = setOf(
            RuntimeStatus.Starting,
            RuntimeStatus.Scanning,
            RuntimeStatus.Running,
            RuntimeStatus.Restarting,
            RuntimeStatus.Choosing,
            RuntimeStatus.Stopping,
        )

        val COLOR_OK = 0xFF4CAF50.toInt()
        val COLOR_ERROR = 0xFFE05C60.toInt()
        val COLOR_MUTED = 0xFF8A93A6.toInt()
    }
}