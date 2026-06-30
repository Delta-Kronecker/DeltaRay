package xyz.zarazaex.olc.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.color.MaterialColors
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.databinding.ActivityMainBinding
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.enums.PermissionType
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.extension.toastError
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.CountryDetector
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
    private val binding by lazy {ActivityMainBinding.inflate(layoutInflater)}
    private var isLiteTesting = false
    private var easterEggClickCount = 0
    private var isEasterEggActive = false
    private var liteActionJob: kotlinx.coroutines.Job? = null
    /** Был ли VPN уже запущен в предыдущем колбэке — чтобы детектировать момент подключения */
    private var wasRunning = false

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    @Volatile private var isFabOperationInProgress = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))

        // edge-to-edge: контент идёт под статус-бар, AppBarLayout тянется под него же
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            v.setPadding(0, insets.getInsets(WindowInsetsCompat.Type.statusBars()).top, 0, 0)
            insets
        }
        // Нижние кнопки поднимаются над навигационной панелью
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomContainer) { v, insets ->
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, navBarHeight)
            insets
        }

        placeTabGroup()

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

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
        // MaterialToolbar с titleCentered рисует отдельный TextView — красим его явно
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
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<android.view.View>(R.id.drawer_per_app)?.setOnClickListener {
            requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
        }
        findViewById<android.view.View>(R.id.drawer_check_update)?.setOnClickListener {
            startActivity(Intent(this, CheckUpdateActivity::class.java))
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
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

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        binding.btnSummaryLite.setOnClickListener { handleLiteAction() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
        importAllSubsOnStartup()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }

        checkForUpdatesOnStartup()
        showDonateDialogIfNeeded()
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { result ->
            setTestState(result)
            if (result != null && mainViewModel.isRunning.value == true) {
                val isSuccess = result.contains(Regex("\\d+\\s*(ms|мс|毫秒)"))
                setStatusDot(if (isSuccess) DotState.CONNECTED else DotState.FAILURE)
            }
        }

        mainViewModel.isTesting.observe(this) { testing ->
            if (testing) {
                // Во время теста: блокируем всё кроме кнопки молнии (стоп)
                binding.fab.isEnabled = false
                binding.fab.alpha = 0.5f
                val menu = binding.toolbar.menu
                menu.findItem(R.id.real_ping_all)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                menu.findItem(R.id.filter_by_country)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                menu.findItem(R.id.sub_update)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                // Молния — стоп-кнопка, всегда активна во время теста
                binding.btnSummaryLite.isEnabled = true
                binding.btnSummaryLite.alpha = 1.0f
                binding.btnSummaryLite.setIconResource(R.drawable.ic_stop_24dp)
                animateButtonTint(binding.btnSummaryLite,
                    com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
                )
            } else {
                setButtonsEnabled(true)
                binding.btnSummaryLite.setIconResource(R.drawable.bolt_24)
                animateButtonTint(binding.btnSummaryLite,
                    com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
                )
                if (!isLiteTesting) {
                    showStatus("Проверка завершена")
                }
            }
        }

        mainViewModel.liteTestFinished.observe(this) { finished ->
            if (finished && isLiteTesting) {
                isLiteTesting = false

                // Ищем лучший сервер ДО сортировки, прямо из текущего cache
                val firstReachable = mainViewModel.serversCache
                    .filter { (MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L) > 0L }
                    .minByOrNull { MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: Long.MAX_VALUE }

                if (firstReachable != null) {
                    MmkvManager.setSelectServer(firstReachable.guid)
                }

                mainViewModel.suppressPinSelected = false
                mainViewModel.sortByTestResults()
                mainViewModel.reloadServerList()

                if (firstReachable != null) {
                    showStatus("Подключаемся к быстрейшему серверу")
                    applyRunningState(isLoading = true, isRunning = false)
                    startV2RayWithPermission()
                } else {
                    showStatus("Нет доступных серверов!")
                    setButtonsEnabled(true)
                }
            }
        }
        mainViewModel.isRunning.observe(this) { isRunning ->
            if (!isFabOperationInProgress) {
                applyRunningState(false, isRunning)
            }
            // Как только VPN только что подключился — обновляем подписки через него
            if (isRunning && !wasRunning) {
                updateSubsViaVpn()
            }
            wasRunning = isRunning
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        binding.tabGroup.isVisible = groups.size > 1

        // Double-tap on a tab scrolls to top of that group
        binding.tabGroup.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                val currentItem = binding.viewPager.currentItem
                val itemId = groupPagerAdapter.getItemId(currentItem)
                val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment
                fragment?.scrollToTop()
            }
        })
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.fab.isEnabled = enabled
        binding.fab.alpha = if (enabled) 1.0f else 0.5f
        setSecondaryButtonsEnabled(enabled)
    }

    private fun setSecondaryButtonsEnabled(enabled: Boolean) {
        binding.btnSummaryLite.isEnabled = enabled
        binding.btnSummaryLite.alpha = if (enabled) 1.0f else 0.5f
        val menu = binding.toolbar.menu
        menu.findItem(R.id.real_ping_all)?.let {
            it.isEnabled = enabled
            it.icon?.alpha = if (enabled) 255 else 128
        }
        menu.findItem(R.id.filter_by_country)?.let {
            it.isEnabled = enabled
            it.icon?.alpha = if (enabled) 255 else 128
        }
        menu.findItem(R.id.sub_update)?.let {
            it.isEnabled = enabled
            it.icon?.alpha = if (enabled) 255 else 128
        }
    }

    private fun handleFabAction() {
        // Если идёт подключение (isLoading) — позволяем прервать и остановить сервис
        if (isFabOperationInProgress) {
            Log.d(AppConfig.TAG, "FAB: cancel in-progress, stopping service")
            isFabOperationInProgress = false
            lifecycleScope.launch {
                V2RayServiceManager.stopVService(this@MainActivity)
            }
            return
        }
        isFabOperationInProgress = true

        val isRunning = mainViewModel.isRunning.value == true

        applyRunningState(isLoading = true, isRunning = false)

        lifecycleScope.launch {
            try {
                if (isRunning) {
                    Log.d(AppConfig.TAG, "FAB: stopping service")
                    V2RayServiceManager.stopVService(this@MainActivity)
                } else {
                    Log.d(AppConfig.TAG, "FAB: starting service")
                    startV2RayWithPermission()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "FAB: error", e)
                applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
            } finally {
                isFabOperationInProgress = false
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun handleLiteAction() {
        // Отмена на любом этапе: обновление подписок или тест
        if (mainViewModel.isTesting.value == true || liteActionJob?.isActive == true) {
            liteActionJob?.cancel()
            liteActionJob = null
            mainViewModel.cancelAllTests()
            mainViewModel.suppressPinSelected = false
            isLiteTesting = false
            isFabOperationInProgress = false
            showStatus("Остановлено")
            setButtonsEnabled(true)
            binding.btnSummaryLite.setIconResource(R.drawable.bolt_24)
            animateButtonTint(binding.btnSummaryLite,
                com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
            )
            hideLoading()
            return
        }

        if (isFabOperationInProgress) {
            return
        }
        isFabOperationInProgress = true

        liteActionJob = lifecycleScope.launch {
            try {
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }

                showStatus("Обновление профилей...")
                showLoading()
                // Иконка молнии → стоп пока идёт обновление; FAB и меню блокируем
                binding.btnSummaryLite.setIconResource(R.drawable.ic_stop_24dp)
                binding.btnSummaryLite.isEnabled = true
                binding.btnSummaryLite.alpha = 1.0f
                binding.fab.isEnabled = false
                binding.fab.alpha = 0.5f
                val menu = binding.toolbar.menu
                menu.findItem(R.id.real_ping_all)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                menu.findItem(R.id.filter_by_country)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                menu.findItem(R.id.sub_update)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
                isLiteTesting = true
                mainViewModel.suppressPinSelected = true

                val result = withContext(Dispatchers.IO) { mainViewModel.updateConfigViaSubAll() }
                val removed = withContext(Dispatchers.IO) { mainViewModel.removeDuplicateByIpAll() }

                mainViewModel.reloadServerList()
                if (result.configCount > 0) {
                    val status = if (removed > 0)
                        "Обновлено ${result.configCount} профилей, удалено $removed дубл. IP. Запуск теста..."
                    else
                        "Обновлено ${result.configCount} профилей. Запуск теста..."
                    showStatus(status)
                } else {
                    showStatus("Запуск теста...")
                }
                hideLoading()

                showStatus("Выполняется замер задержки. Ожидаем завершения...")
                mainViewModel.testAllRealPing()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Пользователь нажал стоп — уже обработано выше
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error in handleLiteAction", e)
                isLiteTesting = false
                hideLoading()
            } finally {
                isFabOperationInProgress = false
                liteActionJob = null
            }
        }
    }

    private fun startV2RayWithPermission() {
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            showStatus(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (isFabOperationInProgress) {
            return
        }
        isFabOperationInProgress = true
        applyRunningState(isLoading = true, isRunning = false)

        lifecycleScope.launch {
            try {
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(1000)
                }
                if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                    // Сервер был снят с выбора — просто остановились, разблокируем UI
                    applyRunningState(isLoading = false, isRunning = false)
                    return@launch
                }
                startV2Ray()
                delay(1000)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error in restartV2Ray", e)
            } finally {
                isFabOperationInProgress = false
                applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
            }
        }
    }

    private var statusResetJob: kotlinx.coroutines.Job? = null

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    /** Show a temporary message in the status bar, then revert to connection state */
    private fun showStatus(message: String) {
        statusResetJob?.cancel()
        binding.tvTestState.text = message
        if (isFabOperationInProgress || mainViewModel.isTesting.value == true) return
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

    private fun animateButtonTint(view: com.google.android.material.button.MaterialButton, toColor: Int, duration: Long = 300L) {
        val from = view.backgroundTintList?.defaultColor ?: toColor
        if (from == toColor) { view.backgroundTintList = ColorStateList.valueOf(toColor); return }
        ValueAnimator.ofArgb(from, toColor).apply {
            this.duration = duration
            addUpdateListener { view.backgroundTintList = ColorStateList.valueOf(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        val secContainer = ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondaryContainer, 0)
        )
        if (isLoading) {
            // Во время подключения: только FAB доступен для отмены, всё остальное заблокировано
            binding.fab.isEnabled = true
            binding.fab.alpha = 1.0f
            binding.fab.backgroundTintList = secContainer
            binding.btnSummaryLite.isEnabled = false
            binding.btnSummaryLite.alpha = 0.5f
            val menu = binding.toolbar.menu
            menu.findItem(R.id.real_ping_all)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
            menu.findItem(R.id.filter_by_country)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
            menu.findItem(R.id.sub_update)?.let { it.isEnabled = false; it.icon?.alpha = 128 }
            setStatusDot(DotState.LOADING)
            return
        }

        val onPrimary = ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary, 0)
        )
        val onSecContainer = ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer, 0)
        )

        if (isRunning) {
            setSecondaryButtonsEnabled(false)
            binding.fab.isEnabled = true
            binding.fab.alpha = 1.0f
            binding.fab.backgroundTintList = accentColor()
            binding.fab.iconTint = onPrimary
            animateButtonTint(binding.btnSummaryLite, secContainer.defaultColor)
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
            setStatusDot(DotState.CONNECTED)
        } else {
            setButtonsEnabled(true)
            binding.fab.backgroundTintList = secContainer
            binding.fab.iconTint = onSecContainer
            animateButtonTint(binding.btnSummaryLite, secContainer.defaultColor)
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            if (mainViewModel.isTesting.value != true && statusResetJob?.isActive != true) {
                setTestState(getString(R.string.connection_not_connected))
            }
            binding.layoutTest.isFocusable = false
            setStatusDot(DotState.IDLE)
        }
    }

    private enum class DotState { IDLE, CONNECTED, LOADING, FAILURE }

    private fun setStatusDot(state: DotState) {
        val dot = binding.statusDot
        dot.animate().cancel()
        dot.alpha = 1f; dot.scaleX = 1f; dot.scaleY = 1f
        dot.backgroundTintList = ColorStateList.valueOf(when (state) {
            DotState.CONNECTED -> ContextCompat.getColor(this, R.color.status_connected)
            DotState.FAILURE   -> ContextCompat.getColor(this, R.color.status_failure)
            DotState.LOADING   -> com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0)
            DotState.IDLE      -> com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, 0)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val iconColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        for (i in 0 until menu.size()) {
            menu.getItem(i).icon?.let {
                DrawableCompat.setTint(DrawableCompat.wrap(it).mutate(), iconColor)
            }
        }

        val searchItem = menu.findItem(R.id.search_view)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                mainViewModel.filterConfig(newText.orEmpty())
                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.alpha = 0f
                searchView.animate().alpha(1f).setDuration(220).start()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                mainViewModel.filterConfig("")
                return true
            }
        })

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {


        R.id.real_ping_all -> {
            showStatus(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.sub_update -> {
            setSecondaryButtonsEnabled(false)
            importConfigViaSub()
            true
        }

        R.id.filter_by_country -> {
            showCountryFilterDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            showStatus(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> showStatus(getString(R.string.toast_failure))
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus(getString(R.string.toast_failure))
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    /**
     * Обновляет подписки через уже поднятый VPN (httpPort > 0).
     * Вызывается сразу после того, как VPN перешёл в состояние isRunning = true.
     */
    private fun updateSubsViaVpn() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Даём VPN пару секунд инициализироваться
            delay(2000)
            Log.d(AppConfig.TAG, "updateSubsViaVpn: starting post-connect subscription update")
            val result = mainViewModel.updateConfigViaSubAll()
            if (result.configCount > 0) {
                val removed = mainViewModel.removeDuplicateByIpAll()
                withContext(Dispatchers.Main) {
                    mainViewModel.reloadServerList()
                    val msg = if (removed > 0)
                        "Подписки обновлены: ${result.configCount} профилей, удалено $removed дубл. IP"
                    else
                        "Подписки обновлены: ${result.configCount} профилей"
                    showStatus(msg)
                }
            }
        }
    }

    private fun importAllSubsOnStartup() {
        showLoading()
        setTestState(getString(R.string.connection_updating_profiles))
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.updateConfigViaSubAll()
            val removed = mainViewModel.removeDuplicateByIpAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    val status = if (removed > 0)
                        "${getString(R.string.title_update_config_count, result.configCount)} (удалено $removed дубл. IP)"
                    else
                        getString(R.string.title_update_config_count, result.configCount)
                    showStatus(status)
                }
                hideLoading()
            }
        }
    }
    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()
        setTestState(getString(R.string.connection_updating_profiles))

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    showStatus(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    showStatus(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    showStatus(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
                hideLoading()
                applyRunningState(isLoading = false, isRunning = mainViewModel.isRunning.value == true)
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    showStatus(getString(R.string.title_export_config_count, ret))
                else
                    showStatus(getString(R.string.toast_failure))
                hideLoading()
            }
        }
    }

    private fun showDonateDialogIfNeeded() {
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DONATE_DIALOG_DISMISSED)) return
        val postponeUntil = MmkvManager.decodeSettingsLong(AppConfig.PREF_DONATE_DIALOG_POSTPONE_UNTIL, 0L)
        if (System.currentTimeMillis() < postponeUntil) return

        // Откладываем на следующий тик, чтобы Activity полностью отрисовался
        binding.root.post { showDonateDialog() }
    }

    private fun showDonateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_donate, null)

        val tonValue = view.findViewById<TextView>(R.id.donate_ton_value)
        val trcValue = view.findViewById<TextView>(R.id.donate_trc_value)
        val btcValue = view.findViewById<TextView>(R.id.donate_btc_value)

        val openCard = View.OnClickListener { openUrl(getString(R.string.donate_card_link_url)) }
        view.findViewById<View>(R.id.donate_card_row).setOnClickListener(openCard)
        view.findViewById<View>(R.id.donate_card_open).setOnClickListener(openCard)

        view.findViewById<View>(R.id.donate_ton_copy).setOnClickListener {
            copyToClipboard(tonValue.text.toString())
        }
        view.findViewById<View>(R.id.donate_trc_copy).setOnClickListener {
            copyToClipboard(trcValue.text.toString())
        }
        view.findViewById<View>(R.id.donate_btc_copy).setOnClickListener {
            copyToClipboard(btcValue.text.toString())
        }

        val titleStr = getString(R.string.donate_dialog_title)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton(R.string.donate_btn_dont_show) { d, _ ->
                MmkvManager.encodeSettings(AppConfig.PREF_DONATE_DIALOG_DISMISSED, true)
                d.dismiss()
            }
            .setCancelable(true)
            .create()
        // Closing (X / outside / back) postpones for 24h
        val postpone = {
            val postponeUntil = System.currentTimeMillis() + 24L * 60 * 60 * 1000
            MmkvManager.encodeSettings(AppConfig.PREF_DONATE_DIALOG_POSTPONE_UNTIL, postponeUntil)
        }
        dialog.setOnCancelListener { postpone() }
        dialog.setCustomTitle(buildDialogTitleWithClose(titleStr) {
            postpone()
            dialog.dismiss()
        })
        dialog.show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("addr", text))
        toast(R.string.donate_toast_copied)
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

    private fun delAllConfig() {
        MaterialAlertDialogBuilder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        showStatus(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        MaterialAlertDialogBuilder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        showStatus(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            showStatus(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            showStatus(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.check_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }

    // ── Country filter dialog ─────────────────────────────────────────────────

    private fun showCountryFilterDialog() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.refreshCountryCache()
            // Collect all countries including UNKNOWN
            val allCountriesMap = mainViewModel.collectAllCountries().toMutableMap()
            // Add Unknown entry
            allCountriesMap[CountryDetector.UNKNOWN] = "🌐 Неизвестно"

            val currentFilter = mainViewModel.countryFilter  // empty = show all

            withContext(Dispatchers.Main) {
                hideLoading()
                if (allCountriesMap.size <= 1) {
                    showStatus("Нет серверов с известной страной")
                    return@withContext
                }

                val codes = allCountriesMap.keys.toTypedArray()

                // currentFilter stores excluded set (empty = show all)
                val checked = BooleanArray(codes.size) { codes[it] in currentFilter }

                val adapter = object : android.widget.BaseAdapter() {
                    override fun getCount() = codes.size
                    override fun getItem(pos: Int) = codes[pos]
                    override fun getItemId(pos: Int) = pos.toLong()
                    override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val view = convertView ?: layoutInflater.inflate(R.layout.item_dialog_country, parent, false)
                        val code = codes[pos]
                        val isUnknown = code == CountryDetector.UNKNOWN
                        view.findViewById<android.widget.TextView>(R.id.flag).text =
                            if (isUnknown) "🌐" else CountryDetector.codeToFlag(code)
                        view.findViewById<android.widget.TextView>(R.id.text).text =
                            if (isUnknown) "Неизвестно" else CountryDetector.codeToName(code)
                        view.findViewById<android.widget.TextView>(R.id.code).text =
                            if (isUnknown) "—" else code
                        val cb = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.check_box)
                        cb.isChecked = checked[pos]
                        view.setOnClickListener {
                            checked[pos] = !checked[pos]
                            cb.isChecked = checked[pos]
                        }
                        return view
                    }
                }

                val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setAdapter(adapter, null)
                    .setPositiveButton("Применить") { _, _ ->
                        val excluded = codes.filterIndexed { i, _ -> checked[i] }.toSet()
                        mainViewModel.applyCountryFilter(excluded)
                        val msg = if (excluded.isEmpty()) "Показаны все страны"
                            else "Скрыто: ${excluded.joinToString { CountryDetector.codeToFlag(it) }}"
                        showStatus(msg)
                    }
                    .setNeutralButton("Сбросить") { _, _ ->
                        mainViewModel.applyCountryFilter(emptySet())
                        showStatus("Показаны все страны")
                    }
                    .create()
                dialog.setCustomTitle(buildDialogTitleWithClose("Исключить страны") { dialog.dismiss() })
                dialog.show()
            }
        }
    }

    private fun checkForUpdatesOnStartup() {
        showStatus("Проверка обновлений...")
        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(true)
                if (result.hasUpdate) {
                    showStatus("Доступно обновление ${result.latestVersion}")
                    showUpdateAvailableDialog(result)
                } else {
                    showStatus("Обновлений нет")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates on startup: ${e.message}")
            }
        }
    }
    
    private fun showUpdateAvailableDialog(result: xyz.zarazaex.olc.dto.CheckUpdateResult) {
        val message = result.releaseNotes?.let { xyz.zarazaex.olc.util.MarkdownUtil.parseBasic(it) } ?: ""
        val titleStr = getString(R.string.update_new_version_found, result.latestVersion)
        val dialog = MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let {
                    Utils.openUri(this, it)
                }
            }
            .create()
        dialog.setCustomTitle(buildDialogTitleWithClose(titleStr) { dialog.dismiss() })
        dialog.show()
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
                0xFFFF0000.toInt(),
                0xFFFF7F00.toInt(),
                0xFFFFFF00.toInt(),
                0xFF00FF00.toInt(),
                0xFF0000FF.toInt(),
                0xFF4B0082.toInt(),
                0xFF9400D3.toInt()
            )
            
            var colorIndex = 0
            while (isEasterEggActive) {
                binding.toolbar.setBackgroundColor(colors[colorIndex])
                binding.tabGroup.setBackgroundColor(colors[(colorIndex + 1) % colors.size])
                binding.fab.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[(colorIndex + 2) % colors.size])
                binding.btnSummaryLite.backgroundTintList = android.content.res.ColorStateList.valueOf(colors[(colorIndex + 3) % colors.size])
                
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

    private fun placeTabGroup() {
        val tabGroup = binding.tabGroup
        val bottomSlot = binding.tabSlotBottom
        val topSlot = binding.tabSlotTop
        val subsBottom = MmkvManager.decodeSettingsBool(AppConfig.PREF_SUBSCRIPTIONS_BOTTOM, false)
        (tabGroup.parent as? android.view.ViewGroup)?.removeView(tabGroup)
        if (subsBottom) {
            bottomSlot.addView(tabGroup, 0)
        } else {
            topSlot.addView(tabGroup)
        }
    }
}
