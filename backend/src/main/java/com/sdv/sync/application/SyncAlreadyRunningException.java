package com.sdv.sync.application;

/**
 * M09A 신규 - 같은 Source에 대해 이미 {@code RUNNING} 상태인 {@code sync_runs}
 * 행이 있을 때(V008 부분 Unique Index 위반) 던지는 안전한 신호. 원본 DB
 * 예외({@link org.springframework.dao.DataIntegrityViolationException}) 메시지를
 * 그대로 노출하지 않는다 - "이미 동기화가 진행 중"이라는 사실만 전달한다.
 */
public class SyncAlreadyRunningException extends RuntimeException {

    public SyncAlreadyRunningException(String safeMessage) {
        super(safeMessage);
    }
}
