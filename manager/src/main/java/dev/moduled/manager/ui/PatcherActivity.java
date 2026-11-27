package dev.moduled.manager.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import com.google.android.material.button.MaterialButton;
import dev.moduled.core.Hashing;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.patcher.AbiSupport;
import dev.moduled.patcher.ApkAnalysis;
import dev.moduled.patcher.ApkAnalyzer;
import dev.moduled.patcher.CompatibilityProfile;
import dev.moduled.patcher.PatchOutcome;
import dev.moduled.patcher.PatchPlan;
import dev.moduled.patcher.PatcherEngine;
import dev.moduled.patcher.RuntimePayload;
import dev.moduled.patcher.SigningKeys;
import dev.moduled.patcher.SigningProfile;
import dev.moduled.ui.Clipboard;
import dev.moduled.ui.Dialogs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран патчера (ТЗ §7, §21.2 экран 2).
 *
 * <p>Порядок обязателен: выбор APK → анализ (исходный файл не изменяется) →
 * выбор ABI → план → подтверждение → патч и подпись → публикация готового APK
 * только после всех проверок. Ключ подписи создаётся один раз во внутреннем
 * хранилище; конфликт подписи показывается до установки.</p>
 */
public final class PatcherActivity extends BaseScreenActivity {

    private static final int REQUEST_PICK_APK = 4101;

    private LinearLayout column;
    private CheckBox abiArm64;
    private CheckBox abiArmV7;
    private CheckBox abiX86_64;
    private MaterialButton planButton;
    private MaterialButton patchButton;

