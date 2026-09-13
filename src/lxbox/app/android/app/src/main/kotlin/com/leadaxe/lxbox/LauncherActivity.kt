package com.leadaxe.lxbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/// DeltaRay: главная страница лаунчера (MAIN + LAUNCHER в манифесте).
///
/// Меню-гамбургер (три линии) открывает штатный DrawerLayout. Пункт «About»
/// открывает репозиторий проекта; долгое удержание (10 с) ведёт на скрытый
/// экран AppChooserActivity («Choose an app»).
///
/// Кнопка «Connect all» — команда-переключатель: когда ничего не активно —
/// запускает связку (ZeroDPI + обновление подписок без ручного открытия
/// L×Box + подключение VPN); когда активно — останавливает обе части
/// (L×Box VPN + ZeroDPI).
class LauncherActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiHandler = Handler(Looper.getMainLooper())

    private var zeroDpiBound = false
    private var connectAllRunning = false
    private var expectingConnectReturn = false
    private var aboutHoldFired = false

    private val openChooserRunnable = Runnable {
        aboutHoldFired = true
        uiHandler.removeCallbacksAndMessages(null)
        closeDrawer()
        startActivity(Intent(this@LauncherActivity, AppChooserActivity::class.java))
    }

    private val zeroDpiConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            zeroDpiBound = true
            val service = (binder as ZeroDpiService.LocalBinder).service()
            service.startZeroDpi(profileId = zeroDpiProfileId())
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
            connectAllRunning = false
            findViewById<Button>(R.id.btn_connect_all).isEnabled = true
        }
        refreshConnectState()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
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

    private fun connectAll() {
        if (connectAllRunning) return
        connectAllRunning = true
        findViewById<Button>(R.id.btn_connect_all).isEnabled = false

        // 1) ZeroDPI — foreground service + bind → startZeroDpi (тот же путь, что Start).
        val serviceIntent = Intent(this, ZeroDpiService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        zeroDpiBound = bindService(serviceIntent, zeroDpiConnection, Context.BIND_AUTO_CREATE)
        if (!zeroDpiBound) {
            Log.e(TAG, "Failed to bind ZeroDpiService")
        }

        // 2) L×Box — обновление подписок (без ручного открытия) + подключение.
        //    MainActivity сама закроется после обработки (finishAfterConsent),
        //    мы вернёмся в onResume.
        expectingConnectReturn = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_ACTION, MainActivity.ACTION_CONNECT_ALL_REFRESH)
            },
        )
    }

    private fun stopAll() {
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

    /// Текущее состояние связки. L×Box — по нативному статусу сервиса;
    /// ZeroDPI — короткий peek статуса через bind (маркер рантайма честно
    /// отвечает на «сервис хоть запущен?», а реле ли = по StateFlow).
    private fun refreshConnectState() {
        val lxActive = BoxVpnService.currentStatus != VpnStatus.Stopped
        scope.launch {
            val zActive = zeroDpiCurrentlyActive()
            applyConnectState(lxActive || zActive)
        }
    }

    private fun applyConnectState(anyActive: Boolean) {
        findViewById<Button>(R.id.btn_connect_all).setText(
            if (anyActive) R.string.app_chooser_disconnect_all
            else R.string.app_chooser_connect_all,
        )
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

    private fun zeroDpiProfileId(): String {
        val marker = ZeroDpiRuntimeStateStore.runtimeMarker(applicationContext)
        return marker.profileId?.takeIf { it.isNotBlank() } ?: ZeroDpiProfile.DEFAULT_PROFILE_ID
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
        val ACTIVE_ZERO_STATUSES = setOf(
            RuntimeStatus.Starting,
            RuntimeStatus.Scanning,
            RuntimeStatus.Running,
            RuntimeStatus.Restarting,
            RuntimeStatus.Choosing,
            RuntimeStatus.Stopping,
        )
    }
}