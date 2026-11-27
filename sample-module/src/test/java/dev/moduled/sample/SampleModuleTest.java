package dev.moduled.sample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.moduled.api.ConfigSnapshot;
import dev.moduled.api.HookApi;
import dev.moduled.api.HookCallback;
import dev.moduled.api.HookResult;
import dev.moduled.api.HostInfo;
import dev.moduled.api.ModuleContext;
import dev.moduled.api.ModuleLogger;
import dev.moduled.api.RestartResult;
import java.io.File;
import java.lang.reflect.Member;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Проверка контракта модуля-примера на JVM (ТЗ §25, приёмка модулей). */
public class SampleModuleTest {

    private static final class RecordingLogger implements ModuleLogger {
        final List<String> lines = new ArrayList<>();

        @Override
        public void log(Level level, String message) {
            lines.add(level + ": " + message);
        }

        @Override
        public void log(Level level, String message, Throwable throwable) {
            lines.add(level + ": " + message + (throwable != null ? ": " + throwable : ""));
        }
    }

    private static final class StubSnapshot implements ConfigSnapshot {
        private final byte[] bytes;

        StubSnapshot(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        @Override
        public String sha256() {
            return "stub-hash";
        }

        @Override
        public long generation() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }
    }

    private static final class StubHooks implements HookApi {
        @Override
        public String backendName() {
            return "none";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public HookResult hook(Member target, HookCallback callback) {
            return HookResult.failed(HookResult.ERR_BACKEND_UNAVAILABLE, "backend недоступен");
        }

        @Override
        public Class<?> findClass(String className) {
            return null;
        }

        @Override
        public Class<?> findClass(String className, ClassLoader classLoader) {
            return null;
        }
    }

    private ModuleContext context(ConfigSnapshot snapshot, RecordingLogger logger) {
        return new ModuleContext() {
            @Override
            public android.content.Context hostContext() {
                return null;
            }

            @Override
            public ClassLoader hostClassLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public ClassLoader moduleClassLoader() {
                return getClass().getClassLoader();
            }

            @Override
            public String moduleId() {
                return "dev.moduled.sample";
            }

            @Override
            public String moduleVersion() {
                return "1.0.0";
            }

            @Override
            public long moduleVersionCode() {
                return 1;
            }

            @Override
            public int apiVersion() {
                return ModuleContext.API_VERSION;
            }

            @Override
            public String runtimeVersion() {
                return "0.1.0-test";
            }

            @Override
            public String processName() {
                return "dev.moduled.demo.host";
            }

            @Override
            public boolean isMainProcess() {
                return true;
            }

            @Override
            public File dataDirectory() {
                return new File(System.getProperty("java.io.tmpdir"), "sample-module-data");
            }

            @Override
            public ConfigSnapshot config() {
                return snapshot;
            }

            @Override
            public ModuleLogger log() {
                return logger;
            }

            @Override
            public HookApi hooks() {
                return new StubHooks();
            }

            @Override
            public HostInfo host() {
                return new HostInfo() {
                    @Override
                    public String packageName() {
                        return "dev.moduled.demo.host";
                    }

                    @Override
                    public String versionName() {
                        return "1.0.0-demo";
                    }

                    @Override
                    public long versionCode() {
                        return 1;
                    }

                    @Override
                    public String adapterName() {
                        return "demo-host";
                    }

                    @Override
                    public String adapterVersion() {
                        return "1";
                    }

                    @Override
                    public boolean adapterSupported() {
                        return true;
                    }

                    @Override
                    public String[] primaryAbis() {
                        return new String[0];
                    }
                };
            }

            @Override
            public RestartResult requestRestart(String reason) {
                return RestartResult.of(RestartResult.Status.UNAVAILABLE, "тест");
            }
        };
    }

    @Test
    public void loadsAndReadsConfigSnapshot() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        SampleModule module = new SampleModule();
        module.onLoad(context(new StubSnapshot("enabled=true"), logger));
        assertTrue(module.isLoaded());
        String log = String.join("\n", logger.lines);
        assertTrue(log.contains("демонстрационный режим=true"));
        // Backend недоступен — модуль честно сообщает об этом, а не имитирует хук.
        assertTrue(log.contains("Hook backend недоступен"));
    }

    @Test
    public void emptyConfigIsAllowed() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        SampleModule module = new SampleModule();
        module.onLoad(context(new StubSnapshot(""), logger));
        assertTrue(module.isLoaded());
        assertTrue(String.join("\n", logger.lines).contains("демонстрационный режим=false"));
    }

    @Test
    public void unloadIsBestEffort() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        SampleModule module = new SampleModule();
        module.onLoad(context(null, logger));
        module.onUnload();
        assertFalse(module.isLoaded());
        assertEquals(1, logger.lines.stream().filter(line -> line.contains("onUnload")).count());
    }
}
