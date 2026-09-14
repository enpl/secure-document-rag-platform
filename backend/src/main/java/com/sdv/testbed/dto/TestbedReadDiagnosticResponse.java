package com.sdv.testbed.dto;

/**
 * M16A follow-up local testbed diagnostic - content-free result only.
 *
 * <p>{@code outcome} is always one of a small set of safe category names
 * (mirroring {@code SourceContentOutcome}, plus a few diagnostic-only
 * pre-check categories) - never a raw Google/provider error string. {@code
 * bytesRead} is the actual byte count read for a successful live fetch (0
 * otherwise). {@code versionVerified} is true only when the connector's
 * pre-fetch and post-fetch version checks both passed. No original bytes,
 * text preview, tokens or file content ever appear here.</p>
 */
public record TestbedReadDiagnosticResponse(boolean success, String outcome, int bytesRead, boolean versionVerified,
        String reason) {
}
