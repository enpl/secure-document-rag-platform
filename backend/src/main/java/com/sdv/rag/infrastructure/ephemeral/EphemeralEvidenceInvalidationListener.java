package com.sdv.rag.infrastructure.ephemeral;

import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.source.domain.ShareAccessRevokedEvent;
import com.sdv.source.domain.SourceConnectionAccessRevokedEvent;
import com.sdv.identity.domain.UserAuthorizationChangedEvent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

/**
 * M12 신규(§2A.6 "Evict on ... relevant local revoke/block/disconnect events") -
 * {@code SourceSharingService}(Source Domain)가 공유 철회/관리자 차단 직후 발행하는
 * domain-neutral {@link ShareAccessRevokedEvent}를 구독해, 그 문서에 대해 이미
 * 암호화 저장돼 있을 수 있는 Ephemeral Evidence를 즉시 제거한다. RAG는 Source에
 * 의존할 수 있으므로(반대 방향은 금지) 이 Listener가 그 연결을 담당한다 - Source
 * Domain 자신은 이 Class의 존재를 전혀 모른다.
 */
@Component
public class EphemeralEvidenceInvalidationListener {

    private final EphemeralEvidenceStore ephemeralEvidenceStore;

    public EphemeralEvidenceInvalidationListener(EphemeralEvidenceStore ephemeralEvidenceStore) {
        this.ephemeralEvidenceStore = ephemeralEvidenceStore;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShareAccessRevoked(ShareAccessRevokedEvent event) {
        ephemeralEvidenceStore.evictByDocument(event.documentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSourceDisconnected(SourceConnectionAccessRevokedEvent event) {
        ephemeralEvidenceStore.evictBySource(event.sourceId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAuthorizationChanged(UserAuthorizationChangedEvent event) {
        ephemeralEvidenceStore.evictByRequester(event.subject());
    }
}
