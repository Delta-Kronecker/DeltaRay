// -----------------------------------------------------------------------------
// §141 P2.4e — централизованные имена MethodChannel / EventChannel.
//
// Раньше строки каналов (`com.leadaxe.lxbox/methods` и т.д.) дублировались в
// 6+ Dart-файлах + Kotlin-стороне. Опечатка в одном месте → молчаливо мёртвый
// канал (вызовы уходят в никуда). Единый источник здесь; Kotlin-зеркало —
// `MainActivity.kt` / `VpnPlugin.kt` (их строки должны совпадать дословно).
//
// Прецедент стиля: `box_vpn_client/method_names.dart` (`_Methods`).
// -----------------------------------------------------------------------------

class PlatformChannels {
  const PlatformChannels._();

  /// §DeltaRay — фактический install-идентификатор приложения
  /// (`applicationId` в `build.gradle.kts`). Именно его видят
  /// PackageManager/ядро, поэтому он — ключ self-исключения из tun
  /// (`tun_packages.dart`) и подстановки в store-ссылки. Должен совпадать с
  /// `applicationId` один-в-один; отличается от оригинального L×Box, чтобы
  /// приложение ставилось рядом.
  static const packageName = 'com.deltakronecker.deltaray';

  /// §DeltaRay — неймспейс MethodChannel/EventChannel. Осознанно оставлен
  /// легаси-строкой `com.leadaxe.lxbox`: каналы scoped нашим Flutter-движком,
  /// кросс-апп конфликта не создают, а Kotlin-зеркало (`MainActivity.kt` /
  /// `VpnPlugin.kt` / `QuickConnectActivity.kt`) содержит эти литералы
  /// дословно — менять их синхронно ради косметики не нужно.
  static const _ns = 'com.leadaxe.lxbox';

  /// Основной двусторонний канал: config/VPN lifecycle/notification/system-proxy
  /// и пр. (`VpnPlugin.kt handleMethodCall`).
  static const methods = '$_ns/methods';

  /// EventChannel статусов туннеля (broadcast от native → `onStatusChanged`).
  static const statusEvents = '$_ns/status_events';

  /// Утилитарный канал (url_launcher, showToast и пр.).
  static const utils = '$_ns/utils';

  /// Wi-Fi history: MethodChannel + native `onWifiSeen` events (§051).
  static const wifiHistory = '$_ns/wifi_history';

  /// EventChannel для forward'а sing-box core-логов в AppLog (§043).
  /// Отдельный (короткий) неймспейс — зеркало `BoxService.coreLog`.
  static const coreLog = 'lxbox/coreLog';

  /// §122 Фаза 0 — EventChannel'ы нативного CommandClient-канала.
  /// Зеркало `VpnPlugin.CC_*_CHANNEL` + `BoxVpnService.cc*Sink`.
  /// status: скорость/память/трафик (always-on). outbounds: плоский node-list +
  /// delay. groups: дерево групп. connections: снапшот соединений (дельты→аккумулятор).
  static const ccStatus = 'lxbox/cc/status';
  static const ccOutbounds = 'lxbox/cc/outbounds';
  static const ccGroups = 'lxbox/cc/groups';
  static const ccConnections = 'lxbox/cc/connections';
  static const ccDns = 'lxbox/cc/dns'; // §180 — DNS-журнал из ядра (SPEC 018)
}
