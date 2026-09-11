package com.sdv.policy.application;

import com.sdv.policy.domain.SecurityLevel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LabelMappingServiceTest {

    private final LabelMappingService service = new LabelMappingService();

    @Test
    void exactCanonicalNameMapsToThatLevel() {
        assertThat(service.resolve("SECRET")).contains(SecurityLevel.SECRET);
    }

    @Test
    void caseInsensitiveAndTrimmedNameMapsToThatLevel() {
        assertThat(service.resolve("  confidential  ")).contains(SecurityLevel.CONFIDENTIAL);
    }

    @Test
    void unrecognizedSourceLabelCannotBeAutoMapped() {
        assertThat(service.resolve("Company Confidential - Legal Only")).isEqualTo(Optional.empty());
    }

    @Test
    void blankOrNullLabelCannotBeAutoMapped() {
        assertThat(service.resolve(null)).isEqualTo(Optional.empty());
        assertThat(service.resolve("   ")).isEqualTo(Optional.empty());
    }
}
