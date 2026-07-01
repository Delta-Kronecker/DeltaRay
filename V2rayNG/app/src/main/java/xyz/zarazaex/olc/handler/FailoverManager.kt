package xyz.zarazaex.olc.handler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.handler.V2RayServiceManager

object FailoverManager {

    private var job: Job? = null
    private var isActive = false
    private var currentServerIndex = 0
    private var sortedServers = mutableListOf<String>()

    /** Ping timeout in milliseconds */
    private const val PING_TIMEOUT_MS = 10_000L
    /** Interval between ping cycles in milliseconds */
    private const val PING_INTERVAL_MS = 5_000L
    /** Number of concurrent tests */
    private const val CONCURRENT_TESTS = 2

    /** Callback for UI updates */
    var onStatusChange: ((String) -> Unit)? = null

    fun start(context: Context) {
        if (isActive) return
        isActive = true
        onStatusChange?.invoke("Failover: started")

        job = CoroutineScope(Dispatchers.IO).launch {
            // Sort servers by best ping (lowest delay > 0)
            sortedServers = getSortedServers()
            if (sortedServers.size < 2) {
                withContext(Dispatchers.Main) {
                    onStatusChange?.invoke("Failover: not enough servers (${sortedServers.size})")
                }
                isActive = false
                return@launch
            }

            currentServerIndex = 0
            val currentServer = sortedServers[currentServerIndex]
            withContext(Dispatchers.Main) {
                onStatusChange?.invoke("Failover: monitoring ${sortedServers.size} servers, current: ${currentServer.take(8)}")
            }

            // Main failover loop
            while (isActive && coroutineContext.isActive) {
                // Test current server
                val currentServer = sortedServers[currentServerIndex]
                val delay = testServerPing()

                if (delay < 0) {
                    // Ping failed or timed out - switch server
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${currentServer.take(8)} TIMEOUT - switching")
                    }

                    val nextIndex = findNextBestServer()
                    if (nextIndex >= 0 && nextIndex != currentServerIndex) {
                        val newServer = sortedServers[nextIndex]
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: switching to ${newServer.take(8)}")
                        }

                        // Stop current VPN
                        V2RayServiceManager.stopVService(context)
                        delay(2000)

                        // Switch to new server
                        MmkvManager.setSelectServer(newServer)
                        currentServerIndex = nextIndex

                        // Restart VPN
                        V2RayServiceManager.startVService(context)

                        // Wait for VPN to start
                        delay(3000)
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: connected to ${newServer.take(8)}")
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: no backup server available")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${currentServer.take(8)} ${delay}ms OK")
                    }
                }

                // Wait before next cycle
                delay(PING_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        isActive = false
        job?.cancel()
        job = null
        sortedServers.clear()
        currentServerIndex = 0
        onStatusChange?.invoke("Failover: stopped")
    }

    fun isRunning(): Boolean = isActive

    /**
     * Test ping to current server through the live VPN tunnel.
     * Returns delay in ms or -1 for failure/timeout.
     */
    private suspend fun testServerPing(): Long {
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(PING_TIMEOUT_MS) {
                    V2RayServiceManager.measurePingSimple()
                } ?: -1L
            } catch (e: CancellationException) {
                -1L
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "FailoverManager: ping error: ${e.message}")
                -1L
            }
        }
    }

    /**
     * Get servers sorted by best ping (lowest delay > 0 first).
     */
    private fun getSortedServers(): MutableList<String> {
        val allSubs = MmkvManager.decodeSubscriptions()
        val allServers = mutableListOf<Pair<String, Long>>() // guid, delay

        for (sub in allSubs) {
            val serverList = MmkvManager.decodeServerList(sub.guid)
            for (serverGuid in serverList) {
                val delay = MmkvManager.decodeServerAffiliationInfo(serverGuid)?.testDelayMillis ?: 0L
                allServers.add(Pair(serverGuid, delay))
            }
        }

        // Sort: reachable servers first (by delay ascending), then unreachable
        return allServers
            .sortedBy { if (it.second > 0) it.second else Long.MAX_VALUE }
            .map { it.first }
            .toMutableList()
    }

    /**
     * Find the next best server (lowest ping, skipping current).
     */
    private fun findNextBestServer(): Int {
        // Re-sort servers by best ping
        sortedServers = getSortedServers()
        // Find a server that isn't the current one
        for (i in sortedServers.indices) {
            if (sortedServers[i] != sortedServers[currentServerIndex]) {
                return i
            }
        }
        return -1
    }
}
