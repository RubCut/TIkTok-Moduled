package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Выбор точки запуска runtime по фактическому анализу APK (ТЗ §7.2 PAT-08).
 *
 * <p>Порядок предпочтения: существующее {@code android:name} application класса
 * (его нужно сохранить — PAT-10), иначе стандартный {@code android.app.Application}.
 * Runtime всегда вставляется как {@code Application} через замену имени класса
 * и последующий вызов оригинала, а не через добавление нового компонента: это
 * единственный надёжный способ запустить код в процессе хоста.</p>
 */
public final class EntryPointAnalysis {

    /** Способ вставки, выбранный анализом. */
    public enum Strategy {
        /** В manifest есть пользовательский Application: оборачиваем его. */
        WRAP_EXISTING_APPLICATION,
        /** Пользовательского Application нет: вставляем runtime напрямую. */
        INSERT_APPLICATION,
        /** Manifest не читается: патч невозможен. */
        UNSUPPORTED
    }

    public final Strategy strategy;
    public final String originalApplicationName;
    public final String runtimeClassName;
    public final List<String> requiredPermissions;
    public final List<String> notes;

    public EntryPointAnalysis(Strategy strategy, String originalApplicationName,
                              String runtimeClassName, List<String> requiredPermissions,
                              List<String> notes) {
        this.strategy = strategy;
        this.originalApplicationName = originalApplicationName;
        this.runtimeClassName = runtimeClassName;
        this.requiredPermissions = Collections.unmodifiableList(new ArrayList<>(requiredPermissions));
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
    }

    public static EntryPointAnalysis from(ApkAnalysis analysis) {
        List<String> notes = new ArrayList<>();
        List<String> permissions = new ArrayList<>();
        // Runtime требует INTERNET только если модули сами обращаются к сети;
        // на старте сеть не используется (RUN-03), поэтому разрешение хоста не
        // расширяется: патч не добавляет новых разрешений.
        String original = analysis.applicationName;
        Strategy strategy;
        if (original == null || original.isEmpty()) {
            strategy = Strategy.INSERT_APPLICATION;
            notes.add("В manifest нет android:name у <application>: будет вставлен класс runtime.");
        } else if (original.startsWith("dev.moduled.runtime.")) {
            strategy = Strategy.WRAP_EXISTING_APPLICATION;
            notes.add("В manifest уже указан класс runtime (" + original
                    + ") — вход ранее изменён (PAT-11).");
        } else {
            strategy = Strategy.WRAP_EXISTING_APPLICATION;
            notes.add("Сохраняется существующий Application: " + original
                    + ". Runtime оборачивает его и вызывает оригинал после инициализации.");
        }
        if (analysis.providerAuthorities.contains("dev.moduled.manager.bridge")) {
            notes.add("Authority моста уже объявлена в APK: дубль не создаётся.");
        }
        return new EntryPointAnalysis(strategy, original,
                "dev.moduled.runtime.RuntimeApplication", permissions, notes);
    }

    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Точка запуска: ").append(strategy).append('\n');
        sb.append("  класс runtime: ").append(runtimeClassName).append('\n');
        if (originalApplicationName != null && !originalApplicationName.isEmpty()) {
            sb.append("  оригинальный Application: ").append(originalApplicationName).append('\n');
        }
        sb.append("  добавляемые разрешения: ")
                .append(requiredPermissions.isEmpty() ? "(не добавляются)" : requiredPermissions)
                .append('\n');
        for (String note : notes) {
            sb.append("  примечание: ").append(note).append('\n');
        }
        return sb.toString();
    }
}
