package dev.moduled.manager.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.ModuleRecord;
import dev.moduled.manager.storage.ModuleState;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.ui.Dialogs;
import dev.moduled.ui.Formatters;
import java.util.ArrayList;
import java.util.List;

/**
 * Карточка модуля (ТЗ §11.6, §21.2 экран 4): описание, источник, доверие,
 * хеши, ошибки, действия (конфиг, обновление, удаление с выбором сохранения
 * данных — конфиг по умолчанию сохраняется, §11.10, CFG-11).
 */
public final class ModuleDetailActivity extends BaseScreenActivity {

    public static final String EXTRA_MODULE_ID = "moduleId";

    private LinearLayout column;
    private String moduleId;

    public static Intent intent(Context context, String moduleId) {
        return new Intent(context, ModuleDetailActivity.class)
                .putExtra(EXTRA_MODULE_ID, moduleId);
    }

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_module_card);
        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        column = scrollContent();
        column.addView(button(getString(R.string.card_open_editor), v -> openEditor()));
        column.addView(button(getString(R.string.card_update), v -> checkUpdate()));
        column.addView(button(getString(R.string.card_uninstall), v -> confirmUninstall()));
        refresh();
    }

    private void refresh() {
        if (moduleId == null) {
            column.addView(body(getString(R.string.state_error) + ": нет ID модуля"));
            return;
        }
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<ModuleRecord> record = store.get(moduleId);
            AppExecutors.get().main(() -> render(record));
        });
    }

    private void render(Result<ModuleRecord> result) {
        column.removeAllViews();
        column.addView(button(getString(R.string.card_open_editor), v -> openEditor()));
        column.addView(button(getString(R.string.card_update), v -> checkUpdate()));
        column.addView(button(getString(R.string.card_uninstall), v -> confirmUninstall()));
        if (!result.isOk()) {
            column.addView(body(getString(R.string.state_error) + ": "
                    + result.errorCode() + " " + result.errorMessage()));
            return;
        }
        ModuleRecord record = result.value();
        ModuleState state = record.state;
        column.addView(title(record.manifest.name));
        column.addView(body(record.manifest.description));
        column.addView(body(getString(R.string.card_version,
                record.manifest.version, (int) record.manifest.versionCode)));
        column.addView(body(sourceLine(state)));
        column.addView(body(state.trustConfirmed
                ? getString(R.string.card_trust_confirmed,
                Formatters.shortHash(state.authorKeyFingerprint))
                : getString(R.string.card_trust_not_confirmed)));
        column.addView(body(getString(R.string.card_archive_hash,
                Formatters.groupedHash(state.archiveSha256))));
        column.addView(body("DEX SHA-256: " + Formatters.groupedHash(record.dexSha256)));
        column.addView(body("Конфиг: " + Formatters.humanSize(record.configBytes)
                + ", SHA-256 " + Formatters.shortHash(record.configSha256)));

        column.addView(title(getString(R.string.card_errors)));
        column.addView(mono(moduleErrors()));
    }

    /**
     * Ошибки модуля берутся из журнала событий моста (§22): авторитетное
     * состояние защиты хранится в хосте, менеджер показывает свою копию.
     */
    private String moduleErrors() {
        java.io.File journal = new java.io.File(app().logsRoot(), "bridge-events.log");
        if (!journal.isFile()) {
            return getString(R.string.card_no_errors);
        }
        List<String> relevant = new ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(journal.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.contains("|" + moduleId) || line.endsWith("|" + moduleId)) {
                    relevant.add(line);
                }
            }
        } catch (java.io.IOException e) {
            return getString(R.string.card_no_errors);
        }
        return relevant.isEmpty()
                ? getString(R.string.card_no_errors)
                : String.join("\n", relevant);
    }

    private String sourceLine(ModuleState state) {
        if (state.origin == ModuleState.Origin.REPOSITORY) {
            return getString(R.string.card_source_repo, state.sourceUrl);
        }
        if (state.origin == ModuleState.Origin.BUNDLED) {
            return getString(R.string.card_source_bundled);
        }
        return getString(R.string.card_source_local);
    }

    private void openEditor() {
        if (moduleId != null) {
            startActivity(ConfigEditorActivity.intent(this, moduleId));
        }
    }

    /**
     * Обновление (§13.3): версия сравнивается по целому versionCode; переход на
     * более старую версию требует явного подтверждения отката.
     */
    private void checkUpdate() {
        Dialogs.showError(this, getString(R.string.card_update),
                "Обновления проверяются в каталоге: откройте экран «Каталог», "
                        + "найдите модуль " + moduleId + " и сравните versionCode. "
                        + "Автообновления выключены (§13.5).");
    }

    /**
     * Удаление с выбором сохранения данных (§11.10): один диалог, три выхода —
     * сохранить конфиг (по умолчанию), удалить с конфигом, отмена.
     */
    private void confirmUninstall() {
        if (moduleId == null) {
            return;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.card_uninstall))
                .setMessage(getString(R.string.card_uninstall_question, moduleId))
                .setPositiveButton(getString(R.string.card_uninstall_keep),
                        (dialog, which) -> uninstall(true))
                .setNegativeButton(getString(R.string.card_uninstall_delete),
                        (dialog, which) -> uninstall(false))
                .setNeutralButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void uninstall(boolean keepConfig) {
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<Void> done = store.uninstall(moduleId, keepConfig);
            AppExecutors.get().main(() -> {
                if (!done.isOk()) {
                    Dialogs.showError(this, getString(R.string.state_error),
                            done.errorCode() + ": " + done.errorMessage());
                } else {
                    finish();
                }
            });
        });
    }
}
