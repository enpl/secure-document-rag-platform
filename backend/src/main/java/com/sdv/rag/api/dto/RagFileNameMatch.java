package com.sdv.rag.api.dto;

/**
 * M17 자연어 파일 검색 교정 - {@link RagFileSearchQuery#q()}를 파일명에 어떻게
 * 적용할지 구분하는 Allowlist. 기존 {@code GET /api/rag/files} 직접 호출은 항상
 * {@link #CONTAINS}였고 그 의미를 그대로 유지한다({@link RagFileSearchQuery}의
 * 하위 호환 생성자가 기본값으로 쓴다) - 이 값을 명시적으로 {@link #PREFIX}로
 * 설정하는 유일한 호출자는 {@code NaturalLanguageFileQueryParser}다("sdv로
 * 시작하는" 같은 접두어 문구를 해석했을 때만).
 */
public enum RagFileNameMatch {
    /** 파일명에 포함되면 일치(기존 동작, 대소문자 무시). */
    CONTAINS,
    /** 파일명이 이 값으로 시작해야 일치(대소문자 무시) - CONTAINS로 조용히 대체되지 않는다. */
    PREFIX
}
