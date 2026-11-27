package dev.moduled.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModuleIdTest {

    @Test
    public void acceptsDocumentedForm() {
        assertTrue(ModuleId.isValid("region.module"));
        assertTrue(ModuleId.isValid("com.author.sample_module"));
        assertTrue(ModuleId.isValid("a1"));
    }

    @Test
    public void rejectsUnsafeOrMalformedIds() {
        assertFalse(ModuleId.isValid(null));
        assertFalse(ModuleId.isValid(""));
        assertFalse(ModuleId.isValid("Region.Module"));       // верхний регистр
        assertFalse(ModuleId.isValid("region module"));       // пробел
        assertFalse(ModuleId.isValid("region/module"));       // разделитель пути
        assertFalse(ModuleId.isValid("region\\module"));      // windows-разделитель
        assertFalse(ModuleId.isValid(".."));                  // traversal
        assertFalse(ModuleId.isValid("../etc"));
        assertFalse(ModuleId.isValid("a..b"));
        assertFalse(ModuleId.isValid(".hidden"));
        assertFalse(ModuleId.isValid("trailing."));
        assertFalse(ModuleId.isValid("1digit-first"));
        assertFalse(ModuleId.isValid("dash-id"));
        assertFalse(ModuleId.isValid(repeat('a', Limits.MAX_MODULE_ID_LENGTH + 1)));
        assertTrue(ModuleId.isValid(repeat('a', Limits.MAX_MODULE_ID_LENGTH)));
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
