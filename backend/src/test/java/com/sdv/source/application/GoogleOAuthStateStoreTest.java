package com.sdv.source.application;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 단위 테스트(Spring Context 없음) - M08 MVP OAuth
 * ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md})의 "unpredictable, one-use, server-side
 * OAuth state bound to... browser flow" 요구를 검증한다: 존재하지 않음/만료/재사용
 * (Replay)/Browser 결합 불일치를 모두 실패로 처리하고, 정상 경로는 등록된 그대로
 * (subject/sourceId/codeVerifier) 정확히 돌려준다.
 */
class GoogleOAuthStateStoreTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-13T00:00:00Z");

    @Test
    void consumeReturnsTheOriginalAttemptWhenEverythingMatches() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));
        String state = store.create("subject-a", 42L, "browser-binding-a", "verifier-a", 7L);

        Optional<GoogleOAuthStateStore.Attempt> result = store.consume(state, "browser-binding-a");

        assertThat(result).isPresent();
        assertThat(result.get().subject()).isEqualTo("subject-a");
        assertThat(result.get().sourceId()).isEqualTo(42L);
        assertThat(result.get().codeVerifier()).isEqualTo("verifier-a");
        // M10B 보안 교정(V011) - 이 시도가 시작될 때 읽은 연결 인가 세대가 그대로 보존된다 -
        // GoogleDriveOAuthService.commitCredential이 나중에 이 값을 현재 값과 비교한다.
        assertThat(result.get().connectionEpoch()).isEqualTo(7L);
    }

    @Test
    void consumeFailsForAnUnknownState() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));

        assertThat(store.consume("never-created", "any-binding")).isEmpty();
    }

    @Test
    void consumeIsOneUse_aSecondConsumeOfTheSameStateFails() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));
        String state = store.create("subject-a", 42L, "browser-binding-a", "verifier-a", 7L);

        assertThat(store.consume(state, "browser-binding-a")).isPresent();
        assertThat(store.consume(state, "browser-binding-a"))
                .as("a replayed callback with the same state must never succeed twice")
                .isEmpty();
    }

    @Test
    void consumeFailsWhenTheBrowserBindingDoesNotMatch() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));
        String state = store.create("subject-a", 42L, "browser-binding-a", "verifier-a", 7L);

        assertThat(store.consume(state, "wrong-binding")).isEmpty();
    }

    @Test
    void consumeFailsWhenTheBrowserBindingIsMissing() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));
        String state = store.create("subject-a", 42L, "browser-binding-a", "verifier-a", 7L);

        assertThat(store.consume(state, null)).isEmpty();
    }

    @Test
    void consumeFailsOncePastTheTenMinuteTtl() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(FIXED_NOW);
        Clock movableClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(movableClock);
        String state = store.create("subject-a", 42L, "browser-binding-a", "verifier-a", 7L);

        now.set(FIXED_NOW.plus(Duration.ofMinutes(10)).plusSeconds(1));

        assertThat(store.consume(state, "browser-binding-a"))
                .as("an attempt older than the 10-minute TTL must fail closed, not be treated as still valid")
                .isEmpty();
    }

    @Test
    void twoAttemptsGetDifferentUnpredictableStateValues() {
        GoogleOAuthStateStore store = new GoogleOAuthStateStore(fixedClock(FIXED_NOW));

        String first = store.create("subject-a", 1L, "binding-1", "verifier-1", 5L);
        String second = store.create("subject-a", 2L, "binding-2", "verifier-2", 5L);

        assertThat(first).isNotEqualTo(second);
        assertThat(first.length()).isGreaterThanOrEqualTo(32);
    }

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
