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
    AI_EXTERNAL_PROVIDER_DENIED,

    /**
     * M10B 신규(SHR-001, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - 이 요청자에게
     * 이 문서·행위를 허용하는 활성(미철회) 공유가 없다. 공유 자체가 없음/철회됨/
     * 요청자가 수신자 명단에 없음/요청한 행위가 부여되지 않음을 모두 이 하나의
     * 값으로 통일한다(공유 존재 여부를 노출하지 않는다 - {@link #RESOURCE_NOT_FOUND}가
     * "존재하지 않음"과 "다른 계정 소유"를 통일하는 것과 같은 원칙).
     */
    SHARE_NOT_AUTHORIZED,

    /** M10B 신규 - 공유 자체는 유효하지만 ADMIN이 명시적으로 차단했다(게시자는 스스로 해제할 수 없다). */
    SHARE_ADMIN_BLOCKED,

    /**
     * M10B 보안 교정 신규 - 호출자가 들고 있는 {@link com.sdv.source.domain.SourceAccessContext}의
     * 공유/연결 세대(Generation/Epoch)가 지금 저장된 값과 다르다. 공유가 그 사이 갱신/철회/
     * 차단됐거나, Source가 Disconnect된 뒤 그 값이 반영되기 전의 낡은 판단을 그대로 재사용하려는
     * 시도다 - 어느 쪽이 바뀌었는지는 노출하지 않고 이 하나의 값으로 통일한다(Fail Closed).
     */
    STALE_AUTHORIZATION_CONTEXT,

    /**
     * M10B 보안 교정 신규 - 이 공유가 걸린 Source 연결이 아직 한 번도 검증된 Provider
     * 신원({@code source_connections.provider_account_id})을 확인받지 못했다(M10B
     * 이전 Legacy 연결 포함). 이메일 일치나 SDV Owner 동일성만으로 같은 Provider
     * 계정이라고 추정하지 않는다 - 소유자가 명시적으로 재인증({@link
     * com.sdv.source.application.GoogleDriveOAuthService})해 Identity를 확정할 때까지
     * 공유 기반 접근을 열어주지 않는다(Fail Closed).
     */
    SOURCE_IDENTITY_UNVERIFIED
}
