package com.leadaxe.lxbox

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.leadaxe.lxbox.vpn.BootReceiver
import com.leadaxe.lxbox.vpn.BoxVpnService
import com.leadaxe.lxbox.vpn.VpnPlugin
import com.leadaxe.lxbox.vpn.VpnStatus
import io.flutter.embedding.android.BackgroundMode
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterShellArgs
import io.flutter.plugin.common.MethodChannel

/// §DeltaRay — НЕВИДИМЫЙ quick-connect замена окну MainActivity при
/// «Connect all» с лаунчера: обновление подписок + старт VPN в фоне, без
/// видимого открытия приложения.
///
/// Почему не headless-движок: раздавать кадры (на которые живёт bootstrap
/// HomeScreen, включая binding autoUpdater в initState) вне окна в штатном
/// embedding нельзя без TextureView в невидимом окне. Поэтому делаем
/// наоборот: прозрачная тема + decorView.alpha = 0 → окно есть (vsync жив),
/// но содержимое не видно; никакой анимации входа.
///
/// КРИТИЧНО: рендер обязан быть texture, не surface. Дефолтный RenderMode
/// .surface — это SurfaceView, чей слой компонуется ОС ОТДЕЛЬНО от окна и
/// игнорирует alpha/прозрачность дерева view (пользователь видел полноценную
/// страницу L×Box поверх прозрачного окна). TextureView — обычный view:
/// alpha=0 его скрывает полностью. BackgroundMode.transparent дополнительно
/// запрещает Flutter заливать окно непрозрачным материалом.
///
/// Dart-сторона — тот же main(), что и у UI: поднявшись, HomeScreen
/// инициализирует контроллеры и AutoUpdater, automation-мост
/// (registerAutomationBridge) принимает `refresh-subs`. После паузы на boot
/// (REFRESH_DELAY) гоним refresh, через CONNECT_GRACE стартуем VPN штатным
/// native-путём (prepare-проверка: единственный видимый элемент — системный
/// диалог согласия на первый connect). Финиш — по факту Started или по
/// страховочному таймауту; лаунчер возвращается в onResume и сам проверяет
/// статус пингом конфигов.
class QuickConnectActivity : FlutterActivity() {

    companion object {
        private const val TAG = "QuickConnectActivity"
        private const val VPN_REQUEST_CODE_QUICK = 7032
        private const val REFRESH_DELAY_MS = 1_500L
        private const val CONNECT_GRACE_MS = 1_000L
        private const val STARTED_WAIT_MS = 8_000L
        private const val TIMEOUT_MS = 30_000L
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Скрыть всё содержимое окна: тема уже прозрачная, alpha=0 гасит и
        // opaque-кадры Flutter (иначе поверх прозрачного фона «прострелит» UI).
        window.decorView.alpha = 0f

        mainHandler.postDelayed(
            {
                VpnPlugin.handleAutomationAction("refresh-subs", mapOf("force" to false))
            },
            REFRESH_DELAY_MS,
        )
        mainHandler.postDelayed({ startVpnQuiet() }, REFRESH_DELAY_MS + CONNECT_GRACE_MS)
        // Страховка от зависания: закрываемся при любом сценарии.
        mainHandler.postDelayed(finishRunnable, TIMEOUT_MS)
    }

    override fun getRenderMode(): RenderMode = RenderMode.texture
    override fun getBackgroundMode(): BackgroundMode = BackgroundMode.transparent

    override fun getFlutterShellArgs(): FlutterShellArgs {
        val args = super.getFlutterShellArgs()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG, "API ${Build.VERSION.SDK_INT} < 31 — disabling Impeller (Skia renderer)")
            args.add("--enable-impeller=false")
        }
        return args
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(VpnPlugin())
        // utils-канал из MainActivity: Dart может дёрнуть в init-флоу; no-op
        // вместо отсутствующего handler'а (иначе invokeMethod падает молча).
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.leadaxe.lxbox/utils",
        ).setMethodCallHandler { _, result -> result.success(null) }
    }

    /// Запуск VPN в фоне: permission уже дан (или proxy без TUN) → нативный
    /// старт; иначе — системный диалог VpnService (OS-обязательство).
    private fun startVpnQuiet() {
        if (!BootReceiver.hasTun(applicationContext) ||
            VpnService.prepare(applicationContext) == null
        ) {
            BoxVpnService.start(applicationContext)
            watchForStarted()
        } else {
            startActivityForResult(
                VpnService.prepare(applicationContext),
                VPN_REQUEST_CODE_QUICK,
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE_QUICK) return
        if (resultCode == Activity.RESULT_OK) {
            BoxVpnService.start(applicationContext)
            watchForStarted()
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    /// Закрываемся, как только туннель поднят (или по дедлайну) — дальше
    /// статусом занимается лаунчер (стадия пинга конфигов).
    private fun watchForStarted() {
        val deadline = System.currentTimeMillis() + STARTED_WAIT_MS
        val check = object : Runnable {
            override fun run() {
                if (BoxVpnService.currentStatus == VpnStatus.Started) {
                    finish()
                } else if (System.currentTimeMillis() < deadline) {
                    mainHandler.postDelayed(this, 150L)
                } else {
                    finish()
                }
            }
        }
        mainHandler.post(check)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}