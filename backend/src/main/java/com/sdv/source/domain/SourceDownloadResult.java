package com.sdv.source.domain;

import java.util.Objects;

/**
 * A verified, transient result for the non-AI shared-file download path.  Bytes are
 * present only for a verified result and are owned by the caller until delivery completes.
 */
public record SourceDownloadResult(SourceContentOutcome outcome, byte[] content, String filename, String mimeType,
        String verifiedSourceVersion, boolean isTransientExport, String reason) {

    public SourceDownloadResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (outcome == SourceContentOutcome.VERIFIED) {
            Objects.requireNonNull(content, "content must not be null for VERIFIED");
            requireNonBlank(filename, "filename");
            requireNonBlank(mimeType, "mimeType");
            requireNonBlank(verifiedSourceVersion, "verifiedSourceVersion");
        } else if (content != null) {
            throw new IllegalArgumentException("content must be null unless outcome is VERIFIED");
        }
    }

    public static SourceDownloadResult verified(byte[] content, String filename, String mimeType,
            String verifiedSourceVersion, boolean isTransientExport) {
        return new SourceDownloadResult(SourceContentOutcome.VERIFIED, content, filename, mimeType,
                verifiedSourceVersion, isTransientExport, null);
    }

    public static SourceDownloadResult failed(SourceContentOutcome outcome, String reason) {
        if (outcome == SourceContentOutcome.VERIFIED) {
            throw new IllegalArgumentException("use verified(...) for SourceContentOutcome.VERIFIED");
        }
        return new SourceDownloadResult(outcome, null, null, null, null, false,
                reason == null ? "download failed" : reason);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
