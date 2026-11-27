package dev.moduled.manager;

import android.content.Context;
import dev.moduled.core.Result;
import dev.moduled.manager.storage.InstallOutcome;
import dev.moduled.manager.storage.InstallRequest;
import dev.moduled.manager.storage.ModuleStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Комплектные модули (ТЗ §11.11, §16.1, §17.1).
 *
 * <p>Комплектные модули поставляются в assets менеджера готовыми ZIP и
 * устанавливаются как ОБЫЧНЫЕ модули с отметкой {@code bundled}: тот же
 * формат пакета, то же хранилище, те же проверки. Отличие только в отметке и
 * в том, что модуль региона предлагается при установке.</p>
 *
 * <p>Новые комплектные модули тоже устанавливаются выключенными: отметка
 * «комплектный» не является подтверждением доверия (§11.8).</p>
 */
public final class BundledModules {

    /** Каталог assets с комплектными модулями. */
    public static final String ASSETS_DIR = "tmoduled/bundled";

    private BundledModules() {
    }

    /** Имена комплектных модулей в assets (стабильный порядок). */
    public static List<String> list(Context context) {
        List<String> names = new ArrayList<>();
        try {
            String[] assets = context.getAssets().list(ASSETS_DIR);
            if (assets != null) {
                for (String asset : assets) {
                    if (asset.endsWith(".zip")) {
                        names.add(asset);
                    }
                }
            }
        } catch (IOException e) {
            // Нет каталога — нет комплектных модулей: это не ошибка.
        }
        java.util.Collections.sort(names);
        return names;
    }

    /**
     * Извлекает ZIP комплектного модуля во внутреннее хранилище и устанавливает
     * его. Повторный вызов для уже установленного модуля безопасен: хранилище
     * само разрешает замену по своим правилам (PKG-06).
     */
    public static Result<InstallOutcome> install(Context context, TModulesApplication app,
                                                 String assetName) {
        return install(context, app, assetName, false, false);
    }

    /**
     * @param confirmReplace пользователь подтвердил замену существующего модуля
     *                       с тем же ID (PKG-06)
     * @param confirmNewKey  пользователь подтвердил смену авторского ключа
     */
    public static Result<InstallOutcome> install(Context context, TModulesApplication app,
                                                 String assetName, boolean confirmReplace,
                                                 boolean confirmNewKey) {
        File bundledDir = new File(app.storageRoot(), "bundled");
        if (!bundledDir.isDirectory() && !bundledDir.mkdirs()) {
            return Result.fail("BUNDLED_NO_DIR",
                    "Не удалось создать каталог комплектных модулей");
        }
        File archive = new File(bundledDir, assetName);
        try (InputStream in = context.getAssets().open(ASSETS_DIR + "/" + assetName);
             OutputStream out = new FileOutputStream(archive)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            return Result.fail("BUNDLED_EXTRACT_FAILED",
                    "Не удалось извлечь " + assetName + ": " + e.getMessage());
        }
        ModuleStore store = new ModuleStore(app.modulesRoot());
        // Замена существующего модуля с тем же ID требует отдельного
        // подтверждения в UI (PKG-06): автоматически не подтверждаем.
        InstallRequest.Builder request = InstallRequest.bundled(archive);
        if (confirmReplace) {
            request.confirmReplace(true);
        }
        if (confirmNewKey) {
            request.confirmNewKey(true);
        }
        return store.install(request.build());
    }
}
