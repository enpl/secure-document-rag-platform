package com.sdv.source.application.port;

import java.util.Optional;

/**
 * F-BE-024. 안전한 OAuth Token 영속화 경계(SRC-003, SRC-010,
 * {@code docs/spec/SDV_v3.2_CORE_SPEC.md} §9 "Source Token Boundary").
 *
 * <p>원본 Token 값은 절대 로그로 남기지 않는다.</p>
 *
 * <h2>M08 후속 교정 - 원안({@code save(Long, String)}/{@code String load(Long)})의 결함</h2>
 * <p>맨 {@link String}으로 Token을 주고받으면, 호출부 어디선가 실수로 그
 * 값을 로그/예외 메시지/디버그 출력에 그대로 찍어도 컴파일러가 막아주지
 * 못한다. CORE_SPEC §9가 이미 개념적으로 확정한 {@code save(SourceId,
 * TokenEnvelope)} 계약을 그대로 구현해, {@link TokenEnvelope}(자신을
 * 절대 노출하지 않는 {@code toString()}을 가진 전용 Type) 하나로만 주고받게
 * 바꿨다. {@code load}도 Token이 없을 수 있는 정상 상태를 명시적으로
 * 표현하도록 {@link Optional}을 반환한다.</p>
 *
 * <h2>M08 시점 상태 - 정직하게 명시(UNVERIFIED)</h2>
 * <p>M08은 이 Port의 실제 Production 구현체(암호화 저장 등, F-BE-043/044
 * {@code GoogleTokenService}/{@code GoogleTokenStoreAdapter})를 만들지
 * 않는다 - 현재 v3.2 Public Manifest/Migration 어디에도 암호화된 Token을
 * 영속 저장할 승인된 테이블/Migration이 없고, 그런 테이블을 이 작업에서
 * 임의로 새로 만들지 않는다("Do not invent a durable token backend...
 * without a public contract" - 이 작업 지시사항). 이 Port가 비어있는 동안
 * ({@code Optional<SourceTokenStore>}가 항상 비어있는 동안) Google Drive
 * Content 접근은 전부 {@code MISSING_CREDENTIAL}로 Fail Closed 한다 -
 * "안전한 Production Token Store가 아직 없다"는 사실을 약화된 형태로
 * 숨기지 않는다({@code .claude-handoff/latest.md}에 UNVERIFIED로 기록).</p>
 */
public interface SourceTokenStore {

    void save(Long sourceId, TokenEnvelope token);

    Optional<TokenEnvelope> load(Long sourceId);

    void delete(Long sourceId);
}
