package dev.moduled.patcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** ТЗ §5.1 и §7.2 PAT-05: ABI выбираются явно, лишние native-записи исключаются. */
public class AbiSupportTest {

    @Test
    public void resolvesOnlyAbisPresentInApk() {
        List<String> resolved = AbiSupport.resolve(
                Arrays.asList(AbiSupport.ARM64_V8A, AbiSupport.X86_64),
                Arrays.asList("arm64-v8a", "armeabi-v7a"));
        assertEquals(Arrays.asList("arm64-v8a"), resolved);
    }

    @Test
    public void excludesOtherAbiEntries() {
        List<String> excluded = AbiSupport.entriesToExclude(
                Arrays.asList("lib/arm64-v8a/libx.so", "lib/armeabi-v7a/libx.so",
                        "lib/x86/libx.so", "classes.dex", "AndroidManifest.xml"),
                Arrays.asList("arm64-v8a"));
        assertEquals(Arrays.asList("lib/armeabi-v7a/libx.so", "lib/x86/libx.so"), excluded);
    }

    @Test
    public void nameMappingIsStable() {
        assertEquals("arm64-v8a", AbiSupport.name(AbiSupport.ARM64_V8A));
        assertEquals("armeabi-v7a", AbiSupport.name(AbiSupport.ARMEABI_V7A));
        assertEquals("x86_64", AbiSupport.name(AbiSupport.X86_64));
        assertEquals("x86", AbiSupport.name(AbiSupport.X86));
        assertEquals(AbiSupport.ARM64_V8A, AbiSupport.fromName("arm64-v8a"));
        assertTrue(AbiSupport.allNames().contains("arm64-v8a"));
    }
}
