package dev.moduled.manager.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import com.google.android.material.card.MaterialCardView;
import dev.moduled.core.BridgeContract;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.ModuleRecord;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.manager.storage.SnapshotStore;
import dev.moduled.ui.Formatters;
import java.util.List;

/**
 * Главный экран (ТЗ §21.2, экран 1): состояние моста и снимка, сводка модулей,
 * переходы на остальные экраны. Чтения состояния — в фоне (§23.1).
 */
public final class MainActivity extends BaseScreenActivity {

    /** Порог «снимок устарел» совпадает с мостом (§20.3): 24 часа. */
    static final long STALE_AFTER_MS = 24L * 60 * 60 * 1000;

    private LinearLayout statusColumn;

    @Override
    protected boolean showsUpNavigation() {
        return false;
    }

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_main);
        LinearLayout column = scrollContent();

        statusColumn = new LinearLayout(this);
        statusColumn.setOrientation(LinearLayout.VERTICAL);
        MaterialCardView statusCard = new MaterialCardView(this);
        int pad = dp(16);
        statusCard.setContentPadding(pad, pad, pad, pad);
        statusCard.addView(statusColumn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(16);
        statusCard.setLayoutParams(cardParams);
        column.addView(statusCard);

        column.addView(button(getString(R.string.main_open_patcher),
                v -> startActivity(new Intent(this, PatcherActivity.class))));
        column.addView(button(getString(R.string.main_open_modules),
                v -> startActivity(new Intent(this, ModulesActivity.class))));
        column.addView(button(getString(R.string.main_open_catalog),
                v -> startActivity(new Intent(this, CatalogActivity.class))));
        column.addView(button(getString(R.string.main_open_access),
                v -> startActivity(new Intent(this, AccessActivity.class))));
        column.addView(button(getString(R.string.main_open_diagnostics),
                v -> startActivity(new Intent(this, DiagnosticsActivity.class))));
        column.addView(button(getString(R.string.main_open_settings),
                v -> startActivity(new Intent(this, SettingsActivity.class))));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        AppExecutors.get().background(() -> {
            SnapshotStore snapshots = new SnapshotStore(app().storageRoot());
            ModuleStore modules = new ModuleStore(app().modulesRoot());
            Result<Snapshot> active = snapshots.active();
            Result<List<ModuleRecord>> list = modules.list();
            long generation = snapshots.currentGeneration();

            int installed = list.isOk() ? list.value().size() : 0;
            int enabled = 0;
            boolean pending = false;
            if (list.isOk()) {
                for (ModuleRecord record : list.value()) {
                    if (record.state.enabled) {
                        enabled++;
                    }
                    if (record.pendingApply(generation)) {
                        pending = true;
                    }
                }
            }

            String snapshotLine;
            if (generation <= 0 || !active.isOk()) {
                snapshotLine = getString(R.string.main_snapshot_none);
            } else {
                long age = System.currentTimeMillis() - active.value().createdWallMs;
                snapshotLine = age > STALE_AFTER_MS
                        ? getString(R.string.main_snapshot_stale, Formatters.humanAge(age))
                        : getString(R.string.main_snapshot_age,
                        (int) generation, Formatters.humanAge(age));
            }
            String modulesLine = getString(R.string.main_modules_summary, installed, enabled);
            String pendingLine = getString(pending
                    ? R.string.main_pending_restart : R.string.main_no_pending);

            AppExecutors.get().main(() -> {
                statusColumn.removeAllViews();
                statusColumn.addView(title(getString(R.string.main_bridge_title)));
                statusColumn.addView(body(getString(R.string.main_bridge_ready,
                        BridgeContract.AUTHORITY)));
                statusColumn.addView(body(snapshotLine));
                statusColumn.addView(body(modulesLine));
                statusColumn.addView(body(pendingLine));
                if (app().startupError() != null) {
                    statusColumn.addView(body(getString(R.string.state_error)
                            + ": " + app().startupError()));
                }
            });
        });
    }
}
