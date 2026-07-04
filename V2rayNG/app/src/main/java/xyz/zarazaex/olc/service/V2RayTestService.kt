package xyz.zarazaex.olc.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.AppConfig.MSG_MEASURE_CONFIG
import xyz.zarazaex.olc.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import xyz.zarazaex.olc.dto.TestServiceMessage
import xyz.zarazaex.olc.extension.serializable
import xyz.zarazaex.olc.handler.MmkvManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.util.MessageUtil
import java.util.Collections

class V2RayTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    override fun onCreate() {
        super.onCreate()
        Log.d(AppConfig.TAG, "TEST_SVC: onCreate")
        V2RayNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(AppConfig.TAG, "TEST_SVC: onDestroy, cancelling ${activeWorkers.size} workers")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content")
        Log.i(AppConfig.TAG, "TEST_SVC: onStartCommand key=${message?.key}")
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "TEST_SVC: received msg key=${message?.key}")
        if (message == null) {
            Log.e(AppConfig.TAG, "TEST_SVC: message is null!")
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "TEST_SVC: ERROR message null")
            return super.onStartCommand(intent, flags, startId)
        }
        when (message.key) {
            MSG_MEASURE_CONFIG -> {
                val guidsList = if (message.serverGuids.isNotEmpty()) {
                    message.serverGuids
                } else if (message.subscriptionId.isNotEmpty()) {
                    MmkvManager.decodeServerList(message.subscriptionId)
                } else {
                    MmkvManager.decodeAllServerList()
                }

                Log.d(AppConfig.TAG, "TEST_SVC: MSG_MEASURE_CONFIG, guidsList.size=${guidsList.size}")
                AppConfig.broadcastLog(this, "TEST_SVC: MSG_MEASURE_CONFIG, guidsList.size=${guidsList.size}")
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "TEST_SVC: starting ${guidsList.size} configs")

                if (guidsList.isNotEmpty()) {
                    lateinit var worker: RealPingWorkerService
                    worker = RealPingWorkerService(this, guidsList) { status ->
                        Log.d(AppConfig.TAG, "TEST_SVC: worker finished with status=$status")
                        MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "TEST_SVC: worker done status=$status")
                        MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                        activeWorkers.remove(worker)
                    }
                    activeWorkers.add(worker)
                    Log.d(AppConfig.TAG, "TEST_SVC: starting worker with ${guidsList.size} configs")
                    worker.start()
                } else {
                    Log.e(AppConfig.TAG, "TEST_SVC: guidsList is EMPTY!")
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                Log.d(AppConfig.TAG, "TEST_SVC: MSG_MEASURE_CONFIG_CANCEL, cancelling ${activeWorkers.size} workers")
                val snapshot = ArrayList(activeWorkers)
                snapshot.forEach { it.cancel() }
                activeWorkers.clear()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
}