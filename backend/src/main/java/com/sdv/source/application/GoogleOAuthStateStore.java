package com.sdv.source.application;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - "Establish unpredictable,
 * one-use, server-side OAuth state bound to authenticated subject, Source ID, browser flow
 * and exact configured redirect URI. Use a bounded in-memory store with a 10-minute TTL for
 * the single-instance MVP; restart invalidates attempts."
 *
 * <p>일부러 DB Table로 만들지 않는다 - 이 상태는 몇 분짜리 진행 중(In-flight) OAuth
 * 시도일 뿐, 영속화할 가치가 있는 Application 상태가 아니다(재기동하면 진행 중이던
 * 시도가 사라지고 사용자는 다시 연결을 시작하면 된다 - 계약이 명시적으로 허용하는
 * 동작). 여러 Backend Instance 사이에서 공유되지 않는다 - 이는 "Single-Instance MVP"
 * 범위의 명시적 한계다(수평 확장 시 별도 공유 저장소가 필요하다 - 이 작업 범위 밖).</p>
 *
 * <p>{@link #consume}은 조회한 즉시 항목을 제거한다(One-Use) - 같은 {@code state}로
 * 두 번째 Callback이 오면(Replay) 항상 실패한다. 성공/실패 어느 경로든 재사용을 막기
 * 위해, 검증에 실패하는 경우도 이미 Map에서 제거된 뒤 실패로 판정된다.</p>
 */
@Component
class GoogleOAuthStateStore {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int RANDOM_BYTES = 32;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    GoogleOAuthStateStore() {
        this(Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자(TTL 만료 판정 결정론화). */
    GoogleOAuthStateStore(Clock clock) {
        this.clock = clock;
    }

    /** 새 OAuth 시도를 등록하고 그 State 값을 반환한다. 등록 시마다 이미 만료된 기존 항목을 함께 정리한다. */
    String create(String subject, Long sourceId, String browserBinding, String codeVerifier) {
        attempts.values().removeIf(attempt -> clock.instant().isAfter(attempt.expiresAt()));
        String state = randomToken();
        attempts.put(state, new Attempt(subject, sourceId, browserBinding, codeVerifier,
                clock.instant().plus(TTL)));
        return state;
    }

    /**
     * State를 소비(제거)하고, 존재/만료/Browser 결합 일치를 모두 확인했을 때만 원본 시도를
     * 반환한다. 무엇이 실패했는지는 호출자에게 구분해 알려주지 않는다(모두 동일하게
     * "이 Callback을 신뢰할 수 없다"로 수렴 - Google에 원본 오류를 노출하지 않는 정책과
     * 같은 이유).
     */
    Optional<Attempt> consume(String state, String browserBinding) {
        if (state == null) {
            return Optional.empty();
        }
        Attempt attempt = attempts.remove(state);
        if (attempt == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(attempt.expiresAt())) {
            return Optional.empty();
        }
        if (browserBinding == null || !constantTimeEquals(attempt.browserBinding(), browserBinding)) {
            return Optional.empty();
        }
        return Optional.of(attempt);
    }

    static String randomToken() {
        byte[] random = new byte[RANDOM_BYTES];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    record Attempt(String subject, Long sourceId, String browserBinding, String codeVerifier, Instant expiresAt) {
    }
}
