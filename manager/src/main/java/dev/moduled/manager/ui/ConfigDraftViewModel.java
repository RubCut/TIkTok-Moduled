package dev.moduled.manager.ui;

import androidx.lifecycle.ViewModel;

/**
 * Черновик конфига, переживающий поворот экрана и смерть процесса через
 * SavedStateHandle-хранилище активности (ТЗ §12 CFG-07).
 *
 * <p>Хранит текст и признак изменённости. Фактическое сохранение выполняет
 * {@code ModuleStore.writeConfig} атомарно (§12 CFG-06); ViewModel отвечает
 * только за то, чтобы черновик не терялся при конфигурационных изменениях.</p>
 */
public final class ConfigDraftViewModel extends ViewModel {

    /** Ключ состояния черновика в SavedStateHandle активности. */
    public static final String STATE_DRAFT = "config-draft";
    public static final String STATE_DIRTY = "config-dirty";
    public static final String STATE_BASELINE = "config-baseline";

    private String draft = "";
    private String baseline = "";
    private boolean dirty;
    private boolean loaded;

    public String draft() {
        return draft;
    }

    public void setDraft(String value) {
        this.draft = value != null ? value : "";
        this.dirty = !this.draft.equals(baseline);
    }

    public String baseline() {
        return baseline;
    }

    public void setBaseline(String value) {
        this.baseline = value != null ? value : "";
        if (!loaded) {
            this.draft = this.baseline;
            loaded = true;
        }
        this.dirty = !this.draft.equals(this.baseline);
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isLoaded() {
        return loaded;
    }

    /** После успешного сохранения сохранённое становится базой (§11.5: без рекурсии). */
    public void markSaved(String savedText) {
        this.baseline = savedText != null ? savedText : "";
        this.draft = this.baseline;
        this.dirty = false;
        this.loaded = true;
    }
}
