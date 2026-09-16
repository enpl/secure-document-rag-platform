package com.sdv.source.domain;

import java.util.Objects;

/** Domain-neutral notification that a committed source disconnect invalidated live access. */
public record SourceConnectionAccessRevokedEvent(Long sourceId) {
    public SourceConnectionAccessRevokedEvent {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
    }
}
