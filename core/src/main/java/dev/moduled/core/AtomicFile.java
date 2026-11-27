package dev.moduled.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Атомарная запись файла (ТЗ §12 CFG-05, §20.2):
 * write temp -> flush -> fsync -> atomic rename.
 * Прерывание записи не должно давать полупустой или смешанный файл.
 *
 * <p>fsync каталога выполняется по возможности; на платформах, где это
 * недоступно, rename остаётся атомарным в пределах файловой системы.</p>
 */
public final class AtomicFile {
    private AtomicFile() {
    }

    public static Result<Void> write(Path target, byte[] data) {
        if (target == null) {
            return Result.fail("WRITE_NO_TARGET", "Target path is null");
        }
        if (data == null) {
            return Result.fail("WRITE_NULL_DATA", "Data is null");
        }
        Path parent = target.getParent();
        Path temp = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = Files.createTempFile(
                    parent != null ? parent : target.getFileSystem().getPath("."),
                    "." + target.getFileName() + ".", ".tmp");
            Files.write(temp, data);
            // force() без TRUNCATE_EXISTING: открытие файла на запись обычным
            // FileOutputStream обнулило бы уже записанные данные.
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    temp, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            syncParent(parent);
            return Result.ok(null);
        } catch (IOException e) {
            // ATOMIC_MOVE может быть неподдержан — откат к неатомарному move
            // всё равно лучше, чем потеря данных.
            if (temp != null) {
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    temp = null;
                    syncParent(parent);
                    return Result.ok(null);
                } catch (IOException e2) {
                    // fallthrough to cleanup
                }
            }
            return Result.fail("WRITE_FAILED", "Atomic write failed: " + e.getMessage());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void syncParent(Path parent) {
        if (parent == null) {
            return;
        }
        try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(parent)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Не все файловые системы позволяют открывать каталог как канал.
        }
    }
}
