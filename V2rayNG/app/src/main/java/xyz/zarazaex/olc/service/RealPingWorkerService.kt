package xyz.zarazaex.olc.service

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.dto.PingProgressUpdate
import xyz.zarazaex.olc.dto.PingResultItem
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.SettingsManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.handler.V2rayConfigManager
import xyz.zarazaex.olc.util.JsonUtil
import xyz.zarazaex.olc.util.MessageUtil

/**
 * Worker that runs a batch of real-ping tests independently. Optimized to use Go-level concurrency
 * for improved performance.
 */
class RealPingWorkerService(
        private val context: Context,
        private val guids: List<String>,
        private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val scope =
            CoroutineScope(job + Dispatchers.Default + CoroutineName("RealPingBatchWorker"))

    private val totalCount = AtomicInteger(guids.size)
    private val finishedCount = AtomicInteger(0)
    private val pendingResults = ArrayList<PingResultItem>()
    private val pendingLock = Any()

    private val delayTestUrl = SettingsManager.getDelayTestUrl()

    companion object {
        private const val RESULT_BATCH_SIZE = 32
        private const val FLUSH_INTERVAL_MS = 1000L
    }

    data class PingItem(val guid: String, val config: String)

    fun start() {
        Log.d(AppConfig.TAG, "PING_WORKER: start() called, ${guids.size} configs")
        AppConfig.broadcastLog(context, "PING_WORKER: start, ${guids.size} configs")
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushPendingResults()
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val selectedGuid = MmkvManager.getSelectServer()
                val shuffledGuids = if (selectedGuid != null && guids.contains(selectedGuid)) {
                    val rest = guids.filter { it != selectedGuid }.shuffled()
                    listOf(selectedGuid) + rest
                } else {
                    guids.shuffled()
                }
                Log.d(AppConfig.TAG, "PING_WORKER: preparing ${shuffledGuids.size} configs")

                val deferredItems = shuffledGuids.map { guid ->
                    async(Dispatchers.IO) {
                        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                        if (configResult.status) {
                            PingItem(guid, configResult.content)
                        } else {
                            Log.d(AppConfig.TAG, "PING_WORKER: config failed for $guid")
                            reportResult(guid, -1L)
                            null
                        }
                    }
                }
                val items = deferredItems.awaitAll().filterNotNull()
                Log.d(AppConfig.TAG, "PING_WORKER: ${items.size} configs prepared, starting batch ping")

                if (items.isNotEmpty()) {
                    val configsJson = JsonUtil.toJson(items)
                    Log.d(AppConfig.TAG, "PING_WORKER: calling measureOutboundDelayBatch with ${items.size} configs, url=$delayTestUrl")

                    V2RayNativeManager.measureOutboundDelayBatch(
                            configsJson,
                            delayTestUrl,
                            object : libv2ray.PingCallback {
                                override fun onResult(guid: String?, delay: Long) {
                                    Log.d(AppConfig.TAG, "PING_WORKER: onResult guid=$guid delay=$delay")
                                    if (guid != null) {
                                        reportResult(guid, delay)
                                    }
                                }
                            }
                    )
                    Log.d(AppConfig.TAG, "PING_WORKER: measureOutboundDelayBatch returned")
                } else {
                    Log.e(AppConfig.TAG, "PING_WORKER: no configs to test!")
                }

                flushPendingResults()
                Log.d(AppConfig.TAG, "PING_WORKER: all done, finishing")
                onFinish("0")
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "PING_WORKER: ERROR: ${e.message}", e)
                flushPendingResults()
                onFinish("-1")
            } finally {
                cancel()
            }
        }
    }

    private fun reportResult(guid: String, delay: Long) {
        val finished = finishedCount.incrementAndGet()
        var readyBatch: PingProgressUpdate? = null
        synchronized(pendingLock) {
            pendingResults.add(PingResultItem(guid, delay))
            if (pendingResults.size >= RESULT_BATCH_SIZE || finished >= totalCount.get()) {
                readyBatch = createProgressUpdateLocked(finished)
                pendingResults.clear()
            }
        }
        readyBatch?.let(::sendBatchUpdate)
    }

    private fun flushPendingResults() {
        val finished = finishedCount.get()
        val update =
            synchronized(pendingLock) {
                if (pendingResults.isEmpty()) {
                    null
                } else {
                    createProgressUpdateLocked(finished).also { pendingResults.clear() }
                }
            }
        update?.let(::sendBatchUpdate)
    }

    private fun createProgressUpdateLocked(finished: Int): PingProgressUpdate {
        return PingProgressUpdate(
            results = ArrayList(pendingResults),
            finished = finished,
            total = totalCount.get()
        )
    }

    private fun sendBatchUpdate(update: PingProgressUpdate) {
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_BATCH, update)
        MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "${update.finished}/${update.total}")
    }

    fun cancel() {
        job.cancel()
    }
}
