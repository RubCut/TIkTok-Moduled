package dev.moduled.manager.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import dev.moduled.core.BridgeContract;
import dev.moduled.core.ModuleId;
import dev.moduled.core.Result;

/**
 * Проверка вызывающей стороны моста (ТЗ §20.2).
 *
 * <p>Для каждой чувствительной операции проверяются UID вызывающего, его пакет и
 * совпадение сертификата с TModules. Signature-permission в manifest — первый
 * уровень, но он не заменяет явную проверку: прошивка или установка с другим
 * сертификатом не должны получать доступ к снимку и DEX.</p>
 */
public final class BridgeGuard {

    private BridgeGuard() {
    }

    public static Result<String> verifyCaller(Context context, String callingPackage) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) {
            return Result.ok(context.getPackageName());
        }
        if (callingPackage == null || callingPackage.isEmpty()) {
            return Result.fail("BRIDGE_NO_CALLER", "Вызывающий пакет не определён");
        }
        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        boolean uidMatches = false;
        if (packages != null) {
            for (String candidate : packages) {
                if (callingPackage.equals(candidate)) {
                    uidMatches = true;
                    break;
                }
            }
        }
        if (!uidMatches) {
            return Result.fail("BRIDGE_UID_MISMATCH",
                    "Пакет " + callingPackage + " не соответствует UID вызывающего " + callingUid);
        }
        int signatures = pm.checkSignatures(context.getPackageName(), callingPackage);
        if (signatures != PackageManager.SIGNATURE_MATCH) {
            return Result.fail("BRIDGE_SIGNATURE_MISMATCH",
                    "Сертификат вызывающего пакета не совпадает с TModules (код " + signatures
                            + "); мост доступен только приложениям с той же подписью");
        }
        return Result.ok(callingPackage);
    }

    /**
     * Проверяет параметр пути: мост не выбирает произвольный файл из
     * IPC-параметра (§20.2), а строит путь из валидированного ID модуля.
     */
    public static Result<String> verifyModuleId(String moduleId) {
        if (!ModuleId.isValid(moduleId)) {
            return Result.fail("BRIDGE_BAD_MODULE_ID",
                    "Недопустимый ID модуля в запросе моста: " + moduleId);
        }
        return Result.ok(moduleId);
    }

    /** Разрешён только режим чтения: запись через мост не предусмотрена. */
    public static Result<Void> verifyReadOnly(String mode) {
        if (mode == null || !mode.contains("r") || mode.contains("w") || mode.contains("a")) {
            return Result.fail("BRIDGE_MODE_NOT_ALLOWED",
                    "Мост отдаёт файлы только на чтение, запрошен режим: " + mode);
        }
        return Result.ok(null);
    }

    public static String authority() {
        return BridgeContract.AUTHORITY;
    }
}
