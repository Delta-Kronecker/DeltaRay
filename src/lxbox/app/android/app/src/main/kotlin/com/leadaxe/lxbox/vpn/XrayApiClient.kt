package com.leadaxe.lxbox.vpn

import android.util.Log
import com.xray.app.observatory.OutboundStatus
import com.xray.core.app.observatory.command.GetOutboundStatusRequest
import com.xray.core.app.observatory.command.ObservatoryServiceGrpc
import com.xray.app.router.command.GetBalancerInfoRequest
import com.xray.app.router.command.ListRuleRequest
import com.xray.app.router.command.OverrideBalancerTargetRequest
import com.xray.app.router.command.RoutingServiceGrpc
import com.xray.app.stats.command.QueryStatsRequest
import com.xray.app.stats.command.StatsServiceGrpc
import com.xray.app.stats.command.SysStatsRequest
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.TimeUnit

/// §Xray — gRPC-клиент к командному интерфейсу Xray-core (Commander).
///
/// Xray commander поднимается config-блоком `{"api":{"tag","listen","services"}}`
/// на loopback; слушает сам (TCP gRPC). Мы ходим только к 127.0.0.1:apiPort.
///
/// Все вызовы — blocking/stub, deadline 3с. Caller'ы (BoxCommandClient) держат
/// их на Dispatchers.IO. Любой RPC обёрнут в no-throw (null/empty на сбой) —
/// UI не должен падать от сбоя командного канала (статус переэмитится на
/// следующем тике).
///
/// Данные для UI (договор с Dart не меняется):
///  - status:  SysStats (memory/goroutines) + QueryStats(">>>traffic>>>",
///    суммарные счётчики → дельты на стороне клиента).
///  - nodes:   конфиг (outbounds) + ObservatoryService.GetOutboundStatus
///    (delay/alive/last_seen_time).
///  - groups:  конфиг (routing.balancers) + RoutingService.GetBalancerInfo
///    (override/selected) + Observatory delay.
///  - rules:   RoutingService.ListRule.
class XrayApiClient(private val port: Int) {

    companion object {
        private const val TAG = "XrayApi"
        private const val DEADLINE_SEC = 3L
    }

    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress("127.0.0.1", port)
        .usePlaintext()
        .build()

    val routing = RoutingServiceGrpc.newBlockingStub(channel)
    val stats = StatsServiceGrpc.newBlockingStub(channel)
    val observatory = ObservatoryServiceGrpc.newBlockingStub(channel)

    fun shutdown() {
        runCatching { channel.shutdownNow() }
        runCatching { channel.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }

    data class StatusSnapshot(
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val memory: Long,
        val goroutines: Int,
    )

    /// Тотальные счётчики всех inbound/outbound/user-каналов traffic.
    /// `reset=false` — счётчики не обнуляем (итоги за сессию, как sing-box).
    fun queryTotals(): Pair<Long, Long> = runCatching {
        val resp = stats.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).queryStats(
            QueryStatsRequest.newBuilder().apply {
                pattern = ">>>traffic>>>"
                reset = false
            }.build(),
        )
        var up = 0L
        var down = 0L
        for (s in resp.statList) {
            when {
                s.name.contains(">>>uplink") -> up += s.value
                s.name.contains(">>>downlink") -> down += s.value
            }
        }
        up to down
    }.getOrElse { err ->
        Log.w(TAG, "queryTotals failed: ${err.message}")
        0L to 0L
    }

    fun sysStats(): Pair<Long, Int> = runCatching {
        val s = stats.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).getSysStats(SysStatsRequest.getDefaultInstance())
        s.alloc to s.numGoroutine
    }.getOrElse { err ->
        Log.w(TAG, "sysStats failed: ${err.message}")
        0L to 0
    }

    /// Наблюдаемые outbound (Observatory). Называем по outbound_tag.
    fun outboundStatus(): Map<String, OutboundStatus> = runCatching {
        val resp = observatory.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).getOutboundStatus(GetOutboundStatusRequest.getDefaultInstance())
        val m = HashMap<String, OutboundStatus>()
        resp.status?.statusList?.forEach { st -> st.outboundTag.takeIf { it.isNotEmpty() }?.let { m[it] = st } }
        m
    }.getOrElse { err ->
        Log.w(TAG, "outboundStatus failed: ${err.message}")
        emptyMap()
    }

    /// Текущий override balancer'а (selected tag). null = его нет (auto).
    fun balancerOverride(balancerTag: String): String? = runCatching {
        val resp = routing.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).getBalancerInfo(
            GetBalancerInfoRequest.newBuilder().setTag(balancerTag).build(),
        )
        resp.balancer?.override?.target?.takeIf { it.isNotEmpty() }
    }.getOrElse { err ->
        Log.w(TAG, "balancerOverride($balancerTag) failed: ${err.message}")
        null
    }

    /// Селецта balancer'а (эквивалент selectOutbound группы).
    fun setBalancerTarget(balancerTag: String, target: String): Boolean = runCatching {
        routing.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).overrideBalancerTarget(
            OverrideBalancerTargetRequest.newBuilder()
                .setBalancerTag(balancerTag)
                .setTarget(target)
                .build(),
        )
        true
    }.getOrElse { err ->
        Log.w(TAG, "setBalancerTarget($balancerTag,$target) failed: ${err.message}")
        false
    }

    /// Routing-правила (route.rules). Список пар (tag, ruleTag) — данные
    /// диагностики; детальная структура правила у Xray недоступна (TypedMessage).
    fun listRules(): List<Pair<String, String>> = runCatching {
        val resp = routing.withDeadlineAfter(
            DEADLINE_SEC, TimeUnit.SECONDS
        ).listRule(ListRuleRequest.getDefaultInstance())
        resp.rulesList.map { it.tag to it.ruleTag }
    }.getOrElse { err ->
        Log.w(TAG, "listRules failed: ${err.message}")
        emptyList()
    }

    override fun toString(): String = "XrayApiClient(127.0.0.1:$port)"
}