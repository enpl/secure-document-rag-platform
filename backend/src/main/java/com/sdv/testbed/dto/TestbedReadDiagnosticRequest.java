package com.sdv.testbed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * M16A follow-up local testbed diagnostic - accepts only a Source ID and a
 * single Google file ID. Never an arbitrary URL, credential, caller-supplied
 * subject or owner identity - the authenticated administrator and their
 * ownership of {@code sourceId} come only from {@code CurrentUserProvider}/
 * the Source Repository, never from this request body.
 */
public record TestbedReadDiagnosticRequest(@NotNull Long sourceId, @NotBlank String fileId) {
}
