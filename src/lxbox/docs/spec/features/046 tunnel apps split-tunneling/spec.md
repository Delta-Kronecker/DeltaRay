# 046 — Tunnel apps (OS-level split-tunneling)

| Поле | Значение |
|------|----------|
| Статус | Implemented (targeting v1.7.1) |
| Дата | 2026-05-08 |
| Связанные spec'ы | [`030 custom routing rules`](../030%20custom%20routing%20rules/spec.md) — co-existing layer (routing-уровень внутри sing-box vs OS-уровень здесь); [`tasks/059-routing-v1-superseded`](../../tasks/059-routing-v1-superseded/spec.md) — исторический UI на Routing-экране; [`031 debug api`](../031%20debug%20api/spec.md) — экспонирует CRUD endpoints |
| Затронутые файлы | `app/lib/services/settings_storage.dart`, `app/lib/services/builder/post_steps.dart`, `app/lib/services/builder/build_config.dart`, `app/lib/screens/routing_screen.dart`, `app/lib/screens/tun_apps_tab.dart` (новый), `app/lib/services/debug/handlers/settings.dart`, `docs/STORAGE.md`, тесты |

## Цель

Дать юзеру стандартный Android-механизм split-tunneling — управлять списком приложений, чьи пакеты идут через VPN-tun, и тех, что идут мимо (через cellular/wifi напрямую). Это OS-уровень, **до** sing-box: пакеты исключённых приложений физически не попадают в tun fd и не видны нашему routing engine.

Закрывает класс юзкейсов:

1. **Российский банкинг работает плохо через VPN** — disclude `ru.tinkoff.investing`, `ru.sberbank.online` etc → они идут direct по cellular, GeoIP-проверки backend'ов не валятся.
2. **Игры нуждаются в прямом канале** — disclude мобильные игры (PUBG, Genshin) → меньше latency, нет VPN-related rate limits.
3. **«Только Telegram через VPN»** — allow-list `org.telegram.messenger` → весь остальной трафик direct.
4. **Privacy-узкий VPN** — allow-list только browser → остальные apps не туннелируются.

