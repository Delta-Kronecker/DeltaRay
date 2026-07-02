package xyz.zarazaex.olc.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import xyz.zarazaex.olc.AngApplication
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.dto.GroupMapItem
import xyz.zarazaex.olc.dto.PingProgressUpdate
import xyz.zarazaex.olc.dto.ServersCache
import xyz.zarazaex.olc.dto.SubscriptionCache
import xyz.zarazaex.olc.dto.SubscriptionUpdateResult
import xyz.zarazaex.olc.dto.TestServiceMessage
import xyz.zarazaex.olc.extension.matchesPattern
import xyz.zarazaex.olc.extension.serializable
import xyz.zarazaex.olc.handler.AngConfigManager
import xyz.zarazaex.olc.handler.CountryDetector
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.SpeedtestManager
import xyz.zarazaex.olc.util.MessageUtil
import xyz.zarazaex.olc.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    /** ISO codes to EXCLUDE (empty = show all) */
    var countryFilter: Set<String> = MmkvManager.getCountryFilter()
        private set

    // Internal mutable cache — never exposed directly
    private val _serversCache = mutableListOf<ServersCache>()
    // Read-only snapshot for external consumers that need direct access (e.g. export, ping)
    val serversCache: List<ServersCache> get() = _serversCache.toList()

    // Single source of truth for the list UI — emits a new immutable snapshot on every change
    private val _serverListFlow = MutableStateFlow<List<ServersCache>>(emptyList())
    val serverListFlow: StateFlow<List<ServersCache>> = _serverListFlow.asStateFlow()

    val isRunning by lazy { MutableLiveData<Boolean>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    val liteTestFinished = MutableLiveData<Boolean>()
    val isTesting by lazy { MutableLiveData<Boolean>().also { it.value = false } }
    var suppressPinSelected = false
    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    fun startListenBroadcast() {
        isRunning.value = false
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        serverList = if (subscriptionId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            val list = MmkvManager.decodeServerList(subscriptionId)
            list.sortWith(compareBy { !(MmkvManager.decodeServerConfig(it)?.isFavorite ?: false) })
            list
        }

        if (!suppressPinSelected) pinSelectedGuidToTop(serverList)
        updateCache()
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = _serversCache.indexOfFirst { it.guid == guid }
        if (index >= 0) {
            _serversCache.removeAt(index)
        }
        publishSnapshot()
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId.isEmpty()) {
            return
        }

        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(_serversCache, fromPosition, toPosition)
        publishSnapshot()

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Rebuilds _serversCache from serverList and publishes a new snapshot to serverListFlow.
     */
    @Synchronized
    fun updateCache() {
        _serversCache.clear()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null
        }
        val activeCountryFilter = countryFilter
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue

            if (activeCountryFilter.isNotEmpty()) {
                val codes = CountryDetector.getCountryCodes(profile.remarks, profile.server)
                if (codes.all { it in activeCountryFilter }) continue
            }

            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L

            if (kw.isEmpty()) {
                _serversCache.add(ServersCache(guid, profile, delay, guid == selectedGuid))
                continue
            }

            val remarks = profile.remarks
            val description = profile.description.orEmpty()
            val server = profile.server.orEmpty()
            val protocol = profile.configType.name
            if (remarks.matchesPattern(searchRegex, kw)
                || description.matchesPattern(searchRegex, kw)
                || server.matchesPattern(searchRegex, kw)
                || protocol.matchesPattern(searchRegex, kw)
            ) {
                _serversCache.add(ServersCache(guid, profile, delay, guid == selectedGuid))
            }
        }
        publishSnapshot()
    }

    /** Emits an immutable copy of _serversCache to the Flow. Must be called on Main or from @Synchronized blocks. */
    private fun publishSnapshot() {
        _serverListFlow.value = _serversCache.toList()
    }

    /** Builds a snapshot of ServersCache for the given subId without changing global state. */
    fun reloadForSub(subId: String): MutableList<ServersCache>? {
        val guids = if (subId.isEmpty()) {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subId)
        }
        return guids.mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            ServersCache(guid, profile, delay)
        }.toMutableList()
    }

    /** Sets excluded countries and reloads list. Pass empty set to show all. */
    fun applyCountryFilter(excludedCodes: Set<String>) {
        countryFilter = excludedCodes
        MmkvManager.setCountryFilter(excludedCodes)
        reloadServerList()
    }

    /**
     * Returns all known countries from ALL servers across all subscriptions (for showing in filter dialog).
     * Key = ISO code, Value = human-readable name + flag.
     */
    fun collectAllCountries(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var hasUnknown = false
        for (guid in MmkvManager.decodeAllServerList()) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            val codes = CountryDetector.getCountryCodes(profile.remarks, profile.server)
            for (code in codes) {
                if (code == CountryDetector.UNKNOWN) {
                    hasUnknown = true
                } else {
                    result[code] = "${CountryDetector.codeToFlag(code)} ${CountryDetector.codeToName(code)}"
                }
            }
        }
        if (hasUnknown) {
            result[CountryDetector.UNKNOWN] = "🌐 Unknown"
        }
        return result.toSortedMap()
    }

    /** Trigger background geo-lookup for IPs not yet cached. */
    fun refreshCountryCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val ips = MmkvManager.decodeAllServerList().mapNotNull {
                MmkvManager.decodeServerConfig(it)?.server?.trim()
            }.distinct()
            CountryDetector.lookupAndCacheAll(ips)
            withContext(Dispatchers.Main) {
                reloadServerList()
            }
        }
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId.isEmpty()) {
            return AngConfigManager.updateConfigViaSubAll()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                serverList.toList()
            } else {
                _serversCache.map { it.guid }
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the TCP ping for all servers.
     */
    fun testAllTcping() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        MmkvManager.clearAllTestDelayResults(_serversCache.map { it.guid })

        val serversCopy = _serversCache.toList()
        for (item in serversCopy) {
            item.profile.let { outbound ->
                val serverAddress = outbound.server
                val serverPort = outbound.serverPort
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                            refreshPingInCache(listOf(item.guid))
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancels all running ping tests.
     */
    @Volatile var stopRequested = false

    fun cancelAllTests() {
        stopRequested = true
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        // Also try to stop the test service directly
        try {
            getApplication<AngApplication>().stopService(
                android.content.Intent(getApplication(), xyz.zarazaex.olc.service.V2RayTestService::class.java)
            )
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop test service", e)
        }
        isTesting.value = false
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        stopRequested = false
        Log.d(AppConfig.TAG, "TEST_PING: START, subscriptionId=$subscriptionId, serverCount=${_serversCache.size}")

        viewModelScope.launch(Dispatchers.IO) {
            val removed = removeDuplicateByIpAll()
            withContext(Dispatchers.Main) {
                if (removed > 0) {
                    reloadServerList()
                }
                if (!suppressPinSelected) {
                    MmkvManager.clearAllTestDelayResults(_serversCache.map { it.guid })
                }
                publishSnapshot()
                isTesting.value = true

                viewModelScope.launch(Dispatchers.Default) {
                    if (_serversCache.isEmpty()) {
                        withContext(Dispatchers.Main) { reloadServerList() }
                    }
                    if (_serversCache.isEmpty()) {
                        Log.e(AppConfig.TAG, "TEST_PING: serverCache EMPTY after reload, aborting")
                        withContext(Dispatchers.Main) { isTesting.value = false }
                        return@launch
                    }

                    val testSubId = subscriptionId
                    val testServerGuids = if (keywordFilter.isNotEmpty()) {
                        _serversCache.map { it.guid }
                    } else {
                        emptyList()
                    }
                    Log.d(AppConfig.TAG, "TEST_PING: sending MSG_MEASURE_CONFIG, subId=$testSubId, guids=${testServerGuids.size}")

                    if (stopRequested) {
                        Log.d(AppConfig.TAG, "TEST_PING: stop requested, aborting")
                        withContext(Dispatchers.Main) { isTesting.value = false }
                        return@launch
                    }

                    try {
                        MessageUtil.sendMsg2TestService(
                            getApplication(),
                            TestServiceMessage(
                                key = AppConfig.MSG_MEASURE_CONFIG,
                                subscriptionId = testSubId,
                                serverGuids = testServerGuids
                            )
                        )
                        Log.d(AppConfig.TAG, "TEST_PING: MSG_MEASURE_CONFIG sent successfully")
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "TEST_PING: FAILED to send message: ${e.message}", e)
                        withContext(Dispatchers.Main) { isTesting.value = false }
                    }
                }
            }
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId != id) {
            subscriptionId = id
            MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        }
        reloadServerList()
    }

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun getSubscriptions(context: Context): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()
        if (subscriptionId.isNotEmpty()
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        val groups = mutableListOf<GroupMapItem>()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)) {
            groups.add(
                GroupMapItem(
                    id = "",
                    remarks = context.getString(R.string.filter_config_all),
                    subIds = emptyList()
                )
            )
        }

        subscriptions.forEach { sub ->
            groups.add(
                GroupMapItem(
                    id = sub.guid,
                    remarks = sub.subscription.remarks,
                    subIds = listOf(sub.guid)
                )
            )
        }

        return groups
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        _serversCache.forEachIndexed { index, it ->
            if (it.guid == guid) return index
        }
        return -1
    }

    /**
     * Removes duplicate servers.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serversCacheCopy = _serversCache.toList()
        val deleteServer = mutableListOf<String>()
        serversCacheCopy.forEachIndexed { index, sc ->
            val profile = sc.profile
            serversCacheCopy.forEachIndexed { index2, sc2 ->
                if (index2 > index) {
                    val profile2 = sc2.profile
                    if (profile == profile2 && !deleteServer.contains(sc2.guid)) {
                        deleteServer.add(sc2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes servers with duplicate IP addresses (same `server` field),
     * keeping the one with the best ping result (or the first encountered if untested).
     * @return Number of removed servers.
     */
    fun removeDuplicateByIp(): Int {
        val selectedGuid = MmkvManager.getSelectServer()
        val byIp = LinkedHashMap<String, MutableList<ServersCache>>()
        for (sc in _serversCache) {
            val ip = sc.profile.server?.trim()?.lowercase() ?: continue
            byIp.getOrPut(ip) { mutableListOf() }.add(sc)
        }

        val toDelete = mutableListOf<String>()
        for ((_, group) in byIp) {
            if (group.size <= 1) continue
            val best = group.minWithOrNull(compareBy(
                { it.guid != selectedGuid },
                { !it.profile.isFavorite },
                {
                    val d = MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L
                    when {
                        d > 0L -> d
                        d == 0L -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                }
            ))!!
            group.filter { it.guid != best.guid }.forEach { toDelete.add(it.guid) }
        }

        for (guid in toDelete) {
            MmkvManager.removeServer(guid)
        }
        return toDelete.size
    }

    /**
     * Removes duplicate servers by IP across ALL subscriptions (for use after sub update / before scan).
     * Per-subscription deduplication: within each sub keeps the best (favorite > lowest ping > first).
     */
    /**
     * Removes servers with duplicate IP addresses across ALL subscriptions globally.
     * Keeps the best one per IP (favorite > lowest ping > first encountered).
     * @return Number of removed servers.
     */
    fun removeDuplicateByIpAll(): Int {
        val selectedGuid = MmkvManager.getSelectServer()
        // Collect every server GUID across all subscriptions
        data class Entry(val guid: String, val ip: String, val isFav: Boolean)

        val allEntries = mutableListOf<Entry>()
        val allSubIds = MmkvManager.decodeSubsList().toMutableList()
        // Add the default (no-sub) slot if not already present
        if (!allSubIds.contains(AppConfig.DEFAULT_SUBSCRIPTION_ID)) {
            allSubIds.add(0, AppConfig.DEFAULT_SUBSCRIPTION_ID)
        }

        for (subId in allSubIds) {
            for (guid in MmkvManager.decodeServerList(subId)) {
                val profile = MmkvManager.decodeServerConfig(guid) ?: continue
                val ip = profile.server?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: continue
                allEntries.add(Entry(guid, ip, profile.isFavorite))
            }
        }

        // Group by IP globally
        val byIp = LinkedHashMap<String, MutableList<Entry>>()
        for (e in allEntries) {
            byIp.getOrPut(e.ip) { mutableListOf() }.add(e)
        }

        val toDelete = mutableListOf<String>()
        for ((_, group) in byIp) {
            if (group.size <= 1) continue
            val best = group.minWith(compareBy(
                { it.guid != selectedGuid },
                { !it.isFav },
                {
                    val d = MmkvManager.decodeServerAffiliationInfo(it.guid)?.testDelayMillis ?: 0L
                    when {
                        d > 0L -> d
                        d == 0L -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                }
            ))
            group.filter { it.guid != best.guid }.forEach { toDelete.add(it.guid) }
        }

        for (guid in toDelete) {
            MmkvManager.removeServer(guid)
        }
        return toDelete.size
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId.isEmpty() && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = _serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCopy.count()
            }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    /**
     * Sorts serversCache in-place by test delay in real time (during a ping test).
     * Favorites always come first, then sorted ascending by delay (failed/untested go to bottom).
     */
    @Synchronized
    fun refreshPingInCache(guids: List<String>) {
        val guidSet = guids.toHashSet()
        for (i in _serversCache.indices) {
            val item = _serversCache[i]
            if (item.guid in guidSet) {
                val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
                if (item.testDelayMillis != delay) {
                    _serversCache[i] = item.copy(testDelayMillis = delay)
                }
            }
        }
        publishSnapshot()
    }

    @Synchronized
    fun sortServersCacheInPlace() {
        for (i in _serversCache.indices) {
            val item = _serversCache[i]
            val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
            if (item.testDelayMillis != delay) {
                _serversCache[i] = item.copy(testDelayMillis = delay)
            }
        }
        _serversCache.sortWith(compareBy(
            { !it.profile.isFavorite },
            {
                val delay = it.testDelayMillis
                when {
                    delay > 0L -> delay
                    delay == 0L -> Long.MAX_VALUE - 1
                    else -> Long.MAX_VALUE
                }
            }
        ))
        if (!suppressPinSelected) pinSelectedCacheItemToTop(_serversCache)
        publishSnapshot()
    }

    fun sortByTestResults() {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long, var isFav: Boolean)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            val isFav = MmkvManager.decodeServerConfig(key)?.isFavorite ?: false
            val sortKey = when {
                delay > 0L -> delay
                delay == 0L -> Long.MAX_VALUE - 1
                else -> Long.MAX_VALUE
            }
            serverDelays.add(ServerDelay(key, sortKey, isFav))
        }
        serverDelays.sortWith(compareBy({ !it.isFav }, { it.testDelayMillis }))

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()
        pinSelectedGuidToTop(sortedServerList)

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }

    private fun pinSelectedGuidToTop(list: MutableList<String>) {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isEmpty()) return
        val index = list.indexOf(selectedGuid)
        if (index > 0) {
            list.removeAt(index)
            list.add(0, selectedGuid)
        }
    }

    private fun pinSelectedCacheItemToTop(list: MutableList<ServersCache>) {
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isEmpty()) return
        val index = list.indexOfFirst { it.guid == selectedGuid }
        if (index > 0) {
            val selectedItem = list.removeAt(index)
            list.add(0, selectedItem)
        }
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()   // rebuilds _serversCache + publishSnapshot
                isTesting.value = false
                liteTestFinished.value = true
                liteTestFinished.value = false
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value = content
                    // Save ping for selected server so it shows in the list
                    val guid = MmkvManager.getSelectServer()
                    if (!guid.isNullOrEmpty() && content != null) {
                        val ms = Regex("\\d+").find(content)?.value?.toLongOrNull()
                        if (ms != null && ms > 0) {
                            MmkvManager.encodeServerTestDelayMillis(guid, ms)
                            refreshPingInCache(listOf(guid))
                        }
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.serializable<Pair<String, Long>>("content") ?: return
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second)
                    refreshPingInCache(listOf(resultPair.first))
                    if (!suppressPinSelected) sortServersCacheInPlace()
                    // publishSnapshot() already called inside refresh/sort above
                }

                AppConfig.MSG_MEASURE_CONFIG_BATCH -> {
                    if (stopRequested) return
                    val update = intent.serializable<PingProgressUpdate>("content") ?: return
                    update.results.forEach { result ->
                        MmkvManager.encodeServerTestDelayMillis(result.guid, result.delay)
                    }
                    refreshPingInCache(update.results.map { it.guid })
                    if (!suppressPinSelected) sortServersCacheInPlace()
                }
                    refreshPingInCache(update.results.map { it.guid })
                    if (!suppressPinSelected) sortServersCacheInPlace()
                    // publishSnapshot() already called inside refresh/sort above
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    if (stopRequested) return
                    val content = intent.getStringExtra("content")
                    updateTestResultAction.value =
                        getApplication<AngApplication>().getString(R.string.connection_runing_task_left, content)
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    if (stopRequested) return
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        onTestsFinished()
                    } else {
                        // cancelled or finished with non-zero count still in queue — mark as not testing
                        isTesting.value = false
                    }
                }
            }
        }
    }
}
