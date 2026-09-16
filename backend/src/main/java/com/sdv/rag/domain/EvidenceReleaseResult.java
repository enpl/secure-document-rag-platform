package com.sdv.rag.domain;

public record EvidenceReleaseResult(EvidenceReleaseStatus status, String text, EvidenceProvenance provenance) {
    public static EvidenceReleaseResult released(String text, EvidenceProvenance provenance) {
        return new EvidenceReleaseResult(EvidenceReleaseStatus.RELEASED, text, provenance);
    }
    public static EvidenceReleaseResult failed(EvidenceReleaseStatus status) {
        return new EvidenceReleaseResult(status, null, null);
    }
}