## Архитектура — три слоя

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: Sing-box config (наш JSON)                          │
│   inbound[type=tun]:                                         │
│     "include_package": ["pkg1", ...]   ← если mode=allow     │
│     "exclude_package": ["pkg1", ..., "com.leadaxe.lxbox"]    │
│                                        ← если mode=deny      │
│     "exclude_package": ["com.leadaxe.lxbox"] ← mode=off      │
└─────────────────────────────────────────────────────────────┘
              ↓ libbox читает config, exposes через PlatformInterface
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: libbox TunOptions.{include,exclude}Package          │
└─────────────────────────────────────────────────────────────┘
              ↓ Kotlin уже умеет (BoxVpnService:557-560)
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Android VpnService.Builder                          │
│   .addAllowedApplication(pkg)  /  .addDisallowedApplication  │
│   .establish() → tun fd с per-UID kernel rules               │
└─────────────────────────────────────────────────────────────┘
```

**Native слой 3 уже работает** (см. `BoxVpnService.kt:557-560`) — итерирует `options.includePackage`/`excludePackage`. Не трогаем.

Добавляем только: storage shape + builder transformation + UI tab + Debug API endpoints.

## Storage shape

В `lxbox_settings.json` новый top-level ключ:

```jsonc
"tun_apps": {
  "mode": "off" | "allow" | "deny",
  "packages": ["com.example.app", "ru.tinkoff.investing", ...]
}
```

**Семантика** (обновлено: self-исключение, см. ниже «Инвариант: self вне tun»):

| `mode` | sing-box config | Эффект |
|---|---|---|
| `"off"` | `tun.exclude_package = ["com.leadaxe.lxbox"]` | Все приложения через tun, **КРОМЕ** самого L×Box/ZeroDPI |
| `"allow"` | `tun.include_package = packages` (self НЕ дописывается) | **Только** перечисленные через tun. Остальные — direct (cellular/wifi), включая наш UID (нет в whitelist = вне tun) |
| `"deny"` | `tun.exclude_package = packages + self` | Все КРОМЕ перечисленных через tun. Перечисленные + self — direct |

**Default для existing юзеров:** `{mode: "off", packages: []}` — backward-compat по UI, но в config `off` теперь пишет `exclude_package=[self]` (см. инвариант ниже).

### Инвариант: собственный UID ВСЕГДА вне tun (§046 rev. 2026-09-12)

Merged-сборка несёт ZeroDPI-relay (встроенный, `src/zerodpi`) как **второй native-процесс с тем же UID отцом-апа** (`com.leadaxe.lxbox`). При proxy-режиме без tun это неважно, но в VPN-режиме чинителем loop'а:

- egress ZeroDPI (socket к реальному хосту) **не** защищён `VpnService.protect()` (это отдельный процесс — parent не может reach его fd'ы);
- если такой socket попадёт в tun, он вернётся в sing-box → outbound → снова `127.0.0.1:44444` (ZeroDPI) → новый egress → снова tun → **бесконечный loop**: VPN-режим не туннелирует НИ ОДНОГО приложения (proxy-режим работает — tun'а нет).

⇒ **Инвариант §046: self всегда исключён из tun** (kernel/netd-уровень), независимо от mode:
- `allow` — self осознанно отсутствует в `include_package`. Нет в whitelist = netd выводит наш UID из tun;
- `off`/`deny` — self принудительно в `exclude_package`.

`include` + `exclude` в одном tun запрещены (Android `Builder.addAllowedApplication`+`addDisallowedApplication` → `UnsupportedOperationException`), поэтому self живёт ровно в одном из полей. **`§124` override (native `buildOverrideOptions` дописывал self в `includePackage` в allow-режиме) УДАЛЁН** — он ломал allow (loop) и зеркалился в `config_staleness.dart`.

**Migration:** unconditional одноразовый default-fill на первом `_load()` после upgrade. Не нужен `presets_migrated`-style guard — пустая структура валидна сама по себе.

**Allow-list для `/state/storage` exposure:** `tun_apps` НЕ sensitive — package names exposed без scrubber'а.

## Builder

Новая функция в `post_steps.dart`:

```dart
/// §046 (self-исключение, rev. 2026-09-12): self (com.leadaxe.lxbox) ВНЕ tun
/// всегда — иначе egress встроенного ZeroDPI-relay зацикливается через tun.
void applyTunPackages(Map<String, dynamic> config, TunAppsConfig tunApps) {
  final inbounds = config['inbounds'] as List?;
  if (inbounds == null) return;
  
  final tun = inbounds.firstWhere(
    (i) => (i as Map)['type'] == 'tun',
    orElse: () => null,
  );
  if (tun == null) return;

  final self = PlatformChannels.packageName;

  if (tunApps.mode == 'allow') {
    if (tunApps.packages.isEmpty) return;
    // self НЕ добавляем: нет в whitelist = netd выводит наш UID из tun.
    (tun as Map)['include_package'] = List<String>.from(tunApps.packages);
    return;
  }
  if (tunApps.mode == 'off') {
    // Всё через tun КРОМЕ нас. Stale `packages` от прежнего режима игнорируем.
    (tun as Map)['exclude_package'] = [self];
    return;
  }
  // deny: список юзера + self (без дубликата).
  final packages = [
    ...tunApps.packages,
    if (!tunApps.packages.contains(self)) self,
  ];
  (tun as Map)['exclude_package'] = packages;
}
```

**Куда вставить в pipeline (`build_config.dart`):** **после** всех остальных `applyXxx()` — это последний transform tun-inbound.

## UI — четвёртая вкладка `Tunnel apps` в RoutingScreen

```
┌─ Routing ──────────────────────────────────────┐
│  [ Channels · Presets · Rules · Tunnel apps ]  │  ← 4-я tab (было 3)
└─────────────────────────────────────────────────┘

