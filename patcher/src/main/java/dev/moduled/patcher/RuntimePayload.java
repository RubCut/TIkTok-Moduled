package dev.moduled.patcher;

import dev.moduled.core.Result;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Полезная нагрузка платформы, встраиваемая в APK (ТЗ §7.2 PAT-05):
 * runtime DEX, native hook backend по ABI, метаданные адаптера.
 *
 * <p>На устройстве содержимое берётся из assets менеджера; в тестах — из
 * каталога. И там и там структура одинакова, поэтому план патча не зависит от
 * источника.</p>
 */
public final class RuntimePayload {

    /** Путь DEX внутри APK. */
    public static final String ENTRY_DEX = "assets/tmoduled/runtime-classes.dex";
    /** Метаданные адаптера: версия хоста, ABI, точка запуска (PAT-01). */
    public static final String ENTRY_ADAPTER = "assets/tmoduled/adapter.json";
    /** Маркер платформы: по нему распознаётся повторный патч (PAT-11). */
    public static final String ENTRY_MARKER = "assets/tmoduled/platform.marker";

    public final File dexFile;
    /** ABI → файл native hook backend. */
    public final Map<String, File> nativeLibraries;
    public final String runtimeVersion;
    public final int runtimeApiVersion;
    /** Имя адаптера точки входа (для adapter.json, PAT-08). */
    public final String adapterName;

    private RuntimePayload(File dexFile, Map<String, File> nativeLibraries,
                           String runtimeVersion, int runtimeApiVersion, String adapterName) {
        this.dexFile = dexFile;
        this.nativeLibraries = Collections.unmodifiableMap(new LinkedHashMap<>(nativeLibraries));
        this.runtimeVersion = runtimeVersion;
        this.runtimeApiVersion = runtimeApiVersion;
        this.adapterName = adapterName;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Проверка полноты перед планированием: без DEX патч невозможен. */
    public Result<Void> validate() {
        if (dexFile == null || !dexFile.isFile()) {
            return Result.fail("PAYLOAD_NO_DEX", "DEX runtime отсутствует: " + dexFile);
        }
        return Result.ok(null);
    }

    /**
     * ABI, для которых нет native hook backend (PAT-05). Это НЕ блокер: runtime
     * честно сообщает о недоступности перехвата в диагностике (RUN-02, §27),
     * а патч остаётся возможным для модулей без хуков.
     */
    public List<String> missingNativeAbis(List<String> requiredAbis) {
        List<String> missing = new ArrayList<>();
        for (String abi : requiredAbis) {
            File library = nativeLibraries.get(abi);
            if (library == null || !library.isFile()) {
                missing.add(abi);
            }
        }
        return missing;
    }

    /** Записи, которые будут добавлены в APK, в порядке имён (детерминизм PAT-06). */
    public List<String> entryNames(List<String> keptAbis) {
        List<String> names = new ArrayList<>();
        names.add(ENTRY_ADAPTER);
        names.add(ENTRY_DEX);
        names.add(ENTRY_MARKER);
        for (String abi : keptAbis) {
            File library = nativeLibraries.get(abi);
            if (library != null) {
                names.add("lib/" + abi + "/libtmoduled-hook.so");
            }
        }
        Collections.sort(names);
        return names;
    }

    public static final class Builder {
        private File dexFile;
        private final Map<String, File> nativeLibraries = new LinkedHashMap<>();
        private String runtimeVersion = "0.1.0";
        private int runtimeApiVersion = 1;
        private String adapterName = "tiktok-generic";

        public Builder dex(File value) { this.dexFile = value; return this; }
        public Builder adapterName(String value) { this.adapterName = value; return this; }
        public Builder nativeLibrary(String abi, File value) {
            this.nativeLibraries.put(abi, value);
            return this;
        }
        public Builder runtimeVersion(String value) { this.runtimeVersion = value; return this; }
        public Builder runtimeApiVersion(int value) { this.runtimeApiVersion = value; return this; }

        public RuntimePayload build() {
            return new RuntimePayload(dexFile, nativeLibraries,
                    runtimeVersion, runtimeApiVersion, adapterName);
        }
    }
}
