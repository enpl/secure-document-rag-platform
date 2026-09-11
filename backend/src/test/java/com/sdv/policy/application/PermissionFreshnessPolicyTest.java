package com.sdv.policy.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionFreshnessPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void syncedAtExactlyNowIsFresh() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(NOW)).isTrue();
    }

    @Test
    void syncedWithinConfiguredMaxAgeIsFresh() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(NOW.minus(Duration.ofHours(1)))).isTrue();
    }

    @Test
    void syncedExactlyAtTheBoundaryIsFresh() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(NOW.minus(Duration.ofHours(24)))).isTrue();
    }

    @Test
    void syncedBeyondConfiguredMaxAgeIsNotFresh() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(NOW.minus(Duration.ofHours(25)))).isFalse();
    }

    @Test
    void nullSyncedAtIsNeverFresh() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(null)).isFalse();
    }

    /**
     * 미래 {@code syncedAt}은 age가 음수가 된다 - 음수를 {@code maxAge} 이하로
     * 비교하면 항상 참이 되어 잘못 Fresh로 취급되던 결함(M05 후속 교정)을 막는다.
     */
    @Test
    void futureSyncedAtIsNeverFreshEvenWellWithinTheConfiguredWindow() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy(Duration.ofHours(24), FIXED_CLOCK);

        assertThat(policy.isFresh(NOW.plus(Duration.ofMinutes(5)))).isFalse();
    }

    @Test
    void unconfiguredMaxAgeFailsClosedEvenForRecentEvidence() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy((Duration) null, FIXED_CLOCK);

        assertThat(policy.isFresh(NOW)).isFalse();
    }

    @Test
    void blankConfiguredPropertyFailsClosed() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy("");

        assertThat(policy.isFresh(Instant.now())).isFalse();
    }

    @Test
    void malformedConfiguredPropertyFailsClosed() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy("not-a-duration");

        assertThat(policy.isFresh(Instant.now())).isFalse();
    }

    @Test
    void zeroOrNegativeConfiguredDurationFailsClosed() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy("PT0S");

        assertThat(policy.isFresh(Instant.now())).isFalse();
    }

    @Test
    void negativeConfiguredDurationStringFailsClosed() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy("PT-1H");

        assertThat(policy.isFresh(Instant.now())).isFalse();
    }

    @Test
    void wellFormedIsoDurationPropertyIsHonored() {
        PermissionFreshnessPolicy policy = new PermissionFreshnessPolicy("PT24H");

        assertThat(policy.isFresh(Instant.now())).isTrue();
        assertThat(policy.isFresh(Instant.now().minus(Duration.ofDays(2)))).isFalse();
    }
}
