package com.sdv.rag.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M12 신규(F-BE-103, §2A.5 Mandatory Live Retrieval) - 요청 하나(다중 파일 포함)에
 * 허용되는 유한한 상한. {@code perFileDeadlineMs}는 파일 하나의 verifyBefore ->
 * fetch -> parse -> verifyAfter 전체에 적용되는 벽시계 예산이고,
 * {@code totalRequestDeadlineMs}는 여러 파일을 처리하는 요청 전체의 상한이다(둘 중
 * 먼저 도달하는 쪽이 그 시점 이후의 나머지 파일을 중단시킨다 - 이미 검증된 파일의
 * 결과는 그대로 유지한다).
 */
@ConfigurationProperties(prefix = "sdv.rag.live-retrieval")
public record LiveRetrievalProperties(int maxFilesPerRequest, long perFileDeadlineMs, long totalRequestDeadlineMs,
        int maxEvidenceChars, int maxTopK, int maxCandidateDocumentScan) {

    public LiveRetrievalProperties {
        if (maxFilesPerRequest <= 0) {
            maxFilesPerRequest = 5;
        }
        if (perFileDeadlineMs <= 0) {
            perFileDeadlineMs = 20_000L;
        }
        if (totalRequestDeadlineMs <= 0) {
            totalRequestDeadlineMs = 60_000L;
        }
        if (maxEvidenceChars <= 0) {
            maxEvidenceChars = 4_000;
        }
        if (maxTopK <= 0 || maxTopK > 50) {
            maxTopK = 10;
        }
        if (maxCandidateDocumentScan <= 0) {
            maxCandidateDocumentScan = 500;
        }
    }
}
