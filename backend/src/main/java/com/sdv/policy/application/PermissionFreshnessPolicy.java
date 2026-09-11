package com.sdv.policy.application;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * F-BE-085. {@code source_permissions.synced_at} 기반 ACL Freshness 판단
 * (POL-007, INV-SRC-002).
 *
 * <p><b>운영 TTL은 UNVERIFIED다.</b> v3.2 Repository Markdown 명세와 현재
 * 설정({@code application*.yml})은 "ACL을 얼마나 오래 신뢰할 수 있는가"에 대한
 * 값을 정의하지 않는다 - 임의의 운영 TTL을 발명하지 않는다. 대신 서버가
 * 소유한 설정 값 {@code sdv.policy.permission-freshness-max-age}(ISO-8601
 * Duration 문자열, 예: {@code PT24H})를 통해서만 신뢰 경계를 구성할 수 있게
 * 하고, HTTP 클라이언트는 이 값을 절대 선택하거나 재정의할 수 없다 - 순수
 * 서버 측 설정이다. 이 속성이 설정되지 않았거나(기본값, 현재 모든
 * {@code application*.yml}에 없음) 파싱할 수 없으면 {@link #isFresh}는 항상
 * {@code false}를 반환한다(Fail Closed) - "만족스러운 실패 없음 테스트"를
 * 통과시키기 위해 이 규칙을 느슨하게 만들지 않는다. 실제 운영 값을 확정하는
 * 것은 이 작업의 범위 밖이며, 운영자가 명시적으로 설정해야 한다.</p>
 */
@Service
public class PermissionFreshnessPolicy {

    private final Duration maxAge;
    private final Clock clock;

    @Autowired
    public PermissionFreshnessPolicy(
            @Value("${sdv.policy.permission-freshness-max-age:}") String maxAgeIso) {
        this(parseMaxAge(maxAgeIso), Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}과 {@link Duration}을 직접 주입하기 위한 패키지 전용 생성자. */
    PermissionFreshnessPolicy(Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
    }

    private static Duration parseMaxAge(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            Duration parsed = Duration.parse(iso);
            return (parsed.isNegative() || parsed.isZero()) ? null : parsed;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * {@code syncedAt}이 없거나, 신뢰 가능한 서버 측 Cutoff가 구성되지 않았으면 항상
     * Fresh하지 않다. {@code syncedAt}이 미래 시각이면(age가 음수) 절대 Fresh로
     * 취급하지 않는다 - 음수 age를 {@code maxAge} 이하로 비교해 "항상 Fresh"로
     * 오판하지 않기 위해 {@code 0 <= age <= maxAge}를 명시적으로 요구한다.
     */
    public boolean isFresh(Instant syncedAt) {
        if (syncedAt == null || maxAge == null) {
            return false;
        }
        Instant now = clock.instant();
        Duration age = Duration.between(syncedAt, now);
        return !age.isNegative() && age.compareTo(maxAge) <= 0;
    }
}
