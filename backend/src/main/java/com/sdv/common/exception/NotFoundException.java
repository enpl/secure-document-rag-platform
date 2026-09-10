package com.sdv.common.exception;

/**
 * 요청한 리소스를 찾지 못했을 때 던지는 범용 신호. Owner-Scoped 조회에서
 * "존재하지만 다른 계정 소유"와 "존재하지 않음"을 동일한 404로 취급해,
 * 다른 계정의 리소스 존재 여부를 노출하지 않기 위해 사용한다.
 *
 * {@link GlobalExceptionHandler}가 이 예외를 고정된 안전한 404로 변환한다 -
 * 리소스 종류별 세부 메시지를 노출하지 않는다.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
