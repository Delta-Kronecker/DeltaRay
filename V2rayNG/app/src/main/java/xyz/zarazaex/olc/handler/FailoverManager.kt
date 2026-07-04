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
    private var consecutiveFailures = 0

    private const val PING_TIMEOUT_MS = 15_000L
    private const val PING_INTERVAL_MS = 5_000L
    private const val INITIAL_DELAY_MS = 10_000L
    private const val MAX_FAILURES_BEFORE_SWITCH = 3
    private const val TOP_N_SERVERS = 10

    var onStatusChange: ((String) -> Unit)? = null

    fun start(context: Context) {
        if (failoverActive) return
        failoverActive = true
        switching = false
        consecutiveFailures = 0
        onStatusChange?.invoke("Failover: started")

        job = CoroutineScope(Dispatchers.IO).launch {
            onStatusChange?.let { withContext(Dispatchers.Main) { it("Failover: waiting ${INITIAL_DELAY_MS/1000}s for VPN...") } }
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
            withContext(Dispatchers.Main) {
                onStatusChange?.invoke("Failover: monitoring ${sortedServers.size} servers")
            }

            while (failoverActive && coroutineContext.isActive) {
                val server = sortedServers[currentServerIndex]
                val delayMs = measurePingWithTimeout()

                if (delayMs < 0) {
                    consecutiveFailures++
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${server.take(8)} TIMEOUT ($consecutiveFailures/$MAX_FAILURES_BEFORE_SWITCH)")
                    }

                    if (consecutiveFailures >= MAX_FAILURES_BEFORE_SWITCH) {
                        consecutiveFailures = 0
                        switching = true

                        // Smart failover: test top 10 servers + current, find best
                        val topServers = getTopNServers(TOP_N_SERVERS, excludeCurrent = server)
                        val candidates = listOf(server) + topServers
                        val testedResults = mutableListOf<Pair<String, Long>>()

                        withContext(Dispatchers.Main) {
                            onStatusChange?.invoke("Failover: testing ${candidates.size} candidates...")
                        }

                        // Test all candidates in parallel
                        val testJobs = candidates.map { srv ->
                            async {
                                val ms = measurePingForServer(srv)
                                Pair(srv, ms)
                            }
                        }
                        val results = testJobs.awaitAll()
                        testedResults.addAll(results)

                        // Find best from results (lowest delay > 0)
                        val bestCandidate = testedResults
                            .filter { it.second > 0 }
                            .minByOrNull { it.second }

                        if (bestCandidate != null && bestCandidate.first != server) {
                            val newServer = bestCandidate.first
                            withContext(Dispatchers.Main) {
                                onStatusChange?.invoke("Failover: switching to ${newServer.take(8)} (${bestCandidate.second}ms)")
                            }
                            V2RayServiceManager.stopVService(context)
                            delay(2000)
                            MmkvManager.setSelectServer(newServer)
                            currentServerIndex = sortedServers.indexOf(newServer).coerceAtLeast(0)
                            V2RayServiceManager.startVService(context)
                            delay(INITIAL_DELAY_MS)
                            switching = false
                            withContext(Dispatchers.Main) {
                                onStatusChange?.invoke("Failover: connected to ${newServer.take(8)}")
                            }
                        } else {
                            switching = false
                            withContext(Dispatchers.Main) {
                                onStatusChange?.invoke("Failover: no better server found")
                            }
                        }
                    }
                } else {
                    consecutiveFailures = 0
                    withContext(Dispatchers.Main) {
                        onStatusChange?.invoke("Failover: ${server.take(8)} ${delayMs}ms OK")
                    }
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private suspend fun measurePingWithTimeout(): Long {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.ipify.org")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = PING_TIMEOUT_MS.toInt()
                conn.readTimeout = PING_TIMEOUT_MS.toInt()
                conn.requestMethod = "GET"
                val startTime = System.currentTimeMillis()
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                val elapsed = System.currentTimeMillis() - startTime
                if (code == 200) elapsed else -1L
            } catch (e: Exception) {
                -1L
            }
        }
    }

    private suspend fun measurePingForServer(serverGuid: String): Long {
        return measurePingWithTimeout()
    }

    fun stop() {
        if (switching) return
        failoverActive = false
        job?.cancel()
        job = null
        sortedServers.clear()
        currentServerIndex = 0
        consecutiveFailures = 0
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

    private fun getTopNServers(n: Int, excludeCurrent: String): List<String> {
        return sortedServers
            .filter { it != excludeCurrent }
            .take(n)
    }
}
