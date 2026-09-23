package com.leadaxe.lxbox.vpn

import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface

/// Мониторинг default-сети. §migration: libbox-вариант ещё дёргал
/// `InterfaceUpdateListener` ядра (startDefaultInterfaceMonitor) — Xray такого
/// API не имеет, поэтому осталась только её половина: трекинг фактического
/// интерфейса и §087-degraded reload на genuine смену сети (BoxService →
/// serviceReload вместо resetNetwork). Пер-нодовые DNS/typedef-колбэки
/// sing-box не реплицируются (минимальный срез).
object DefaultNetworkMonitor {
    /// §087 — окно debounce для force-reset на смену интерфейса (мс). Android
    /// при переходе шлёт пачку callback'ов; копим тишину и ресетим один раз.
    private const val RESET_DEBOUNCE_MS = 1500L

    var defaultNetwork: Network? = null
    private var scope: CoroutineScope? = null

    /// §087 — callback на genuine смену интерфейса (BoxService → reload).
    private var onNetworkSwitch: (() -> Unit)? = null

    /// §087 — имя последнего реального интерфейса (baseline для детекта смены).
    /// `@Volatile`: пишется из checkUpdate (actor).
    @Volatile
    private var lastIfName: String? = null
    private var resetJob: Job? = null

    suspend fun start(scope: CoroutineScope, onNetworkSwitch: () -> Unit) {
        this.scope = scope
        this.onNetworkSwitch = onNetworkSwitch
        // §119 — seed `defaultNetwork` из getActiveNetwork(), но НИКОГДА не нашим
        // же VPN. getActiveNetwork() возвращает per-app default, который штатно
        // ВКЛЮЧАЕТ наш tun, если VPN уже поднят к моменту start() (док:
        // ConnectivityManager.getActiveNetwork / registerDefaultNetworkCallback —
        // «may be ... a VPN that applies to the application»). NOT_VPN из
        // NetworkRequest сюда НЕ применяется (это прямой геттер, не запрос),
        // поэтому фильтруем явно. Callback-источник (ниже) уже отфильтрован
        // NOT_VPN'ом самого NetworkRequest и здесь перезапишет seed.
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BoxApplication.connectivity.activeNetwork?.takeUnless(::isVpn)
        } else null
        logDefaultNetwork("init", defaultNetwork)

        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkUpdate(it)
        }

        if (defaultNetwork == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            defaultNetwork = DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
        resetJob?.cancel()
        resetJob = null
        onNetworkSwitch = null
        lastIfName = null
        scope = null
    }

    private fun checkUpdate(network: Network?) {
        val s = scope ?: return  // service already dead — don't touch anything
        if (network == null) {
            // §087 — disconnect: НЕ трогаем lastIfName (сохраняем baseline, чтобы
            // switch через "" к ДРУГОМУ интерфейсу всё равно детектился) и НЕ
            // ресетим (нет сети для re-dial).
            return
        }
        logDefaultNetwork("update", network)
        val linkProps = BoxApplication.connectivity.getLinkProperties(network)
        val ifName = linkProps?.interfaceName ?: return
        for (attempt in 0 until 10) {
            try {
                val ni = NetworkInterface.getByName(ifName) ?: continue
                maybeResetOnSwitch(ifName, s)  // §087
                lastIfName = ifName
                return
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
    }

    /// §087 — force-reset стейл-соединений на genuine смену интерфейса.
    ///
    /// `checkUpdate` дёргается из `DefaultNetworkListener` на ЛЮБОЙ
    /// `onCapabilitiesChanged`, поэтому reset только на реальный switch:
    /// `prev → new`, оба непустые и разные. НЕ на первый connect (prev пуст),
    /// capability-update (prev == new) или disconnect (см. ветку network==null).
    /// Debounced: пачка переходов схлопывается в один вызов.
    private fun maybeResetOnSwitch(newIfName: String, s: CoroutineScope) {
        val prev = lastIfName
        if (prev.isNullOrEmpty() || newIfName.isEmpty() || prev == newIfName) return
        resetJob?.cancel()
        resetJob = s.launch(Dispatchers.IO) {
            delay(RESET_DEBOUNCE_MS)
            runCatching { onNetworkSwitch?.invoke() }
        }
    }

    /// §119 — true, если у network есть VPN-транспорт (наш собственный tun).
    private fun isVpn(network: Network): Boolean =
        BoxApplication.connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    /// §119 — постоянная диагностика: какой `defaultNetwork` поймали — VPN или
    /// underlying-физика. Читать: `adb logcat -s LxBoxNet`.
    private fun logDefaultNetwork(where: String, network: Network?) {
        runCatching {
            if (network == null) {
                Log.i("LxBoxNet", "[$where] defaultNetwork=null")
                return
            }
            val ifName = BoxApplication.connectivity
                .getLinkProperties(network)?.interfaceName ?: "?"
            Log.i("LxBoxNet", "[$where] defaultNetwork iface=$ifName vpn=${isVpn(network)}")
        }
    }
}