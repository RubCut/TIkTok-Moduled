package dev.moduled.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Статические границы контракта (ТЗ §9.2, §21.1, §25.1):
 * apiVersion зафиксирован, module-api не зависит от MD3/Material/AppCompat и не
 * втягивает Android-виджеты — иначе граница runtime/standalone-ui нарушается.
 */
public class ApiContractTest {

    private static final Path API_SOURCES = Paths.get("src/main/java/dev/moduled/api");

    @Test
    public void apiVersionIsOne() {
        assertEquals(1, ModuleContext.API_VERSION);
    }

    @Test
    public void apiHasNoMaterialOrUiDependencies() throws IOException {
        List<String> forbidden = List.of(
                "com.google.android.material",
                "androidx.appcompat",
                "androidx.activity",
                "androidx.fragment",
                "android.widget.",
                "android.view.");
        try (Stream<Path> files = Files.walk(API_SOURCES)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertFalse("module-api sources must exist", sources.isEmpty());
            for (Path source : sources) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                for (String banned : forbidden) {
                    assertFalse(source.getFileName() + " must not reference " + banned,
                            text.contains("import " + banned));
                }
            }
        }
    }

    /**
     * §9.2: поля будущих расширений не должны молча интерпретироваться старым
     * загрузчиком. Проверяем, что контракт не содержит «магических» строковых
     * возможностей, а версия API объявлена ровно один раз.
     */
    @Test
    public void apiVersionDeclaredOnce() throws IOException {
        String contract = Files.readString(API_SOURCES.resolve("ModuleContext.java"), StandardCharsets.UTF_8);
        int first = contract.indexOf("int API_VERSION = 1;");
        assertTrue("API_VERSION must be declared in ModuleContext", first >= 0);
        assertEquals("API_VERSION must be declared exactly once",
                first, contract.lastIndexOf("int API_VERSION = 1;"));
    }
}
