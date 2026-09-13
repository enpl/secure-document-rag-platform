package com.sdv.source.domain;

/**
 * M08 신규 - {@link SourceChangeRecord}의 종류. Google {@code changes.list}의
 * {@code removed=true}는 "삭제됐다" 또는 "이 Credential이 접근권한을
 * 잃었다"를 구분하지 않고 하나로 뭉뚱그린다(공식 문서: "removed - Whether
 * the file or shared drive has been removed from this list of changes, for
 * example by deletion or loss of access.", 2026-09-13 확인 -
 * {@code docs/spec}가 아니라 이 Class의 Javadoc에 근거를 남긴다). 이 두
 * 사실을 하나의 "삭제됨" 값으로 합치면, 다른 사용자에게는 여전히 보이는
 * Catalog 문서를 한 사용자의 접근권한 상실만으로 전역 삭제 처리하는 심각한
 * 오류가 날 수 있다 - 그래서 이 Enum 값 이름 자체가 그 모호함을 그대로
 * 드러낸다. 실제 판단(전역 삭제 vs 이 Credential만의 접근권한 상실)은 M09가
 * Catalog Sync 시점에 다른 근거(예: 다른 Credential로 재확인)와 함께
 * 내려야 한다 - M08은 그 판단을 대신하지 않는다.
 */
public enum SourceChangeType {
    /** 문서가 바뀌었다({@code file}이 채워져 있다) - 새 Metadata를 그대로 반영할 수 있다. */
    CHANGED,
    /** Google이 {@code removed=true}를 보고했다 - 삭제됐거나 이 Credential이 접근권한을 잃었다(구분 불가). */
    REMOVED_OR_ACCESS_LOST
}
