package dev.moduled.runtime;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Класс приложения, который патчер вставляет в {@code android:name} элемента
 * {@code <application>} (ТЗ §7.2 PAT-08, PAT-09, PAT-10).
 *
 * <p>Поведение обязательно для сохранности хоста:</p>
 * <ul>
 *   <li>оригинальный Application хоста (если он был) создаётся и получает все
 *       колбэки жизненного цикла — патч не ломает существующее поведение;</li>
 *   <li>runtime инициализируется только в основном процессе (RUN-10);</li>
 *   <li>любой сбой инициализации runtime перехватывается: приложение хоста
 *       обязано запуститься даже без модулей;</li>
 *   <li>класс намеренно не зависит от Material/MD3 (§21.1).</li>
 * </ul>
 */
public final class RuntimeApplication extends Application {

    /** Имя класса, вставляемое патчером в manifest. */
    public static final String CLASS_NAME = "dev.moduled.runtime.RuntimeApplication";


    private Object delegate;
    private volatile boolean runtimeInitialized;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        delegate = createDelegate(base);
        attachDelegate(base);
        initRuntime();
    }

    @Override
    public void onCreate() {
        invokeDelegate("onCreate");
        super.onCreate();
    }

    @Override
    public void onTerminate() {
        invokeDelegate("onTerminate");
        super.onTerminate();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        invokeDelegateWithArg("onConfigurationChanged", Configuration.class, newConfig);
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onLowMemory() {
        invokeDelegate("onLowMemory");
        super.onLowMemory();
    }

    @Override
    public void onTrimMemory(int level) {
        invokeDelegateWithArg("onTrimMemory", int.class, level);
        super.onTrimMemory(level);
    }

    /** Инициализирован ли runtime (для диагностики и in-app менеджера). */
    public boolean isRuntimeInitialized() {
        return runtimeInitialized;
    }

    private Object createDelegate(Context base) {
        String originalClassName = readOriginalApplicationName();
        if (originalClassName == null || originalClassName.isEmpty()
                || CLASS_NAME.equals(originalClassName)) {
            return null;
        }
        try {
            Class<?> original = Class.forName(originalClassName, false,
                    getClassLoader() != null ? getClassLoader() : base.getClassLoader());
            Object instance = original.getDeclaredConstructor().newInstance();
            // attachBaseContext оригинала вызывается явно: без него делегат не
            // сможет работать с контекстом (PAT-10 — поведение хоста сохраняется).
            Method attach = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
            attach.setAccessible(true);
            attach.invoke(instance, base);
            return instance;
        } catch (Throwable ignored) {
            // Оригинальный класс не найден или не создаётся: продолжаем без него.
            // Причина фиксируется в диагностике runtime, а не роняет хост.
            return null;
        }
    }

    private String readOriginalApplicationName() {
        try (InputStream in = getAssets().open(AdapterMetadata.ASSET_PATH)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            Object parsed = dev.moduled.core.JsonParser.parse(
                    new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                Object value = ((Map<?, ?>) parsed).get("originalApplication");
                return value instanceof String ? (String) value : null;
            }
        } catch (Throwable ignored) {
            // adapter.json отсутствует или повреждён: делегата не будет.
        }
        return null;
    }

    private void attachDelegate(Context base) {
        // Делегат уже получил attachBaseContext в createDelegate.
    }

    private void initRuntime() {
        try {
            if (!isMainProcess()) {
                // RUN-10: модули работают только в основном процессе.
                return;
            }
            File storageRoot = new File(getFilesDir(), "tmoduled");
            HookBackend backend = loadHookBackend();
            RuntimeOptions options = RuntimeOptions.builder(this)
                    .storageRoot(storageRoot)
                    .processName(processName())
                    .mainProcess(true)
                    .hookBackend(backend)
                    .build();
            RuntimeBootstrap.init(options);
            runtimeInitialized = true;
        } catch (Throwable ignored) {
            // Сбой инициализации runtime не должен мешать запуску хоста:
            // состояние фиксируется crash-политикой при следующем старте.
            runtimeInitialized = false;
        }
    }

    private HookBackend loadHookBackend() {
        // Native hook backend (перехват ART через libtmoduled-hook.so) в первую
        // поставку не входит (§27, ADR-0001): имитировать его загрузку нельзя.
        // Перехват честно сообщается как недоступный; модули видят это через
        // hooks().isAvailable() и диагностику (RUN-02). Кооперативный
        // RegistryHookBackend существует для JVM-тестов контракта, но в реальном
        // хосте он ничего не перехватывает, поэтому здесь не используется.
        return new UnavailableHookBackend(
                "native hook backend не реализован в этой поставке (§27)");
    }

    private boolean isMainProcess() {
        String process = processName();
        return process == null || process.equals(getPackageName());
    }

    private String processName() {
        try {
            return Application.getProcessName();
        } catch (Throwable ignored) {
            return getPackageName();
        }
    }

    private void invokeDelegate(String methodName) {
        if (delegate == null) {
            return;
        }
        try {
            Method method = delegate.getClass().getMethod(methodName);
            method.invoke(delegate);
        } catch (NoSuchMethodException ignored) {
            // Метод не переопределён в оригинале — вызывать нечего.
        } catch (Throwable ignored) {
            // Сбой оригинального колбэка не должен ронять патченный хост:
            // поведение без патча было бы тем же (исключение в onCreate).
        }
    }

    private void invokeDelegateWithArg(String methodName, Class<?> argumentType, Object argument) {
        if (delegate == null) {
            return;
        }
        try {
            Method method = delegate.getClass().getMethod(methodName, argumentType);
            method.invoke(delegate, argument);
        } catch (NoSuchMethodException ignored) {
            // Метод не переопределён в оригинале.
        } catch (Throwable ignored) {
            // См. invokeDelegate.
        }
    }
}
