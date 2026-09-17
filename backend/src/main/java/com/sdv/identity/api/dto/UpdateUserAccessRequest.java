package com.sdv.identity.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateUserAccessRequest(@NotNull @Min(0) Long expectedVersion, String maximumClassification,
        @NotNull Boolean active) { }
