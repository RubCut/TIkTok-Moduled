package dev.moduled.patcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Детерминированное разрешение конфликтов package / class / resource
 * (ТЗ §7.2 PAT-06).
 *
 * <p>Правило одно и то же на всех платформах и версиях: приоритет отдаётся
 * записям входного APK, добавляемые платформой записи с тем же именем
 * отклоняются, а конфликт фиксируется в плане и показывается пользователю.
 * Порядок разрешения зависит только от имени записи, поэтому результат
 * воспроизводим.</p>
 */
public final class ConflictResolver {

    public enum Kind {
        PACKAGE, CLASS, RESOURCE, NATIVE_LIBRARY, DEX, SIGNATURE_BLOCK
    }

    public enum Decision {
        /** Оставляем запись входного APK, добавляемая отклоняется. */
        KEEP_ORIGINAL,
        /** Добавляем новую запись (конфликта нет). */
        ADD_NEW,
        /** Переименовываем добавляемую запись в уникальное имя. */
        RENAME_ADDED,
        /** Запись платформы заменяется новой версией (повторный патч, PAT-11). */
        REPLACE,
        /** Конфликт неразрешим: патч должен остановиться. */
        REJECT
    }

    public static final class Conflict {
        public final Kind kind;
        public final String name;
        public final Decision decision;
        public final String reason;
        public final String resolvedName;

        Conflict(Kind kind, String name, Decision decision, String reason, String resolvedName) {
            this.kind = kind;
            this.name = name;
            this.decision = decision;
            this.reason = reason;
            this.resolvedName = resolvedName;
        }

        @Override
        public String toString() {
            return kind + " " + name + " -> " + decision
                    + (resolvedName != null ? " as " + resolvedName : "")
                    + (reason != null ? " (" + reason + ')' : "");
        }
    }

    private ConflictResolver() {
    }

    /**
     * Разрешает конфликты между записями входного APK и добавляемыми платформой.
     *
     * @param originalEntries записи входного APK
     * @param addedEntries    записи, которые платформа хочет добавить
     * @return упорядоченный по имени список решений
     */
    public static List<Conflict> resolve(Iterable<String> originalEntries,
                                         Iterable<String> addedEntries,
                                         boolean allowRenaming) {
        Map<String, Kind> originals = new LinkedHashMap<>();
        for (String entry : originalEntries) {
            originals.put(entry, kindOf(entry));
        }
        List<String> added = new ArrayList<>();
        for (String entry : addedEntries) {
            added.add(entry);
        }
        // Детерминизм: сортировка по имени, а не по порядку добавления.
        Collections.sort(added);
        List<Conflict> conflicts = new ArrayList<>();
        for (String entry : added) {
            Kind kind = kindOf(entry);
            if (!originals.containsKey(entry)) {
                conflicts.add(new Conflict(kind, entry, Decision.ADD_NEW, null, null));
                continue;
            }
            if (!allowRenaming) {
                conflicts.add(new Conflict(kind, entry, Decision.KEEP_ORIGINAL,
                        "запись уже присутствует во входном APK", null));
                continue;
            }
            String renamed = uniqueName(entry, originals);
            if (renamed == null) {
                conflicts.add(new Conflict(kind, entry, Decision.REJECT,
                        "не удалось подобрать уникальное имя после 100 попыток", null));
            } else {
                originals.put(renamed, kind);
                conflicts.add(new Conflict(kind, entry, Decision.RENAME_ADDED,
                        "имя занято во входном APK", renamed));
            }
        }
        return conflicts;
    }

    /** Замена собственной записи платформы при повторном патче (PAT-11). */
    public static Conflict replaced(String name) {
        return new Conflict(kindOf(name), name, Decision.REPLACE,
                "повторный патч: запись платформы заменяется новой версией", null);
    }

    /** Конфликт package ID: смена package ID — отдельный профиль (§7.7). */
    public static Conflict resolvePackageId(String originalPackageId, String requestedPackageId) {
        if (originalPackageId.equals(requestedPackageId)) {
            return new Conflict(Kind.PACKAGE, originalPackageId, Decision.KEEP_ORIGINAL,
                    "package ID совпадает с входным APK", null);
        }
        return new Conflict(Kind.PACKAGE, requestedPackageId, Decision.REJECT,
                "смена package ID требует отдельного профиля совместимости; "
                        + "входной APK: " + originalPackageId, null);
    }

    /**
     * Конфликт подписи: показывается до установки (§7.8), а не после.
     *
     * @param existingSignatureFingerprint отпечаток подписи установленного пакета
     *                                     (если известен)
     * @param newSignatureFingerprint      отпечаток подписи, которой будет подписан APK
     */
    public static Conflict resolveSignature(String existingSignatureFingerprint,
                                            String newSignatureFingerprint) {
        if (existingSignatureFingerprint == null || existingSignatureFingerprint.isEmpty()) {
            return new Conflict(Kind.SIGNATURE_BLOCK, newSignatureFingerprint, Decision.ADD_NEW,
                    "установленной подписи не обнаружено", null);
        }
        if (existingSignatureFingerprint.equalsIgnoreCase(newSignatureFingerprint)) {
            return new Conflict(Kind.SIGNATURE_BLOCK, newSignatureFingerprint,
                    Decision.KEEP_ORIGINAL, "подпись совпадает: обновление возможно", null);
        }
        return new Conflict(Kind.SIGNATURE_BLOCK, newSignatureFingerprint, Decision.REJECT,
                "конфликт подписи: установленный пакет подписан "
                        + shortFingerprint(existingSignatureFingerprint) + ", новый — "
                        + shortFingerprint(newSignatureFingerprint)
                        + ". Обновление без удаления невозможно.", null);
    }

    public static Kind kindOf(String entry) {
        if (entry == null || entry.isEmpty()) {
            return Kind.RESOURCE;
        }
        if (entry.startsWith("lib/")) {
            return Kind.NATIVE_LIBRARY;
        }
        if (entry.endsWith(".dex")) {
            return Kind.DEX;
        }
        String upper = entry.toUpperCase(java.util.Locale.ROOT);
        if (upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"))) {
            return Kind.SIGNATURE_BLOCK;
        }
        if (entry.endsWith(".class") || entry.contains("/")) {
            return entry.startsWith("res/") || entry.startsWith("resources.arsc")
                    || entry.endsWith(".png") || entry.endsWith(".xml") && entry.startsWith("res/")
                    ? Kind.RESOURCE : Kind.CLASS;
        }
        return Kind.RESOURCE;
    }

    private static String uniqueName(String entry, Map<String, Kind> taken) {
        int dot = entry.lastIndexOf('.');
        String base = dot > 0 ? entry.substring(0, dot) : entry;
        String extension = dot > 0 ? entry.substring(dot) : "";
        for (int attempt = 1; attempt <= 100; attempt++) {
            String candidate = base + "-tmoduled-" + attempt + extension;
            if (!taken.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() <= 16) {
            return fingerprint == null ? "(нет)" : fingerprint;
        }
        return fingerprint.substring(0, 16) + "…";
    }

    /** Все ли решения приемлемы для продолжения патча. */
    public static boolean hasBlocking(List<Conflict> conflicts) {
        for (Conflict conflict : conflicts) {
            if (conflict.decision == Decision.REJECT) {
                return true;
            }
        }
        return false;
    }
}
