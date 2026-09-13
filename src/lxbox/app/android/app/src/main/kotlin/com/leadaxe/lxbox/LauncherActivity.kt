package com.leadaxe.lxbox

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.net.VpnService
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
import com.leadaxe.lxbox.vpn.BootReceiver
import com.leadaxe.lxbox.vpn.BoxVpnService
import dev.zerodpi.android.profile.ZeroDpiProfile
import dev.zerodpi.android.service.RuntimeStatus
import dev.zerodpi.android.service.ZeroDpiRuntimeStateStore
import dev.zerodpi.android.service.ZeroDpiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/// DeltaRay: главная страница лаунчера (MAIN + LAUNCHER в манифесте).
///
/// Содержит меню-гамбургер (три линии). Пункт «About» открывает репозиторий
/// проекта; долгое удержание (10 с) ведёт на скрытый экран AppChooserActivity
/// («Choose an app» — выбор между L×Box и ZeroDPI). Кнопка «Connect all»
/// запускает связку без разворачивания UI: сначала ZeroDPI (тот же путь,
/// что Start на его Dashboard), затем — дождавшись Running — L×Box (тот же
/// путь, что Connect + consent-приготовление).
class LauncherActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val holdHandler = Handler(Looper.getMainLooper())

    private var zeroDpiBound = false
    private var connectAllRunning = false
    private var drawerOpen = false
    private var drawerWidthPx = 0
    private var aboutHoldFired = false

    private val openChooserRunnable = Runnable {
        aboutHoldFired = true
        holdHandler.removeCallbacksAndMessages(null)
        closeDrawer()
        startActivity(Intent(this@LauncherActivity, AppChooserActivity::class.java))
    }

    private val zeroDpiConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            zeroDpiBound = true
            val service = (binder as ZeroDpiService.LocalBinder).service()
            service.startZeroDpi(profileId = zeroDpiProfileId())
            // Ждём, пока ZeroDPI дойдёт до Running (реле слушает реальный трафик);
            // по таймауту стартуем L×Box всё равно — каскад предпочитает попытку.
            scope.launch {
                val reachedRunning = withTimeoutOrNull(ZERODPI_WAIT_MS) {
                    service.state().first { it.status == RuntimeStatus.Running }
                } != null
                if (!reachedRunning) {
                    Log.w(TAG, "ZeroDPI not Running within ${ZERODPI_WAIT_MS}ms — starting L×Box anyway")
                }
                startLxBox()
                unbindZeroDpi()
                connectAllFinished()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            zeroDpiBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        findViewById<ImageButton>(R.id.btn_drawer_toggle).setOnClickListener { toggleDrawer() }
        findViewById<View>(R.id.launcher_main).setOnClickListener {
            if (drawerOpen) closeDrawer()
        }
        findViewById<Button>(R.id.btn_connect_all).setOnClickListener { connectAll() }

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
                    holdHandler.postDelayed(openChooserRunnable, ABOUT_HOLD_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    holdHandler.removeCallbacksAndMessages(null)
                else -> Unit
            }
            false
        }

        findViewById<View>(R.id.launcher_drawer).post {
            drawerWidthPx = findViewById<View>(R.id.launcher_drawer).width
        }
    }

    override fun onDestroy() {
        holdHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        unbindZeroDpi()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawerOpen) closeDrawer() else super.onBackPressed()
    }

    private fun toggleDrawer() {
        if (drawerOpen) closeDrawer() else openDrawer()
    }

    private fun openDrawer() {
        drawerOpen = true
        val offset = if (drawerWidthPx > 0) drawerWidthPx else DEFAULT_DRAWER_WIDTH_DP
        findViewById<View>(R.id.launcher_main)
            .animate().translationX(offset.toFloat()).setDuration(DRAWER_ANIMATION_MS).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        findViewById<View>(R.id.launcher_main)
            .animate().translationX(0f).setDuration(DRAWER_ANIMATION_MS).start()
    }

    private fun openGithubRepo() {
        val uri = Uri.parse(GITHUB_REPO_URL)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun connectAll() {
        if (connectAllRunning) return
        connectAllRunning = true
        findViewById<Button>(R.id.btn_connect_all).isEnabled = false
        // Тот же старт, что у ZeroDPI: foreground service + bind → startZeroDpi.
        val serviceIntent = Intent(this, ZeroDpiService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        zeroDpiBound = bindService(serviceIntent, zeroDpiConnection, Context.BIND_AUTO_CREATE)
        if (!zeroDpiBound) {
            Log.e(TAG, "Failed to bind ZeroDpiService — falling through to L×Box only")
            startLxBox()
            connectAllFinished()
        }
    }

    private fun connectAllFinished() {
        connectAllRunning = false
        findViewById<Button>(R.id.btn_connect_all).isEnabled = true
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

    /// §192 — зеркало startVpnWithConsent из MainActivity: proxy-режим без TUN
    /// стартует без prepare; иначе consent через system UI (эта activity —
    /// foreground-контекст для startActivityForResult).
    private fun startLxBox() {
        if (!BootReceiver.hasTun(applicationContext)) {
            BoxVpnService.start(applicationContext)
            return
        }
        val prep = VpnService.prepare(applicationContext)
        if (prep == null) {
            BoxVpnService.start(applicationContext)
            return
        }
        try {
            startActivityForResult(prep, REQUEST_VPN_CONSENT)
        } catch (e: Exception) {
            Log.e(TAG, "VPN consent prepare failed: ${e.message}", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CONSENT && resultCode == RESULT_OK) {
            BoxVpnService.start(applicationContext)
        }
    }

    private companion object {
        val TAG: String = LauncherActivity::class.java.simpleName
        const val GITHUB_REPO_URL = "https://github.com/Delta-Kronecker/DeltaRay"
        const val REQUEST_VPN_CONSENT = 0x1024
        const val ZERODPI_WAIT_MS = 25_000L
        const val ABOUT_HOLD_MS = 10_000L
        const val DEFAULT_DRAWER_WIDTH_DP = 272
        const val DRAWER_ANIMATION_MS = 200L
    }
}