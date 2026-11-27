package dev.moduled.manager.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import com.google.android.material.card.MaterialCardView;
import dev.moduled.core.CatalogIndex;
import dev.moduled.core.RemoteDescriptor;
import dev.moduled.core.Result;
import dev.moduled.manager.AppExecutors;
import dev.moduled.manager.R;
import dev.moduled.manager.repo.CatalogClient;
import dev.moduled.manager.repo.UrlFetcher;
import dev.moduled.manager.storage.InstallOutcome;
import dev.moduled.manager.storage.InstallRequest;
import dev.moduled.manager.storage.ModuleStore;
import dev.moduled.manager.storage.RepositoryStore;
import dev.moduled.manager.storage.SettingsStore;
import dev.moduled.ui.Dialogs;
import dev.moduled.ui.Formatters;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Каталог и репозитории (ТЗ §13, §21.2 экран 6).
 *
 * <ul>
 *   <li>обновление только по явному запросу пользователя, с прогрессом и
 *       отменой (§13.2);</li>
 *   <li>кеш последнего валидного каталога с датой: ошибка источника кеш не
 *       уничтожает (§13.1);</li>
 *   <li>дубли ID между источниками показываются пользователю для выбора;</li>
 *   <li>установка из репозитория — с подтверждением доверия, модуль
 *       устанавливается выключенным (§11.8);</li>
 *   <li>HTTPS-only, редиректы перепроверяются (UrlPolicy).</li>
 * </ul>
 */
public final class CatalogActivity extends BaseScreenActivity {

    private LinearLayout column;
    private LinearLayout resultsColumn;
    private EditText searchField;
    private final boolean[] cancelFlag = new boolean[1];
    private final Map<String, List<SourceEntry>> merged = new LinkedHashMap<>();

    /** Запись каталога с указанием источника: нужна для выбора при дублях ID. */
    private static final class SourceEntry {
        final RepositoryStore.Source source;
        final CatalogIndex.Entry entry;

