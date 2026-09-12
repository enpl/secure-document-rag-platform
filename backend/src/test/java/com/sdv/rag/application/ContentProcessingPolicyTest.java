package com.sdv.rag.application;

import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.source.domain.DocumentIndexStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ContentProcessingPolicyTest {

    private final ContentProcessingPolicy policy = new ContentProcessingPolicy();

    @Test
    void successIsEmptyIndexStatusStaysUntouched() {
        assertThat(policy.classify(ParseOutcomeKind.SUCCESS)).isEqualTo(Optional.empty());
    }

    @Test
    void unsupportedFormatMapsToSkippedUnsupported() {
        assertThat(policy.classify(ParseOutcomeKind.UNSUPPORTED_FORMAT))
                .contains(DocumentIndexStatus.SKIPPED_UNSUPPORTED);
    }

    @Test
    void noTextMapsToSkippedNoText() {
        assertThat(policy.classify(ParseOutcomeKind.NO_TEXT)).contains(DocumentIndexStatus.SKIPPED_NO_TEXT);
    }

    @Test
    void failedMapsToFailed() {
        assertThat(policy.classify(ParseOutcomeKind.FAILED)).contains(DocumentIndexStatus.FAILED);
    }
}
