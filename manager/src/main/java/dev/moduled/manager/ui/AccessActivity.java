package dev.moduled.manager.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.ModuleRecord;
import dev.moduled.manager.storage.ModuleStore;
import java.util.List;

/**
 * Экран доступов (ТЗ §15.2, §21.2 экран 7). Три раздельных понятия, которые
 * нельзя смешивать:
 *
 * <ol>
 *   <li>возможности модуля — что заявлено в манифесте модуля;</li>
 *   <li>разрешения хоста — что разрешено патченному приложению;</li>
 *   <li>патч manifest — фактические изменения AndroidManifest.xml (diff).</li>
 * </ol>
 */
public final class AccessActivity extends BaseScreenActivity {

    /** Diff последнего патча, сохранённый PatcherActivity. */
    public static final String LAST_PATCH_DIFF_FILE = "last-patch-report.txt";

    private LinearLayout column;

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_access);
        column = scrollContent();
        column.addView(body(getString(R.string.access_dex_warning)));
        column.addView(body(getString(R.string.access_shizuku_note)));
        renderStaticSections();
        loadModuleCapabilities();
        loadManifestDiff();
    }

    private void renderStaticSections() {
        column.addView(title(getString(R.string.access_host_permissions_title)));
        column.addView(body(getString(R.string.access_host_permissions_note)));
        column.addView(mono("android.permission.INTERNET — единственный permission менеджера;\n"
                + "патч НЕ добавляет хосту новых разрешений (§7.2 PAT-09);\n"
                + "dev.moduled.manager.permission.BRIDGE (signature) — доступ к мосту."));
    }

    private void loadModuleCapabilities() {
        column.addView(title(getString(R.string.access_capabilities_title)));
        column.addView(body(getString(R.string.access_capabilities_note)));
        LinearLayout capabilities = new LinearLayout(this);
        capabilities.setOrientation(LinearLayout.VERTICAL);
        capabilities.setTag("capabilities");
        column.addView(capabilities);
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<List<ModuleRecord>> listed = store.list();
            AppExecutors.get().main(() -> {
                LinearLayout target = column.findViewWithTag("capabilities");
                if (!listed.isOk() || listed.value().isEmpty()) {
                    target.addView(body(getString(R.string.diagnostics_empty)));
                    return;
                }
                for (ModuleRecord record : listed.value()) {
                    target.addView(mono(record.id()
                            + " · apiVersion " + record.manifest.apiVersion
                            + " · entrypoint " + record.manifest.entrypoint
                            + " · targets " + record.manifest.targetPackages
                            + " · " + (record.state.enabled
                            ? getString(R.string.modules_state_enabled)
                            : getString(R.string.modules_state_disabled))));
                }
            });
        });
    }

    private void loadManifestDiff() {
        column.addView(title(getString(R.string.access_manifest_diff_title)));
        column.addView(body(getString(R.string.access_manifest_diff_note)));
        AppExecutors.get().background(() -> {
            String text = readLastPatchReport();
            AppExecutors.get().main(() -> column.addView(mono(text)));
        });
    }

    /** Читает diff-отчёт последнего патча; при отсутствии/ошибке — пустое состояние. */
    private String readLastPatchReport() {
        java.io.File report = new java.io.File(app().storageRoot(), LAST_PATCH_DIFF_FILE);
        if (!report.isFile()) {
            return getString(R.string.diagnostics_empty);
        }
        try {
            return new String(java.nio.file.Files.readAllBytes(report.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return getString(R.string.diagnostics_empty);
        }
    }
}
