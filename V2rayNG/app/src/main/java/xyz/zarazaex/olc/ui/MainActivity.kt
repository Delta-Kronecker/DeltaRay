package xyz.zarazaex.olc.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.databinding.ActivityMainBinding
import xyz.zarazaex.olc.enums.PermissionType
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.extension.toastError
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.FailoverManager
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsChangeManager
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.UpdateCheckerManager
import xyz.zarazaex.olc.handler.V2RayServiceManager
import xyz.zarazaex.olc.util.MessageUtil
import xyz.zarazaex.olc.util.Utils
import xyz.zarazaex.olc.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var easterEggClickCount = 0
    private var isEasterEggActive = false
    private var connectJob: kotlinx.coroutines.Job? = null
    private var wasRunning = false
    private var subsUpdatedAfterConnect = false

    val mainViewModel: MainViewModel by viewModels()
    @Volatile private var isOperationInProgress = false

    private var groupNames = mutableListOf<String>()
    private var groupIds = mutableListOf<String>()
    private val logMessages = mutableListOf<String>()
    private val maxLogLines = 200

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        val line = "$ts $msg"
        Log.d(AppConfig.TAG, line)
        runOnUiThread {
            logMessages.add(line)
            if (logMessages.size > maxLogLines) logMessages.removeAt(0)
            binding.tvLog.text = logMessages.joinToString("\n")
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
            val msg = intent?.getStringExtra("log_msg") ?: return
            log(msg)
        }
    }

    private var pendingVpnIntent: android.content.Intent? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        log("VPN_PERM: resultCode=${it.resultCode} OK=${RESULT_OK}")
        pendingVpnIntent = null
        if (it.resultCode == RESULT_OK) {
            log("VPN_PERM: granted, starting VPN immediately")
            startConnectFlow()
        } else {
            log("VPN_PERM: denied")
            isOperationInProgress = false
            binding.btnConnect.setIconResource(R.drawable.bolt_24)
            applyRunningState(false)
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupSpinner()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)

        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, typedValue, true)
        val onSurface = typedValue.data
        binding.toolbar.setTitleTextColor(onSurface)
        for (i in 0 until binding.toolbar.childCount) {
            val child = binding.toolbar.getChildAt(i)
            if (child is android.widget.TextView) {
                child.setTextColor(onSurface)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerContentLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        findViewById<android.view.View>(R.id.drawer_settings)?.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<android.view.View>(R.id.drawer_per_app)?.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<android.view.View>(R.id.check_update)?.setOnClickListener {
            startActivity(Intent(this, CheckUpdateActivity::class.java))
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        fun removeUnderlines(textView: android.widget.TextView?) {
            if (textView == null) return
            textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
            val text = textView.text
            if (text is android.text.Spanned) {
                val spannable = android.text.SpannableStringBuilder(text)
                val spans = spannable.getSpans(0, spannable.length, android.text.style.URLSpan::class.java)
                for (span in spans) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    val flags = spannable.getSpanFlags(span)
                    val url = span.url
                    spannable.removeSpan(span)
                    spannable.setSpan(object : android.text.style.URLSpan(url) {
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }, start, end, flags)
                }
                textView.text = spannable
            }
        }
        removeUnderlines(findViewById(R.id.tv_forked))
        removeUnderlines(findViewById(R.id.tv_developed))

        findViewById<android.widget.TextView>(R.id.tv_developed)?.setOnClickListener {
            easterEggClickCount++
            if (easterEggClickCount >= 16) {
                activateEasterEgg()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.btnConnect.setOnClickListener { handleConnectAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnSwitchServer.setOnClickListener { switchToNextServer() }

        binding.btnTelegram.setOnClickListener {
            openUrl("https://t.me/DeltaKroneckerGithub")
        }
        binding.btnDonateIcon.setOnClickListener {
            val donateAddress = "0x2a434FF74737be5B94634040D010a458507b0741"
            MaterialAlertDialogBuilder(this)
                .setTitle("Donate")
                .setMessage(getString(R.string.drawer_donate_text))
                .setPositiveButton("Copy Address") { _, _ ->
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("donate", donateAddress))
                    toast("Address copied")
                }
                .setNegativeButton("OK", null)
                .show()
        }
        binding.btnSourceIcon.setOnClickListener {
            openUrl("https://github.com/Delta-Kronecker/DeltaRay")
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        mainViewModel.resetState()

        val logFilter = android.content.IntentFilter("${packageName}.action.LOG")
        registerReceiver(logReceiver, logFilter, android.content.Context.RECEIVER_NOT_EXPORTED)

        binding.btnCopyLog.setOnClickListener {
            val logText = logMessages.joinToString("\n")
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("app_log", logText))
            toast("Log copied to clipboard")
        }

        var logVisible = false
        binding.drawerLog.setOnClickListener {
            logVisible = !logVisible
            binding.logScroll.visibility = if (logVisible) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnCopyLog.visibility = if (logVisible) android.view.View.VISIBLE else android.view.View.GONE
            if (logVisible) binding.drawerLog.text = "Hide Log" else binding.drawerLog.text = "Log"
        }

        setupSpinner()
        setupViewModel()
        mainViewModel.reloadServerList()

        val isBooted = MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_BOOTED)
        if (!isBooted) {
            showStartupDialog()
            importAllSubsOnStartup()
        }

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }

        checkForUpdatesOnStartup()
    }

    private var startupDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showStartupDialog() {
        binding.btnConnect.isEnabled = false
        binding.btnConnect.alpha = 0.5f
        binding.spinnerGroup.isEnabled = false

        val tv = TextView(this).apply {
            text = "Downloading configs..."
            setPadding(64, 48, 64, 16)
            textSize = 16f
        }
        val progressBar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(64, 16, 64, 48)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(tv)
            addView(progressBar)
        }

        startupDialog = MaterialAlertDialogBuilder(this)
            .setTitle("DeltaRay")
            .setView(container)
            .setCancelable(false)
            .show()
    }

    private fun dismissStartupDialog() {
        startupDialog?.dismiss()
        startupDialog = null
        binding.btnConnect.isEnabled = true
        binding.btnConnect.alpha = 1.0f
        binding.spinnerGroup.isEnabled = true
        MmkvManager.encodeSettings(AppConfig.PREF_IS_BOOTED, true)
    }

    private fun setupSpinner() {
        val subscriptions = MmkvManager.decodeSubscriptions()
        groupNames.clear()
        groupIds.clear()
        for (sub in subscriptions) {
            groupNames.add(sub.subscription.remarks)
            groupIds.add(sub.guid)
        }

        val adapter = ArrayAdapter(this, R.layout.spinner_item, groupNames)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerGroup.adapter = adapter

        val savedId = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
        val savedIndex = groupIds.indexOf(savedId)
        if (savedIndex >= 0) {
            binding.spinnerGroup.setSelection(savedIndex)
        } else if (groupIds.isNotEmpty()) {
            binding.spinnerGroup.setSelection(0)
            mainViewModel.subscriptionIdChanged(groupIds[0])
        }

        binding.spinnerGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < groupIds.size) {
                    mainViewModel.subscriptionIdChanged(groupIds[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { result ->
            log("OBS updateTestResult: $result")
            if (result == null) return@observe
            if (result.matches(Regex("Tested \\d+/\\d+"))) {
                if (mainViewModel.isTesting.value != true && !isOperationInProgress) return@observe
                val parts = result.removePrefix("Tested ").split("/")
                val done = parts[0].toIntOrNull() ?: 0
                val total = parts[1].toIntOrNull() ?: 1
                val pct = (done * 100 / total).coerceIn(0, 100)
                binding.tvTestState.text = "$pct%"
                updateProgressFill(pct)
            } else {
                val msMatch = Regex("Connection took (\\d+)ms").find(result)
                val ms = msMatch?.groupValues?.get(1) ?: ""
                if (ms.isNotEmpty() && mainViewModel.isRunning.value == true) {
                    binding.tvTestState.text = "Connected ${ms}ms"
                }
            }
        }

        mainViewModel.isTesting.observe(this) { testing ->
            log("OBS isTesting=$testing isOpInProgress=$isOperationInProgress")
            if (testing) {
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setIconResource(R.drawable.ic_stop_24dp)
                binding.btnConnect.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).withEndAction {
                    binding.btnConnect.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
                setStatusDot(DotState.LOADING)
            } else {
                log("OBS isTesting=false, btn re-enabled")
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setIconResource(R.drawable.bolt_24)
            }
        }

        mainViewModel.liteTestFinished.observe(this) { finished ->
            log("OBS liteTestFinished=$finished isOpInProgress=$isOperationInProgress")
            if (finished && isOperationInProgress) {
                isOperationInProgress = false

                val firstReachable = mainViewModel.serversCache
                    .filter { (MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L) > 0L }
                    .minByOrNull { MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: Long.MAX_VALUE }

                val currentServer = MmkvManager.getSelectServer()
                log("OBS liteTestFinished: firstReachable=${firstReachable?.guid} current=$currentServer")

                mainViewModel.suppressPinSelected = false
                mainViewModel.sortByTestResults()
                mainViewModel.reloadServerList()

                if (firstReachable != null) {
                    MmkvManager.setSelectServer(firstReachable.guid)
                    log("OBS: connecting to best server ${firstReachable.guid}")
                    startV2RayWithPermission()
                } else {
                    log("OBS: no reachable servers found")
                }
            }
        }

        mainViewModel.isRunning.observe(this) { isRunning ->
            log("OBS isRunning=$isRunning wasRunning=$wasRunning")
            if (isRunning && !wasRunning) {
                wasRunning = true
                if (!subsUpdatedAfterConnect) {
                    subsUpdatedAfterConnect = true
                    updateSubsViaVpn()
                }
                FailoverManager.onStatusChange = { status ->
                    log(status)
                    runOnUiThread { showStatus(status) }
                }
                FailoverManager.start(this)
            } else if (!isRunning) {
                wasRunning = false
                FailoverManager.stop()
            }
            applyRunningState(isRunning)
        }
    }

    private fun checkForUpdatesOnStartup() {
        lifecycleScope.launch {
            try {
                val result: xyz.zarazaex.olc.dto.CheckUpdateResult = withContext(Dispatchers.IO) {
                    UpdateCheckerManager.checkForUpdate(false)
                }
                if (result.hasUpdate) {
                    log("UPDATE: new version ${result.latestVersion} available")
                    showUpdateNotification(result)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates", e)
            }
        }
    }

    private fun showUpdateNotification(result: xyz.zarazaex.olc.dto.CheckUpdateResult) {
        val channelId = "update_channel"
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Update", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }
        val intent = Intent(this, CheckUpdateActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_check_update_24dp)
            .setContentTitle("DeltaRay")
            .setContentText("New version ${result.latestVersion} is available")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, notification)
    }

    private fun importAllSubsOnStartup() {
        log("STARTUP: importing all subscriptions")
        setTestState(getString(R.string.connection_updating_profiles))
        lifecycleScope.launch(Dispatchers.IO) {
            var result = AngConfigManager.updateConfigViaSubAll()
            var retryCount = 0
            while (result.configCount == 0 && retryCount < 5) {
                retryCount++
                log("STARTUP: no configs found, retry $retryCount/5 in 3s...")
                delay(3000)
                result = AngConfigManager.updateConfigViaSubAll()
                log("STARTUP: retry result: configCount=${result.configCount}")
            }
            val removed = mainViewModel.removeDuplicateByIpAll()
            log("STARTUP: sub update done: configCount=${result.configCount} removed=$removed")
            launch(Dispatchers.Main) {
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    setupSpinner()
                }
                log("STARTUP: done. serverCount=${mainViewModel.serversCache.size}")
                dismissStartupDialog()
            }
        }
    }

    private fun handleConnectAction() {
        val isRunning = mainViewModel.isRunning.value == true
        val isTesting = mainViewModel.isTesting.value == true
        log("BTN CLICK: isRunning=$isRunning isTesting=$isTesting isOpInProgress=$isOperationInProgress")

        if (isRunning || isTesting) {
            log("BTN: stopping everything")
            FailoverManager.stop()
            connectJob?.cancel()
            connectJob = null
            mainViewModel.cancelAllTests()
            mainViewModel.suppressPinSelected = false

            lifecycleScope.launch {
                V2RayServiceManager.stopVService(this@MainActivity)
            }

            isOperationInProgress = false
            binding.tvTestState.text = getString(R.string.connection_not_connected)
            binding.btnConnect.isEnabled = true
            binding.btnConnect.setIconResource(R.drawable.bolt_24)
            applyRunningState(false)
            return
        }

        if (isOperationInProgress) return
        isOperationInProgress = true
        binding.btnConnect.isEnabled = false

        // Request VPN permission early if needed
        val isVpn = SettingsManager.isVpnMode()
        if (isVpn) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                log("BTN: requesting VPN permission before test")
                pendingVpnIntent = vpnIntent
                requestVpnPermission.launch(vpnIntent)
                return
            }
        }

        binding.btnConnect.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).withEndAction {
            binding.btnConnect.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()

        startConnectFlow()
    }

    private fun startConnectFlow() {
        binding.btnConnect.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).withEndAction {
            binding.btnConnect.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()

        connectJob = lifecycleScope.launch {
            try {
                log("FLOW: waiting for service to be ready...")
                delay(2000)

                mainViewModel.reloadServerList()
                var serverCount = mainViewModel.serversCache.size
                log("FLOW: serverList reloaded, count=$serverCount servers")

                if (serverCount == 0) {
                    log("FLOW: no servers found, downloading subscriptions...")
                    log("FLOW: downloading configs...")
                    val result = withContext(Dispatchers.IO) {
                        AngConfigManager.updateConfigViaSubAll()
                    }
                    log("FLOW: sub download done: configCount=${result.configCount}")
                    mainViewModel.reloadServerList()
                    setupSpinner()
                    serverCount = mainViewModel.serversCache.size
                    log("FLOW: after download, serverCount=$serverCount")
                }

                if (serverCount == 0) {
                    log("FLOW: NO SERVERS! aborting")
                    isOperationInProgress = false
                    binding.btnConnect.isEnabled = true
                    binding.btnConnect.setIconResource(R.drawable.bolt_24)
                    applyRunningState(false)
                    showStatus("No servers available")
                    return@launch
                }

                log("FLOW: starting test service process...")
                try {
                    val testSvcIntent = android.content.Intent(this@MainActivity, xyz.zarazaex.olc.service.V2RayTestService::class.java)
                    startService(testSvcIntent)
                    log("FLOW: test service startService OK")
                } catch (e: Exception) {
                    log("FLOW: test service startService FAILED: ${e.message}")
                }
                delay(3000)
                log("FLOW: test service should be ready now")

                log("FLOW: starting test...")
                binding.btnConnect.setIconResource(R.drawable.ic_stop_24dp)
                binding.btnConnect.isEnabled = true
                mainViewModel.suppressPinSelected = true
                mainViewModel.testAllRealPing()
                log("FLOW: testAllRealPing() sent")
            } catch (e: kotlinx.coroutines.CancellationException) {
                log("FLOW: CANCELLED by user")
            } catch (e: Exception) {
                log("FLOW: ERROR: ${e.message}")
                Log.e(AppConfig.TAG, "Error in startConnectFlow", e)
                isOperationInProgress = false
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setIconResource(R.drawable.bolt_24)
                applyRunningState(false)
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun switchToNextServer() {
        val isRunning = mainViewModel.isRunning.value == true
        if (!isRunning) {
            log("SWITCH: not running, skip")
            return
        }

        val currentServer = MmkvManager.getSelectServer() ?: return

        // Get top 10 servers sorted by initial test ping (best first), exclude current
        val allSubs = MmkvManager.decodeSubscriptions()
        val allServers = mutableListOf<Pair<String, Long>>()
        for (sub in allSubs) {
            val serverList = MmkvManager.decodeServerList(sub.guid)
            for (guid in serverList) {
                if (guid == currentServer) continue
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                if (delay > 0) allServers.add(Pair(guid, delay))
            }
        }
        val candidates = allServers.sortedBy { it.second }.take(10)
        if (candidates.isEmpty()) {
            log("SWITCH: no candidates, skip")
            return
        }

        log("SWITCH: ${candidates.size} candidates, trying each...")
        binding.btnConnect.isEnabled = false

        lifecycleScope.launch {
            for ((guid, _) in candidates) {
                log("SWITCH: trying ${guid.take(8)}...")

                // Stop current VPN
                FailoverManager.stop()
                V2RayServiceManager.stopVService(this@MainActivity)
                var waitCount = 0
                while (mainViewModel.isRunning.value == true && waitCount < 20) {
                    delay(500)
                    waitCount++
                }

                // Start VPN with candidate
                MmkvManager.setSelectServer(guid)
                V2RayServiceManager.startVService(this@MainActivity)
                var startWait = 0
                while (mainViewModel.isRunning.value == false && startWait < 10) {
                    delay(500)
                    startWait++
                }
                if (mainViewModel.isRunning.value != true) {
                    log("SWITCH: ${guid.take(8)} failed to start")
                    continue
                }

                // Measure ping through tunnel
                delay(1000)
                val delay = withContext(Dispatchers.IO) {
                    try {
                        val url = xyz.zarazaex.olc.handler.SettingsManager.getDelayTestUrl()
                        V2RayServiceManager.getCoreController().measureDelay(url)
                    } catch (e: Exception) { -1L }
                }

                if (delay > 0) {
                    log("SWITCH: ${guid.take(8)} OK ${delay}ms - connected!")
                    binding.btnConnect.isEnabled = true
                    return@launch
                } else {
                    log("SWITCH: ${guid.take(8)} failed ping")
                }
            }

            // None worked — reconnect original
            log("SWITCH: none responded, reconnecting original")
            FailoverManager.stop()
            V2RayServiceManager.stopVService(this@MainActivity)
            var waitCount = 0
            while (mainViewModel.isRunning.value == true && waitCount < 20) {
                delay(500)
                waitCount++
            }
            MmkvManager.setSelectServer(currentServer)
            V2RayServiceManager.startVService(this@MainActivity)
            var startWait = 0
            while (mainViewModel.isRunning.value == false && startWait < 10) {
                delay(500)
                startWait++
            }
            binding.btnConnect.isEnabled = true
        }
    }

    private fun startV2RayWithPermission() {
        val isVpn = SettingsManager.isVpnMode()
        log("START_VPN: isVpnMode=$isVpn")
        if (isVpn) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                log("START_VPN: no permission needed, starting directly")
                startV2Ray()
            } else {
                log("START_VPN: requesting VPN permission")
                requestVpnPermission.launch(intent)
            }
        } else {
            log("START_VPN: not VPN mode, starting directly")
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        val selected = MmkvManager.getSelectServer()
        log("START_V2: selectedServer=$selected")
        if (selected.isNullOrEmpty()) {
            log("START_V2: ABORT - no server selected!")
            showStatus(R.string.title_file_chooser)
            return
        }
        log("START_V2: calling startVService")
        V2RayServiceManager.startVService(this)

        lifecycleScope.launch {
            delay(3000)
            val running = mainViewModel.isRunning.value == true
            log("START_V2: after 3s, isRunning=$running")
            if (!running) {
                log("START_V2: service failed to start, retrying...")
                V2RayServiceManager.startVService(this@MainActivity)
                delay(3000)
                val running2 = mainViewModel.isRunning.value == true
                log("START_V2: after retry, isRunning=$running2")
                if (!running2) {
                    showStatus("Failed to start VPN service")
                    applyRunningState(false)
                    isOperationInProgress = false
                    binding.btnConnect.setIconResource(R.drawable.bolt_24)
                }
            }
        }
    }

    fun restartV2Ray() {
        if (isOperationInProgress) return
        isOperationInProgress = true
        lifecycleScope.launch {
            try {
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }
                startV2RayWithPermission()
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "restartV2Ray: error", e)
            } finally {
                isOperationInProgress = false
            }
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun showStatus(message: String) {
    }

    private fun showStatus(resId: Int) {
    }

    private fun accentColor(): ColorStateList {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, typedValue, true)
        val color = if (typedValue.resourceId != 0)
            ContextCompat.getColor(this, typedValue.resourceId)
        else
            typedValue.data
        return ColorStateList.valueOf(color)
    }

    private fun applyRunningState(isRunning: Boolean) {
        val ring1 = binding.pulseRing
        val ring2 = binding.pulseRing2
        if (isRunning) {
            val onPrimary = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, 0)
            )
            binding.btnConnect.backgroundTintList = accentColor()
            binding.btnConnect.iconTint = onPrimary
            binding.btnConnect.setIconResource(R.drawable.ic_stop_24dp)
            binding.tvSwitchServer.text = getString(R.string.switch_server_connected)
            startPulseAnimation(ring1, ring2)
            setStatusDot(DotState.CONNECTED)
            lifecycleScope.launch {
                delay(2000)
                handleLayoutTestClick()
            }
        } else {
            stopPulseAnimation(ring1, ring2)
            binding.tvSwitchServer.text = getString(R.string.switch_server_not_connected)
            binding.tvTestState.text = getString(R.string.connection_not_connected)
            updateProgressFill(0)
            val secContainer = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
            )
            val onSecContainer = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
            )
            binding.btnConnect.backgroundTintList = secContainer
            binding.btnConnect.iconTint = onSecContainer
            binding.btnConnect.setIconResource(R.drawable.bolt_24)
            setStatusDot(DotState.IDLE)
        }
    }

    private var pulseAnimator1: android.animation.ObjectAnimator? = null
    private var pulseAnimator2: android.animation.ObjectAnimator? = null

    private fun startPulseAnimation(ring1: View, ring2: View) {
        pulseAnimator1 = android.animation.ObjectAnimator.ofFloat(ring1, "alpha", 0.7f, 0f).apply {
            duration = 1500
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
        pulseAnimator2 = android.animation.ObjectAnimator.ofFloat(ring2, "alpha", 0f, 0.7f).apply {
            duration = 1500
            repeatCount = android.animation.ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation(ring1: View, ring2: View) {
        pulseAnimator1?.cancel()
        pulseAnimator2?.cancel()
        ring1.alpha = 0f
        ring2.alpha = 0f
    }

    private enum class DotState { IDLE, CONNECTED, LOADING, FAILURE }

    private fun setStatusDot(state: DotState) {
        val dot = binding.statusDot
        dot.animate().cancel()
        dot.alpha = 1f; dot.scaleX = 1f; dot.scaleY = 1f
        dot.backgroundTintList = ColorStateList.valueOf(when (state) {
            DotState.CONNECTED -> ContextCompat.getColor(this, R.color.status_connected)
            DotState.FAILURE -> ContextCompat.getColor(this, R.color.status_failure)
            DotState.LOADING -> com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0)
            DotState.IDLE -> com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0)
        })
        if (state == DotState.LOADING) {
            pulseDot(dot)
        }
    }

    private fun updateProgressFill(percent: Int) {
        val fill = binding.progressFill
        val parent = fill.parent as android.view.ViewGroup
        val parentWidth = parent.width
        val targetWidth = (parentWidth * percent / 100f).toInt()
        val lp = fill.layoutParams
        lp.width = targetWidth
        fill.layoutParams = lp
        if (percent > 0) {
            fill.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
        }
    }

    private fun pulseDot(dot: android.view.View) {
        dot.animate()
            .alpha(0.25f)
            .setDuration(600)
            .withEndAction {
                if (dot.isAttachedToWindow) {
                    dot.animate()
                        .alpha(1f)
                        .setDuration(600)
                        .withEndAction {
                            if (dot.isAttachedToWindow && mainViewModel.isTesting.value == true) {
                                pulseDot(dot)
                            }
                        }.start()
                }
            }.start()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to open url: $url", e)
        }
    }

    override fun onResume() {
        super.onResume()
        MessageUtil.sendMsg2Service(this, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.check_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun updateSubsViaVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            log("SUB_UPDATE: starting post-connect subscription update")
            val result = mainViewModel.updateConfigViaSubAll()
            if (result.configCount > 0) {
                val removed = mainViewModel.removeDuplicateByIpAll()
                log("SUB_UPDATE: done. configCount=${result.configCount} success=${result.successCount} fail=${result.failureCount} removed=$removed")
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    setupSpinner()
                }
            } else {
                log("SUB_UPDATE: no new configs")
            }
        }
    }

    private fun buildDialogTitleWithClose(title: String, onClose: () -> Unit): View {
        val view = layoutInflater.inflate(R.layout.dialog_title_with_close, null)
        view.findViewById<TextView>(R.id.dialog_title_text).text = title
        view.findViewById<android.widget.ImageButton>(R.id.dialog_close_btn).setOnClickListener { onClose() }
        return view
    }

    private fun activateEasterEgg() {
        if (isEasterEggActive) return
        isEasterEggActive = true
        lifecycleScope.launch {
            val colors = listOf(
                0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(),
                0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(), 0xFF9400D3.toInt()
            )
            var colorIndex = 0
            while (isEasterEggActive) {
                binding.toolbar.setBackgroundColor(colors[colorIndex])
                binding.btnConnect.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[(colorIndex + 2) % colors.size])
                colorIndex = (colorIndex + 1) % colors.size
                delay(200)
            }
        }
        replaceAllTextWith67(binding.root)
    }

    private fun replaceAllTextWith67(view: android.view.View) {
        when (view) {
            is android.widget.TextView -> {
                if (view.text.isNotEmpty()) {
                    view.text = "67"
                }
            }
            is android.view.ViewGroup -> {
                for (i in 0 until view.childCount) {
                    replaceAllTextWith67(view.getChildAt(i))
                }
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(logReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
