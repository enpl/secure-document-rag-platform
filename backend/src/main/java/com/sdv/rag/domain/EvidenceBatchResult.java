package com.sdv.rag.domain;

import java.util.List;
import java.util.Objects;

/**
 * M12 신규 - {@code RagRetrievalService.retrieveVerifiedEvidence}(다중 파일)의
 * 결과. {@code requestPartial}은 이번 요청 자체가 상한(파일 수/총 시간 예산)에
 * 걸려 일부 파일을 아예 시도하지 못했음을 뜻한다 - 개별 파일의 {@link
 * LiveRetrievalResult#partialCoverage()}(그 파일 안에서 일부 위치만 담음)와는
 * 다른, 요청 전체 수준의 정직성 신호다. 둘 중 하나라도 참이면 호출자는 이
 * 결과를 "완전한 비교/요약"으로 제시해서는 안 된다.
 */
public record EvidenceBatchResult(List<LiveRetrievalResult> results, boolean requestPartial) {

    public EvidenceBatchResult {
        Objects.requireNonNull(results, "results must not be null");
        results = List.copyOf(results);
    }
}
