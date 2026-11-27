package dev.moduled.inapp;

import dev.moduled.api.ModuleContext;
import dev.moduled.api.TModule;

/**
 * Менеджер внутри приложения (ТЗ §17).
 *
 * <ul>
 *   <li>самостоятельный модуль со своим ID, версией и DEX (§17.1);</li>
 *   <li>источник состояния — мост менеджера: тот же снимок, та же модель
 *       «изменения применяются после перезапуска» (§17.2);</li>
 *   <li>при недоступном мосте модуль переходит в read-only с явной причиной
 *       (§17.3), а не имитирует состояние;</li>
 *   <li>в safe mode модуль не загружается: это обеспечивает runtime до вызова
 *       {@code onLoad} (§17.4), поэтому отдельной проверки здесь нет.</li>
 * </ul>
 */
public final class InAppManagerModule implements TModule {

    private ModuleContext context;
    private BridgeClient bridge;
    private volatile boolean readOnly = true;
    private volatile String lastError = "";
    private volatile long generation = -1;

    @Override
    public void onLoad(ModuleContext context) throws Exception {
        this.context = context;
        context.log().i("InAppManagerModule onLoad: процесс=" + context.processName()
                + ", основной=" + context.isMainProcess());

        Object host = context.hostContext();
        if (!(host instanceof android.content.Context)) {
            lastError = "Контекст хоста недоступен: мост недостижим, режим read-only";
            context.log().w(lastError);
            return;
        }
        bridge = new BridgeClient((android.content.Context) host);
        refresh();
    }

    /** Перечитывает состояние из моста (§17.2: источник один). */
    public final void refresh() {
        if (bridge == null) {
            return;
        }
        BridgeClient.BridgeResult snapshot = bridge.getSnapshot();
        if (!snapshot.available) {
            readOnly = true;
            lastError = snapshot.message;
            context.log().w("Мост недоступен (" + snapshot.errorCode + "): " + snapshot.message
                    + " — модуль в read-only (§17.3)");
            return;
        }
        if (!snapshot.ok) {
            readOnly = true;
            lastError = snapshot.errorCode + ": " + snapshot.message;
            context.log().w("Мост ответил ошибкой: " + lastError);
            return;
        }
        readOnly = false;
        lastError = "";
        generation = snapshot.bundle != null
                ? snapshot.bundle.getLong(BridgeClient.KEY_GENERATION, -1) : -1;
        boolean stale = snapshot.bundle != null
                && snapshot.bundle.getBoolean(BridgeClient.KEY_STALE, false);
        context.log().i("Снимок получен: поколение " + generation
                + (stale ? " (УСТАРЕЛ — изменения могут не отражать фактическое состояние)" : "")
                + ". Изменения применяются после перезапуска хоста.");
    }

    /** Доступен ли мост: false — read-only или ошибка (§17.3). */
    public boolean isReadOnly() {
        return readOnly;
    }

    public String lastError() {
        return lastError;
    }

    public long generation() {
        return generation;
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.log().i("InAppManagerModule onUnload");
        }
        readOnly = true;
    }
}
