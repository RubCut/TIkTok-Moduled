package dev.moduled.manager.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.OnBackPressedCallback;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.snackbar.Snackbar;
import dev.moduled.core.Limits;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.ui.Dialogs;
import dev.moduled.ui.Insets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Редактор непрозрачного конфига (ТЗ §12 CFG-05…CFG-11, §21.2 экран 5).
 *
 * <ul>
 *   <li>CFG-05: моноширинный шрифт, автокоррекция и предиктивный ввод выключены,
 *       синтаксис не подсвечивается — конфиг непрозрачен;</li>
 *   <li>CFG-06: сохранение атомарно ({@code ModuleStore.writeConfig}), старый
 *       файл не теряется при сбое;</li>
 *   <li>CFG-07: черновик переживает поворот экрана через onSaveInstanceState;</li>
 *   <li>CFG-08: «Назад» с несохранёнными изменениями — один диалог с тремя
 *       вариантами, изменения не теряются одним нажатием;</li>
 *   <li>CFG-09: изменения применяются после рестарта через снимок;</li>
 *   <li>CFG-10: локальные изменения модуля не синхронизируются обратно —
 *       редактор показывает фактически сохранённое содержимое;</li>
 *   <li>CFG-11: импорт/экспорт через системный выбор файла.</li>
 * </ul>
 */
public final class ConfigEditorActivity extends BaseScreenActivity {

    public static final String EXTRA_MODULE_ID = "moduleId";
    private static final int REQUEST_IMPORT = 4301;
    private static final int REQUEST_EXPORT = 4302;

    private ConfigDraftViewModel draftModel;
    private EditText editor;
    private String moduleId;
    /** Текст черновика, восстановленный после поворота/смерти процесса. */
    private String pendingRestore;

    public static Intent intent(android.content.Context context, String moduleId) {
        return new Intent(context, ConfigEditorActivity.class)
                .putExtra(EXTRA_MODULE_ID, moduleId);
    }

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_editor);
        moduleId = getIntent().getStringExtra(EXTRA_MODULE_ID);
        draftModel = new ViewModelProvider(this).get(ConfigDraftViewModel.class);

        LinearLayout column = scrollContent();
        column.addView(body(getString(R.string.editor_module_note)));

        editor = new EditText(this);
        editor.setTypeface(Typeface.MONOSPACE);
        // CFG-05: никакой автокоррекции, предиктивного ввода и подсказок.
        editor.setRawInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHorizontallyScrolling(false);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setMinLines(10);
        editor.setContentDescription(getString(R.string.editor_content_description));
        editor.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                draftModel.setDraft(s.toString());
            }
        });
        editor.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
        column.addView(editor);
        Insets.applyImePadding(editor);

        column.addView(button(getString(R.string.editor_save), v -> save()));
        LinearLayout importExport = new LinearLayout(this);
        importExport.setOrientation(LinearLayout.HORIZONTAL);
        importExport.addView(button(getString(R.string.editor_import), v -> pickImport()));
        importExport.addView(button(getString(R.string.editor_export), v -> pickExport()));
        column.addView(importExport);
        column.addView(body("Изменения применяются после перезапуска хоста (§12 CFG-09)."));

        // CFG-08: системное «Назад» не теряет изменения одним нажатием.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (draftModel.isDirty()) {
                    Dialogs.unsavedChanges(ConfigEditorActivity.this,
                            ConfigEditorActivity.this::save,
                            ConfigEditorActivity.this::finish,
                            null);
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState != null) {
            pendingRestore = savedInstanceState.getString(ConfigDraftViewModel.STATE_DRAFT, "");
        }
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // CFG-07: черновик переживает поворот и смерть процесса.
        outState.putString(ConfigDraftViewModel.STATE_DRAFT, editor.getText().toString());
        outState.putBoolean(ConfigDraftViewModel.STATE_DIRTY, draftModel.isDirty());
    }

    private void load() {
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<byte[]> config = store.readConfig(moduleId);
            AppExecutors.get().main(() -> {
                if (!config.isOk()) {
                    Dialogs.showError(this, getString(R.string.state_error),
                            config.errorCode() + ": " + config.errorMessage());
                    return;
                }
                String saved = new String(config.value(), StandardCharsets.UTF_8);
                draftModel.setBaseline(saved);
                if (pendingRestore != null) {
                    // Восстановленный черновик важнее файла: пользователь его
                    // редактировал до поворота (CFG-07).
                    editor.setText(pendingRestore);
                    draftModel.setDraft(pendingRestore);
                    pendingRestore = null;
                } else {
                    editor.setText(saved);
                }
            });
        });
    }

    private void save() {
        String text = editor.getText().toString();
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (data.length > Limits.MAX_CONFIG_BYTES) {
            Dialogs.showError(this, getString(R.string.state_error),
                    "Конфиг больше лимита " + Limits.MAX_CONFIG_BYTES + " байт");
            return;
        }
        AppExecutors.get().background(() -> {
            ModuleStore store = new ModuleStore(app().modulesRoot());
            Result<Void> written = store.writeConfig(moduleId, data);
            if (!written.isOk()) {
                // §11.5: UI возвращается к фактически сохранённому содержимому.
                Result<byte[]> actual = store.readConfig(moduleId);
                String actualText = actual.isOk()
                        ? new String(actual.value(), StandardCharsets.UTF_8) : "";
                AppExecutors.get().main(() -> {
                    editor.setTextKeepState(actualText);
                    draftModel.markSaved(actualText);
                    Dialogs.showError(this, getString(R.string.state_error),
                            getString(R.string.editor_save_failed,
                                    written.errorCode() + ": " + written.errorMessage()));
                });
                return;
            }
            AppExecutors.get().main(() -> {
                draftModel.markSaved(text);
                Snackbar.make(editor, R.string.editor_saved, Snackbar.LENGTH_LONG).show();
            });
        });
    }

    private void pickImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void pickExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, moduleId + "-config.txt");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_IMPORT) {
            AppExecutors.get().background(() -> {
                try {
                    byte[] bytes = readUri(data.getData(), Limits.MAX_CONFIG_BYTES);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    AppExecutors.get().main(() -> editor.setText(text));
                } catch (IOException e) {
                    AppExecutors.get().main(() -> Dialogs.showError(this,
                            getString(R.string.state_error),
                            "Импорт не удался: " + e.getMessage()));
                }
            });
        } else if (requestCode == REQUEST_EXPORT) {
            String text = editor.getText().toString();
            AppExecutors.get().background(() -> {
                try (OutputStream out = getContentResolver()
                        .openOutputStream(data.getData())) {
                    if (out == null) {
                        throw new IOException("не удалось открыть файл для записи");
                    }
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    AppExecutors.get().main(() -> Dialogs.showError(this,
                            getString(R.string.state_error),
                            "Экспорт не удался: " + e.getMessage()));
                }
            });
        }
    }

    private byte[] readUri(android.net.Uri uri, long limit) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("content resolver вернул null");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    throw new IOException("файл больше лимита " + limit + " байт");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
