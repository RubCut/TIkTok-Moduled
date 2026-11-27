package dev.moduled.ui;

/**
 * Форматирование значений для экранов (ТЗ §21.2, §22).
 * Класс чистый: проверяется JVM-тестами без Android.
 */
public final class Formatters {

    private Formatters() {
    }

    /** Возраст снимка или события в понятных человеку единицах. */
    public static String humanAge(long ageMs) {
        if (ageMs < 0) {
            return "неизвестно";
        }
        long seconds = ageMs / 1000;
        if (seconds < 5) {
            return "только что";
        }
        if (seconds < 60) {
            return seconds + " с назад";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " мин назад";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " ч назад";
        }
        long days = hours / 24;
        return days + " дн назад";
    }

    /** Размер в байтах — человекочитаемо, но без потери смысла лимитов. */
    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "неизвестно";
        }
        if (bytes < 1024) {
            return bytes + " Б";
        }
        double value = bytes;
        String[] units = {"КиБ", "МиБ", "ГиБ"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String formatted = value >= 100
                ? String.format(java.util.Locale.ROOT, "%.0f", value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
        return formatted + " " + units[unit];
    }

    /** Точное число байтов всегда показывается рядом: лимит конфига байтовый (CFG-01). */
    public static String sizeWithBytes(long bytes) {
        return humanSize(bytes) + " (" + bytes + " байт)";
    }

    /** Короткий fingerprint SHA-256 для карточки доверия (ТЗ §14.2). */
    public static String shortHash(String sha256) {
        if (sha256 == null || sha256.length() < 16) {
            return "—";
        }
        return sha256.substring(0, 8) + "…" + sha256.substring(sha256.length() - 8);
    }

    /** Подпись колонки хеша с разделителями каждые 8 символов — для сверки глазами. */
    public static String groupedHash(String sha256) {
        if (sha256 == null || sha256.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sha256.length(); i += 8) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(sha256, i, Math.min(i + 8, sha256.length()));
        }
        return sb.toString();
    }

    /** Текст состояния «ожидает перезапуска» (ТЗ §11, §12 CFG-09). */
    public static String pendingRestart(boolean pending) {
        return pending ? "Применится после перезапуска" : "";
    }

    /** Доля выполненной работы — только реальная, без фиктивных процентов (ТЗ §7.5). */
    public static String progress(int done, int total, String stageName) {
        if (total <= 0) {
            return stageName;
        }
        int percent = (int) Math.floor(100.0 * done / total);
        return stageName + " — " + percent + "% (" + done + "/" + total + ")";
    }
}
