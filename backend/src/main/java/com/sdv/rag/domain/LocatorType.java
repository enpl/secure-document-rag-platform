package com.sdv.rag.domain;

/**
 * M06 신규 - v3.2 Citation Model(CLAUDE.md "Citation Model", V003
 * {@code chk_document_chunk_locator_type})이 이미 확정한 Core Locator
 * 어휘를 Java Enum으로 formalize한다. 새 값을 임의로 추가하지 않는다 -
 * PDF 페이지 전용으로 설계하지 않는 Citation Model의 정신을 그대로
 * 따른다.
 *
 * <p>포맷에 없는 구조는 꾸며내지 않는다: 예를 들어 페이지 개념이 없는
 * DOCX/일반 텍스트에 {@link #PAGE}를 억지로 붙이지 않는다(M06
 * {@code parser_service.py}가 포맷별로 실제 사용하는 값만 방출한다).</p>
 */
public enum LocatorType {
    PAGE,
    SLIDE,
    SHEET_RANGE,
    LINE_RANGE,
    SECTION,
    DOCUMENT
}
