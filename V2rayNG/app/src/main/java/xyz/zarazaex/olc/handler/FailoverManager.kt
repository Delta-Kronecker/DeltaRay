package xyz.zarazaex.olc.handler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import xyz.zarazaex.olc.AppConfig

object FailoverManager {

    private var job: Job? = null
    private var failoverActive = false
    private var switching = false
    private var currentServerIndex = 0
    private var sortedServers = mutableListOf<String>()

    private const val PING_TIMEOUT_MS = 10_000L
    private const val PING_INTERVAL_MS = 5_000L
    private const val INITIAL_DELAY_MS = 8_000L

    var onStatusChange: ((String) -> Unit)? = null

    fun start(context: Context) {
        if (failoverActive) return
        failoverActive = true
        switching = false
        onStatusChange?.invoke("Failover: started")

        job = CoroutineScope(Dispatchers.IO).launch {
            // Wait for VPN tunnel to fully establish
            delay(INITIAL_DELAY_MS)

            sortedServers = getSortedServers()
            if (sortedServers.size < 2) {
                withContext(Dispatchers.Main) {
                    onStatusChange?.invoke("Failover: not enough servers (${sortedServers.size})")
                }
                failoverActive = false
                return@launch
            }

            currentServerIndex = 0
            val currentServer = sortedServers[currentServerIndex]
            withContext(Dispatchers.Main) {
                onStatusChange?.invoke("Failover: monitoring ${sortedServers.size} servers, current: ${currentServer.take(8)}")
            }

            while (failoverActive && coroutineContext.isActive) {
                val server = sortedServers[currentServerIndex]
                val delay = measurePingWithTimeout()

                if (delay < 0) {
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${server.take(8)} TIMEOUT - switching")
                    }

                    switching = true

                    sortedServers = getSortedServers()
                    val nextIdx = sortedServers.indexOfFirst { it != server }
                    if (nextIdx >= 0) {
                        val newServer = sortedServers[nextIdx]
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: switching to ${newServer.take(8)}")
                        }
                        V2RayServiceManager.stopVService(context)
                        delay(2000)
                        MmkvManager.setSelectServer(newServer)
                        currentServerIndex = nextIdx
                        V2RayServiceManager.startVService(context)
                        // Wait for VPN to establish before next ping
                        delay(INITIAL_DELAY_MS)
                        switching = false
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: connected to ${newServer.take(8)}")
                        }
                    } else {
                        switching = false
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: no backup server available")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${server.take(8)} ${delay}ms OK")
                    }
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun measurePingWithTimeout(): Long {
        return try {
            withTimeout(PING_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val ctrl = V2RayServiceManager.getCoreController()
                    if (!ctrl.isRunning) return@withContext -1L
                    val url = SettingsManager.getDelayTestUrl()
                    ctrl.measureDelay(url)
                }
            }
        } catch (e: TimeoutCancellationException) {
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun stop() {
        if (switching) return
        failoverActive = false
        job?.cancel()
        job = null
        sortedServers.clear()
        currentServerIndex = 0
        onStatusChange?.invoke("Failover: stopped")
    }

    fun isRunning(): Boolean = failoverActive

    private fun getSortedServers(): MutableList<String> {
        val allSubs = MmkvManager.decodeSubscriptions()
        val allServers = mutableListOf<Pair<String, Long>>()
        for (sub in allSubs) {
            val serverList = MmkvManager.decodeServerList(sub.guid)
            for (guid in serverList) {
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                allServers.add(Pair(guid, delay))
            }
        }
        return allServers
            .sortedBy { if (it.second > 0) it.second else Long.MAX_VALUE }
            .map { it.first }
            .toMutableList()
    }
}
