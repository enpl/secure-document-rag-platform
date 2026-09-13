package com.sdv.source.infrastructure.google;

/**
 * M08 신규 - {@link GoogleDriveClient}가 Byte를 실제로 받는 도중(다 받은
 * 뒤가 아니라) 정한 상한을 넘겼을 때만 던진다. "받는 도중" 감지가 핵심이다
 * - {@code byte[].length}를 다 받은 뒤에야 확인하는 방식은 이미 그만큼의
 * Byte를 Network/Memory로 흘려보낸 뒤이므로 이 작업 지시사항이 명시적으로
 * 금지한다.
 */
public class GoogleContentSizeLimitExceededException extends RuntimeException {

    public GoogleContentSizeLimitExceededException(String safeMessage) {
        super(safeMessage);
    }
}
