package dev.moduled.core;

import java.nio.file.Path;

/**
 * Безопасное выведение путей в хранилище модулей (ТЗ §5.2, §20.1).
 * ID модуля НИКОГДА не используется как компонент пути без валидации:
 * даже корректный по синтаксису ID дополнительно сверяется с нормализованным
 * путём, чтобы исключить выход за пределы каталога.
 */
public final class ModulePaths {
    private ModulePaths() {
    }

    /**
     * Каталог модуля внутри root. Возвращает Result.fail, если ID недопустим
     * или нормализованный путь выходит за пределы root.
     */
    public static Result<Path> moduleDir(Path root, String moduleId) {
        if (root == null) {
            return Result.fail("PATH_NO_ROOT", "Storage root is null");
        }
        if (!ModuleId.isValid(moduleId)) {
            return Result.fail("PATH_BAD_ID", "Invalid module id: " + moduleId);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path candidate = normalizedRoot.resolve(moduleId).normalize();
        if (!candidate.startsWith(normalizedRoot) || candidate.equals(normalizedRoot)) {
            return Result.fail("PATH_ESCAPE", "Derived path escapes module storage root: " + candidate);
        }
        return Result.ok(candidate);
    }

    public static Result<Path> dexFile(Path root, String moduleId) {
        Result<Path> dir = moduleDir(root, moduleId);
        return dir.isOk() ? Result.ok(dir.value().resolve("classes.dex"))
                : Result.fail(dir.errorCode(), dir.errorMessage());
    }

    public static Result<Path> configFile(Path root, String moduleId) {
        Result<Path> dir = moduleDir(root, moduleId);
        return dir.isOk() ? Result.ok(dir.value().resolve("config.txt"))
                : Result.fail(dir.errorCode(), dir.errorMessage());
    }

    public static Result<Path> descriptorFile(Path root, String moduleId) {
        Result<Path> dir = moduleDir(root, moduleId);
        return dir.isOk() ? Result.ok(dir.value().resolve("module.json"))
                : Result.fail(dir.errorCode(), dir.errorMessage());
    }

    public static Result<Path> tempFile(Path root, String moduleId, String suffix) {
        Result<Path> dir = moduleDir(root, moduleId);
        if (!dir.isOk()) {
            return Result.fail(dir.errorCode(), dir.errorMessage());
        }
        if (suffix == null || suffix.isEmpty()) {
            suffix = "tmp";
        }
        if (!ModuleId.isValid(suffix) && !suffix.equals("tmp")) {
            return Result.fail("PATH_BAD_SUFFIX", "Invalid temp suffix: " + suffix);
        }
        return Result.ok(dir.value().resolve(suffix + ".tmp"));
    }

    /** Каталог снимков (TЗ §18.2): активный, previous и candidate. */
    public static Path snapshotsDir(Path root) {
        return root.resolve("snapshots").normalize();
    }

    public static Path activeSnapshot(Path root) {
        return snapshotsDir(root).resolve("active.txt");
    }

    public static Path previousSnapshot(Path root) {
        return snapshotsDir(root).resolve("previous.txt");
    }

    public static Path candidateSnapshot(Path root) {
        return snapshotsDir(root).resolve("candidate.txt");
    }

    /** Каталог crash-отчётов (TЗ §22). */
    public static Path crashReportsDir(Path root) {
        return root.resolve("crash").normalize();
    }

    /** Каталог runtime-журналов (TЗ §22). */
    public static Path runtimeLogsDir(Path root) {
        return root.resolve("logs").normalize();
    }

    /** Каталог кэша репозитория (TЗ §14). */
    public static Path repositoryCacheDir(Path root) {
        return root.resolve("repository").normalize();
    }

    /** Каталог загруженных архивов. */
    public static Path downloadsDir(Path root) {
        return root.resolve("downloads").normalize();
    }
}
