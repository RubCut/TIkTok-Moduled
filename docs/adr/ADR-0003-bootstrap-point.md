# ADR-0003: точка запуска runtime — Application через manifest (§7.2 PAT-08)

**Статус:** принято.

**Решение.** Патчер заменяет `android:name` у `<application>` на
`dev.moduled.runtime.RuntimeApplication` и добавляет `SafeModeRecoveryActivity`
(exported=false). `RuntimeApplication`:

1. создаёт оригинальный Application-класс хоста (имя берётся из
   `assets/tmoduled/adapter.json`, поле `originalApplication`) и передаёт ему
   `attachBaseContext` и все колбэки жизненного цикла;
2. инициализирует `RuntimeBootstrap` только в основном процессе (RUN-10);
3. перехватывает любой сбой инициализации: хост обязан запуститься без модулей.

Альтернативы (ContentProvider-инжексия, инструментация) отклонены: provider
меняет порядок инициализации и виден в manifest как новый компонент, а
Instrumentation требует прав, которых у приложения нет.

**Обоснование.** Application — единственная точка, гарантированно выполняемая
до любого кода хоста в его процессе; делегирование сохраняет поведение
оригинала (PAT-10).

**Последствия.** Если хост сам использует подмену Application (например,
обфусцированный wrapper), оригинальный класс всё равно создаётся и получает
колбэки; порядок «сначала runtime, потом оригинал» зафиксирован в
`attachBaseContext`.
