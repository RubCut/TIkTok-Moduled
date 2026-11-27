package dev.moduled.manager.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import dev.moduled.core.CrashReports;
import dev.moduled.core.Result;
import dev.moduled.core.Snapshot;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.SnapshotStore;
import dev.moduled.ui.Clipboard;
import dev.moduled.ui.Dialogs;
import dev.moduled.ui.Formatters;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Диагностика (ТЗ §16.4, §18, §22, §21.2 экран 8): журнал runtime, отчёты о
 * сбоях с кнопкой «Скопировать» (автокопирование — только по явному согласию,
 * §18.5), сведения о мосте и возрасте снимка, состояние safe mode, контур
 * B-001.
 */
public final class DiagnosticsActivity extends BaseScreenActivity {

    /** Запрос перезапуска хоста передаётся с экрана модулей (§19). */
    public static final String EXTRA_RESTART_REQUEST = "restartRequest";

    private LinearLayout column;

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_diagnostics);
        column = scrollContent();
        if (getIntent().getBooleanExtra(EXTRA_RESTART_REQUEST, false)) {
            column.addView(body(getString(R.string.modules_restart_host)
                    + ": перезапуск планируется runtime'ом хоста (§19); "
                    + "при недоступном механизме — инструкция запустить приложение вручную."));
        }
        column.addView(body(getString(R.string.diagnostics_crash_coverage)));
        renderBridgeInfo();
        renderSnapshot();
        renderRuntimeLog();
        renderCrashReports();
        renderB001();
    }

    private void renderBridgeInfo() {
        column.addView(title(getString(R.string.diagnostics_bridge_info)));
        column.addView(mono("authority: " + dev.moduled.core.BridgeContract.AUTHORITY + '\n'
                + "permission: " + dev.moduled.core.BridgeContract.PERMISSION + '\n'
                + "проверка вызывающего: UID + сертификат (signature), §20.2\n"
                + "пути к файлам: только из внутреннего реестра, не из IPC-параметров"));
    }

    private void renderSnapshot() {
        column.addView(title(getString(R.string.diagnostics_snapshot)));
        AppExecutors.get().background(() -> {
            SnapshotStore store = new SnapshotStore(app().storageRoot());
            Result<Snapshot> active = store.active();
            long generation = store.currentGeneration();
            String text;
            if (active.isOk()) {
                long age = System.currentTimeMillis() - active.value().createdWallMs;
                text = "поколение: " + generation
                        + "\nвозраст: " + Formatters.humanAge(age)
                        + (age > MainActivity.STALE_AFTER_MS ? " (устарел)" : "")
                        + "\nмодулей в снимке: " + active.value().modules.size();
            } else {
                text = getString(R.string.main_snapshot_none);
            }
            AppExecutors.get().main(() -> column.addView(mono(text)));
        });
    }

    private void renderRuntimeLog() {
        column.addView(title(getString(R.string.diagnostics_runtime_log)));
        AppExecutors.get().background(() -> {
            File logFile = new File(app().logsRoot(), "runtime.log");
            String text = readTail(logFile, 200);
            AppExecutors.get().main(() -> {
                column.addView(mono(text));
                column.addView(button(getString(R.string.diagnostics_copy),
                        v -> Clipboard.copyText(this, "runtime-log", text)));
            });
        });
    }

    private void renderCrashReports() {
        column.addView(title(getString(R.string.diagnostics_crash_reports)));
        AppExecutors.get().background(() -> {
            CrashReports reports = app().crashReports();
            List<File> files = reports.reports();
            AppExecutors.get().main(() -> {
                if (files.isEmpty()) {
                    column.addView(body(getString(R.string.diagnostics_empty)));
                    return;
                }
                for (File file : files) {
                    String text = readTail(file, 400);
                    column.addView(mono(file.getName() + ":\n" + text));
                    // §18.5: кнопка «Скопировать» есть всегда; автокопирование —
                    // отдельная настройка по явному согласию.
                    column.addView(button(getString(R.string.diagnostics_copy),
                            v -> Clipboard.copyText(this, file.getName(), text)));
                }
            });
        });
    }

    /**
     * B-001 (§18.6): журнал показывает, что исходный обработчик сбоев хоста
     * сохранён до передачи ему управления. Закрытие дефекта возможно только по
     * реальному stack trace с устройства (§25) — здесь честное состояние контура.
     */
    private void renderB001() {
        column.addView(title(getString(R.string.diagnostics_b001)));
        AppExecutors.get().background(() -> {
            boolean installed = CrashReports.verifyHandlerStillInstalled(app().crashHandler());
            String text = "Обработчик диагностики TModules: "
                    + (installed ? "установлен" : "не установлен (или заменён хостом)")
                    + "\nПроверка сбоев записи/замены handler хостом: выполняется при старте."
                    + "\nРеальный stack trace B-001 с устройства: НЕ ПОЛУЧЕН — дефект не закрыт,"
                    + "\nконтур диагностики поставлен (§18.6, §25).";
            AppExecutors.get().main(() -> {
                column.addView(mono(text));
                column.addView(button(getString(R.string.diagnostics_copy),
                        v -> Clipboard.copyText(this, "b001", text)));
            });
        });
    }

    private String readTail(File file, int maxLines) {
        if (file == null || !file.isFile()) {
            return getString(R.string.diagnostics_empty);
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() > maxLines) {
                lines = lines.subList(lines.size() - maxLines, lines.size());
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return getString(R.string.state_error) + ": " + e.getMessage();
        }
    }
}
