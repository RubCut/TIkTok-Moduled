package dev.moduled.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Минимальный экран восстановления в safe mode (ТЗ §18.1 CR-03: «минимальная
 * часть runtime для диагностики и восстановления остаётся»).
 *
 * <p>Намеренно построен на базовых виджетах Android без Material Components:
 * MD3-библиотеки не переносятся внутрь хоста (PAT-07, §21.1). Активность
 * объявляется патчером в manifest хоста; без объявления она не доступна, и это
 * фиксируется в отчёте патчинга.</p>
 */
public final class SafeModeRecoveryActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("TikTok Moduled: safe mode");
        title.setTextSize(20f);
        root.addView(title);

        TextView body = new TextView(this);
        body.setText("Модули отключены: зарегистрировано несколько подтверждённых сбоев "
                + "подряд. Приложение работает без пользовательских и комплектных модулей, "
                + "включая модуль региона и внутренний менеджер.");
        body.setPadding(0, dp(12), 0, dp(12));
        root.addView(body);

        RuntimeBootstrap bootstrap = RuntimeBootstrap.get();
        TextView details = new TextView(this);
        if (bootstrap == null) {
            details.setText("Runtime не инициализирован в этом процессе: откройте приложение "
                    + "заново или воспользуйтесь TModules для диагностики.");
        } else {
            details.setText(bootstrap.diagnostics().toReport());
            details.setTextIsSelectable(true);
        }
        root.addView(details);

        Button reset = new Button(this);
        reset.setText("Выйти из safe mode и включить модули");
        reset.setMinimumHeight(dp(48));
        reset.setOnClickListener(view -> {
            RuntimeBootstrap current = RuntimeBootstrap.get();
            if (current != null) {
                current.resetSafeMode("SafeModeRecoveryActivity");
            }
            TextView note = new TextView(SafeModeRecoveryActivity.this);
            note.setText("Защёлка снята. Закройте и откройте приложение заново: "
                    + "модули загружаются только при следующем запуске процесса.");
            note.setPadding(0, dp(12), 0, 0);
            root.addView(note);
            reset.setEnabled(false);
        });
        LinearLayout.LayoutParams resetParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        resetParams.topMargin = dp(16);
        resetParams.gravity = Gravity.START;
        root.addView(reset, resetParams);

        Button close = new Button(this);
        close.setText("Закрыть");
        close.setMinimumHeight(dp(48));
        close.setOnClickListener(view -> finish());
        root.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Проверка доступности экрана для патчера и диагностики. */
    public static boolean isDeclared(android.content.Context context) {
        try {
            android.content.pm.ActivityInfo[] activities = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),
                            android.content.pm.PackageManager.GET_ACTIVITIES).activities;
            if (activities == null) {
                return false;
            }
            for (android.content.pm.ActivityInfo info : activities) {
                if (SafeModeRecoveryActivity.class.getName().equals(info.name)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
