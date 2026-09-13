package com.sdv.source.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * M08 신규 - {@code docs/spec/SDV_v3.2_CORE_SPEC.md} §9 "Source Token
 * Boundary"가 이미 확정한 {@link SourceTokenStore}의 개념적 계약({@code
 * save(SourceId, TokenEnvelope)})을 실제 Type으로 구현한다 - 원래
 * {@code save(Long, String)}처럼 원본 Token 값을 맨 String으로 주고받는
 * 대신, 이 구조화된 Envelope 하나로만 주고받게 해 호출부에서 실수로
 * 원본 값을 로그/예외에 그대로 찍기 어렵게 만든다({@link #toString()}이
 * 절대 원본 값을 노출하지 않는다).
 *
 * <p>이 Record 자체는 암호화를 수행하지 않는다 - 암호화/복호화와 실제
 * 영속 저장은 {@link SourceTokenStore} 구현체의 책임이다(M08 시점에는
 * Production 구현체가 없다 - Class Javadoc 참고). 이 Envelope은 이미
 * 복호화된, 메모리 안에서만 잠깐 존재하는 값이다 - 어디에도 그대로
 * 영속화하지 않는다.</p>
 *
 * <h2>M08 Review 교정(항목 7) - {@link #boundSubject()} 추가</h2>
 * <p>원안은 {@code source_connections.owner_subject}(DB)와 요청자만
 * 비교했다 - 저장된 Token 자체에는 "이 Token이 실제로 어느 SDV 사용자
 * 것인지"를 나타내는 필드가 없어서, 예를 들어 잘못된 {@code sourceId}
 * 아래 다른 사용자(또는 더 광범위한 Service Account) Token이 실수로
 * 저장된 경우를 이 Envelope 자체만 보고는 구분할 수 없었다. 이제
 * {@link SourceTokenStore} 구현체가 저장 시점에 결합한 SDV Subject를
 * 이 필드에 명시적으로 담아, Connector가 DB 조회 결과와 별개로 한 번 더
 * 검증할 수 있게 한다(방어 심층화 - 두 값이 우연히도 항상 같을 것이라고
 * 가정하지 않는다).</p>
 */
public record TokenEnvelope(String boundSubject, String accessToken, String refreshToken,
        Instant accessTokenExpiresAt, List<String> scopes) {

    public TokenEnvelope {
        Objects.requireNonNull(boundSubject, "boundSubject must not be null");
        if (boundSubject.isBlank()) {
            throw new IllegalArgumentException("boundSubject must not be blank");
        }
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        if (accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * 원본 Access/Refresh Token 값이 로그·예외 메시지에 실수로 찍히지 않도록
     * 절대 노출하지 않는다. {@link #boundSubject()}는 비밀이 아니므로(감사
     * 대상 식별자일 뿐) 그대로 남긴다.
     */
    @Override
    public String toString() {
        return "TokenEnvelope[boundSubject=" + boundSubject + ", accessToken=REDACTED, refreshToken="
                + (refreshToken == null ? "absent" : "REDACTED") + ", accessTokenExpiresAt=" + accessTokenExpiresAt
                + ", scopes=" + scopes + "]";
    }
}
