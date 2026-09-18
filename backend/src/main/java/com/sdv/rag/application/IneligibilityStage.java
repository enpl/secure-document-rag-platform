package com.sdv.rag.application;

/**
 * M17 진단 교정 - {@link IndexOrchestrator#processWithReasonCode}가 {@code
 * SKIPPED_INELIGIBLE}을 반환할 때, 정확히 어느 단계에서 제외됐는지 구분하는 고정
 * 허용 값(이 작업 지시사항 2번, "자격 검사, 자격증명/원본 읽기, 버전 검증, 최종
 * 세대 검증 중 어느 단계에서 제외됐는지 고정된 허용 코드로 구분한다"). {@link
 * #reasonCode()}만 {@code processed_events.reason_code}에 기록한다 - 원시 예외
 * 메시지/Google 응답 본문/파일명/토큰/원문은 이 값 어디에도 담기지 않는다(모두
 * 판정 시점의 분류일 뿐이다).
 *
 * <p>이 값은 판정을 내리는 그 자리에서 채워진다 - 나중에 DB를 다시 조회해 과거
 * 실패 이유를 추측하지 않는다("판정 시점의 typed 결과를 전달한다").</p>
 */
enum IneligibilityStage {
    /**
     * {@link IndexOrchestrator#resolveEligibility}(공유 존재/철회/관리자 차단/대상
     * 일관성, 연결 활성 여부, AI 사용 정책) 단계 - Google 호출 자체가 시도되기
     * 전이다.
     */
    ELIGIBILITY_CHECK,
    /**
     * 자격증명/원본 읽기 단계 - Connector 해석 실패(원본 Source Type을 인식할 수
     * 없거나, 알려진 유형이지만 등록된 Connector가 없음) 또는 {@link
     * com.sdv.source.domain.SourceContentOutcome}이 {@code VERIFIED}가 아닌 경우
     * (자격증명 문제/삭제·휴지통/버전 불일치/Export 상한 등).
     *
     * <p>M17 진단 세분화(2차) - {@link IndexOrchestrator}는 이 단계로 판정된 경우
     * {@link #reasonCode()}(=={@code "INELIGIBLE_AT_SOURCE_ACCESS"}) 뒤에 실제 typed
     * 원인을 고정 접미사로 덧붙인다(예: {@code INELIGIBLE_AT_SOURCE_ACCESS_MISSING_CREDENTIAL},
     * {@code ..._VERSION_MISMATCH}, {@code ..._CONNECTOR_UNAVAILABLE}) - 이 상수 자체의
     * {@link #reasonCode()}가 그대로 기록되는 것은 아니다(다른 3개 단계와 다른 점).
     * {@link IndexOrchestrator#handleUnverifiedContent} 참고.</p>
     */
    SOURCE_ACCESS,
    /**
     * AI 파싱 이후, 아직 어떤 DB Lock도 열지 않은 상태에서 수행하는 발행 직전
     * Provider 접근/버전 재확인 단계.
     */
    VERSION_RECHECK,
    /**
     * 발행 직전 짧은 Transaction 안에서의 최종 Lock 재조회/재검증(연결 Epoch,
     * 문서 버전, 공유 Generation, 등급 재해석, AI 정책 재확인) 단계.
     */
    FINAL_GENERATION_FENCE;

    /** {@code processed_events.reason_code}(VARCHAR(200))에 기록할 고정 문자열. */
    String reasonCode() {
        return "INELIGIBLE_AT_" + name();
    }
}
