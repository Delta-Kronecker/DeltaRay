part of '../post_steps.dart';

/// Post-step: §046 — OS-level split-tunneling. Подставляет `include_package`
/// или `exclude_package` в `inbound[type=tun]` на основе `tun_apps` storage.
///
/// Mode → sing-box config:
/// - `off`        → `tun.exclude_package = [self]` (все apps через tun, КРОМЕ
///                  самого L×Box/ZeroDPI — см. ниже почему self обязателен)
/// - `allow`      → `tun.include_package = packages` (только эти через tun;
///                  self НЕ дописывается)
/// - `deny`       → `tun.exclude_package = packages + self`
///
/// **Инвариант §046: собственный UID (`com.leadaxe.lxbox`, он же ZeroDPI —
/// отдельный native-процесс без `protect(fd)`) обязан быть ВНЕ tun в любом
/// режиме.** Если egress встроенного ZeroDPI-relay попадёт в tun, сокет
/// вернётся в sing-box → outbound → снова ZeroDPI → бесконечный loop: в
/// VPN-режиме не туннелируется НИ одно приложение (proxy-режим без tun
/// работает). Механизм исключения у ядра/system разный:
/// - `allow`: self осознанно отсутствует в whitelist'е (нет в include = netd
///   выводит наш UID из tun);
/// - `off`/`deny`: self кладём в deny-список.
/// В одной ветке include + exclude запрещены (Android `Builder` бросает
/// `UnsupportedOperationException`), поэтому self живёт ровно в одном из полей.
///
/// libbox потом читает эти поля из config и передаёт в native слой
/// (`BoxVpnService.openTun` → `addAllowedApplication`/`addDisallowedApplication`,
/// ~`BoxVpnService.kt:208-211`). Применяется на `builder.establish()` — нужен
/// FULL VPN restart, light reload не работает.
///
/// Если `mode == allow` но `packages` пустой — silently no-op (нечего apply'ить).
/// Если в config нет tun-inbound — silently no-op (некуда apply'ить).
void applyTunPackages(Map<String, dynamic> config, TunAppsConfig tunApps) {
  final inbounds = config['inbounds'];
  if (inbounds is! List) return;

  Map<String, dynamic>? tun;
  for (final i in inbounds) {
    if (i is Map<String, dynamic> && i['type'] == 'tun') {
      tun = i;
      break;
    }
  }
  if (tun == null) return;

  // Свой пакет исключён из tun всегда (invariant §046 — loop ZeroDPI). В allow
  // его просто НЕТ в include — whitelist'а не досчитываемся, netd выводит наш
  // UID из tun сам.
  final self = PlatformChannels.packageName;

  if (tunApps.isAllow) {
    if (tunApps.packages.isEmpty) return;
    // §046: self НЕ добавляем в include_package. Раньше native-слой
    // (`buildOverrideOptions`) дописывал self в allow-режиме, и собственный
    // egress ZeroDPI зацикливался через tun — баг «VPN-режим не туннелирует
    // ничего». Порядок пользовательских пакетов сохраняем как есть.
    tun['include_package'] = List<String>.from(tunApps.packages);
    return;
  }

  if (tunApps.isOff) {
    // off — весь трафик через tun, КРОМЕ нашего пакета. Список `packages`
    // игнорируем: режим сильнее (в off UI список не редактируется), и stale
    // пакеты от прежнего режима не должны выводить приложения из tun.
    tun['exclude_package'] = <String>[self];
    return;
  }

  // deny: весь трафик через tun КРОМЕ списка пользователя + нашего пакета.
  // Список пользователя не трогаем и не сортируем; self дописываем в конец
  // (если пользователь уже указал self — без дубликата).
  final packages = <String>[
    ...tunApps.packages,
    if (!tunApps.packages.contains(self)) self,
  ];
  tun['exclude_package'] = packages;
}
