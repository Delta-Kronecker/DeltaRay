package com.leadaxe.lxbox

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Button
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

/// DeltaRay: экран выбора между двумя приложениями в одном APK.
///
/// LauncherActivity — единственная entry point из home-launcher'а
/// (MAIN + LAUNCHER в манифесте). Каждая кнопка открывает полное приложение;
/// возврат на этот экран — обычным Back (эта activity остаётся в task ниже).
/// Кнопка «Connect all» запускает связку без разворачивания UI: сначала
/// ZeroDPI (тот же путь, что Start на его Dashboard), затем — дождавшись
/// Running — L×Box (тот же путь, что Connect + consent-приготовление).
class LauncherActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var zeroDpiBound = false
    private var connectAllRunning = false

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

        findViewById<Button>(R.id.btn_open_lxbox).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btn_open_zerodpi).setOnClickListener {
            startActivity(Intent(this, dev.zerodpi.android.MainActivity::class.java))
        }
        findViewById<Button>(R.id.btn_connect_all).setOnClickListener { connectAll() }
    }

    override fun onDestroy() {
        scope.cancel()
        unbindZeroDpi()
        super.onDestroy()
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
        const val REQUEST_VPN_CONSENT = 0x1024
        const val ZERODPI_WAIT_MS = 25_000L
    }
}