package dev.moduled.region;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Конфиг модуля региона (ТЗ §16.2). Формат — простые строки {@code key=value}
 * в UTF-8: конфиг непрозрачен для платформы, но модуль обязан терпимо
 * обрабатывать пустой и частично заполненный файл (CFG-02).
 *
 * <p>Поддерживаемые ключи:</p>
 * <ul>
 *   <li>{@code region} — код региона приложения, например {@code US};</li>
 *   <li>{@code language} — язык приложения, например {@code en};</li>
 *   <li>{@code simCountry} — страна SIM, которую приложение сообщает своим
 *       кодом (не реальная SIM);</li>
 *   <li>{@code verifyServerResponse} — {@code true}, чтобы различать локальную
 *       подмену и ответ сервера (§16.3).</li>
 * </ul>
 */
public final class RegionConfig {

    public static final String KEY_REGION = "region";
    public static final String KEY_LANGUAGE = "language";
    public static final String KEY_SIM_COUNTRY = "simCountry";
    public static final String KEY_VERIFY_SERVER = "verifyServerResponse";

    public final String region;
    public final String language;
    public final String simCountry;
    public final boolean verifyServerResponse;
    /** Ключи, которые модуль не знает: показываются в диагностике, не отбрасываются молча. */
    public final Map<String, String> unknownKeys;

    private RegionConfig(String region, String language, String simCountry,
                         boolean verifyServerResponse, Map<String, String> unknownKeys) {
        this.region = region;
        this.language = language;
        this.simCountry = simCountry;
        this.verifyServerResponse = verifyServerResponse;
        this.unknownKeys = unknownKeys;
    }

    public static RegionConfig defaults() {
        return new RegionConfig("", "", "", false, new LinkedHashMap<>());
    }

    public boolean isEmpty() {
        return region.isEmpty() && language.isEmpty() && simCountry.isEmpty();
    }

    /** Java-локаль, соответствующая конфигу; при пустых значениях — default. */
    public Locale locale() {
        if (region.isEmpty() && language.isEmpty()) {
            return Locale.getDefault();
        }
        return new Locale(language.isEmpty() ? Locale.getDefault().getLanguage() : language,
                region.isEmpty() ? "" : region);
    }

    public static RegionConfig parse(String text) {
        Map<String, String> unknown = new LinkedHashMap<>();
        String region = "";
        String language = "";
        String sim = "";
        boolean verify = false;
        if (text != null) {
            for (String rawLine : text.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    unknown.put(line, "");
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                switch (key) {
                    case KEY_REGION:
                        region = normalizeRegion(value);
                        break;
                    case KEY_LANGUAGE:
                        language = normalizeLanguage(value);
                        break;
                    case KEY_SIM_COUNTRY:
                        sim = normalizeRegion(value);
                        break;
                    case KEY_VERIFY_SERVER:
                        verify = "true".equalsIgnoreCase(value) || "1".equals(value);
                        break;
                    default:
                        unknown.put(key, value);
                        break;
                }
            }
        }
        return new RegionConfig(region, language, sim, verify, unknown);
    }

    private static String normalizeRegion(String value) {
        // Код региона: две буквы ISO 3166-1, регистр не значим.
        String trimmed = value.trim();
        if (trimmed.length() == 2 && trimmed.chars().allMatch(Character::isLetter)) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed;
    }

    private static String normalizeLanguage(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.length() <= 3
                && trimmed.chars().allMatch(Character::isLetter)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return trimmed;
    }
}
