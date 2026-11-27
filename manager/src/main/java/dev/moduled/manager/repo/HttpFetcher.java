package dev.moduled.manager.repo;

import java.io.IOException;

/**
 * Транспорт для каталога (ТЗ §13.2). Интерфейс позволяет проверять поведение
 * клиента JVM-тестами: таймауты, подмену архива, отказ источника, лимиты.
 */
public interface HttpFetcher {

    /**
     * Скачивает ресурс целиком в память с ограничением размера.
     *
     * @param url           HTTPS-адрес
     * @param maxBytes      жёсткий лимит байтов
     * @param timeoutMs     таймаут соединения и чтения
     * @param maxRedirects  лимит перенаправлений, каждое перепроверяется политикой URL
     * @param cancel        флаг отмены пользователем
     */
    byte[] fetch(String url, long maxBytes, int timeoutMs, int maxRedirects, boolean[] cancel)
            throws IOException;

    /**
     * Скачивает ресурс во временный файл (для архивов модулей): лимит
     * контролируется по потоку, а не по заявленному Content-Length.
     *
     * @return число записанных байтов
     */
    long fetchToFile(String url, java.io.File target, long maxBytes, int timeoutMs,
                     int maxRedirects, boolean[] cancel) throws IOException;
}
