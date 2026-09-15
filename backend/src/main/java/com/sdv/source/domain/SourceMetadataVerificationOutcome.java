package com.sdv.source.domain;

import java.util.Set;

/**
 * M10 신규(RAG-011 File Metadata Discovery, `docs/spec/SDV_v3.2_CORE_SPEC.md`
 * §2A.4) - {@link com.sdv.source.application.port.DocumentSourceConnector#verifyCurrentMetadata}
 * 한 번의 시도가 끝날 수 있는 모든 경우. {@link #VERIFIED}일 때만 지금 이 이름/
 * 타입/Version을 노출해도 된다 - {@link #fetchContent}(M08)와 달리 이 연산은
 * Content Byte를 절대 요청하지 않는다(Media/Export 호출 없음).
 *
 * <h2>M10 후속 교정 - Coverage(응답 완전성) 분류</h2>
 * <p>{@link #isDefinitiveExclusion()}는 "이 문서가 지금 보이지 않는다는 것을
 * 확실히 확인했다"(더 검증해도 결론이 달라지지 않는다)와 "확인 자체가 실패했다"
 * (Network/자격증명 문제 등으로 판단을 내리지 못했다)를 구분한다 - Discovery
 * 응답의 Page/Coverage(부분/전체) 판단이 이 구분에 의존한다({@code
 * FileMetadataDiscoveryService} 참고). {@link #TRASHED}/{@link #NOT_FOUND}/
 * {@link #ACCESS_DENIED}/{@link #CREDENTIAL_NOT_BOUND_TO_USER}는 Google(또는
 * 현재 Owner-Only 결합 규칙)이 명시적으로 "아니다"라고 답한 것이다 - 나중에 다시
 * 물어봐도 이 요청 시점 기준으로는 같은 답이 나온다. 그 밖의 값({@link
 * #MISSING_CREDENTIAL}/{@link #CREDENTIAL_UNREADABLE}/{@link #INSUFFICIENT_SCOPE}/
 * {@link #ACCESS_UNKNOWN}/{@link #FAILED})은 확인 절차 자체가 깨진 것이다 - 이
 * 문서가 실제로 보여야 하는지 여부를 전혀 알아내지 못했다는 뜻이므로, 이런
 * 결과가 하나라도 있으면 그 Page/Lookahead 판단을 "확정"으로 보고할 수 없다.</p>
 */
public enum SourceMetadataVerificationOutcome {
    /** Fetch 전/후가 아니라 지금 한 번의 조회로 사용자 접근권한과 현재 Metadata가 확인됐다. */
    VERIFIED,
    /** 이 Source에 대한 Credential(Token) 자체가 없다. */
    MISSING_CREDENTIAL,
    /** 현재 Data Model이 Source Owner에게만 Credential을 안전하게 묶을 수 있는데, 요청자가 Owner가 아니다. */
    CREDENTIAL_NOT_BOUND_TO_USER,
    /** 저장된 Token을 복호화/역직렬화할 수 없다. */
    CREDENTIAL_UNREADABLE,
    /** OAuth Scope가 이 작업에 부족하다. */
    INSUFFICIENT_SCOPE,
    /** 이 사용자 기준으로 명시적으로 접근이 거부됐다. */
    ACCESS_DENIED,
    /** 접근 가능 여부를 신뢰 가능하게 판단할 수 없다(Fail Closed). */
    ACCESS_UNKNOWN,
    /** 문서가 휴지통에 있다 - Metadata Discovery 결과에서 제외해야 한다. */
    TRASHED,
    /** 문서를 찾을 수 없다(삭제됐거나 이 사용자에게 원래부터 보이지 않는다 - 구분하지 않는다). */
    NOT_FOUND,
    /** 그 밖의 예상치 못한 실패(Network/Timeout/알 수 없는 응답/Malformed/Identity 불일치 등) - 안전하게 실패로 처리한다. */
    FAILED;

    private static final Set<SourceMetadataVerificationOutcome> DEFINITIVE_EXCLUSIONS =
            Set.of(TRASHED, NOT_FOUND, ACCESS_DENIED, CREDENTIAL_NOT_BOUND_TO_USER);

    /**
     * {@code true}면 "이 문서는 지금 보이지 않는다"를 확정적으로 확인한 것이다
     * (재시도해도 이 요청 시점 기준으로는 같은 결론) - Coverage(부분/전체)
     * 판단에서 이 결과는 "미확인(Unknown)"으로 세지 않는다. {@link #VERIFIED}에
     * 대해서는 이 메서드를 호출하지 않는다(호출자가 먼저 VERIFIED를 분기 처리한다).
     */
    public boolean isDefinitiveExclusion() {
        return DEFINITIVE_EXCLUSIONS.contains(this);
    }
}
