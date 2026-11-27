package dev.moduled.manager.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.InstallOutcome;
import dev.moduled.manager.storage.InstallRequest;
import dev.moduled.manager.storage.ModuleRecord;
import dev.moduled.manager.storage.ModuleState;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.manager.storage.SnapshotStore;
import dev.moduled.ui.Dialogs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Список модулей (ТЗ §11.3, §11.4, §21.2 экран 3): состояние, включение с
 * предупреждением о доверенном DEX, массовое отключение, установка из файла
 * без сети, отметка «ожидает применения» (§11.9).
 */
public final class ModulesActivity extends BaseScreenActivity {

    private static final int REQUEST_PICK_MODULE = 4201;

    private LinearLayout column;

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_modules);
        column = scrollContent();
        column.addView(button(getString(R.string.modules_install_file), v -> pickModuleFile()));
        column.addView(button(getString(R.string.modules_disable_all), v -> confirmDisableAll()));
        column.addView(button(getString(R.string.modules_restart_host), v -> restartHost()));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("module-list");
        column.addView(list);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            SnapshotStore snapshots = new SnapshotStore(app().storageRoot());
            Result<List<ModuleRecord>> listed = store.list();
            long generation = snapshots.currentGeneration();
            AppExecutors.get().main(() -> render(listed, generation));
        });
    }

    private void render(Result<List<ModuleRecord>> listed, long generation) {
        LinearLayout list = column.findViewWithTag("module-list");
        list.removeAllViews();
        if (!listed.isOk()) {
            list.addView(body(getString(R.string.state_error) + ": "
                    + listed.errorCode() + " " + listed.errorMessage()));
            return;
        }
        List<ModuleRecord> records = new ArrayList<>(listed.value());
        records.sort((a, b) -> a.id().compareTo(b.id()));
        if (records.isEmpty()) {
            list.addView(body(getString(R.string.modules_empty)));
            return;
        }
        for (ModuleRecord record : records) {
            list.addView(moduleCard(record, generation));
        }
    }

    private MaterialCardView moduleCard(ModuleRecord record, long generation) {
        MaterialCardView card = new MaterialCardView(this);
        int pad = dp(12);
        card.setContentPadding(pad, pad, pad, pad);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);

        String badge = record.state.bundled
                ? " · " + getString(R.string.modules_bundled_badge) : "";
        inner.addView(title(record.manifest.name + badge));
        inner.addView(body(record.id() + " · " + record.manifest.version));

        // Состояние дублируется текстом, а не только цветом/положением (§21.8).
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(record.state.enabled);
        toggle.setText(record.state.enabled
                ? getString(R.string.modules_state_enabled)
                : getString(brokenState(record)
                ? R.string.modules_state_broken : R.string.modules_state_disabled));
        toggle.setContentDescription(record.id() + ": " + toggle.getText());
        toggle.setOnCheckedChangeListener((view, checked) ->
                onToggle(record, checked, () -> toggle.setChecked(!checked)));
        inner.addView(toggle);

        if (record.pendingApply(generation)) {
            inner.addView(body("⏳ " + getString(R.string.modules_pending_apply)));
        }
        inner.addView(button(getString(R.string.screen_module_card),
                v -> startActivity(ModuleDetailActivity.intent(this, record.id()))));

        card.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private boolean brokenState(ModuleRecord record) {
        return record.dexSha256 == null || record.dexSha256.isEmpty();
    }

    /**
     * Включение стороннего модуля — только после предупреждения о доверенном DEX
     * (§14.1). Отключение действует на следующий запуск (§8 RUN-10).
     */
    private void onToggle(ModuleRecord record, boolean enable, Runnable revert) {
        if (!enable || record.state.trustConfirmed) {
            applyEnabled(record, enable, revert);
            return;
        }
        Dialogs.showTrustedDexWarning(this, record.manifest.name,
                () -> applyEnabled(record, true, revert));
        // Пользователь может отказаться: возвращаем переключатель.
        revert.run();
    }

    private void applyEnabled(ModuleRecord record, boolean enable, Runnable revert) {
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<Void> done = store.setEnabled(record.id(), enable);
            if (!done.isOk()) {
                AppExecutors.get().main(() -> {
                    revert.run();
                    Dialogs.showError(this, getString(R.string.state_error),
                            done.errorCode() + ": " + done.errorMessage());
                });
                return;
            }
            AppExecutors.get().main(this::refresh);
        });
    }

    private void confirmDisableAll() {
        Dialogs.confirmWithConsequences(this,
                getString(R.string.modules_disable_all),
                getString(R.string.modules_confirm_disable_all),
                "все модули перестанут загружаться после следующего запуска хоста; "
                        + "конфигурации сохранятся",
                getString(R.string.action_ok),
                () -> AppExecutors.get().background(() -> {
                    ModuleStore store = new ModuleStore(app().modulesRoot());
                    Result<List<ModuleRecord>> listed = store.list();
                    if (listed.isOk()) {
                        for (ModuleRecord record : listed.value()) {
                            store.setEnabled(record.id(), false);
                        }
                    }
                    AppExecutors.get().main(this::refresh);
                }));
    }

    private void restartHost() {
        startActivity(new Intent(this, DiagnosticsActivity.class)
                .putExtra(DiagnosticsActivity.EXTRA_RESTART_REQUEST, true));
    }

    private void pickModuleFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/zip", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_PICK_MODULE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_MODULE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        AppExecutors.get().background(() -> {
            File downloads = app().downloadsRoot();
            if (!downloads.isDirectory() && !downloads.mkdirs()) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error), "Нет каталога загрузок"));
                return;
            }
            File copy = new File(downloads, "module-" + System.currentTimeMillis() + ".zip");
            try {
                copyUri(uri, copy);
            } catch (IOException e) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error),
                        "Не удалось прочитать файл: " + e.getMessage()));
                return;
            }
            installLocal(copy);
        });
    }

    private void copyUri(Uri uri, File target) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) {
                throw new IOException("content resolver вернул null");
            }
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private void installLocal(File archive) {
        ModuleStore store = new ModuleStore(app().modulesRoot());
        Result<InstallOutcome> first = store.install(InstallRequest.fromFile(archive).build());
        if (first.isOk()) {
            AppExecutors.get().main(() -> {
                Dialogs.showError(this, getString(R.string.screen_modules),
                        getString(R.string.modules_new_installed_disabled));
                refresh();
            });
            return;
        }
        if ("INSTALL_REQUIRES_CONFIRMATION".equals(first.errorCode())) {
            // Замена существующего ID: отдельное подтверждение (§11.7, PKG-06).
            AppExecutors.get().main(() -> Dialogs.confirmReplaceModule(this,
                    archive.getName(), first.errorMessage(), () ->
                            AppExecutors.get().background(() -> {
                                Result<InstallOutcome> confirmed = store.install(
                                        InstallRequest.fromFile(archive)
                                                .confirmReplace(true).build());
                                finishInstall(confirmed);
                            })));
            return;
        }
        if ("INSTALL_KEY_CHANGED".equals(first.errorCode())) {
            AppExecutors.get().main(() -> Dialogs.confirmReplaceModule(this,
                    archive.getName(), first.errorMessage(), () ->
                            AppExecutors.get().background(() -> {
                                Result<InstallOutcome> confirmed = store.install(
                                        InstallRequest.fromFile(archive)
                                                .confirmReplace(true)
                                                .confirmNewKey(true).build());
                                finishInstall(confirmed);
                            })));
            return;
        }
        finishInstall(first);
    }

    private void finishInstall(Result<InstallOutcome> result) {
        AppExecutors.get().main(() -> {
            if (!result.isOk()) {
                Dialogs.showError(this, getString(R.string.state_error),
                        result.errorCode() + ": " + result.errorMessage());
            } else if (result.value().enabled) {
                Dialogs.showError(this, getString(R.string.screen_modules),
                        getString(R.string.modules_new_installed_disabled));
            }
            refresh();
        });
    }
}
