package dev.moduled.runtime;

/** Версии runtime и контракта (ТЗ §9.2). */
public final class RuntimeVersion {
    private RuntimeVersion() {
    }

    /** Версия runtime-компонента. */
    public static final String VERSION = "0.1.0";

    /** Версия контракта SDK, которую runtime реализует. */
    public static final int API_VERSION = dev.moduled.api.ModuleContext.API_VERSION;

    /** Имя продукта для логов и диагностики. */
    public static final String PRODUCT = "TikTok Moduled runtime";
}
