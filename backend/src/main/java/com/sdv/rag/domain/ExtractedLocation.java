package com.sdv.rag.domain;

import java.util.Objects;

/**
 * M06 신규 - {@code document_extracted_content.normalized_text} 안의 한 구간이
 * 어떤 Source 위치(페이지/시트/줄/섹션/문서 전체)에 해당하는지를 나타낸다.
 * M11이 Citation을 재구성할 때 다시 Parsing하지 않고 이 정보만으로 위치를
 * 복원할 수 있어야 한다(Structure를 이 계층에서 평평하게 뭉개지 않는다).
 *
 * <p>{@code startOffset}/{@code endOffset}은 {@code normalized_text}에 대한
 * Java {@link String}과 동일한 단위(UTF-16 code unit index, {@code [start,
 * end)} 반개구간)다 - Python {@code parser_service.py}는 Python
 * {@code str}(코드포인트) 오프셋이 아니라 이 단위로 변환해 응답해야 한다
 * (Non-BMP 문자는 Java에서 Surrogate Pair 2개로 계산되므로 단순 코드포인트
 * 수와 다를 수 있다) - 그렇지 않으면 Java 쪽에서 저장된 오프셋으로 부분
 * 문자열을 잘랐을 때 위치가 어긋난다.</p>
 */
public record ExtractedLocation(LocatorType locatorType, String locatorValue, int startOffset, int endOffset) {

    public ExtractedLocation {
        Objects.requireNonNull(locatorType, "locatorType must not be null");
        Objects.requireNonNull(locatorValue, "locatorValue must not be null");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must not be negative");
        }
        if (endOffset < startOffset) {
            throw new IllegalArgumentException("endOffset must not be before startOffset");
        }
    }
}
