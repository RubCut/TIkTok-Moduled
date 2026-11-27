package dev.moduled.api;

/**
 * Сведения о хосте и версионном адаптере (ТЗ §9, PAT-08/PAT-09).
 *
 * <p>{@code adapterSupported == false} означает, что runtime не подтвердил
 * поддержку этой версии хоста: модуль обязан сообщить несовместимость, а не
 * создавать видимость успешной работы (ТЗ §16).</p>
 */
public interface HostInfo {

    /** Package ID приложения-хоста, например {@code com.zhiliaoapp.musically}. */
    String packageName();

    /** versionName хоста. */
    String versionName();

    /** versionCode хоста. */
    long versionCode();

    /** Имя версионного адаптера точки входа, выбранного патчером. */
    String adapterName();

    /** Версия адаптера. */
    String adapterVersion();

    /** Подтверждена ли поддержка этой версии хоста выбранным адаптером. */
    boolean adapterSupported();

    /** Основные ABI процесса хоста (ТЗ §4.1). */
    String[] primaryAbis();
}
