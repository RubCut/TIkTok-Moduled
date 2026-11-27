# Отчёт поставки (§26)

## 26.1 Что сделано

**Платформа (полностью в коде, собирается и тестируется CI):**

- `core` — лимиты, Result, SHA-256, ModuleId, JSON parser/writer (строгий:
  неизвестные поля отклоняются), валидаторы манифеста/архива (ZIP с явной
  сверкой CRC)/DEX/конфига, версия-сравнение, crash-политика и отчёты, снимок
  и его кодек, ModulePaths, AtomicFile (temp→fsync→rename), контракт моста,
  descriptor v1, политика URL (только HTTPS, редиректы перепроверяются),
  индекс каталога.
- `module-api` — контракт `apiVersion: 1`: TModule, ModuleContext,
  ConfigSnapshot, ModuleLogger, HookApi/HookResult/HookHandle, HostInfo,
  RestartResult.
- `runtime` — RuntimeApplication (делегирует оригинальному Application,
  PAT-10), RuntimeBootstrap (идемпотентный, главный процесс), ModuleLoader
  (детерминированный порядок, safe mode блокирует всё, RUN-06..08),
  DexImporter (SHA-256 + read-only для Android 14), SnapshotSync через мост с
  кешем, SafeModePolicy/RecoveryActivity, CrashReporter + проверка handler
  (B-001 контур), RuntimeLog с ротацией 5 MiB, hook-слой
  (HookBackend/HookRegistry/HookContext).
- `patcher` — AXML reader/writer (пул строк, resource map, вставка атрибутов),
  ApkAnalyzer, CompatibilityProfile (schemaVersion 1), AbiSupport,
  EntryPointAnalysis (PAT-08), ConflictResolver (детерминированный:
  KEEP/ADD/RENAME/REPLACE/REJECT), ManifestEditor (идемпотентный PAT-11),
  PatchPlan, RuntimePayload (DEX+adapter.json+marker+native),
  SigningKeys (RSA 4096 PKCS12, production запрещён — ADR-0002),
  RepatchDetector, PatcherEngine (repack STORED+CRC → apksig v1+v2+v3 →
  независимая verify → атомарный move в /storage/emulated/0/tmoduled).
- `manager` (TModules) — хранилище модулей (staging→verify→publish, откат,
  замены/смены ключа с подтверждением), SnapshotStore (поколения,
  candidate→active→previous), SettingsStore, RepositoryStore (кеши, ошибки не
  затираются), BridgeProvider + BridgeGuard (проверка UID/сертификата,
  read-only openFile), CatalogClient/UrlFetcher, 9 MD3-экранов:
  главная, патчер, модули, детали модуля, редактор конфига (моноширинный,
  черновик в ViewModel + onSaveInstanceState, откат при ошибке записи),
  каталог, доступы (diff последнего патча), диагностика (B-001 раздел),
  настройки (тема/приватность/автокопирование crash).
- `standalone-ui` — ThemeApplier, Formatters, Dialogs
  (confirmWithConsequences, confirmReplaceModule, unsavedChanges,
  showTrustedDexWarning), EmptyState, Insets, Clipboard.

**Модули:**

- `sample-module` — эталонный пример с JVM-тестами контракта;
- `region-module` — комплектный модуль региона (§16): конфиг key=value
  (region/language/simCountry/verifyServerResponse, неизвестные ключи
  логируются), локальная подмена уровня приложения, различение локальной
  подмены и ответа сервера (verifyServerResponse), честное предупреждение о
  неподдерживаемой версии хоста (§16.5), ограничения зафиксированы (§16.4:
  без IP/SIM/GPS/региона аккаунта);
- `inapp-manager` — менеджер внутри хоста (§17): мост как единственный
  источник состояния, read-only при недоступном мосте (§17.3), поколение/
  staleness.

**Инфраструктура:**

- `demo-host` — тестовый хост с отметкой «ТЕСТОВЫЙ ХОСТ» (§26.2), детекция
  runtime отражением;
