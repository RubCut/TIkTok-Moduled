package dev.moduled.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** CFG-05: сохранение транзакционно; temp-файлы не остаются в хранилище. */
public class AtomicFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesContentAndCreatesParents() throws IOException {
        Path target = tmp.getRoot().toPath().resolve("modules/region.module/config.txt");
        byte[] data = "region = us\r\n".getBytes(StandardCharsets.UTF_8);

        Result<Void> result = AtomicFile.write(target, data);
        assertTrue(result.errorMessage(), result.isOk());
        assertArrayEquals(data, Files.readAllBytes(target));
    }

    @Test
    public void overwriteIsCompleteNotMixed() throws IOException {
        Path target = tmp.getRoot().toPath().resolve("config.txt");
        byte[] first = "aaaaaaaaaaaaaaaa".getBytes(StandardCharsets.UTF_8);
        byte[] second = "bb".getBytes(StandardCharsets.UTF_8);
        assertTrue(AtomicFile.write(target, first).isOk());
        assertTrue(AtomicFile.write(target, second).isOk());
        assertArrayEquals(second, Files.readAllBytes(target));
    }

    @Test
    public void leavesNoTemporaryFiles() throws IOException {
        Path dir = tmp.getRoot().toPath();
        assertTrue(AtomicFile.write(dir.resolve("config.txt"), "x".getBytes(StandardCharsets.UTF_8)).isOk());
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> assertFalse("temp file left behind: " + path,
                    path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    public void rejectsBadInput() {
        assertEquals("WRITE_NO_TARGET", AtomicFile.write(null, new byte[0]).errorCode());
        Path target = tmp.getRoot().toPath().resolve("config.txt");
        assertEquals("WRITE_NULL_DATA", AtomicFile.write(target, null).errorCode());
    }

    @Test
    public void allowsEmptyFile() throws IOException {
        Path target = tmp.getRoot().toPath().resolve("empty.txt");
        assertTrue(AtomicFile.write(target, new byte[0]).isOk());
        assertEquals(0, Files.size(target));
    }
}
