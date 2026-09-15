package com.sdv.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M10 신규(RAG-011 File Metadata Discovery) - 한 요청에서 허용되는 Page 크기/개수와
 * Live 검증(Google 재확인) 전체에 허용되는 벽시계 시간 예산. 기존
 * {@code @ConfigurationPropertiesScan}({@code SecureDocumentVaultApplication})로
 * 자동 등록된다({@code SyncRunProperties}와 동일한 패턴).
 *
 * <p>{@code liveCheckBudgetMs}는 이번 요청 전체(공개 Page를 찾기 위한 처음부터의
 * 연속 Scan + 그 Page를 채우는 것 + {@code hasMore} 확인용 Verified 항목 1개 더
 * 찾기까지 전부)에 걸친 모든 {@code DocumentSourceConnector.verifyCurrentMetadata}
 * 순차 호출에 적용되는 하나의 공유 Deadline이다(개별 Google 호출 자체의 Timeout/
 * 재시도는 {@code GoogleDriveClient}가 별도로 관리한다) - 예산을 넘기면 남은 후보는
 * 검증하지 않고 응답의 {@code partial=true}로 정직하게 보고한다. 이 예산은 이미
 * 시작된 개별 HTTP 호출을 강제로 취소한다는 뜻이 아니다 - 다음 후보로 넘어가기
 * 전에 더 진행할지 여부를 결정하는 상한일 뿐이다(진행 중이던 마지막 확인이 실제로
 * 성공적으로 끝났다면 그 결과는 그대로 쓴다).</p>
 *
 * <p><b>M10 후속 교정</b> - {@code maxCandidateScan}(이전 이름
 * {@code maxLookaheadCandidates})은 이번 요청 전체에서 정책/Live로 실제로
 * "평가"하는(허용/거부와 무관하게 평가 자체를 시도하는) 원시 후보 수의 상한이다
 * ({@code FileMetadataDiscoveryService} 참고). 공개 Page는 이제 원시 DB 행
 * 오프셋이 아니라 "확인된(Verified+정책 통과) 후보의 순서상 위치"를 기준으로
 * 계산되므로, 앞쪽에 권한 없는/거부된 후보가 많으면 이 상한 안에서 요청한 Page에
 * 도달하지 못할 수 있다 - 그 경우 정직하게 {@code hasMore=null}(Unknown)/
 * {@code partial=true}로 보고한다(깊은 Page의 알려진, 문서화된 한계 - 전체
 * Catalog를 끝까지 훑는 Exhaustive Count Engine이 아니다).</p>
 */
@ConfigurationProperties(prefix = "sdv.rag.discovery")
public record RagDiscoveryProperties(int defaultPageSize, int maxPageSize, int maxPageIndex, long liveCheckBudgetMs,
        int maxCandidateScan) {

    public RagDiscoveryProperties {
        if (defaultPageSize <= 0) {
            defaultPageSize = 20;
        }
        if (maxPageSize <= 0) {
            maxPageSize = 50;
        }
        if (maxPageIndex <= 0) {
            maxPageIndex = 500;
        }
        if (liveCheckBudgetMs <= 0) {
            liveCheckBudgetMs = 10_000L;
        }
        if (maxCandidateScan <= 0) {
            maxCandidateScan = 200;
        }
    }
}
