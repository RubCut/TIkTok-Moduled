package dev.moduled.manager.ui;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.radiobutton.MaterialRadioButton;
import dev.moduled.manager.R;
import dev.moduled.manager.storage.SettingsStore;
import dev.moduled.ui.ThemeApplier;
import dev.moduled.ui.ThemeMode;

/**
 * Настройки (ТЗ §21.1 тема, §18.5 автокопирование отчёта, §22 журналирование).
 * Все значения сохраняются атомарно через {@link SettingsStore}; изменение темы
 * применяется сразу, но не пересоздаёт экраны принудительно — новые значения
 * подхватываются при следующем открытии.
 */
public final class SettingsActivity extends BaseScreenActivity {

    private boolean applying;

    @Override
    protected void onCreateScreen(Bundle savedInstanceState) {
        toolbar.setTitle(R.string.screen_settings);
        LinearLayout column = scrollContent();
        SettingsStore settings = app().settings();

        column.addView(title(getString(R.string.settings_theme)));
        RadioGroup themeGroup = new RadioGroup(this);
        addThemeOption(themeGroup, ThemeMode.SYSTEM, R.string.settings_theme_system);
        addThemeOption(themeGroup, ThemeMode.LIGHT, R.string.settings_theme_light);
        addThemeOption(themeGroup, ThemeMode.DARK, R.string.settings_theme_dark);
        addThemeOption(themeGroup, ThemeMode.DYNAMIC, R.string.settings_theme_dynamic);
        ThemeMode current = ThemeMode.fromStorage(
                settings.getString(SettingsStore.KEY_THEME, ThemeMode.SYSTEM.name()));
        applying = true;
        for (int i = 0; i < themeGroup.getChildCount(); i++) {
            MaterialRadioButton radio = (MaterialRadioButton) themeGroup.getChildAt(i);
            if (radio.getTag() == current) {
                radio.setChecked(true);
            }
        }
        applying = false;
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (applying) {
                return;
            }
            MaterialRadioButton selected = group.findViewById(checkedId);
            if (selected == null) {
                return;
            }
            ThemeMode mode = (ThemeMode) selected.getTag();
            settings.setString(SettingsStore.KEY_THEME, mode.name());
            ThemeApplier.apply(getApplication(), mode);
            recreate();
        });
        column.addView(themeGroup);

        column.addView(title(getString(R.string.settings_log_privacy)));
        RadioGroup privacyGroup = new RadioGroup(this);
        addPrivacyOption(privacyGroup, SettingsStore.LogPrivacy.MINIMAL,
                R.string.settings_privacy_minimal);
        addPrivacyOption(privacyGroup, SettingsStore.LogPrivacy.NORMAL,
                R.string.settings_privacy_normal);
        addPrivacyOption(privacyGroup, SettingsStore.LogPrivacy.VERBOSE,
                R.string.settings_privacy_verbose);
        SettingsStore.LogPrivacy privacy = settings.logPrivacy();
        applying = true;
        for (int i = 0; i < privacyGroup.getChildCount(); i++) {
            MaterialRadioButton radio = (MaterialRadioButton) privacyGroup.getChildAt(i);
            if (radio.getTag() == privacy) {
                radio.setChecked(true);
            }
        }
        applying = false;
        privacyGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (applying) {
                return;
            }
            MaterialRadioButton selected = group.findViewById(checkedId);
            if (selected != null) {
                settings.setLogPrivacy((SettingsStore.LogPrivacy) selected.getTag());
            }
        });
        column.addView(privacyGroup);

        column.addView(title(getString(R.string.settings_auto_copy_crash)));
        MaterialSwitch autoCopy = new MaterialSwitch(this);
        autoCopy.setMinHeight(dp(48));
        autoCopy.setChecked(settings.autoCopyCrashReport());
        autoCopy.setText(getString(R.string.settings_auto_copy_note));
        autoCopy.setOnCheckedChangeListener((view, checked) ->
                settings.setBoolean(SettingsStore.KEY_AUTO_COPY_CRASH, checked));
        column.addView(autoCopy);

        column.addView(title(getString(R.string.settings_about)));
        column.addView(body(getString(R.string.settings_about_body)));
    }

    private void addThemeOption(RadioGroup group, ThemeMode mode, int labelRes) {
        MaterialRadioButton radio = new MaterialRadioButton(this);
        // Программным view нужны явные ID: RadioGroup отслеживает выбор по ID.
        radio.setId(android.view.View.generateViewId());
        radio.setText(labelRes);
        radio.setTag(mode);
        radio.setMinHeight(dp(48));
        group.addView(radio);
    }

    private void addPrivacyOption(RadioGroup group, SettingsStore.LogPrivacy privacy,
                                  int labelRes) {
        MaterialRadioButton radio = new MaterialRadioButton(this);
        radio.setId(android.view.View.generateViewId());
        radio.setText(labelRes);
        radio.setTag(privacy);
        radio.setMinHeight(dp(48));
        group.addView(radio);
    }
}
