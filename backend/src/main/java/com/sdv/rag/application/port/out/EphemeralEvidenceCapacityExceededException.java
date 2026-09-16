package com.sdv.rag.application.port.out;

/**
 * M12 신규 - {@link EphemeralEvidenceStore#putEncrypted}가 구성된 Bounded Memory
 * 상한({@code EphemeralEvidenceProperties#maxEntries()})에 도달했을 때 던진다.
 * 가장 오래된 항목을 몰래 밀어내는 대신 Fail Closed 한다 - 호출자({@link
 * com.sdv.rag.application.LiveEvidenceRetrievalService})가 이를 안전한 고정
 * 사유 코드({@code CAPACITY_EXHAUSTED})로 옮긴다.
 */
public class EphemeralEvidenceCapacityExceededException extends RuntimeException {
    public EphemeralEvidenceCapacityExceededException() {
        super("ephemeral evidence store capacity exceeded");
    }
}
