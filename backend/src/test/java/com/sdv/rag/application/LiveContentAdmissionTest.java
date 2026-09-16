package com.sdv.rag.application;

import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveContentAdmissionTest {

    @Test
    void finiteReservationRejectsConcurrentExcessAndRestoresCapacityAfterFailurePath() {
        LiveContentAdmission admission = new LiveContentAdmission(1, 100);
        LiveRetrievalDeadline deadline = LiveRetrievalDeadline.startingNow(1_000);

        LiveContentAdmission.Reservation reservation = admission.acquire(100, deadline);
        assertThatThrownBy(() -> admission.acquire(100, deadline))
                .isInstanceOf(EphemeralEvidenceCapacityExceededException.class);

        reservation.close();

        assertThatCode(() -> admission.acquire(100, deadline).close()).doesNotThrowAnyException();
    }
}
