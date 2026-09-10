package com.sdv.source.domain;

/**
 * F-BE-022. 정규화된 Source Principal(SRC-005, AUT-004).
 *
 * {@code type}은 v3.2 명세가 canonical Enum File을 별도로 정의하지 않으므로
 * 문자열로 유지한다(예: {@code user}/{@code group}/{@code domain}/
 * {@code anyone}) - 임의로 새 Enum을 만들지 않는다.
 */
public record SourcePrincipal(String type, String value) {
}
