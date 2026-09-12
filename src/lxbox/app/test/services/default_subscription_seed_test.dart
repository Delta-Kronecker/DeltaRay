import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lxbox/models/server_list.dart';
import 'package:lxbox/services/settings_storage.dart';

// §DeltaRay — сид дефолтной подписки на чистой установке.
//
// Севентя в main() до runApp, поэтому тесты именно хранилища: чистая установка
// (нет ключа `server_lists`) → стартовая подписка; повторный зов — no-op;
// удаление ВСЕХ подписок не воскрешает сид.

void main() {
  late Directory tmp;
  const channel = MethodChannel('plugins.flutter.io/path_provider');

  setUp(() async {
    TestWidgetsFlutterBinding.ensureInitialized();
    tmp = await Directory.systemTemp.createTemp('lxbox_subseed_');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'getApplicationDocumentsDirectory' ||
          call.method == 'getApplicationDocumentsPath') {
        return tmp.path;
      }
      return null;
    });
    SettingsStorage.resetCacheForTesting();
  });

  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    try {
      if (tmp.existsSync()) await tmp.delete(recursive: true);
    } on FileSystemException {
      /* ignore */
    }
  });

  group('default subscription seed', () {
    test('чистая установка: сеется стартовая подписка', () async {
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      final subs = await SettingsStorage.getServerLists();
      expect(subs, hasLength(1));
      final s = subs.single as SubscriptionServers;
      expect(s.name, 'Default');
      expect(s.enabled, isTrue);
      expect(s.url, kDefaultSubscriptionUrl);
    });

    test('идемпотентность: повторный сев не плодит дубли', () async {
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      expect(await SettingsStorage.getServerLists(), hasLength(1));
    });

    test('удаление ВСЕХ подписок не воскрешает сид', () async {
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      await SettingsStorage.saveServerLists([]);
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      expect(await SettingsStorage.getServerLists(), isEmpty);
    });

    test('существующие подписки не трогает', () async {
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      final seeded = await SettingsStorage.getServerLists();
      await SettingsStorage.saveServerLists([
        ...seeded,
        SubscriptionServers(
          id: 'second',
          name: 'Mine',
          enabled: true,
          tagPrefix: '',
          detourPolicy: DetourPolicy.defaults,
          url: 'https://example.com/sub.txt',
        ),
      ]);
      await SettingsStorage.seedDefaultSubscriptionIfNeeded();
      final subs = await SettingsStorage.getServerLists();
      expect(subs, hasLength(2));
      expect(subs.map((e) => e.name), containsAll(['Default', 'Mine']));
    });
  });
}