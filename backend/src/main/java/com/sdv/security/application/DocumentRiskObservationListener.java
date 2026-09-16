package com.sdv.security.application;

import com.sdv.source.domain.DocumentAccessMetadataChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs finding detection only after the source/share observation that triggered it commits. */
@Component
public class DocumentRiskObservationListener {
    private final OversharingDetectionService detector;

    public DocumentRiskObservationListener(OversharingDetectionService detector) {
        this.detector = detector;
    }

    @EventListener
    public void onChange(DocumentAccessMetadataChangedEvent event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                detector.inspect(event.documentId());
            }
        });
    }
}
