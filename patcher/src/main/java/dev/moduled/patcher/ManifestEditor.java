package dev.moduled.patcher;

import dev.moduled.core.Result;
import dev.moduled.patcher.xml.BinaryXmlReader;
import dev.moduled.patcher.xml.BinaryXmlWriter;
import dev.moduled.patcher.xml.XmlDocument;
import dev.moduled.patcher.xml.XmlElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Правка {@code AndroidManifest.xml} (ТЗ §7.2 PAT-08, §15.2).
 *
 * <p>Изменяется ровно то, что нужно для запуска runtime: {@code android:name} у
 * {@code <application>} и объявление экрана восстановления safe mode. Остальное
 * содержимое manifest сохраняется байт в байт, поэтому launcher, authorities,
 * providers и placeholders остаются нетронутыми (PAT-10).</p>
 */
public final class ManifestEditor {

    /** Класс runtime, который вставляется в manifest. */
    public static final String RUNTIME_APPLICATION = "dev.moduled.runtime.RuntimeApplication";

    /** Экран восстановления safe mode (§8 RUN-08, RUN-09). */
    public static final String SAFE_MODE_ACTIVITY = "dev.moduled.runtime.SafeModeRecoveryActivity";

    private ManifestEditor() {
    }

    /** Одна правка manifest для отчёта «до/после» (§15.2). */
    public static final class Change {
        public final String target;
        public final String field;
        public final String before;
        public final String after;

        Change(String target, String field, String before, String after) {
            this.target = target;
            this.field = field;
            this.before = before;
            this.after = after;
        }

        public String toText() {
            return target + " → " + field + ": "
                    + (before == null ? "(отсутствовало)" : before)
                    + " ⟶ " + after;
        }
    }

    public static final class EditResult {
        public final byte[] manifestBytes;
        public final List<Change> changes;

        EditResult(byte[] manifestBytes, List<Change> changes) {
            this.manifestBytes = manifestBytes;
            this.changes = changes;
        }
    }

