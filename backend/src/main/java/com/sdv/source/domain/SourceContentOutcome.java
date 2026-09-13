package com.sdv.source.domain;

/**
 * M08 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#fetchContent}
 * 한 번의 시도가 끝날 수 있는 모든 경우를 명시적으로 나열한다(v1.4
 * Mandatory Live Retrieval, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.5). 오직
 * {@link #VERIFIED}일 때만 Content Byte가 존재한다 - 그 밖의 모든 값은
 * "무엇을 왜 거부/실패했는지"를 안전하게 구분하기 위한 것이며, 실패를
 * 뭉뚱그려 하나의 Boolean이나 예외로 감추지 않는다(Fail Closed 원칙 -
 * 호출자가 각 상황에 다르게 반응해야 한다).
 */
public enum SourceContentOutcome {
    /** Fetch 전/후 사용자 접근권한과 Version이 모두 검증됐다 - Content Byte가 존재하는 유일한 값. */
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
    /** 문서가 휴지통에 있다. */
    TRASHED,
    /** 문서를 찾을 수 없다(삭제됐거나 이 사용자에게 원래부터 보이지 않는다 - 구분하지 않는다). */
    NOT_FOUND,
    /** capabilities.canDownload가 false다. */
    NOT_DOWNLOADABLE,
    /** Fetch 전 기대 Version과 실제 Version이 이미 다르다 - Content를 하나도 Fetch하지 않았다. */
    VERSION_MISMATCH,
    /** Fetch 도중/직후 Version이 바뀌어 한 번 재시도했지만 또 바뀌었다 - 어떤 Byte도 반환하지 않는다. */
    DOCUMENT_CHANGED,
    /** Google Workspace 문서 동기 Export가 10MB 상한을 넘는다. */
    EXPORT_LIMIT_EXCEEDED,
    /** Core 지원 포맷이 아니거나(PPTX/이미지 등) 기본적으로 비활성화됐다(XLSX). */
    UNSUPPORTED_FORMAT,
    /** 그 밖의 예상치 못한 실패(Network/Timeout/알 수 없는 응답 등) - 안전하게 실패로 처리한다. */
    FAILED
}