    private File selectedApk;
    private ApkAnalysis analysis;
    private PatchPlan plan;
    private File payloadDex;

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_patcher);
        column = scrollContent();

        column.addView(body(getString(R.string.patcher_original_untouched)));
        column.addView(body(getString(R.string.patcher_signature_note)));
        column.addView(button(getString(R.string.patcher_pick_apk), v -> pickApk()));

        column.addView(title(getString(R.string.patcher_abi_title)));
        abiArm64 = checkBox("arm64-v8a", true);
        abiArmV7 = checkBox("armeabi-v7a", false);
        abiX86_64 = checkBox("x86_64", false);
        column.addView(abiArm64);
        column.addView(abiArmV7);
        column.addView(abiX86_64);

        planButton = button(getString(R.string.patcher_plan), v -> buildPlan());
        planButton.setEnabled(false);
        column.addView(planButton);

        patchButton = button(getString(R.string.patcher_run), v -> confirmAndPatch());
        patchButton.setEnabled(false);
        column.addView(patchButton);

        column.addView(body(getString(R.string.patcher_output_note)));

        AppExecutors.get().background(this::preparePayload);
    }

    private CheckBox checkBox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        box.setContentDescription(getString(R.string.patcher_abi_title) + ": " + label);
        return box;
    }

    /**
     * Полезная нагрузка платформы извлекается из assets один раз (§7.2 PAT-05).
     * Если runtime DEX не встроен в сборку, экран честно сообщает об этом:
     * тихий отказ недопустим (§22).
     */
    private void preparePayload() {
        File dir = new File(app().storageRoot(), "payload");
        File dex = new File(dir, "runtime-classes.dex");
        boolean ok = dex.isFile() && dex.length() > 0;
        if (!ok) {
            ok = copyAsset("tmoduled/runtime-classes.dex", dex);
        }
        boolean ready = ok;
        AppExecutors.get().main(() -> {
            if (ready) {
                payloadDex = dex;
                if (selectedApk != null) {
                    planButton.setEnabled(true);
                }
            } else {
                planButton.setEnabled(false);
                patchButton.setEnabled(false);
                Dialogs.showError(this, getString(R.string.state_error),
                        getString(R.string.patcher_payload_missing));
            }
        });
    }

    private boolean copyAsset(String assetPath, File target) {
        try (InputStream in = getAssets().open(assetPath)) {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            return target.length() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        try {
            startActivityForResult(intent, REQUEST_PICK_APK);
        } catch (android.content.ActivityNotFoundException e) {
            // Некоторые устройства не знают MIME APK: пробуем любой файл.
            Intent any = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            any.addCategory(Intent.CATEGORY_OPENABLE);
            any.setType("*/*");
            startActivityForResult(any, REQUEST_PICK_APK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        AppExecutors.get().background(() -> {
            File work = new File(app().storageRoot(), "patcher-input");
            if (!work.isDirectory() && !work.mkdirs()) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error), "Нет рабочего каталога"));
                return;
            }
            File copy = new File(work, "input-" + System.currentTimeMillis() + ".apk");
            String displayName = queryDisplayName(uri);
            try {
                copyUri(uri, copy);
            } catch (IOException e) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error),
                        "Не удалось прочитать файл: " + e.getMessage()));
                return;
            }
            Result<ApkAnalysis> analyzed = ApkAnalyzer.analyze(copy);
            AppExecutors.get().main(() -> {
                if (!analyzed.isOk()) {
                    Dialogs.showError(this, getString(R.string.patcher_failed),
                            analyzed.errorCode() + ": " + analyzed.errorMessage());
                    return;
                }
                selectedApk = copy;
                analysis = analyzed.value();
                plan = null;
                patchButton.setEnabled(false);
                planButton.setEnabled(payloadDex != null);
                column.addView(title(displayName != null ? displayName : copy.getName()));
                column.addView(mono(analysis.toReportText()));
            });
        });
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException e) {
            // Имя не критично: покажем имя копии.
        }
        return null;
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

    private List<Integer> selectedAbis() {
        List<Integer> abis = new ArrayList<>();
        if (abiArm64.isChecked()) {
            abis.add(AbiSupport.ARM64_V8A);
        }
        if (abiArmV7.isChecked()) {
            abis.add(AbiSupport.ARMEABI_V7A);
        }
        if (abiX86_64.isChecked()) {
            abis.add(AbiSupport.X86_64);
        }
        return abis;
    }

    private CompatibilityProfile profileFor(ApkAnalysis analysis) {
        // Профиль первой поставки (§28): фактический вход и выбранные ABI.
        // versionName не фиксируется: пользователь сам выбирает версию APK.
        List<Integer> abis = selectedAbis();
        String abiName = AbiSupport.name(abis.isEmpty() ? AbiSupport.ARM64_V8A : abis.get(0));
        CompatibilityProfile.Builder builder = CompatibilityProfile
                .builder(analysis.packageId + "-" + abiName)
                .packageId(analysis.packageId)
                .versionCodes(analysis.versionCode)
                .abiName(abiName)
                .baseApkOnly(true)
                .note("Профиль создан по фактическому анализу выбранного APK");
        builder.supportedAbis(abis.toArray(new Integer[0]));
        return builder.build();
    }

    private void buildPlan() {
        if (selectedApk == null || payloadDex == null) {
            return;
        }
        List<Integer> abis = selectedAbis();
        if (abis.isEmpty()) {
            Dialogs.showError(this, getString(R.string.state_error),
                    "Выберите хотя бы один ABI");
            return;
        }
        AppExecutors.get().background(() -> {
            PatcherEngine engine = new PatcherEngine(payload(),
                    new File(app().storageRoot(), "patcher-work"));
            File keyDir = new File(app().storageRoot(), "keys");
            Result<SigningKeys.Loaded> key = SigningKeys.loadOrCreate(
                    keyDir, SigningKeys.DEFAULT_ALIAS, SigningProfile.Kind.DEVELOPMENT_SELF_SIGNED);
            if (!key.isOk()) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.patcher_failed),
                        key.errorCode() + ": " + key.errorMessage()));
                return;
            }
            Result<PatchPlan> planned = engine.plan(selectedApk, profileFor(analysis), abis,
                    null, key.value().fingerprint, null);
            AppExecutors.get().main(() -> {
                if (!planned.isOk()) {
                    Dialogs.showError(this, getString(R.string.patcher_failed),
                            planned.errorCode() + ": " + planned.errorMessage());
                    return;
                }
                plan = planned.value();
                column.addView(title(getString(R.string.patcher_plan)));
                column.addView(mono(plan.toText()));
                column.addView(button(getString(R.string.patcher_copy_report),
                        v -> Clipboard.copyText(this, "plan", plan.toText())));
                patchButton.setEnabled(plan.canProceed());
            });
        });
    }

    private RuntimePayload payload() {
        return RuntimePayload.builder()
                .dex(payloadDex)
                .runtimeVersion(dev.moduled.manager.TModulesApplication.VERSION)
                .adapterName("tiktok-generic")
                .build();
    }

    private void confirmAndPatch() {
        if (plan == null) {
            return;
        }
        Dialogs.confirmWithConsequences(this,
                getString(R.string.patcher_run),
                getString(R.string.patcher_original_untouched),
                "исходный APK не изменяется; готовый файл появится в каталоге публикации "
                        + "только если подпись и все проверки пройдены",
                getString(R.string.action_ok),
                this::runPatch);
    }

    private void runPatch() {
        patchButton.setEnabled(false);
        planButton.setEnabled(false);
        column.addView(body(getString(R.string.patcher_running)));
        AppExecutors.get().background(() -> {
            PatcherEngine engine = new PatcherEngine(payload(),
                    new File(app().storageRoot(), "patcher-work"));
            File keyDir = new File(app().storageRoot(), "keys");
            File publicDir = publicOutputDir();
            String outputName = analysis.packageId + "-tmoduled-v"
                    + analysis.versionName + ".apk";
            Result<PatchOutcome> patched = engine.patch(plan, selectedApk, keyDir,
                    SigningKeys.DEFAULT_ALIAS, outputName, publicDir);
            AppExecutors.get().main(() -> {
                planButton.setEnabled(true);
                if (!patched.isOk()) {
                    Dialogs.showError(this, getString(R.string.patcher_failed),
                            patched.errorCode() + ": " + patched.errorMessage());
                    return;
                }
                PatchOutcome outcome = patched.value();
                saveLastPatchReport(outcome);
                patchButton.setEnabled(false);
                column.addView(title(outcome.succeeded()
                        ? getString(R.string.patcher_done, outputName)
                        : getString(R.string.patcher_failed)));
                column.addView(mono(outcome.toReportText()));
                column.addView(button(getString(R.string.patcher_copy_report),
                        v -> Clipboard.copyText(this, "patch-report", outcome.toReportText())));
                if (!outcome.succeeded()) {
                    Dialogs.showError(this, getString(R.string.patcher_failed),
                            String.join("\n", outcome.blockers));
                    return;
                }
                offerBundledRegionModule();
            });
        });
    }

    /** Отчёт последнего патча сохраняется для экрана доступов (§15.2, diff manifest). */
    private void saveLastPatchReport(PatchOutcome outcome) {
        try {
            java.nio.file.Files.write(
                    new File(app().storageRoot(), AccessActivity.LAST_PATCH_DIFF_FILE).toPath(),
                    outcome.toReportText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Отчёт не критичен для результата патча: экран доступов покажет пустое состояние.
        }
    }

    /**
     * §16.1: после успешного патча предлагается комплектный модуль региона.
     * Установка выполняется обычным порядком — модуль ставится выключенным.
     */
    private void offerBundledRegionModule() {
        List<String> bundled = dev.moduled.manager.BundledModules.list(this);
        String region = null;
        for (String asset : bundled) {
            if (asset.contains("region")) {
                region = asset;
                break;
            }
        }
        if (region == null) {
            return;
        }
        final String assetName = region;
        Dialogs.confirmWithConsequences(this,
                getString(R.string.modules_install_bundled),
                getString(R.string.modules_bundled_offer_region),
                "модуль региона подменяет региональные параметры только на уровне "
                        + "приложения и не меняет IP, SIM, GPS и регион аккаунта (§16.4)",
                getString(R.string.catalog_install),
                () -> AppExecutors.get().background(() -> {
                    Result<dev.moduled.manager.storage.InstallOutcome> installed =
                            dev.moduled.manager.BundledModules.install(this, app(), assetName);
                    AppExecutors.get().main(() -> {
                        if (!installed.isOk()) {
                            Dialogs.showError(this, getString(R.string.state_error),
                                    installed.errorCode() + ": " + installed.errorMessage());
                        }
                    });
                }));
    }

    /**
     * Каталог публикации (§20.4): готовые артефакты — в
     * {@code /storage/emulated/0/tmoduled}. Если общего хранилища нет
     * (ограничения ОС), используется каталог внешнего хранилища приложения,
     * а путь честно показывается в отчёте.
     */
    private File publicOutputDir() {
        File shared = new File(Environment.getExternalStorageDirectory(), "tmoduled");
        if (shared.isDirectory() || shared.mkdirs()) {
            return shared;
        }
        File fallback = getExternalFilesDir("tmoduled");
        return fallback != null ? fallback : new File(app().storageRoot(), "out");
    }
}
