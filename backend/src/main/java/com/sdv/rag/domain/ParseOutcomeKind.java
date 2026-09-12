package com.sdv.rag.domain;

/**
 * M06 신규 - Python AI Service({@code parser_service.py})의 파싱 결과를 Backend가
 * 분류하기 위한 내부(비영속) 값이다. {@code source_documents.index_status}
 * (Canonical {@code DocumentIndexStatus})에 새 값을 추가하지 않는다 - 이
 * 값들은 오직 기존 {@code DocumentIndexStatus}의 네 값(PENDING 유지/
 * SKIPPED_UNSUPPORTED/SKIPPED_NO_TEXT/FAILED) 중 하나로 매핑하기 위한
 * 중간 분류일 뿐이다({@link com.sdv.rag.application.ContentProcessingPolicy}
 * 참고). DB에 저장되지 않는다.
 */
public enum ParseOutcomeKind {
    /** 정규화된 텍스트를 성공적으로 얻었다 - INDEXED가 아니다(M11이 이후 처리). */
    SUCCESS,
    /** 이 포맷은 Core RAG가 텍스트를 추출할 수 없다(승인된 초기 지원 集合 밖). */
    UNSUPPORTED_FORMAT,
    /** 지원 포맷이지만 추출 가능한 텍스트가 없다(예: 이미지만 있는 PDF). */
    NO_TEXT,
    /** 손상/악성 의심/크기·구조 상한 초과/Timeout 등으로 처리에 실패했다. */
    FAILED
}
