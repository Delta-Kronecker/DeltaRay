package com.leadaxe.lxbox

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.leadaxe.lxbox.vpn.BoxVpnService
import com.leadaxe.lxbox.vpn.RemoteRuntimeUpdater
import com.leadaxe.lxbox.vpn.ReleaseNotifier
import com.leadaxe.lxbox.vpn.VpnStatus
import com.leadaxe.lxbox.vpn.WatchdogLog
import com.leadaxe.lxbox.vpn.WatchdogService
import com.leadaxe.lxbox.vpn.WatchdogStats
import dev.zerodpi.android.profile.ZeroDpiProfile
import dev.zerodpi.android.service.RuntimeStatus
import dev.zerodpi.android.service.ZeroDpiRuntimeStateStore
import dev.zerodpi.android.service.ZeroDpiService
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /// Круглая кнопка подключения с золотым кольцевым прогрессом скана.
    private val connectButton: ConnectRingButton
        get() = findViewById(R.id.btn_connect_all)

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private var zeroDpiBound = false
    private var zeroDpiService: ZeroDpiService? = null
    private var connectAllRunning = false
    private var expectingConnectReturn = false
    private var zdpiRunningNotified = false
    private var aboutHoldFired = false
    private var zeroDpiMonitorJob: Job? = null
    private var pingJob: Job? = null
    private var statsTickerJob: Job? = null
    /// Максимум наблюдённого прогресса скана ZeroDPI за эту сессию: ИП
    /// отвечают НЕ по порядку, `completed` может откатываться между батчами
    /// (32 → 21 → 30), поэтому процент считаем от лучшего наблюдения.
    private var zdpiScannedBest = 0

    /// Метка текущего скана (`sni`/`ip`/`proxy`) для per-scan максимума
    /// `zdpiScannedBest` — см. ТЗ в startZeroDpiMonitor.
    private var zdpiScanKey: String? = null

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
            zeroDpiService = service
            if (connectAllRunning) {
                service.startZeroDpi(profileId = zeroDpiProfileId())
                startZeroDpiMonitor(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            zeroDpiBound = false
            zeroDpiService = null
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
        connectButton.setOnClickListener {
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

        // Журнал вачдога: последние строки кольцевого буфера + кнопка копирования.
        findViewById<TextView>(R.id.drawer_watchdog_log).setOnClickListener {
            closeDrawer()
            startActivity(Intent(this, WatchdogLogActivity::class.java))
        }

        // Проверка обновления runtime-ресурсов ZeroDPI (config/sni/ip по
        // version.txt в репозитории) — с диалогом прогресса.
        scope.launch {
            val dialog = Dialog(this@LauncherActivity)
            withContext(Dispatchers.Main) {
                dialog.setContentView(R.layout.dialog_updating_settings)
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                dialog.setCancelable(false)
                dialog.show()
            }
            try {
                RemoteRuntimeUpdater.checkAndUpdate(applicationContext)
            } finally {
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) dialog.dismiss()
                }
            }
        }
        // Оповещение о новом релизе (диалог с прямой ссылкой на APK).
        maybePromptUpdate()
    }

    /// Новый релиз в репозитории — тематический диалог с прямой ссылкой на
    /// APK-файл (скачивание в браузере). Показывается при каждом запуске:
    /// персистентной отметки «уже предлагалось» нет.
    private fun maybePromptUpdate() {
        scope.launch {
            val release = ReleaseNotifier.check(applicationContext) ?: return@launch
            val dialog = Dialog(this@LauncherActivity)
            dialog.setContentView(R.layout.dialog_update)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.findViewById<TextView>(R.id.dialog_update_message).text =
                getString(R.string.launcher_update_message, release.tag)
            dialog.findViewById<View>(R.id.dialog_update_download).setOnClickListener {
                openUri(ReleaseNotifier.directDownloadUrl(release.tag))
            }
            dialog.findViewById<View>(R.id.dialog_update_notnow).setOnClickListener {
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun openUri(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Log.w(TAG, "no activity for $url: ${it.message}") }
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
        statsTickerJob?.cancel()
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
        connectButton.isEnabled = false
        scope.launch {
            val anyActive =
                BoxVpnService.currentStatus != VpnStatus.Stopped || zeroDpiCurrentlyActive()
            if (anyActive) {
                stopAll()
                connectButton.isEnabled = true
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
        connectButton.isEnabled = false
        connectButton.setIdle()
        setConnectingStatus(R.string.launcher_status_zdpi_starting)
        WatchdogLog.add("── CONNECT ALL: stage 1 — starting Bypass Engine (ZeroDPI) ──")

        // Уже связаны с прошлого флоу (bind живёт до onDestroy): повторный
        // bindService с тем же ServiceConnection НЕ вызовет onServiceConnected,
        // поэтому стартуем напрямую через удержанный ссылку на службу.
        val service = zeroDpiService
        if (service != null) {
            zeroDpiMonitorJob?.cancel()
            zeroDpiMonitorJob = null
            WatchdogLog.add("ZeroDPI already bound — startZeroDpi directly")
            service.startZeroDpi(profileId = zeroDpiProfileId())
            startZeroDpiMonitor(service)
            return
        }
        unbindZeroDpi()

        val serviceIntent = Intent(this, ZeroDpiService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        zeroDpiBound = bindService(serviceIntent, zeroDpiConnection, Context.BIND_AUTO_CREATE)
        if (!zeroDpiBound) {
            Log.e(TAG, "Failed to bind ZeroDpiService")
            WatchdogLog.add("✗ bind ZeroDpiService failed")
        }
    }

    /// Мониторинг скана ZeroDPI «из лога»: поток runner-событий службы уже
    /// превращён в state() (scan_started/scan_progress/scan_completed).
    /// ТЗ: прогресс — в процентах, живой, по текущей фазе скана. Сканы идут
    /// подряд (sni → ip → proxy), у каждого свой total, и внутри IP-скана
    /// фазы `tcp` и `probe` бегут параллельно. Максимум берём ТОЛЬКО внутри
    /// одной фазы (ключ scan/phase), а фазу-предпрогон `tcp` в процент не
    /// считаем вовсе: иначе старт ip-скана «выстреливал» на 98%, пока probe
    /// по логу ещё на 23/100 (наблюдение оператора).
    private fun startZeroDpiMonitor(service: ZeroDpiService) {
        zeroDpiMonitorJob?.cancel()
        zdpiScannedBest = 0
        zdpiScanKey = null
        zeroDpiMonitorJob = scope.launch {
            service.state().collect { s ->
                if (!connectAllRunning) return@collect
                when (s.status) {
                    RuntimeStatus.Scanning -> {
                        val p = s.scanProgress
                        if (p != null) {
                            // Прогресс строим строго по событиям из лога
                            // runner'а «в момент»: максимум не переносится
                            // между фазами. В IP-скане фаза `tcp` — быстрый
                            // предпрогон (до ~100% за первую секунду), а
                            // результаты даёт фаза `probe` (23/100 → 32/100);
                            // по `tcp` процент не показываем.
                            if (p.phase != "tcp") {
                                // Локальные копии: smart-cast на публичные
                                // API-свойства другого модуля (zerodpi) запрещён.
                                val total = p.total ?: 0
                                val completed = p.completed ?: -1
                                if (total > 0 && completed >= 0) {
                                    val key = "${p.scan.ifBlank { "scan" }}/${p.phase ?: ""}"
                                    if (key != zdpiScanKey) {
                                        zdpiScanKey = key
                                        zdpiScannedBest = 0
                                        WatchdogLog.add("ZeroDPI: scan “$key” started (total=${p.total ?: "?"})")
                                    }
                                    if (completed > zdpiScannedBest) {
                                        zdpiScannedBest = completed
                                    }
                                    val percent =
                                        (zdpiScannedBest.toLong() * 100 / total)
                                            .coerceIn(0L, 100L).toInt()
                                    connectButton.setConnecting(percent)
                                    setConnectingStatus(
                                        R.string.launcher_status_zdpi_scanning,
                                        percent,
                                    )
                                } else {
                                    setConnectingStatus(R.string.launcher_status_zdpi_starting)
                                }
                            } else {
                                setConnectingStatus(R.string.launcher_status_zdpi_starting)
                            }
                        } else {
                            setConnectingStatus(R.string.launcher_status_zdpi_starting)
                        }
                    }
                    RuntimeStatus.Starting,
                    RuntimeStatus.Choosing,
                    RuntimeStatus.Restarting -> {
                        connectButton.setConnecting()
                        setConnectingStatus(R.string.launcher_status_zdpi_starting)
                    }
                    RuntimeStatus.Running -> {
                        if (!zdpiRunningNotified) {
                            zdpiRunningNotified = true
                            WatchdogLog.add("ZeroDPI: Running (relay up) → stage 2")
                            connectButton.setDisconnect()
                            zeroDpiMonitorJob?.cancel()
                            launchLxBoxStage()
                        }
                    }
                    RuntimeStatus.Failed -> {
                        connectButton.setIdle()
                        WatchdogLog.add("✗ ZeroDPI Failed: ${s.lastError ?: s.status.name}")
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

    /// Стадия 2 — L×Box: запуск через НЕВИДИМЫЙ QuickConnectActivity
    /// (обновление подписок + consent + старт VPN в фоне, без видимого окна;
    /// тема прозрачная, контент скрыт). Единственный возможный видимый элемент —
    /// системный диалог согласия VPN при первом connect (OS-обязательство).
    /// Активность сама закрывается по факту Started / таймауту; мы возвращаемся
    /// в onResume (expectingConnectReturn) и переходим на стадию пинга.
    private fun launchLxBoxStage() {
        setConnectingStatus(R.string.launcher_status_zdpi_active)
        WatchdogLog.add("── stage 2: quick-connect L×Box (invisible) ──")
        expectingConnectReturn = true
        startActivity(
            Intent(this, QuickConnectActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
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
            connectButton.setConnecting()
            setConnectingStatus(R.string.launcher_status_lxbox_starting)
            val coreUp = withTimeoutOrNull(CORE_UP_TIMEOUT_MS) {
                while (BoxVpnService.currentStatus != VpnStatus.Started) {
                    delay(CORE_POLL_MS)
                    if (BoxVpnService.currentStatus == VpnStatus.Stopped) break
                }
                BoxVpnService.currentStatus == VpnStatus.Started
            } ?: false

            if (!coreUp) {
                WatchdogLog.add("✗ L×Box core did not come up within ${CORE_UP_TIMEOUT_MS / 1000}s (status=${BoxVpnService.currentStatus})")
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
            WatchdogLog.add("── stage 3: core Started → waiting for app pings (getGroups, up to ${ConnectConfigPing.POLL_MAX_WAIT_MS / 1000}s) ──")
            val okDelays = ConnectConfigPing.pollAppPings(
                isCoreAlive = { BoxVpnService.currentStatus == VpnStatus.Started },
            )

            if (!okDelays.isNullOrEmpty()) {
                WatchdogLog.add("⚡ configs answered: ${okDelays.entries.joinToString { "${it.key}=${it.value}ms" }} → Connected")
                WatchdogStats.init(applicationContext).setInitialPings(okDelays)
                ensureWatchdogRunning()
                onConnectSucceeded()
            } else if (!verify) {
                WatchdogLog.add(
                    if (okDelays == null) {
                        "✗ ping failed: core went down or command client did not connect"
                    } else {
                        "✗ no config answered within the timeout"
                    },
                )
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
                    connectButton.setDisconnect()
                    if (prefs.getBoolean(KEY_CONNECT_VERIFIED, false)) {
                        // Уже подключено (перезапуск лаунчера): подхватываем
                        // вачдог + его тикер, как при свежем коннекте.
                        // ВАЖНО: relight на auto здесь НЕ делаем — каждое
                        // возвращение с экрана журнала/приложения сбрасывало
                        // выбор вачдога на auto-двойник, urltest перебирал
                        // ноды между пробами и streak никогда не доходил до
                        // порога. Релайт — только на старте цикла вачдога.
                        ensureWatchdogRunning()
                        WatchdogStats.init(applicationContext)
                        startStatsTicker()
                        showConnectedStatus()
                    } else if (pingJob?.isActive != true) {
                        startPingStage(verify = true)
                    }
                }
                zActive -> {
                    connectButton.setDisconnect()
                    setConnectingStatus(R.string.launcher_status_zdpi_active)
                }
                else -> {
                    connectButton.setIdle()
                    showIdleStatus()
                }
            }
        }
    }

    private fun stopAll() {
        WatchdogLog.add("■ DISCONNECT ALL: stopping L×Box + ZeroDPI + watchdog (defective list dies with session)")
        connectAllRunning = false
        expectingConnectReturn = false
        zeroDpiMonitorJob?.cancel()
        pingJob?.cancel()
        prefs.edit().putBoolean(KEY_CONNECT_VERIFIED, false).apply()
        hideWatchdogStatus()
        // L×Box — штатная остановка VPN.
        BoxVpnService.stop(applicationContext)
        stopService(Intent(this, WatchdogService::class.java))
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
        connectButton.isEnabled = true
        showConnectedStatus()
        startStatsTicker()
    }

    private fun onConnectFlowFailed(@StringRes messageRes: Int, arg: String? = null) {
        Log.w(TAG, "connect flow failed: $messageRes $arg")
        WatchdogLog.add("✗✗ connect flow failed: ${getString(messageRes, arg ?: "")}")
        connectAllRunning = false
        expectingConnectReturn = false
        zdpiRunningNotified = false
        zeroDpiMonitorJob?.cancel()
        connectButton.isEnabled = true
        connectButton.setIdle()
        setConnectingStatus(messageRes, arg ?: "", isError = true)
        hideWatchdogStatus()
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
        connectButton.setDisconnect()
    }

    private fun showIdleStatus() {
        findViewById<TextView>(R.id.launcher_connect_status).visibility = View.GONE
        hideWatchdogStatus()
    }

    // -- Watchdog UI ----------------------------------------------------------

    /// Поднять фоновый сервис вачдога (обычный startService: сервис живёт,
    /// пока VPN запущен, сам останавливается по BROADCAST_STATUS=Stopped).
    /// Повторный старт безопасен — сервис держит один цикл.
    private fun ensureWatchdogRunning() {
        startService(Intent(this, WatchdogService::class.java))
    }

    /// Тикер UI вачдога (те же 3с, что и период замера): читает снапшот
    /// статистики и перерисовывает два TextView главной страницы.
    private fun startStatsTicker() {
        stopStatsTicker()
        statsTickerJob = scope.launch {
            while (true) {
                refreshWatchdogUi()
                delay(STATS_TICK_MS)
            }
        }
    }

    private fun stopStatsTicker() {
        statsTickerJob?.cancel()
        statsTickerJob = null
    }

    private fun hideWatchdogStatus() {
        stopStatsTicker()
        findViewById<TextView>(R.id.launcher_watchdog_status).visibility = View.GONE
        findViewById<TextView>(R.id.launcher_watchdog_stats).visibility = View.GONE
        findViewById<TextView>(R.id.launcher_traffic_stats).visibility = View.GONE
    }

    private fun refreshWatchdogUi() {
        val status = findViewById<TextView>(R.id.launcher_watchdog_status)
        val stats = findViewById<TextView>(R.id.launcher_watchdog_stats)
        val snap = WatchdogStats.instance.snapshot()
        val stateText = when (snap.state) {
            WatchdogStats.State.Idle,
            WatchdogStats.State.Starting -> getString(R.string.launcher_watchdog_starting)
            WatchdogStats.State.Ok ->
                getString(R.string.launcher_watchdog_ok, snap.lastRttMs)
            WatchdogStats.State.Fail ->
                getString(R.string.launcher_watchdog_timeouts, snap.timeouts.coerceAtLeast(1))
            WatchdogStats.State.Disabled -> getString(R.string.launcher_watchdog_disabled)
        }
        status.text = stateText
        status.setTextColor(
            when (snap.state) {
                WatchdogStats.State.Ok -> COLOR_OK
                WatchdogStats.State.Fail -> COLOR_ERROR
                else -> COLOR_MUTED
            },
        )
        // ТЗ: смена конфигов в логе НЕ показывается — только счётчики.
        val base = getString(
            R.string.launcher_watchdog_stats,
            snap.tests,
            snap.ok,
            snap.timeouts,
            snap.switches,
        )
        stats.text = base
        status.visibility = View.VISIBLE
        stats.visibility = View.VISIBLE
        // Объём ↑/↓ из status-стрима ядра (Stats-экран приложения).
        val traffic = findViewById<TextView>(R.id.launcher_traffic_stats)
        traffic.text = getString(
            R.string.launcher_traffic_stats,
            formatBytes(snap.uplinkTotal),
            formatBytes(snap.downlinkTotal),
        )
        traffic.visibility = View.VISIBLE
    }

    /// Размер файла как в приложении (format_utils.formatBytes, spaced=true):
    /// `0 B`, `<1024` → B, KB/MB с 1 знаком, GB с 2.
    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        if (b < 1024L) return "$b B"
        if (b < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", b / 1024.0)
        if (b < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
        }
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
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
            zeroDpiService = null
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
        const val STATS_TICK_MS = 3_000L

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

        val COLOR_OK = 0xFF62C07E.toInt()
        val COLOR_ERROR = 0xFFD96A6A.toInt()
        val COLOR_MUTED = 0xFF93A59E.toInt()
    }
}