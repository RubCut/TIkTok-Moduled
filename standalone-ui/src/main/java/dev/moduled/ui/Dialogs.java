package dev.moduled.ui;

import android.content.Context;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * MD3-диалоги платформы (ТЗ §21.1, §6.4, §6.5).
 *
 * <p>Все подтверждения формулируются с последствиями: пользователь видит, что
 * произойдёт, а не просто «Да/Нет». Диалог несохранённых изменений всегда даёт
 * три выхода — сохранить, выйти без сохранения, отменить выход (CFG-08).</p>
 */
public final class Dialogs {

    private Dialogs() {
    }

    /** Действие с необратимыми или рискованными последствиями. */
    public static void confirmWithConsequences(Context context, String title, String message,
                                               String consequences, String confirmLabel,
                                               Runnable onConfirm) {
        String body = message + "\n\nПоследствия: " + consequences;
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(body)
                .setNegativeButton(R.string.moduled_action_cancel, null)
                .setPositiveButton(confirmLabel, (dialog, which) -> {
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                })
                .show();
    }

    /** CR-07: сброс safe mode — явное действие с указанием последствий. */
    public static void confirmSafeModeReset(Context context, Runnable onConfirm) {
        confirmWithConsequences(context,
                "Выйти из safe mode",
                "Модули снова будут загружаться при следующем запуске приложения.",
                "если сбой вызван модулем, приложение может упасть снова и safe mode включится "
                        + "заново после трёх подтверждённых сбоев. Рекомендуется сначала отключить "
                        + "подозрительные модули.",
                "Выйти из safe mode",
                onConfirm);
    }

    /** PKG-06/§14.2: установка поверх существующего ID требует проверки доверия. */
    public static void confirmReplaceModule(Context context, String moduleId, String details,
                                           Runnable onConfirm) {
        confirmWithConsequences(context,
                "Заменить модуль " + moduleId,
                details,
                "ID модуля сам по себе не доказывает того же автора. Существующий код будет заменён, "
                        + "конфиг сохранится, изменения применятся после перезапуска.",
                "Заменить",
                onConfirm);
    }

    /** CFG-08: выход с несохранёнными изменениями — три явных варианта. */
    public static void unsavedChanges(Context context, Runnable onSave, Runnable onDiscard,
                                      Runnable onStay) {
        new MaterialAlertDialogBuilder(context)
                .setTitle("Несохранённые изменения")
                .setMessage("Конфиг изменён. Сохранить изменения перед выходом?")
                .setPositiveButton(R.string.moduled_action_save, (dialog, which) -> {
                    if (onSave != null) {
                        onSave.run();
                    }
                })
                .setNegativeButton(R.string.moduled_action_discard, (dialog, which) -> {
                    if (onDiscard != null) {
                        onDiscard.run();
                    }
                })
                .setNeutralButton(R.string.moduled_action_stay, (dialog, which) -> {
                    if (onStay != null) {
                        onStay.run();
                    }
                })
                .setCancelable(false)
                .show();
    }

    /** Ошибка операции с реальной причиной — без имитации успеха. */
    public static void showError(Context context, String title, String message) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.moduled_action_close, null)
                .show();
    }

    /** Предупреждение о доверенном DEX перед первым включением модуля (ТЗ §14.1). */
    public static void showTrustedDexWarning(Context context, String moduleName,
                                             Runnable onAccept) {
        confirmWithConsequences(context,
                "Включить " + moduleName + "?",
                context.getString(R.string.moduled_warning_trusted_dex),
                "модуль получит те же возможности, что и TikTok. Отключение действует только "
                        + "после перезапуска приложения.",
                "Понятно, включить",
                onAccept);
    }
}
