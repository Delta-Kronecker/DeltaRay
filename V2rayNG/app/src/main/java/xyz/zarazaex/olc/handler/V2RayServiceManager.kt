package xyz.zarazaex.olc.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.contracts.ServiceControl
import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.service.V2RayProxyOnlyService
import xyz.zarazaex.olc.service.V2RayVpnService
import xyz.zarazaex.olc.util.MessageUtil
import xyz.zarazaex.olc.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())

    fun getCoreController(): CoreController = coreController
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private val operationLock = Any()
    @Volatile private var isOperationInProgress = false
    @Volatile var isIntentionalStop = false

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        isIntentionalStop = false
        startContextService(context)
        return true
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     * @param guid The GUID of the server configuration to use (optional).
     */
    fun startVService(context: Context, guid: String? = null) {
        synchronized(operationLock) {
            if (isOperationInProgress) {
                Log.w(AppConfig.TAG, "StartCore-Manager: Operation already in progress")
                return
            }
            isOperationInProgress = true
        }

        try {
            Log.i(AppConfig.TAG, "StartCore-Manager: startVService from ${context::class.java.simpleName}")

            if (guid != null) {
                MmkvManager.setSelectServer(guid)
            }

            isIntentionalStop = false
            startContextService(context)
        } finally {
            synchronized(operationLock) {
                isOperationInProgress = false
            }
        }
    }

    /**
     * Stops the V2Ray service.
     * @param context The context from which the service is stopped.
     */
    fun stopVService(context: Context) {
        Log.i(AppConfig.TAG, "StartCore-Manager: stopVService called")
        isIntentionalStop = true
        val svc = serviceControl?.get()
        if (svc != null) {
            svc.stopService()
            return
        }
        val intent = Intent(AppConfig.BROADCAST_ACTION_SERVICE_STOP)
        intent.setPackage(AppConfig.ANG_PACKAGE)
        context.sendBroadcast(intent)
    }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Starts the context service for V2Ray.
     * Chooses between VPN service or Proxy-only service based on user settings.
     * @param context The context from which the service is started.
     */
    private fun startContextService(context: Context) {
        Log.i(AppConfig.TAG, "StartCore-Manager: startContextService called")
        AppConfig.addServiceLog("startContextService called")
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            AppConfig.addServiceLog("Core already running, skip")
            return
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            AppConfig.addServiceLog("ERROR: No server selected")
            return
        }
        Log.i(AppConfig.TAG, "StartCore-Manager: server guid=$guid")
        AppConfig.addServiceLog("server guid=$guid")

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            AppConfig.addServiceLog("ERROR: Failed to decode server config")
            return
        }
        Log.i(AppConfig.TAG, "StartCore-Manager: config decoded, type=${config.configType}, server=${config.server}")
        AppConfig.addServiceLog("config type=${config.configType}, server=${config.server}")

        if (config.configType != EConfigType.CUSTOM
            && config.configType != EConfigType.POLICYGROUP
            && !Utils.isValidUrl(config.server)
            && !Utils.isPureIpAddress(config.server.orEmpty())
        ) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Invalid server config: server=${config.server}")
            AppConfig.addServiceLog("ERROR: Invalid server config")
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)) {
            Log.i(AppConfig.TAG, "StartCore-Manager: proxy sharing enabled")
        }

        val isVpnMode = SettingsManager.isVpnMode()
        Log.i(AppConfig.TAG, "StartCore-Manager: isVpnMode=$isVpnMode")
        AppConfig.addServiceLog("isVpnMode=$isVpnMode")
        val intent = if (isVpnMode) {
            Log.i(AppConfig.TAG, "StartCore-Manager: Creating VPN intent")
            AppConfig.addServiceLog("Creating VPN intent")
            Intent(context.applicationContext, V2RayVpnService::class.java)
        } else {
            Log.i(AppConfig.TAG, "StartCore-Manager: Creating Proxy intent")
            AppConfig.addServiceLog("Creating Proxy intent")
            Intent(context.applicationContext, V2RayProxyOnlyService::class.java)
        }

        try {
            Log.i(AppConfig.TAG, "StartCore-Manager: calling startForegroundService")
            AppConfig.addServiceLog("calling startForegroundService")
            ContextCompat.startForegroundService(context, intent)
            Log.i(AppConfig.TAG, "StartCore-Manager: startForegroundService returned OK")
            AppConfig.addServiceLog("startForegroundService OK")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: FAILED to start service: ${e.message}", e)
            AppConfig.addServiceLog("ERROR: ${e.message}")
        }
    }


    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        val service = getService()
        if (service == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }
        AppConfig.broadcastLog(service, "CORE: startCoreLoop called, isRunning=${coreController.isRunning}")

        // Wait for core to fully stop if it's still running
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core still running, waiting...")
            AppConfig.broadcastLog(service, "CORE: waiting for core to stop...")
            var waitCount = 0
            while (coreController.isRunning && waitCount < 20) {
                Thread.sleep(500)
                waitCount++
            }
            if (coreController.isRunning) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Core still running after wait")
                AppConfig.broadcastLog(service, "CORE: ERROR core still running after ${waitCount * 500}ms")
                return false
            }
            Log.i(AppConfig.TAG, "StartCore-Manager: Core stopped after ${waitCount * 500}ms")
            AppConfig.broadcastLog(service, "CORE: core stopped after ${waitCount * 500}ms")
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            AppConfig.broadcastLog(service, "CORE: ERROR no server selected")
            return false
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            AppConfig.broadcastLog(service, "CORE: ERROR decode config failed")
            return false
        }

        AppConfig.broadcastLog(service, "CORE: getting v2ray config for ${config.remarks}")
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to get V2Ray config")
            AppConfig.broadcastLog(service, "CORE: ERROR getV2rayConfig failed")
            return false
        }
        AppConfig.broadcastLog(service, "CORE: v2ray config OK, registering receiver")

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
            AppConfig.broadcastLog(service, "CORE: ERROR register receiver: ${e.message}")
            return false
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            AppConfig.broadcastLog(service, "CORE: starting core loop, tunFd=$tunFd")
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
            AppConfig.broadcastLog(service, "CORE: startLoop returned, isRunning=${coreController.isRunning}")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start core loop", e)
            AppConfig.broadcastLog(service, "CORE: ERROR startLoop: ${e.message}")
            return false
        }

        if (coreController.isRunning == false) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Core failed to start")
            AppConfig.broadcastLog(service, "CORE: ERROR core not running after startLoop")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            NotificationManager.cancelNotification()
            return false
        }

        try {
            AppConfig.broadcastLog(service, "CORE: sending MSG_STATE_RUNNING")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
            NotificationManager.startSpeedNotification(currentConfig)
            Log.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
            AppConfig.broadcastLog(service, "CORE: STARTED OK")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to complete startup", e)
            AppConfig.broadcastLog(service, "CORE: ERROR final: ${e.message}")
            return false
        }
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        NotificationManager.cancelNotification()

        if (coreController.isRunning) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                } finally {
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
                }
            }
        } else {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }

        return true
    }

    /**
     * Queries the statistics for a given tag and link.
     * @param tag The tag to query.
     * @param link The link to query.
     * @return The statistics value.
     */
    fun queryStats(tag: String, link: String): Long {
        return coreController.queryStats(tag, link)
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            val urls = listOf(
                SettingsManager.getDelayTestUrl(),
                SettingsManager.getDelayTestUrl(true)
            )
            for (url in urls) {
                if (time >= 0) break
                try {
                    time = coreController.measureDelay(url)
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }
            // One more retry after a brief pause to reduce false negatives
            if (time == -1L) {
                kotlinx.coroutines.delay(500)
                try {
                    time = coreController.measureDelay(urls[0])
                } catch (e: Exception) {
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result  $ip")
                }
            }
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                Log.w(AppConfig.TAG, "StartCore-Manager: Core shutdown callback, attempting restart")
                val service = serviceControl.getService()
                MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_NOT_RUNNING, "")
                if (isIntentionalStop) {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Intentional stop, skipping restart")
                    return 0
                }
                CoroutineScope(Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(1000L)
                    val ctx = service.applicationContext
                    if (coreController.isRunning == false) {
                        Log.i(AppConfig.TAG, "StartCore-Manager: Restarting service after core shutdown")
                        startVService(ctx)
                    }
                }
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to handle core shutdown", e)
                -1
        }
    }

    /**
     * Simple ping test for failover. Returns delay in ms or -1 for failure.
     */
    suspend fun measurePingSimple(): Long {
        if (coreController.isRunning == false) return -1L
        return withContext(Dispatchers.IO) {
            try {
                val url = SettingsManager.getDelayTestUrl()
                val result = withTimeoutOrNull(10_000L) {
                    coreController.measureDelay(url)
                }
                result ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
    }

    /**
     * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    val svc = serviceControl?.get() ?: return
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(svc.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(svc.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                }

                AppConfig.MSG_STATE_START -> {
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    synchronized(operationLock) {
                        isOperationInProgress = false
                    }
                    val svc = serviceControl?.get()
                    if (svc != null) {
                        svc.stopService()
                    } else if (ctx != null) {
                        Log.w(AppConfig.TAG, "StartCore-Manager: serviceControl null on stop, stopping core directly")
                        stopCoreLoop()
                        ctx.stopService(Intent(ctx, V2RayVpnService::class.java))
                        ctx.stopService(Intent(ctx, V2RayProxyOnlyService::class.java))
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    synchronized(operationLock) {
                        isOperationInProgress = false
                    }
                    serviceControl?.get()?.stopService()
                    Thread.sleep(500L)
                    if (ctx != null) startVService(ctx)
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}