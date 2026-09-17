package com.sdv.identity;

import com.sdv.identity.domain.UserAuthorizationChangedEvent;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class UserAuthorizationInvalidationTransactionTest {
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private EphemeralEvidenceStore evidenceStore;

    @BeforeEach
    void resetStore() {
        reset(evidenceStore);
    }

    @Test
    void rolledBackAuthorizationChangeDoesNotEvictEvidenceAsIfItCommitted() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            events.publishEvent(new UserAuthorizationChangedEvent("issuer", "user-b", 2));
            status.setRollbackOnly();
        });

        verifyNoInteractions(evidenceStore);
    }

    @Test
    void committedAuthorizationChangeEvictsOnlyTheAffectedRequester() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status ->
                events.publishEvent(new UserAuthorizationChangedEvent("issuer", "user-b", 3)));

        verify(evidenceStore).evictByRequester("user-b");
    }
}