        SourceEntry(RepositoryStore.Source source, CatalogIndex.Entry entry) {
            this.source = source;
            this.entry = entry;
        }
    }

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_catalog);
        column = scrollContent();
        column.addView(body(getString(R.string.catalog_https_only)));
        column.addView(button(getString(R.string.catalog_refresh), v -> refreshCatalogs()));
        column.addView(button(getString(R.string.catalog_cancel), v -> cancelFlag[0] = true));
        column.addView(button(getString(R.string.catalog_add_source), v -> addSourcePrompt()));

        searchField = new EditText(this);
        searchField.setHint(R.string.catalog_search_hint);
        searchField.setMinHeight(dp(48));
        searchField.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderResults(s.toString());
            }
        });
        column.addView(searchField);

        resultsColumn = new LinearLayout(this);
        resultsColumn.setOrientation(LinearLayout.VERTICAL);
        column.addView(resultsColumn);

        loadCaches();
    }

    private void addSourcePrompt() {
        EditText input = new EditText(this);
        input.setHint("https://…/catalog.json");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.catalog_add_source)
                .setView(input)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    AppExecutors.get().background(() -> {
                        RepositoryStore store = new RepositoryStore(app().repositoryRoot());
                        Result<Void> added = store.addSource(url, url);
                        AppExecutors.get().main(() -> {
                            if (!added.isOk()) {
                                Dialogs.showError(this, getString(R.string.state_error),
                                        added.errorCode() + ": " + added.errorMessage());
                            } else {
                                loadCaches();
                            }
                        });
                    });
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Загружает кеши источников (без сети) и показывает их возраст (§13.1). */
    private void loadCaches() {
        AppExecutors.get().background(() -> {
            RepositoryStore store = new RepositoryStore(app().repositoryRoot());
            List<RepositoryStore.Source> sources = store.sources();
            Map<String, List<SourceEntry>> result = new LinkedHashMap<>();
            List<String> notes = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (RepositoryStore.Source source : sources) {
                Result<RepositoryStore.CachedCatalog> cached = store.cachedCatalog(source.url);
                if (cached.isOk()) {
                    for (CatalogIndex.Entry entry : cached.value().index.modules) {
                        result.computeIfAbsent(entry.id, key -> new ArrayList<>())
                                .add(new SourceEntry(source, entry));
                    }
                    notes.add(source.url + ": " + getString(R.string.catalog_cached,
                            Formatters.humanAge(now - cached.value().updatedMs)));
                } else {
                    notes.add(source.url + ": " + getString(R.string.catalog_not_updated));
                }
                if (source.lastError != null && !source.lastError.isEmpty()) {
                    notes.add(getString(R.string.catalog_error_keep_cache, source.lastError));
                }
            }
            AppExecutors.get().main(() -> {
                merged.clear();
                merged.putAll(result);
                renderResults(searchField.getText().toString());
                for (String note : notes) {
                    column.addView(body(note));
                }
            });
        });
    }

    /** Обновление только по явному запросу (§13.2): сеть, прогресс, отмена. */
    private void refreshCatalogs() {
        cancelFlag[0] = false;
        AppExecutors.get().background(() -> {
            RepositoryStore store = new RepositoryStore(app().repositoryRoot());
            CatalogClient client = new CatalogClient(new UrlFetcher());
            List<String> notes = new ArrayList<>();
            for (RepositoryStore.Source source : store.sources()) {
                if (!source.enabled) {
                    continue;
                }
                if (cancelFlag[0]) {
                    notes.add(source.url + ": " + getString(R.string.catalog_cancel));
                    break;
                }
                Result<CatalogIndex> fetched = client.fetchCatalog(source.url, cancelFlag);
                if (fetched.isOk()) {
                    // Кеш обновляется только валидным каталогом.
                    store.cacheCatalog(source.url,
                            dev.moduled.core.JsonWriter.writeValue(
                                    catalogToMap(fetched.value()))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    notes.add(source.url + ": OK, модулей " + fetched.value().modules.size());
                } else {
                    // Ошибка источника не уничтожает кеш (§13.1).
                    store.recordError(source.url,
                            fetched.errorCode() + ": " + fetched.errorMessage());
                    notes.add(getString(R.string.catalog_error_keep_cache,
                            fetched.errorCode() + ": " + fetched.errorMessage()));
                }
            }
            AppExecutors.get().main(() -> {
                for (String note : notes) {
                    column.addView(body(note));
                }
                loadCaches();
            });
        });
    }

    private Map<String, Object> catalogToMap(CatalogIndex index) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", CatalogIndex.SCHEMA_VERSION);
        map.put("name", index.name);
        List<Object> modules = new ArrayList<>();
        for (CatalogIndex.Entry entry : index.modules) {
            Map<String, Object> module = new LinkedHashMap<>();
            module.put("id", entry.id);
            module.put("name", entry.name);
            module.put("description", entry.description);
            module.put("manifestUrl", entry.manifestUrl);
            modules.add(module);
        }
        map.put("modules", modules);
        return map;
    }

    private void renderResults(String query) {
        resultsColumn.removeAllViews();
        for (Map.Entry<String, List<SourceEntry>> group : merged.entrySet()) {
            List<SourceEntry> entries = group.getValue();
            SourceEntry first = entries.get(0);
            if (query != null && !query.isEmpty()
                    && !matches(query, first.entry) && entries.size() < 2) {
                continue;
            }
            if (query != null && !query.isEmpty()) {
                boolean any = false;
                for (SourceEntry entry : entries) {
                    if (matches(query, entry.entry)) {
                        any = true;
                        break;
                    }
                }
                if (!any) {
                    continue;
                }
            }
            resultsColumn.addView(entryCard(group.getKey(), entries));
        }
        if (resultsColumn.getChildCount() == 0) {
            resultsColumn.addView(body(getString(R.string.modules_empty)));
        }
    }

    private boolean matches(String query, CatalogIndex.Entry entry) {
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        return entry.id.toLowerCase(java.util.Locale.ROOT).contains(lower)
                || (entry.name != null && entry.name.toLowerCase(java.util.Locale.ROOT).contains(lower))
                || (entry.description != null
                && entry.description.toLowerCase(java.util.Locale.ROOT).contains(lower));
    }

    private MaterialCardView entryCard(String moduleId, List<SourceEntry> entries) {
        MaterialCardView card = new MaterialCardView(this);
        int pad = dp(12);
        card.setContentPadding(pad, pad, pad, pad);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        SourceEntry first = entries.get(0);
        inner.addView(title(first.entry.name != null ? first.entry.name : moduleId));
        inner.addView(body(moduleId));
        if (first.entry.description != null && !first.entry.description.isEmpty()) {
            inner.addView(body(first.entry.description));
        }
        if (entries.size() > 1) {
            // Дубль ID между источниками: выбор за пользователем (§13.1).
            inner.addView(body(getString(R.string.catalog_duplicate_id, moduleId)));
        }
        for (SourceEntry entry : entries) {
            inner.addView(button(getString(R.string.catalog_install) + " · " + entry.source.url,
                    v -> confirmInstall(entry)));
        }
        card.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private void confirmInstall(SourceEntry entry) {
        Dialogs.confirmWithConsequences(this,
                getString(R.string.catalog_install),
                getString(R.string.catalog_install_confirm, entry.entry.id, entry.source.url),
                "будет скачан архив, проверен SHA-256 точных байтов и сопоставлен с descriptor'ом; "
                        + "при любом расхождении установка отменяется",
                getString(R.string.action_ok),
                () -> download(entry));
    }

    private void download(SourceEntry entry) {
        cancelFlag[0] = false;
        column.addView(body(getString(R.string.catalog_downloading)));
        AppExecutors.get().background(() -> {
            CatalogClient client = new CatalogClient(new UrlFetcher());
            Result<RemoteDescriptor> descriptor =
                    client.fetchDescriptor(entry.entry.manifestUrl, cancelFlag);
            if (!descriptor.isOk()) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error),
                        descriptor.errorCode() + ": " + descriptor.errorMessage()));
                return;
            }
            Result<CatalogClient.DownloadedModule> downloaded = client.downloadModule(
                    descriptor.value(), app().downloadsRoot(), cancelFlag);
            if (!downloaded.isOk()) {
                AppExecutors.get().main(() -> Dialogs.showError(this,
                        getString(R.string.state_error),
                        downloaded.errorCode() + ": " + downloaded.errorMessage()));
                return;
            }
            install(downloaded.value(), entry);
        });
    }

    private void install(CatalogClient.DownloadedModule downloaded, SourceEntry entry) {
        ModuleStore store = new ModuleStore(app().modulesRoot());
        InstallRequest.Builder request = InstallRequest
                .fromRepository(downloaded.archive, entry.source.url)
                .archiveSha256(downloaded.archiveSha256)
                .authorKeyFingerprint(downloaded.descriptor.authorKeyFingerprint);
        Result<InstallOutcome> result = store.install(request.build());
        AppExecutors.get().main(() -> {
            if (result.isOk()) {
                Dialogs.showError(this, getString(R.string.screen_catalog),
                        getString(R.string.modules_new_installed_disabled));
                return;
            }
            if ("INSTALL_REQUIRES_CONFIRMATION".equals(result.errorCode())
                    || "INSTALL_KEY_CHANGED".equals(result.errorCode())) {
                Dialogs.confirmReplaceModule(this, entry.entry.id, result.errorMessage(),
                        () -> AppExecutors.get().background(() -> {
                            Result<InstallOutcome> confirmed = store.install(request
                                    .confirmReplace(true)
                                    .confirmNewKey("INSTALL_KEY_CHANGED".equals(result.errorCode()))
                                    .build());
                            AppExecutors.get().main(() -> {
                                if (!confirmed.isOk()) {
                                    Dialogs.showError(this, getString(R.string.state_error),
                                            confirmed.errorCode() + ": "
                                                    + confirmed.errorMessage());
                                }
                            });
                        }));
                return;
            }
            Dialogs.showError(this, getString(R.string.state_error),
                    result.errorCode() + ": " + result.errorMessage());
        });
    }
}
