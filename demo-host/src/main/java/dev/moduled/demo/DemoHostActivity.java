package dev.moduled.demo;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.card.MaterialCardView;

/**
 * Единственный экран демо-хоста: отметка «тестовый хост», состояние
 * делегирования Application и признак встроенного runtime (ТЗ §26.2).
 *
 * <p>Проверка runtime выполняется отражением: сам демо-хост НЕ зависит от
 * runtime-модуля, поэтому непатченая сборка остаётся честной — «runtime не
 * встроен» вместо имитации.</p>
 */
public final class DemoHostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);

        MaterialCardView banner = new MaterialCardView(this);
        TextView bannerText = new TextView(this);
        bannerText.setText(R.string.demo_banner);
        bannerText.setPadding(pad, pad, pad, pad);
        banner.addView(bannerText);
        column.addView(banner);

        column.addView(section(getString(R.string.demo_runtime_status), runtimeStatus()));
        column.addView(section(getString(R.string.demo_bridge_title), getString(R.string.demo_bridge_note)));
        column.addView(section("Делегирование Application (PAT-10)",
                "attachBaseContext: " + DemoHostApplication.attached
                        + "\nonCreate: " + DemoHostApplication.created));

        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private TextView section(String title, String body) {
        TextView view = new TextView(this);
        view.setText(title + "\n" + body);
        view.setPadding(0, dp(12), 0, 0);
        view.setTextIsSelectable(true);
        view.setGravity(Gravity.START);
        return view;
    }

    private String runtimeStatus() {
        try {
            Class<?> bootstrap = Class.forName("dev.moduled.runtime.RuntimeBootstrap");
            Object instance = bootstrap.getMethod("get").invoke(null);
            if (instance == null) {
                return "Runtime встроен, но bootstrap ещё не выполнялся в этом процессе.";
            }
            Object diagnostics = bootstrap.getMethod("diagnostics").invoke(instance);
            return "Runtime встроен и инициализирован:\n" + diagnostics;
        } catch (ClassNotFoundException e) {
            return getString(R.string.demo_runtime_absent);
        } catch (Throwable t) {
            return "Runtime встроен; состояние не читается: " + t;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
