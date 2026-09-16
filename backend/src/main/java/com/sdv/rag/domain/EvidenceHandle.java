package com.sdv.rag.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * M12 신규 - {@link com.sdv.rag.application.port.out.EphemeralEvidenceStore}가
 * 발급하는 불투명(Opaque) 참조. 평문 근거 Text를 담지 않는다 - 오직 조회용 ID와
 * 이 근거의 Hard Expiry 시각뿐이다({@code createdAt + 300초} 이하, §2A.6). 호출자는
 * {@code expiresAt}만으로 재사용 가능 여부를 판단해서는 안 된다 - 매 재사용 전마다
 * {@link com.sdv.rag.application.port.out.EphemeralEvidenceStore#getIfAuthorizedAndCurrent}로
 * 최신 인가/세대 결합까지 함께 재확인해야 한다.
 */
public record EvidenceHandle(UUID id, Instant createdAt, Instant expiresAt) {

    public EvidenceHandle {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must not be before createdAt");
        }
    }
}
