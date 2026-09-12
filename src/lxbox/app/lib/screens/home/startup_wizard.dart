import 'package:flutter/widgets.dart';

import '../../vpn/box_vpn_client.dart';
import 'home_dialogs.dart';

/// Единый last-mile движок first-run-онбординга.
///
/// Перенос permission-промптов из онбординга по требованию оператора:
/// notification спрашиваем только в момент ручного старта VPN (см.
/// `HomeScreen._startWithAutoRefresh`), VPN-consent — системный диалог при
/// `VpnService.prepare()` тоже на старте. Шаги battery / add-tile (QS) из
/// онбординга убраны полностью — ручные кнопки в App Settings остаются.
///
/// Здесь остался единственный шаг — согласие на автопроверку обновлений
/// (§395): фоновый запрос к github.com без согласия — anti-feature Tracking
/// у F-Droid.
class StartupWizard {
  StartupWizard(this.context, this.vpn);

  final BuildContext context;
  final BoxVpnClient vpn;

  Future<void> run() async {
    // §395 — автопроверка обновлений: спрашиваем явно (см. doc сверху).
    if (!context.mounted) return;
    await maybeShowUpdateCheckPrompt(context);
  }
}
