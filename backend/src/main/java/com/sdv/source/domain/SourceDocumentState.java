package com.sdv.source.domain;

/**
 * F-BE-129. Source 문서 생명주기 전용 - {@link DocumentIndexStatus}(F-BE-182,
 * RAG/콘텐츠 색인 상태)와는 별개 개념이다. SYNCED/READY/STALE/FAILED는 더 이상
 * 이 Enum의 값이 아니다(STALE/FAILED는 DocumentIndexStatus에만 있다).
 */
public enum SourceDocumentState {
    ACTIVE,
    DELETED
}
