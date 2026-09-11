package com.sdv.policy.domain;

/**
 * F-BE-080. {@link PolicyDecision}의 기계 판독 가능한 사유 코드(POL-008).
 *
 * <p>v3.2 Repository Markdown 명세는 정확한 값 목록을 정의하지 않는다 - 이는
 * M05 구현 결정이며(README/handoff에 기록), 테스트로 검증되는 판단 경로를
 * 표현하는 데 필요한 최소 집합만 사용한다. 새 판단 경로가 필요해지면 이
 * 목록을 확장하되, 이미 있는 값을 재사용할 수 있는지 먼저 검토한다.</p>
 *
 * <p>{@link #RESOURCE_NOT_FOUND}는 "존재하지 않음"과 "다른 계정 소유"를 의도적으로
 * 구분하지 않는다 - Owner-Scoped 조회가 이미 두 경우를 동일한 빈 결과로 반환하므로
 * (M04 {@code NotFoundException} 패턴과 동일), Policy 경계 밖으로 그 차이가 드러나지
 * 않는다.</p>
 */
public enum PolicyReasonCode {
    /** 모든 적용 가능한 검사를 통과했다. */
    ALLOWED,

    /** 내부 입력값(documentId/action 등)이 불완전하다 - Fail Closed. */
    INVALID_REQUEST,

    /** 문서가 존재하지 않거나 다른 계정 소유다(구분하지 않음). */
    RESOURCE_NOT_FOUND,

    /** 문서가 논리적으로 삭제된 상태다(INV-SRC-003). */
    DOCUMENT_DELETED,

    /**
     * 문서 {@code state}가 {@code ACTIVE}도 {@code DELETED}도 아니다 - V004
     * {@code chk_source_document_state}(NOT VALID)가 보존하는 레거시 행 등,
     * v3.2 canonical {@code SourceDocumentState}로 인식되지 않는 값은 "삭제되지
     * 않았으니 사용 가능"으로 해석하지 않고 Fail Closed 한다.
     */
    DOCUMENT_STATE_UNRECOGNIZED,

    /** 문서가 속한 Source 연결이 ACTIVE가 아니다. */
    SOURCE_INACTIVE,

    /** ACL 증거가 없거나, 신뢰할 수 있을 만큼 최신(Fresh)이 아니다(INV-SRC-002). */
    PERMISSION_DATA_UNTRUSTED,

    /** 신뢰 가능한 ACL 중 현재 사용자에게 일치하는 권한 부여가 없다(INV-SRC-001). */
    SOURCE_PERMISSION_DENIED,

    /** Overlay Policy의 일치하는 DENY 규칙이 적용됐다. */
    OVERLAY_DENIED,

    /** AI Usage Policy가 없거나 AI_DENIED이다(INV-AI-001, INV-AI-002). */
    AI_USAGE_DENIED,

    /** External Provider 사용이 요청됐지만 정책상 허용되지 않는다(INV-AI-002). */
    AI_EXTERNAL_PROVIDER_DENIED
}
