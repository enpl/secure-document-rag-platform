package com.sdv.source.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Conservative bounds for the buffered, full-download MVP endpoint. */
@ConfigurationProperties(prefix = "sdv.shared-download")
public record SharedFileDownloadProperties(long maxBytes, int maxConcurrentDownloads, long requestTimeoutMs) {

    public SharedFileDownloadProperties {
        if (maxBytes <= 0) {
            maxBytes = 25L * 1024 * 1024;
        }
        if (maxConcurrentDownloads <= 0) {
            maxConcurrentDownloads = 2;
        }
        if (requestTimeoutMs <= 0) {
            requestTimeoutMs = 30_000L;
        }
    }
}
