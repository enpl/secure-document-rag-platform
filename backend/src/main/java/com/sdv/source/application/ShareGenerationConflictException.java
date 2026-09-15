package com.sdv.source.application;

/**
 * M10B 신규 - 소유자(게시자)가 넘긴 {@code expectedGeneration}이 지금 저장된
 * 공유의 실제 세대와 다를 때(동시에 다른 변경이 먼저 반영됨 - 본인의 다른 탭,
 * 또는 ADMIN의 차단) 던지는 안전한 신호. 어느 값이 옳았는지, 무엇이 바꿨는지는
 * 노출하지 않는다 - 호출자가 최신 상태를 다시 읽고 재시도해야 한다는 사실만
 * 전달한다.
 */
public class ShareGenerationConflictException extends RuntimeException {

    public ShareGenerationConflictException(String safeMessage) {
        super(safeMessage);
    }
}
