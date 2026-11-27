package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Выбор ABI при патче (ТЗ §5.1). Патчер включает только те ABI, которые выбраны
 * пользователем и реально присутствуют во входном APK; остальные исключаются,
 * чтобы уменьшить размер и избежать несовместимых native-библиотек.
 */
public final class AbiSupport {

    public static final int ARM64_V8A = 0;
    public static final int ARMEABI_V7A = 1;
    public static final int X86_64 = 2;
    public static final int X86 = 3;

    private static final String[] NAMES = {"arm64-v8a", "armeabi-v7a", "x86_64", "x86"};

    private AbiSupport() {
    }

    public static String name(int abi) {
        return abi >= 0 && abi < NAMES.length ? NAMES[abi] : "unknown(" + abi + ')';
    }

    public static int fromName(String abiName) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(abiName)) {
                return i;
            }
        }
        return -1;
    }

    public static List<String> allNames() {
        List<String> names = new ArrayList<>();
        Collections.addAll(names, NAMES);
        return names;
    }

    /** Пересечение выбранных ABI с фактически присутствующими в APK. */
    public static List<String> resolve(List<Integer> selected, Iterable<String> presentInApk) {
        List<String> resolved = new ArrayList<>();
        for (Integer abi : selected) {
            String name = name(abi);
            for (String present : presentInApk) {
                if (present.equals(name) && !resolved.contains(name)) {
                    resolved.add(name);
                }
            }
        }
        return resolved;
    }

    /**
     * Список lib-записей, которые нужно исключить из APK: все ABI, кроме разрешённых.
     *
     * @return имена записей вида {@code lib/<abi>/<file>.so}
     */
    public static List<String> entriesToExclude(Iterable<String> entryNames, List<String> keepAbis) {
        List<String> excluded = new ArrayList<>();
        for (String name : entryNames) {
            if (!name.startsWith("lib/")) {
                continue;
            }
            String[] parts = name.split("/");
            if (parts.length < 2) {
                continue;
            }
            if (!keepAbis.contains(parts[1])) {
                excluded.add(name);
            }
        }
        return excluded;
    }
}
