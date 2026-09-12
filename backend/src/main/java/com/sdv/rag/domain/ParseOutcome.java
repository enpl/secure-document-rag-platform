package com.sdv.rag.domain;

import java.util.List;
import java.util.Objects;

/**
 * M06 신규 - Python AI Service {@code /parse} 호출 한 번의 결과를 표현하는
 * 순수 Domain 값(HTTP/JPA에 의존하지 않는다). {@link com.sdv.rag.infrastructure.ai.DocumentParsingClient}가
 * 생성하고 {@link com.sdv.rag.application.ContentExtractionService}가 소비한다.
 *
 * <p>{@code kind == SUCCESS}일 때만 {@code parserName}/{@code parserVersion}/
 * {@code normalizationVersion}/{@code normalizedText}/{@code locations}가
 * 채워진다 - 성공한 결과는 절대 잘리지 않는다(출력 길이 상한을 넘기면 이
 * {@code kind}는 {@link ParseOutcomeKind#FAILED}다, 잘린 텍스트를 성공으로
 * 포장하지 않는다). 그 밖의 {@code kind}에서는 {@code reason}만 채워진다 -
 * 이 값은 감사/로그에 안전하게 남을 수 있는 짧고 고정된 사유여야 한다(원본
 * 문서 내용/Stack Trace/파일 경로를 담지 않는다).</p>
 */
public record ParseOutcome(
        ParseOutcomeKind kind,
        String parserName,
        String parserVersion,
        String normalizationVersion,
        String normalizedText,
        List<ExtractedLocation> locations,
        String reason) {

    public ParseOutcome {
        Objects.requireNonNull(kind, "kind must not be null");
        if (kind == ParseOutcomeKind.SUCCESS) {
            Objects.requireNonNull(parserName, "parserName must not be null for SUCCESS");
            Objects.requireNonNull(parserVersion, "parserVersion must not be null for SUCCESS");
            Objects.requireNonNull(normalizationVersion, "normalizationVersion must not be null for SUCCESS");
            Objects.requireNonNull(normalizedText, "normalizedText must not be null for SUCCESS");
            Objects.requireNonNull(locations, "locations must not be null for SUCCESS");
            locations = List.copyOf(locations);
        } else {
            Objects.requireNonNull(reason, "reason must not be null for a non-SUCCESS outcome");
            locations = locations == null ? List.of() : List.copyOf(locations);
        }
    }

    public static ParseOutcome success(String parserName, String parserVersion, String normalizationVersion,
            String normalizedText, List<ExtractedLocation> locations) {
        return new ParseOutcome(ParseOutcomeKind.SUCCESS, parserName, parserVersion, normalizationVersion,
                normalizedText, locations, null);
    }

    public static ParseOutcome failure(ParseOutcomeKind kind, String reason) {
        if (kind == ParseOutcomeKind.SUCCESS) {
            throw new IllegalArgumentException("Use success(...) for ParseOutcomeKind.SUCCESS");
        }
        return new ParseOutcome(kind, null, null, null, null, List.of(), reason);
    }
}
