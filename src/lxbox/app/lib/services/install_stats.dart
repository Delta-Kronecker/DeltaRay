import 'package:http/http.dart' as http;

import 'app_log.dart';
import 'project_links.dart';
import 'settings_storage.dart';

/// §DeltaRay — одноразовый «маячок» установки.
///
/// При **самом первом** запуске приложение скачивает служебный ассет
/// [ProjectLinks.installStats] из GitHub Releases и сразу его выбрасывает.
/// Файл нигде не сохраняется и не используется приложением — единственный
/// смысл в том, что GitHub инкрементит счётчик скачиваний этого ассета, и
/// автор по нему видит, сколько людей поставило приложение.
///
/// Свойства:
/// - ровно один раз за установку (persist-флаг [_flagKey]);
/// - download-and-discard: тело ответа скачивается и не читается;
/// - fire-and-forget: не блокирует старт, ошибки глушатся в [AppLog];
/// - флаг ставится только при успешном HTTP 200 — если на первом запуске
///   сети не было, попытка повторится на следующем, а не потеряется.
///
/// Privacy: запрос уходит на GitHub, который видит IP устройства, как и
/// любой другой сетевой поход приложения (обновление подписок и т.п.).
class InstallStats {
  InstallStats._();

  static const _flagKey = 'install_stats_v1';
  static const _httpTimeout = Duration(seconds: 15);
  static const _userAgent = 'DeltaRay';

  /// Вызывается один раз на старте (см. `main.dart`). Идемпотентна.
  static Future<void> maybeReportFirstRun() async {
    try {
      final done = await SettingsStorage.getVar(_flagKey, '0');
      if (done == '1') return;
      final ok = await _download();
      if (ok) await SettingsStorage.setVar(_flagKey, '1');
    } catch (e) {
      AppLog.I.warning('InstallStats: $e');
    }
  }

  /// Скачивает и отбрасывает тело. Возвращает `true` на HTTP 200.
  static Future<bool> _download() async {
    try {
      final resp = await http.get(
        Uri.parse(ProjectLinks.installStats),
        headers: {'User-Agent': _userAgent},
      ).timeout(_httpTimeout);
      AppLog.I.info('InstallStats: HTTP ${resp.statusCode}');
      return resp.statusCode == 200;
    } catch (e) {
      AppLog.I.warning('InstallStats: $e');
      return false;
    }
  }
}
