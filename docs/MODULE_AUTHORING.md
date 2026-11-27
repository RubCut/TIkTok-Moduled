# Руководство автора модуля

## Пакет (§10.1)

Модуль — ZIP ровно из двух записей:

```
module.json    — внутренний манифест
classes.dex    — код модуля (D8, min-api 28)
```

Проверяется при установке: состав архива, манифест, заголовок DEX, размер
(архив ≤ 64 MiB, DEX ≤ 64 MiB, распаковано ≤ 128 MiB). `archiveSha256` в
descriptor — SHA-256 ТОЧНЫХ байтов ZIP и не хранится внутри архива (§10.2).

## Манифест module.json

```json
{
  "id": "org.author.example",
  "name": "Пример модуля",
  "description": "Что модуль делает, честно.",
  "version": "1.2.0",
  "versionCode": 12,
  "apiVersion": 1,
  "entrypoint": "org.author.example.MainModule",
  "targetPackages": ["com.zhiliaoapp.musically"]
}
```

- `id`: `[a-z0-9._]`, первая буква, ≤ 128 символов, постоянен между версиями;
- `apiVersion`: **1** и только 1 — контракт SDK фиксирован (§9.4);
- `versionCode`: целое, монотонно растёт; обновления и откат сравниваются по
  нему (§13.3);
- неизвестные поля отклоняются (`MANIFEST_UNKNOWN_FIELD`).

## Точка входа

Класс из `entrypoint` реализует `dev.moduled.api.TModule`:

```java
public final class MainModule implements TModule {
    @Override
    public void onLoad(ModuleContext context) throws Exception {
        context.log().i("загрузка");                    // лог с ID модуля
        ConfigSnapshot config = context.config();       // снимок конфига
        if (context.hooks().isAvailable()) {            // честно о backend
            // ...перехват...
        }
    }
}
```

Правила runtime (§8), которые обязан учитывать автор:

- `onLoad` вызывается один раз на процесс, только в основном процессе, только
  после проверок (safe mode, снимок, манифест, DEX);
- исключение ДО установки хуков блокирует только ваш модуль (RUN-07);
- исключение ПОСЛЕ установки хуков не откатывает их (RUN-08) — хуки живут до
  перезапуска процесса;
- выключение модуля действует на следующий запуск (RUN-10);
- сети и тяжёлой работы в `onLoad` быть не должно (RUN-03/RUN-04);
- отдельный ClassLoader не является sandbox: код выполняется с правами процесса
  хоста (§14.1).

## Конфиг (§12)

Конфиг — непрозрачный для платформы текстовый файл ≤ 1 MiB. Модуль получает
его как снимок (`ConfigSnapshot.text()/bytes()/sha256()/generation()`).
Локальные изменения конфига самим модулем НЕ синхронизируются обратно в
менеджер (CFG-10): источник истины — файл модуля в хранилище менеджера.
Изменения применяются после перезапуска хоста (CFG-09).

## Публикация в каталоге

1. Соберите ZIP (`gradle/module-package.gradle` делает это автоматически).
2. Сгенерируйте descriptor (`.github/scripts/publish-descriptors.py`):
   downloadUrl (HTTPS), archiveSha256, автор, источник.
3. Модерация — `docs/CATALOG_MODERATION.md`.
