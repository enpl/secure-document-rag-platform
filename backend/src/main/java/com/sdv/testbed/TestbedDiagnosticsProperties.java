package com.sdv.testbed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M16A follow-up local testbed diagnostic
 * ({@code docs/runbooks/M16A_LOCAL_TESTBED.md}) - not a canonical File/Feature
 * ID, a supporting fixture for the approved local testbed only.
 *
 * <p>Bound by the existing app-wide {@code @ConfigurationPropertiesScan}
 * ({@code SecureDocumentVaultApplication}) - no extra wiring needed. {@code
 * allowedFileId} is a single Google Drive file ID; this diagnostic accepts no
 * other file ID and never enumerates the Drive to pick one (the operator
 * chooses it once, out of band).</p>
 */
@ConfigurationProperties(prefix = "sdv.testbed.diagnostics")
public record TestbedDiagnosticsProperties(boolean enabled, String allowedFileId) {
}
