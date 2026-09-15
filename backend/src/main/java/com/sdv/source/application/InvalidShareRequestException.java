package com.sdv.source.application;

/**
 * M10B 신규 - {@link SourceSharingService}가 공유 생성/수정 요청 자체의 형태
 * 문제(알 수 없는 행위/등급 문자열, 빈/과다한 수신자 등)를 안전하게 신호하기
 * 위한 예외. {@code RagQueryController.InvalidDiscoveryQueryException}과 같은
 * 원칙 - 전역 예외 처리 범위를 넓히지 않고, 이 기능 경계 안에서만 400으로
 * 변환한다({@code SourceShareController}).
 */
public class InvalidShareRequestException extends RuntimeException {

    public InvalidShareRequestException(String safeMessage) {
        super(safeMessage);
    }
}
