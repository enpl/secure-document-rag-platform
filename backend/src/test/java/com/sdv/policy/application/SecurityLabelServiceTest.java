package com.sdv.policy.application;

import com.sdv.common.exception.NotFoundException;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SecurityLabelServiceTest {

    @Autowired
    private SecurityLabelService securityLabelService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Test
    void missingLabelIsEmpty() {
        String owner = "owner-" + UUID.randomUUID();
        long documentId = createDocument(owner);

        assertThat(securityLabelService.getSecurityLevel(userContext(owner), documentId)).isEmpty();
    }

    @Test
    void setThenGetRoundTrips() {
        String owner = "owner-" + UUID.randomUUID();
        long documentId = createDocument(owner);

        securityLabelService.setLabel(userContext(owner), documentId, SecurityLevel.CONFIDENTIAL, "MANUAL",
                "internal-only");

        assertThat(securityLabelService.getSecurityLevel(userContext(owner), documentId))
                .contains(SecurityLevel.CONFIDENTIAL);
    }

    @Test
    void setTwiceUpdatesRatherThanDuplicating() {
        String owner = "owner-" + UUID.randomUUID();
        long documentId = createDocument(owner);

        securityLabelService.setLabel(userContext(owner), documentId, SecurityLevel.INTERNAL, "SOURCE_MAPPING",
                "internal-label");
        securityLabelService.setLabel(userContext(owner), documentId, SecurityLevel.SECRET, "MANUAL", null);

        assertThat(securityLabelService.getSecurityLevel(userContext(owner), documentId))
                .contains(SecurityLevel.SECRET);
    }

    @Test
    void getSecurityLevelForAForeignAccountDocumentIsRejected() {
        String owner = "owner-" + UUID.randomUUID();
        String attacker = "attacker-" + UUID.randomUUID();
        long documentId = createDocument(owner);
        securityLabelService.setLabel(userContext(owner), documentId, SecurityLevel.SECRET, "MANUAL", null);

        assertThatThrownBy(() -> securityLabelService.getSecurityLevel(userContext(attacker), documentId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getSecurityLevelForANonexistentDocumentIsRejectedTheSameWayAsAForeignOne() {
        String attacker = "attacker-" + UUID.randomUUID();

        assertThatThrownBy(() -> securityLabelService.getSecurityLevel(userContext(attacker), 987_654_321L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void setLabelForAForeignAccountDocumentIsRejectedAndDoesNotCreateALabel() {
        String owner = "owner-" + UUID.randomUUID();
        String attacker = "attacker-" + UUID.randomUUID();
        long documentId = createDocument(owner);

        assertThatThrownBy(() -> securityLabelService.setLabel(userContext(attacker), documentId,
                SecurityLevel.SECRET, "MANUAL", null))
                .isInstanceOf(NotFoundException.class);

        // The unauthorized attempt must not have created a label for the real owner either.
        assertThat(securityLabelService.getSecurityLevel(userContext(owner), documentId)).isEmpty();
    }

    private long createDocument(String ownerSubject) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "doc-" + UUID.randomUUID(),
                "Doc", "text/plain", null, null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }
}
