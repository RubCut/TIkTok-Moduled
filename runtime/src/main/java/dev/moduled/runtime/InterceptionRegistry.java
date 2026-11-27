package dev.moduled.runtime;

import dev.moduled.api.HookCallback;
import dev.moduled.api.HookHandle;
import dev.moduled.api.HookParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Реестр кооперативных точек перехвата для изолированного тестового хоста
 * (ТЗ §3.1: demo-host нужен для разработки и не заменяет настоящий TikTok).
 *
 * <p>Это НЕ ART-хук и не подмена методов: хост сам вызывает
 * {@link #dispatch(String, Object, Object[], OriginalCall)} в своих точках,
 * а модуль регистрирует обработчики через {@link RegistryHookBackend}. Такая
 * схема позволяет проверять семантику контракта (аргументы, замена результата,
 * исключение, порядок хуков, снятие хука) обычными JVM-тестами, не выдавая её за
 * перехват в реальном приложении.</p>
 */
public final class InterceptionRegistry {

    /** Оригинальный код точки перехвата. */
    public interface OriginalCall {
        Object invoke(Object[] args) throws Throwable;
    }

    private static final class Registration {
        final HookCallback callback;
        boolean active = true;

        Registration(HookCallback callback) {
            this.callback = callback;
        }
    }

    private final Map<String, List<Registration>> registrations = new LinkedHashMap<>();
    private final Object lock = new Object();

    /** Ключ точки перехвата для метода: {@code полное.имя.Класса#метод(типы)}. */
    public static String keyFor(java.lang.reflect.Member member) {
        if (member instanceof java.lang.reflect.Method) {
            java.lang.reflect.Method method = (java.lang.reflect.Method) member;
            StringBuilder sb = new StringBuilder(method.getDeclaringClass().getName());
            sb.append('#').append(method.getName()).append('(');
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(types[i].getName());
            }
            return sb.append(')').toString();
        }
        return member.getDeclaringClass().getName() + "#<init>(" + member.getName() + ')';
    }

    public HookHandle register(String key, HookCallback callback) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        Registration registration = new Registration(callback);
        synchronized (lock) {
            registrations.computeIfAbsent(key, k -> new ArrayList<>()).add(registration);
        }
        return new HookHandle() {
            @Override
            public String targetDescription() {
                return key;
            }

            @Override
            public boolean unhook() {
                synchronized (lock) {
                    if (!registration.active) {
                        return false;
                    }
                    registration.active = false;
                    return true;
                }
            }
        };
    }

    public boolean hasRegistrations(String key) {
        synchronized (lock) {
            List<Registration> list = registrations.get(key);
            if (list == null) {
                return false;
            }
            for (Registration r : list) {
                if (r.active) {
                    return true;
                }
            }
            return false;
        }
    }

    public int activeCount(String key) {
        synchronized (lock) {
            List<Registration> list = registrations.get(key);
            if (list == null) {
                return 0;
            }
            int count = 0;
            for (Registration r : list) {
                if (r.active) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Прогоняет вызов через активные обработчики. Порядок — порядок регистрации:
     * платформа не обещает произвольную приоритизацию (ТЗ §9.1).
     */
    public Object dispatch(String key, Object thisObject, Object[] args, OriginalCall original)
            throws Throwable {
        List<Registration> active;
        synchronized (lock) {
            List<Registration> list = registrations.get(key);
            if (list == null || list.isEmpty()) {
                return original.invoke(args);
            }
            active = new ArrayList<>(list.size());
            for (Registration r : list) {
                if (r.active) {
                    active.add(r);
                }
            }
        }
        if (active.isEmpty()) {
            return original.invoke(args);
        }

        HookParam param = new HookParam(thisObject, args);
        for (Registration registration : active) {
            registration.callback.before(param);
            if (param.returnsImmediately()) {
                break;
            }
        }

        if (!param.returnsImmediately()) {
            try {
                Object result = original.invoke(param.args());
                param.setResult(result);
                param.clearImmediateReturn();
            } catch (Throwable t) {
                param.setThrowable(t);
            }
        }

        for (Registration registration : active) {
            registration.callback.after(param);
        }
        if (param.throwable() != null) {
            throw param.throwable();
        }
        return param.result();
    }

    /** Снимает все регистрации: используется safe mode и тестами. */
    public void clear() {
        synchronized (lock) {
            registrations.clear();
        }
    }
}
