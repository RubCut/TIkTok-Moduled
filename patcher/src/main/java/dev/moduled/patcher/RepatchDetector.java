package dev.moduled.patcher;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Распознавание повторного патча (ТЗ §7.2 PAT-11).
 *
 * <p>Признаки: класс runtime в {@code android:name} application, объявленный
 * экран восстановления, asset {@code assets/tmoduled/adapter.json}, маркер
 * runtime в DEX. Любой из них означает, что вход уже изменён платформой.</p>
 */
public final class RepatchDetector {

    public static final class Verdict {
        public final boolean repatched;
        public final List<String> markers;

        Verdict(boolean repatched, List<String> markers) {
            this.repatched = repatched;
            this.markers = markers;
        }

        public String toText() {
            if (!repatched) {
                return "Вход не изменялся платформой: маркеры не найдены.";
            }
            StringBuilder sb = new StringBuilder("Вход уже изменён платформой. Маркеры:\n");
            for (String marker : markers) {
                sb.append("  • ").append(marker).append('\n');
            }
            sb.append("Повторный патч выполняется идемпотентно: правки manifest ")
                    .append("не дублируются, а маркерные записи заменяются новыми версиями.");
            return sb.toString();
        }
    }

    private RepatchDetector() {
    }

    public static Verdict detect(ApkAnalysis analysis, File apk) {
        List<String> markers = new ArrayList<>(analysis.markerEntries);
        if (EntryPointAnalysis.from(analysis).strategy
                == EntryPointAnalysis.Strategy.WRAP_EXISTING_APPLICATION
                && analysis.applicationName.startsWith("dev.moduled.runtime.")) {
            markers.add("android:name application = " + analysis.applicationName);
        }
        if (apk != null && ApkAnalyzer.dexContainsRuntimeMarker(apk)) {
            markers.add(ApkAnalyzer.MARKER_RUNTIME_CLASS + " найден в DEX");
        }
        List<String> unique = new ArrayList<>();
        for (String marker : markers) {
            if (marker != null && !marker.isEmpty() && !unique.contains(marker)) {
                unique.add(marker);
            }
        }
        return new Verdict(!unique.isEmpty(), unique);
    }
}
