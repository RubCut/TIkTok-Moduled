package dev.moduled.manager.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import dev.moduled.manager.R;
import dev.moduled.manager.TModulesApplication;
import dev.moduled.ui.Insets;

/**
 * Каркас экрана TModules (ТЗ §21.2, §21.7): MaterialToolbar, edge-to-edge,
 * системные insets, минимальная ширина 360 dp достигается вертикальной
 * компоновкой без фиксированных горизонтальных размеров.
 */
public abstract class BaseScreenActivity extends AppCompatActivity {

    protected MaterialToolbar toolbar;
    protected FrameLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_screen);
        LinearLayout root = findViewById(R.id.screen_root);
        Insets.applySystemBars(root);
        toolbar = findViewById(R.id.toolbar);
        content = findViewById(R.id.screen_content);
        if (showsUpNavigation()) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
            toolbar.setNavigationOnClickListener(v -> finish());
        }
        onCreateScreen(savedInstanceState);
    }

    protected boolean showsUpNavigation() {
        return true;
    }

    protected abstract void onCreateScreen(Bundle savedInstanceState);

    protected TModulesApplication app() {
        return (TModulesApplication) getApplication();
    }

    /** Вертикальный прокручиваемый контейнер с отступами 16 dp. */
    protected LinearLayout scrollContent() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        column.setPadding(padding, padding, padding, padding);
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return column;
    }

    protected int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Кнопка высотой не менее 48 dp (§21.6). */
    protected com.google.android.material.button.MaterialButton button(String text,
                                                                       View.OnClickListener onClick) {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(this);
        button.setText(text);
        button.setMinHeight(dp(48));
        button.setOnClickListener(onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        button.setLayoutParams(params);
        return button;
    }

    protected android.widget.TextView title(String text) {
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(text);
        view.setTextAppearance(R.style.Widget_Moduled_SectionTitle);
        return view;
    }

    protected android.widget.TextView body(String text) {
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(text);
        view.setTextAppearance(R.style.Widget_Moduled_Body);
        view.setFocusable(true);
        return view;
    }

    protected android.widget.TextView mono(String text) {
        android.widget.TextView view = new android.widget.TextView(this);
        view.setText(text);
        view.setTextAppearance(R.style.Widget_Moduled_MonoBody);
        view.setTextIsSelectable(true);
        view.setFocusable(true);
        return view;
    }
}
