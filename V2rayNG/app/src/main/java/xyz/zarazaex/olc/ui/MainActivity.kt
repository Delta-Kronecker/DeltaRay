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
import com.google.android.material.navigation.NavigationView
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.databinding.ActivityMainBinding
import xyz.zarazaex.olc.enums.PermissionType
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.extension.toastError
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsChangeManager
import xyz.zarazaex.olc.handler.SettingsManager
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

    val mainViewModel: MainViewModel by viewModels()
    @Volatile private var isOperationInProgress = false

    private var groupNames = mutableListOf<String>()
    private var groupIds = mutableListOf<String>()
    private val logMessages = mutableListOf<String>()
    private val maxLogLines = 100

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

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        log("VPN_PERM: resultCode=${it.resultCode} OK=${RESULT_OK}")
        if (it.resultCode == RESULT_OK) {
            log("VPN_PERM: granted, calling startV2Ray")
            startV2Ray()
        } else {
            log("VPN_PERM: denied")
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
        findViewById<android.view.View>(R.id.drawer_telegram)?.setOnClickListener {
            openUrl("https://t.me/DeltaKroneckerGithub")
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<android.view.View>(R.id.drawer_source)?.setOnClickListener {
            openUrl("https://github.com/Delta-Kronecker/DeltaRay")
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

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)

        val logFilter = android.content.IntentFilter("xyz.zarazaex.olc.action.LOG")
        registerReceiver(logReceiver, logFilter, android.content.Context.RECEIVER_NOT_EXPORTED)

        binding.btnCopyLog.setOnClickListener {
            val logText = logMessages.joinToString("\n")
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("app_log", logText))
            toast("Log copied to clipboard")
        }

        setupSpinner()
        setupViewModel()
        mainViewModel.reloadServerList()
        importAllSubsOnStartup()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
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
            setTestState(result)
            if (result != null && mainViewModel.isRunning.value == true) {
                val isSuccess = result.contains(Regex("\\d+\\s*(ms|ms|毫秒)"))
                setStatusDot(if (isSuccess) DotState.CONNECTED else DotState.FAILURE)
            }
        }

        mainViewModel.isTesting.observe(this) { testing ->
            log("OBS isTesting=$testing")
            if (testing) {
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setIconResource(R.drawable.ic_stop_24dp)
                binding.btnConnect.animate().scaleX(0.9f).scaleY(0.9f).setDuration(150).withEndAction {
                    binding.btnConnect.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
                setStatusDot(DotState.LOADING)
            } else {
                binding.btnConnect.isEnabled = true
                binding.btnConnect.setIconResource(R.drawable.bolt_24)
                if (!isOperationInProgress) {
                    showStatus("Test completed")
                }
            }
        }

        mainViewModel.liteTestFinished.observe(this) { finished ->
            log("OBS liteTestFinished=$finished isOpInProgress=$isOperationInProgress")
            if (finished && isOperationInProgress) {
                isOperationInProgress = false

                val firstReachable = mainViewModel.serversCache
                    .filter { (MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L) > 0L }
                    .minByOrNull { MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: Long.MAX_VALUE }

                log("OBS liteTestFinished: firstReachable=${firstReachable?.guid}")
                if (firstReachable != null) {
                    MmkvManager.setSelectServer(firstReachable.guid)
                    log("OBS: selected server ${firstReachable.guid}")
                }

                mainViewModel.suppressPinSelected = false
                mainViewModel.sortByTestResults()
                mainViewModel.reloadServerList()

                if (firstReachable != null) {
                    showStatus("Connecting to fastest server")
                    log("OBS: calling startV2RayWithPermission")
                    startV2RayWithPermission()
                } else {
                    showStatus("No servers available!")
                    log("OBS: no reachable servers found")
                }
            }
        }

        mainViewModel.isRunning.observe(this) { isRunning ->
            log("OBS isRunning=$isRunning wasRunning=$wasRunning")
            if (isRunning && !wasRunning) {
                wasRunning = true
                updateSubsViaVpn()
            } else if (!isRunning) {
                wasRunning = false
            }
            applyRunningState(isRunning)
        }
    }

    private fun handleConnectAction() {
        val isRunning = mainViewModel.isRunning.value == true
        val isTesting = mainViewModel.isTesting.value == true
        log("BTN CLICK: isRunning=$isRunning isTesting=$isTesting isOpInProgress=$isOperationInProgress")

        if (isRunning || isTesting) {
            log("BTN: stopping (isRunning=$isRunning isTesting=$isTesting)")
            if (isTesting) {
                mainViewModel.cancelAllTests()
                mainViewModel.suppressPinSelected = false
                log("BTN: cancelled tests")
            }
            if (isRunning) {
                lifecycleScope.launch {
                    log("BTN: calling stopVService")
                    V2RayServiceManager.stopVService(this@MainActivity)
                    log("BTN: stopVService done")
                }
            }
            isOperationInProgress = false
            showStatus("Stopped")
            binding.btnConnect.setIconResource(R.drawable.bolt_24)
            applyRunningState(false)
            return
        }

        if (isOperationInProgress) {
            log("BTN: already in progress, skip")
            return
        }
        isOperationInProgress = true
        log("BTN: starting test flow")

        binding.btnConnect.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).withEndAction {
            binding.btnConnect.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
        }.start()

        connectJob = lifecycleScope.launch {
            try {
                log("FLOW: reloading server list")
                showStatus("Testing all servers...")
                showLoading()
                binding.btnConnect.setIconResource(R.drawable.ic_stop_24dp)
                mainViewModel.suppressPinSelected = true

                mainViewModel.reloadServerList()
                val serverCount = mainViewModel.serversCache.size
                log("FLOW: serverList reloaded, count=$serverCount servers")

                if (serverCount == 0) {
                    log("FLOW: NO SERVERS! aborting test")
                    isOperationInProgress = false
                    hideLoading()
                    binding.btnConnect.setIconResource(R.drawable.bolt_24)
                    applyRunningState(false)
                    showStatus("No servers available")
                    return@launch
                }

                log("FLOW: calling testAllRealPing()")
                mainViewModel.testAllRealPing()
                log("FLOW: testAllRealPing() returned")
            } catch (e: kotlinx.coroutines.CancellationException) {
                log("FLOW: CANCELLED by user")
            } catch (e: Exception) {
                log("FLOW: ERROR: ${e.message}")
                Log.e(AppConfig.TAG, "Error in handleConnectAction", e)
                isOperationInProgress = false
                hideLoading()
                binding.btnConnect.setIconResource(R.drawable.bolt_24)
                applyRunningState(false)
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
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

    private var statusResetJob: kotlinx.coroutines.Job? = null

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun showStatus(message: String) {
        statusResetJob?.cancel()
        binding.tvTestState.text = message
        if (isOperationInProgress || mainViewModel.isTesting.value == true) return
        statusResetJob = lifecycleScope.launch {
            delay(3000)
            val isRunning = mainViewModel.isRunning.value == true
            binding.tvTestState.text = getString(
                if (isRunning) R.string.connection_connected
                else R.string.connection_not_connected
            )
        }
    }

    private fun showStatus(resId: Int) = showStatus(getString(resId))

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
            startPulseAnimation(ring1, ring2)
            setTestState(getString(R.string.connection_connected))
            setStatusDot(DotState.CONNECTED)
        } else {
            stopPulseAnimation(ring1, ring2)
            val secContainer = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
            )
            val onSecContainer = ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
            )
            binding.btnConnect.backgroundTintList = secContainer
            binding.btnConnect.iconTint = onSecContainer
            binding.btnConnect.setIconResource(R.drawable.bolt_24)
            if (mainViewModel.isTesting.value != true && statusResetJob?.isActive != true) {
                setTestState(getString(R.string.connection_not_connected))
            }
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
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun updateSubsViaVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            delay(2000)
            Log.d(AppConfig.TAG, "updateSubsViaVpn: starting post-connect subscription update")
            val result = mainViewModel.updateConfigViaSubAll()
            if (result.configCount > 0) {
                val removed = mainViewModel.removeDuplicateByIpAll()
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    val msg = if (removed > 0)
                        "Subscriptions updated: ${result.configCount} profiles, removed $removed duplicate IPs"
                    else
                        "Subscriptions updated: ${result.configCount} profiles"
                    showStatus(msg)
                }
            }
        }
    }

    private fun importAllSubsOnStartup() {
        log("STARTUP: importing all subscriptions")
        showLoading()
        setTestState(getString(R.string.connection_updating_profiles))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSubAll()
            val removed = mainViewModel.removeDuplicateByIpAll()
            log("STARTUP: sub update done: configCount=${result.configCount} success=${result.successCount} fail=${result.failureCount} removed=$removed")
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    setupSpinner()
                    val status = if (removed > 0)
                        "${getString(R.string.title_update_config_count, result.configCount)} (removed $removed duplicate IPs)"
                    else
                        getString(R.string.title_update_config_count, result.configCount)
                    showStatus(status)
                }
                val serverCount = mainViewModel.serversCache.size
                log("STARTUP: done. serverCount=$serverCount")
                hideLoading()
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
