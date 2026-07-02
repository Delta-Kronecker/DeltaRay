package xyz.zarazaex.olc.handler

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.util.MessageUtil

object FailoverManager {

    private var job: Job? = null
    private var failoverActive = false
    private var switching = false
    private var currentServerIndex = 0
    private var sortedServers = mutableListOf<String>()
    private var consecutiveFailures = 0
    private var pendingPingDeferred: kotlinx.coroutines.CompletableDeferred<Long>? = null

    private const val PING_TIMEOUT_MS = 10_000L
    private const val PING_INTERVAL_MS = 5_000L
    private const val INITIAL_DELAY_MS = 15_000L
    private const val MAX_FAILURES_BEFORE_SWITCH = 3

    var onStatusChange: ((String) -> Unit)? = null

    fun start(context: Context) {
        if (failoverActive) return
        failoverActive = true
        switching = false
        consecutiveFailures = 0
        onStatusChange?.invoke("Failover: started")

        job = CoroutineScope(Dispatchers.IO).launch {
            // Wait for VPN tunnel to fully establish
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
            val currentServer = sortedServers[currentServerIndex]
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
        return try {
            val svc = V2RayServiceManager.getService() ?: return -1L
            val ctrl = V2RayServiceManager.getCoreController()
            if (!ctrl.isRunning) return -1L

            val deferred = kotlinx.coroutines.CompletableDeferred<Long>()
            pendingPingDeferred = deferred

            MessageUtil.sendMsg2Service(svc, AppConfig.MSG_MEASURE_DELAY, "")

            withTimeout(PING_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            -1L
        } catch (e: Exception) {
            -1L
        } finally {
            pendingPingDeferred = null
        }
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

    fun resolvePingResult(delay: Long) {
        pendingPingDeferred?.complete(delay)
    }

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
