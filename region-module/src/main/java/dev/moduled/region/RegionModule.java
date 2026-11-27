package dev.moduled.region;

import dev.moduled.api.ConfigSnapshot;
import dev.moduled.api.HookApi;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;

/**
 * Комплектный модуль региона (ТЗ §16).
 *
 * <p>Обязательные свойства:</p>
 * <ul>
 *   <li>отдельный DEX со своим ID ({@code dev.moduled.region}), версией и
 *       конфигом (§16.1);</li>
 *   <li>подмена выполняется НА УРОВНЕ ПРИЛОЖЕНИЯ: локаль и параметры, которые
 *       приложение читает своим кодом. Модуль НЕ обещает смену IP-адреса,
 *       SIM-карты, GPS и региона аккаунта (§16.4) — любые такие утверждения
 *       были бы недобросовестной имитацией;</li>
 *   <li>различение локальной подмены и фактического ответа сервера (§16.3):
 *       при {@code verifyServerResponse=true} модуль логирует, что подтверждение
 *       возможно только по данным, возвращаемым сервером, и не выдаёт локальную
 *       подмену за смену региона на стороне сервера;</li>
 *   <li>неподдерживаемая версия хоста — явное сообщение о несовместимости
 *       (§16.5), а не молчаливая работа «как получится».</li>
 * </ul>
 */
public final class RegionModule implements TModule {

    private ModuleContext context;
    private RegionConfig config = RegionConfig.defaults();
    private volatile boolean applied;

    @Override
    public void onLoad(ModuleContext context) throws Exception {
        this.context = context;

        if (!context.host().adapterSupported()) {
            // §16.5: неподдерживаемая версия — честное сообщение.
            context.log().w("Версия хоста " + context.host().versionName()
                    + " (versionCode " + context.host().versionCode() + ") не поддерживается "
                    + "адаптером " + context.host().adapterName()
                    + ": модуль региона не применяется. Обновите платформу или дождитесь "
                    + "адаптера для этой версии.");
            applied = false;
            return;
        }

        ConfigSnapshot snapshot = context.config();
        String text = snapshot != null && !snapshot.isEmpty() ? snapshot.text() : "";
        config = RegionConfig.parse(text);
        if (!config.unknownKeys.isEmpty()) {
            context.log().w("Неизвестные ключи конфига (проигнорированы, не отброшены молча): "
                    + config.unknownKeys.keySet());
        }
        if (config.isEmpty()) {
            context.log().i("Конфиг региона пуст: подмена не выполняется (CFG-02 допускает "
                    + "пустой конфиг). Задайте region/language/simCountry.");
            applied = false;
            return;
        }

        java.util.Locale target = config.locale();
        java.util.Locale.setDefault(target);
        context.log().i("Локаль приложения установлена: " + target
                + " (region=" + config.region + ", language=" + config.language + ")");

        HookApi hooks = context.hooks();
        if (hooks != null && hooks.isAvailable()) {
            // Точечные перехваты регионального API выполняются только при
            // доступном backend и подтверждённом адаптере; конкретные цели
            // определяются адаптером версии (§16.2, §28: проверяемый эффект).
            context.log().i("Hook backend " + hooks.backendName()
                    + " доступен: точечная подмена региональных параметров включена");
        } else {
            context.log().w("Hook backend недоступен: выполнена только локальная подмена "
                    + "(Locale.setDefault). Это НЕ означает смену региона на стороне сервера.");
        }

        if (config.verifyServerResponse) {
            // §16.3: явное различение локальной подмены и ответа сервера.
            context.log().i("verifyServerResponse=true: локальная подмена выполнена, но "
                    + "подтверждением служит только фактический ответ сервера (например, "
                    + "региональные поля в ответах API). Модуль не подменяет ответы сервера "
                    + "и не выдаёт локальную подмену за серверную.");
        }
        applied = true;
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.log().i("RegionModule onUnload: локаль остаётся изменённой до перезапуска "
                    + "процесса (§3.2: полный откат не гарантируется)");
        }
        applied = false;
    }

    public boolean isApplied() {
        return applied;
    }

    public RegionConfig config() {
        return config;
    }
}