    /**
     * Применяет правки. Если вход уже изменён (PAT-11), результат содержит
     * пустой список изменений и исходные байты: повторный патч распознаётся, а не
     * дублирует правки.
     */
    public static Result<EditResult> apply(byte[] manifestBytes, String runtimeApplication,
                                          String safeModeActivity) {
        Result<XmlDocument> document = BinaryXmlReader.parse(manifestBytes);
        if (!document.isOk()) {
            return Result.fail(document.errorCode(),
                    "Manifest не читается: " + document.errorMessage());
        }
        XmlElement manifest = document.value().root;
        XmlElement application = manifest.firstChild("application");
        if (application == null) {
            return Result.fail("MANIFEST_NO_APPLICATION",
                    "В manifest нет элемента <application>: точка запуска не определена");
        }
        Result<BinaryXmlWriter> writerResult = BinaryXmlWriter.read(manifestBytes);
        if (!writerResult.isOk()) {
            return Result.fail(writerResult.errorCode(), writerResult.errorMessage());
        }
        BinaryXmlWriter writer = writerResult.value();
        List<Change> changes = new ArrayList<>();
        Map<String, String> before = snapshotOf(document.value());

        String currentApplication = application.stringAttribute("name");
        if (runtimeApplication.equals(currentApplication)) {
            // PAT-11: manifest уже содержит класс runtime — правка не повторяется.
            return Result.ok(new EditResult(manifestBytes.clone(), changes));
        }
        Result<String> set = writer.setStringAttribute(
                new String[] {"manifest", "application"}, "name", runtimeApplication,
                BinaryXmlWriter.RES_ANDROID_NAME);
        if (!set.isOk()) {
            return Result.fail(set.errorCode(), set.errorMessage());
        }
        changes.add(new Change("<application>", "android:name", set.value(), runtimeApplication));

        boolean hasSafeModeActivity = false;
        for (XmlElement activity : application.childrenNamed("activity")) {
            if (safeModeActivity.equals(activity.stringAttribute("name"))) {
                hasSafeModeActivity = true;
                break;
            }
        }
        if (!hasSafeModeActivity) {
            // exported=false и отдельная taskAffinity: экран восстановления не
            // попадает в историю хоста и не доступен другим приложениям (§20).
            Result<Void> added = writer.addElement(new String[] {"manifest", "application"},
                    "activity", new String[][] {
                            {"name", safeModeActivity},
                            {"exported", "false"},
                            {"excludeFromRecents", "true"},
                            {"taskAffinity", ""},
                    });
            if (!added.isOk()) {
                return Result.fail(added.errorCode(),
                        "Не удалось добавить экран восстановления: " + added.errorMessage());
            }
            changes.add(new Change("<application>", "activity " + safeModeActivity,
                    null, safeModeActivity + " (exported=false)"));
        }

        byte[] edited = writer.write();
        // Контрольная перепроверка: итоговый manifest обязан читаться тем же парсером.
        Result<XmlDocument> verify = BinaryXmlReader.parse(edited);
        if (!verify.isOk()) {
            return Result.fail("MANIFEST_EDIT_BROKEN",
                    "После правки manifest не читается: " + verify.errorCode());
        }
        Map<String, String> after = snapshotOf(verify.value());
        // Проверка неизменности критичного: launcher и authorities (PAT-10).
        List<String> lost = new ArrayList<>();
        for (Map.Entry<String, String> entry : before.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().equals(after.get(entry.getKey()))) {
                lost.add(entry.getKey());
            }
        }
        List<String> expectedChanges = new ArrayList<>();
        expectedChanges.add("/manifest[0]/application[0]/@name");
        for (String key : lost) {
            boolean expected = false;
            for (String allowed : expectedChanges) {
                if (key.equals(allowed)) {
                    expected = true;
                    break;
                }
            }
            if (!expected) {
                return Result.fail("MANIFEST_UNEXPECTED_CHANGE",
                        "Правка manifest изменила незапланированное поле: " + key
                                + ". Launcher, authorities и providers обязаны сохраниться.");
            }
        }
        return Result.ok(new EditResult(edited, changes));
    }

    private static Map<String, String> snapshotOf(XmlDocument document) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        collect(document.root, "", snapshot, new LinkedHashMap<>());
        return snapshot;
    }

    /**
     * Ключи снимка устойчивы к добавлению нового элемента в конец: элемент
     * адресуется именем и индексом среди ОДНОИМЁННЫХ соседей, а не позицией
     * среди всех детей. Иначе добавление экрана восстановления «сдвигало» бы
     * ключи и проверка неизменности launcher/authorities давала бы ложный отказ.
     */
    private static void collect(XmlElement element, String path, Map<String, String> out,
                                Map<String, Integer> nameCounters) {
        String counterKey = path + "/" + element.name;
        int siblingIndex = nameCounters.getOrDefault(counterKey, 0);
        nameCounters.put(counterKey, siblingIndex + 1);
        String current = path + "/" + element.name + "[" + siblingIndex + "]";
        for (Map.Entry<String, dev.moduled.patcher.xml.XmlValue> attribute
                : element.attributes.entrySet()) {
            out.put(current + "/@" + attribute.getKey(), attribute.getValue().asString());
        }
        for (XmlElement child : element.children) {
            collect(child, current, out, nameCounters);
        }
    }

    /** Текст diff для экрана патчера (§15.2: патч manifest отдельно от прав модуля). */
    public static String diffText(List<Change> changes) {
        if (changes.isEmpty()) {
            return "Изменений manifest нет: вход уже содержал классы платформы (PAT-11).";
        }
        StringBuilder sb = new StringBuilder("Изменения AndroidManifest.xml:\n");
        for (Change change : changes) {
            sb.append("  • ").append(change.toText()).append('\n');
        }
        return sb.toString();
    }
}