- `gradle/module-package.gradle` — общий плагин сборки модулей (compileOnly
  module-api+android.jar, D8 из build-tools, ZIP ровно из 2 записей);
- manager собирает runtime DEX и комплектные модули в свои assets
  (bundleRuntimeDex/bundleModuleZips): патчер берёт payload из assets (PAT-05),
  после успешного патча предлагается модуль региона (§16.1);
- `site/` — каталог GitHub Pages (index.html + catalog.json, схема
  проверяется CI-workflow `Pages`);
- `.github/workflows/ci.yml` — единственная сборка: ciTest, ciAssembleDebug,
  ciLint, артефакты APK/ZIP, failure-репорт в аннотации check-run;
- `docs/` — архитектура, 7 ADR, методика НФТ, модерация каталога, B-001,
  руководство автора модуля, этот отчёт.

## 26.2 Как проверить (методика приёмки на эмуляторе)

Реального TikTok в поставке нет; полная приёмка (§25) выполняется на
эталонном устройстве (ADR-0006). Доступная проверка:

1. **Сборка:** CI-джоб зелёный; артефакты `build-outputs`: APK `manager`,
   APK `demo-host`, ZIP трёх модулей.
2. **Установка:** `adb install manager-debug.apk demo-host-debug.apk`.
3. **Патч демо-хоста:** TModules → Патчер → выбрать demo-host APK → план
   (профиль, точка входа `DemoHostApplication` → RuntimeApplication, ABI) →
   патч → подпись разработческим ключом → verify → результат в
   `/storage/emulated/0/tmoduled`. Повторный патч того же входа идемпотентен
   (PAT-11), записи платформы заменяются (REPLACE).
4. **Модули:** установить `sample-module.zip` из файла → статус «выключен» →
   включить → снимок опубликован (поколение +1) → «Перезапустить хост» в
   демо-хосте недоступен напрямую: перезапуск = force-stop + старт; баннер
   демо-хоста показывает `RuntimeBootstrap` как доступный, диагностика —
   поколение снимка и состояния модулей.
5. **Safe mode:** 3 сбоя загрузки подряд (можно форсировать модулем, падающим
   в onLoad) → safe mode → SafeModeRecoveryActivity → подтверждение сброса в
   менеджере (метод моста `resetSafeMode`).
6. **B-001 контур:** экран диагностики показывает состояние handler;
   `CrashReports.verifyHandlerStillInstalled` покрыт тестами.

## 26.3 Ограничения первой поставки (честно)

- **B-001 не закрыт** (нет реального stack trace) — см. `docs/B-001.md`;
- **native hook backend** (перехват ART через .so) не реализован: контракт и
  диагностика есть, `hooks().isAvailable()` честно возвращает false, патч без
  `.so` возможен и предупреждает в плане (ADR-0001, §27);
- **НФТ не замерены** (нет эталонного устройства) — методика готова
  (`docs/NFR_METHOD.md`);
- **descriptor'ы в каталоге** появятся с первым релизом (скрипт генерации
  готов: `.github/scripts/publish-descriptors.py`);
- production-подпись намеренно запрещена (ADR-0002);
- лицензия не выбрана (ADR-0007).

## 26.4 Артефакты

- `manager-debug.apk` — TModules;
- `demo-host-debug.apk` — тестовый хост;
- `sample-module.zip`, `region-module.zip`, `inapp-manager.zip` — пакеты
  модулей (ровно module.json + classes.dex);
- внутри APK менеджера: `assets/tmoduled/runtime-classes.dex` (payload
  патчера) и `assets/tmoduled/bundled/*.zip` (комплектные модули).

## 26.5 Что нужно от заказчика для завершения

1. Реальный stack trace B-001 + эталонное устройство с TikTok (ADR-0006);
2. решение по native hook backend (§27);
3. решение по production-подписи (ADR-0002) и лицензии (ADR-0007);
4. подтверждение адреса каталога (ADR-0005).
