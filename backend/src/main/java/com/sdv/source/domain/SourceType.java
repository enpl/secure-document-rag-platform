package com.sdv.source.domain;

/**
 * F-BE-018. 명세가 정의한 정확한 Source 종류(SRC-001, SRC-002).
 *
 * Core에서 실제로 구현되는 것은 GOOGLE_DRIVE/LOCAL_VAULT뿐이다.
 * SHAREPOINT/S3는 Contract/Skeleton 수준만 허용된다(M04에서 실제
 * Connector를 만들지 않는다).
 */
public enum SourceType {
    GOOGLE_DRIVE,
    LOCAL_VAULT,
    SHAREPOINT,
    S3
}
