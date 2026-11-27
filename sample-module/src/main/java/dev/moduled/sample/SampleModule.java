package dev.moduled.sample;

import dev.moduled.api.ConfigSnapshot;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;

/**
 * Модуль-пример (ТЗ §10.1). Показывает авторам обязательный порядок:
 *
 * <ol>
 *   <li>логировать через {@code context.log()} — ID модуля добавляется
 *       автоматически (§22);</li>
 *   <li>читать конфиг из снимка {@code context.config()}, а не из файлов хоста
 *       (§12.6);</li>
 *   <li>не выполнять сеть и тяжёлую работу в {@code onLoad} (§8 RUN-04);</li>
 *   <li>проверять доступность hook backend перед перехватом и честно сообщать
 *       о недоступности (§9.1, §27).</li>
 * </ol>
 */
public final class SampleModule implements TModule {

    /** Ключ конфига: включать ли демонстрационное сообщение. */
    public static final String CONFIG_KEY_ENABLED = "enabled=true";

    private ModuleContext context;
    private volatile boolean loaded;

    @Override
    public void onLoad(ModuleContext context) throws Exception {
        this.context = context;
        context.log().i("SampleModule onLoad: host=" + context.host().packageName()
                + ", процесс=" + context.processName()
                + ", apiVersion=" + context.apiVersion()
                + ", runtime=" + context.runtimeVersion());

        ConfigSnapshot config = context.config();
        String text = config != null && !config.isEmpty() ? config.text() : "";
        boolean enabled = text.contains(CONFIG_KEY_ENABLED);
        context.log().i("Снимок конфига: sha256="
                + (config != null ? config.sha256() : "нет")
                + ", демонстрационный режим=" + enabled);

        if (context.hooks() != null && context.hooks().isAvailable()) {
            context.log().i("Hook backend доступен: " + context.hooks().backendName()
                    + " (пример ничего не перехватывает)");
        } else {
            context.log().w("Hook backend недоступен: модуль работает без перехвата методов");
        }
        loaded = true;
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.log().i("SampleModule onUnload (best-effort, §3.2)");
        }
        loaded = false;
    }

    public boolean isLoaded() {
        return loaded;
    }
}
