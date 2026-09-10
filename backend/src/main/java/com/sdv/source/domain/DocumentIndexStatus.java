package com.sdv.source.domain;

/**
 * F-BE-182. RAG/콘텐츠 색인 상태 - {@link SourceDocumentState}(F-BE-129, Source
 * 문서 생명주기)와는 별개 개념이다. {@code com.sdv.rag.domain}이 아니라
 * {@code com.sdv.source.domain}에 둔다 - RAG는 Source Domain에 의존할 수
 * 있지만 그 반대는 아니다.
 */
public enum DocumentIndexStatus {
    PENDING,
    INDEXED,
    SKIPPED_UNSUPPORTED,
    SKIPPED_NO_TEXT,
    FAILED,
    STALE
}