┌─ Tunnel apps ──────────────────────────────⋮──┐
│                                                │
│  Mode:  [ Off · Allow-list · Deny-list ]      │  ← SegmentedButton
│                                                │
│  Off — all apps go through VPN (default)       │  ← description под current mode
│                                                │
│  ⚠ Changes apply after VPN restart             │  ← banner при modified+VPN-up
│                            [Restart VPN now]   │
│  ───────────────────────────────────────────   │
│                                                │
│  Apps in this list (3):           [+ Add app]  │  ← скрыто при mode=off
│                                                │
│  [✓] ru.tinkoff.investing       Тинькофф       │
│  [✓] org.telegram.messenger     Telegram       │
│  [✗] com.android.chrome         Chrome         │
│       (uninstalled — auto-skipped)             │
│                                                │
└────────────────────────────────────────────────┘
```

**Поведение:**

- **Mode SegmentedButton**: 3 опции. Single-select, mutually exclusive.
- **Description meaning под mode**:
  - Off — `All apps go through VPN (default)`
  - Allow-list — `Only selected apps go through VPN. Others bypass via cellular/wifi`
  - Deny-list — `Selected apps bypass VPN. Others go through`
- **`[+ Add app]` button** → существующий `AppPickerScreen` (multi-select, `selected: Set<String>`). Возвращает `List<String>`, replaces `packages`. Disabled при `mode == "off"`.
- **App list inline**: каждая row = checkbox (toggle inclusion в list, **не удаление**) + icon + display-name + package-id. Sort: alphabetical by display name.
- **Uninstalled apps**: native ловит `NameNotFoundException` (уже работает). UI помечает `(uninstalled — auto-skipped)` с greyed-out icon. Юзер может оставить — переустановит app, опять заработает.
- **Restart banner**: показывается когда (а) изменения сохранены **и** (б) tunnel up. Click `[Restart VPN now]` → `home.stop()` + `home.start()` (full teardown, не light reload — `addAllowedApplication` applies только на `establish()`).
- **Overflow menu (⋮)** Tab'а:
  - `Show system apps` (toggle, default OFF) — пробрасывается в AppPicker
  - `Clear all` (показывает confirm dialog: «Remove all N apps from tun_apps list?»)
  - `Help` — opens dialog с объяснением что значит OS-level split

**Tooltip про конфликт с `package_name` rules** (на header-row tab'а, info-icon ⓘ):

> This is OS-level split. A package in **Allow-list** goes through tun and your routing rules apply normally. A package outside the Allow-list (or in Deny-list) bypasses VPN entirely — sing-box doesn't see those packets, and your custom rules with `package_name` won't match.

## Debug API endpoints

```
GET /settings/tun_apps
→ {"mode": "off", "packages": []}

PUT /settings/tun_apps
Content-Type: application/json
{"mode": "deny", "packages": ["ru.tinkoff.investing"]}
→ {"ok": true, "rebuild_needed": true}    ← на каждое изменение
```

PUT validates:
- `mode` ∈ {`"off"`, `"allow"`, `"deny"`} else 400
- `packages` это array of strings (валидные package-names matching `^[a-z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)*$`); else 400
- Дубликаты в array → dedup (idempotent)

Без отдельных per-package `add` / `remove` endpoints — overkill для array shape.

## Edge cases

| Сценарий | Поведение |
|---|---|
| Юзер удалил app из системы — package в `tun_apps.packages` | Native `NameNotFoundException` skip (уже работает). UI greyed-icon + label `(uninstalled)`. Не auto-удаляем (юзер может переустановить) |
| Юзер добавляет `com.leadaxe.lxbox` в Deny-list | Не блокируем добавление (эффект нет — self и так **всегда** исключён из tun, §046 инвариант). Snackbar warning при выборе в picker'е: `L×Box itself doesn't need VPN — adding here has no effect` |
| Юзер переключил mode `allow → deny` или наоборот | `packages` сохраняется (тот же list, разная семантика) |
| Юзер переключил mode на `off` | `packages` сохраняется (на случай возврата). В config'е пишем `exclude_package=[self]`: всё через tun КРОМЕ нас (без tun снова образуется loop ZeroDPI) |
| VPN не запущен на момент save | Banner не показываем. Изменения применятся при следующем старте |
| Light reload (`startOrReloadService`) после save | НЕ перетворяет tun fd — настройки apps **не применяются**. Нужен full teardown. UI явно показывает `Restart VPN`, не `Reload core` |
| `find_process: false` в config'е (`§044 edge case`) | Tun-уровневый split работает независимо от process detection sing-box'а. Нет связи. |
| Config locked (`§037`) | UI banner: `Config is locked for debug — changes saved to storage but not applied until unlock`. Storage пишется, builder skip. |

## STORAGE.md

Новый раздел `## tun_apps — §046` после раздела про `ping_options`:

```markdown
## `tun_apps` — §046

OS-level split-tunneling: какие apps идут через VPN-tun, какие direct.

{ "mode": "off" | "allow" | "deny", "packages": [...] }

mode=off:    sing-box config: tun.exclude_package = [com.leadaxe.lxbox] — все apps через tun, КРОМЕ L×Box/ZeroDPI (§046 self-исключение)
mode=allow:  tun.include_package = packages → ТОЛЬКО эти через tun (self НЕ дописывается)
mode=deny:   tun.exclude_package = packages + self → все КРОМЕ этих и L×Box через tun

Native слой (BoxVpnService.kt:557-560) iterates options и зовёт
VpnService.Builder.addAllowedApplication / addDisallowedApplication.
applies на builder.establish() — нужен full VPN restart, не light reload.

Default для existing юзеров: {mode: "off", packages: []}.
В /state/storage exposed без scrubber'а.
```

## Tests

`app/test/services/builder/post_steps_test.dart` (новый или extend existing):

1. `applyTunPackages mode=off → tun.exclude_package = [self]`
2. `applyTunPackages mode=off + packages non-empty → exclude_package = [self], stale packages ignored (mode wins)`
3. `applyTunPackages mode=allow + empty packages → no changes`
4. `applyTunPackages mode=allow + 2 pkgs → tun.include_package = [pkg1, pkg2]`, **без self** (инвариант)
5. `applyTunPackages mode=deny + 1 pkg → tun.exclude_package = [pkg1, self]`
5a. `applyTunPackages mode=deny, self уже в списке → без дубликата, порядок сохранён`
6. `applyTunPackages no tun-inbound в config → silent no-op`

Storage:
7. `default fill on first load — tun_apps = {off, []}`
8. `setTunApps + getTunApps round-trip`

Debug API:
9. `PUT /settings/tun_apps invalid mode → 400`
10. `PUT /settings/tun_apps invalid package format → 400`
11. `PUT /settings/tun_apps dedup → idempotent`

## План имплементации

1. Эта spec'а (✓)
2. **Storage** — `getTunApps()` / `setTunApps()` + `TunAppsConfig` typed class в `settings_storage.dart`
3. **Builder** — `applyTunPackages()` в `post_steps.dart` + integration в `build_config.dart`
4. **Tests** — `post_steps_test.dart` extend (6 builder cases) + storage round-trip
5. **UI** — `tun_apps_tab.dart` (новый widget) + 4-я Tab в `routing_screen.dart`
6. **Debug API** — `GET/PUT /settings/tun_apps` в `handlers/settings.dart`
7. **STORAGE.md** — раздел `tun_apps`
8. **Spec status → Released** + **CHANGELOG entry**
9. `flutter analyze && flutter test` зелёные

## Acceptance criteria

- [ ] Spec written и approved (этот файл).
- [ ] Storage CRUD работает; default fill на первом load; round-trip тесты.
- [ ] Builder `applyTunPackages` правильно эмитит `include_package`/`exclude_package` в config.tun-inbound; **self всегда вне tun** (allow — не в include; off/deny — в exclude); 7 case-тестов зелёные.
- [ ] Routing tab 4 = `Tunnel apps`. SegmentedButton mode + inline list + AppPicker integration + Show-system-apps overflow menu + Clear-all + Help.
- [ ] Restart banner появляется на modified state + tunnel up; `Restart VPN now` button делает full stop+start.
- [ ] `(uninstalled)` label для apps удалённых из системы.
- [ ] Конфликт-tooltip ⓘ на tab header'е.
- [ ] Debug API endpoints + validation тесты.
- [ ] STORAGE.md обновлён с разделом `tun_apps`.
- [ ] CHANGELOG entry в `[Unreleased]`.
- [ ] `flutter analyze` чистый, `flutter test` зелёный.
- [ ] Smoke-тест на телефоне: mode=deny + Telegram → Telegram идёт через cellular (видно в `Statistics → Connections` что conn'ы Telegram'а **не** проходят через `inbound/tun`).

## Future extensions (post-MVP)

- **Per-app routing с conditional outbound** — гибрид с §030 custom_rules: `package=X → outbound=Y`. Уже частично доступно через rules с `package_name` match, но требует чтобы пакет попал в tun сначала.
- **Profile presets** — pre-defined sets («Russian banking direct», «Games direct», «Browser-only VPN») — one-click apply.
- **Statistics tab integration** — показывать `excluded` apps в Stats screen с плашкой «bypassing VPN» в connections list.
- **Auto-detect** — predefined recommendation: «We detected Tinkoff/Sberbank — recommend deny-list?» при первом launch.
