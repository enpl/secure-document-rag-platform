package com.sdv.policy.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval Handoff({@link EffectivePermissionService#filterAllowed}) 검증 - 실제
 * Spring Bean과 실제 Testcontainers PostgreSQL을 사용한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class EffectivePermissionServiceFilterAllowedTest {

    private static final String ACTION = "VIEW";

    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;

    @Test
    void returnsOnlyAllowedIdsPreservingInputOrder() {
        String owner = "owner-batch-" + UUID.randomUUID();
        long allowed1 = createDocument(owner);
        long denied = createDocument(owner); // no permission row -> denied
        long allowed2 = createDocument(owner);
        grantFreshRead(allowed1, owner);
        grantFreshRead(allowed2, owner);

        List<Long> result = effectivePermissionService.filterAllowed(userContext(owner),
                List.of(allowed1, denied, allowed2), ACTION, null);

        assertThat(result).containsExactly(allowed1, allowed2);
    }

    @Test
    void emptyInputReturnsEmptyOutput() {
        String owner = "owner-batch-empty-" + UUID.randomUUID();

        List<Long> result = effectivePermissionService.filterAllowed(userContext(owner), List.of(), ACTION, null);

        assertThat(result).isEmpty();
    }

    @Test
    void whenNothingIsAllowedTheResultIsEmptyNotUnrestricted() {
        String owner = "owner-batch-none-" + UUID.randomUUID();
        long doc1 = createDocument(owner); // no permission rows
        long doc2 = createDocument(owner); // no permission rows

        List<Long> result = effectivePermissionService.filterAllowed(userContext(owner), List.of(doc1, doc2),
                ACTION, null);

        assertThat(result).isEmpty();
    }

    private long createDocument(String ownerSubject) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(),
                "doc-" + UUID.randomUUID(), "Doc", "text/plain", null, null, "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }

    private void grantFreshRead(long documentId, String ownerSubject) {
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", ownerSubject, "READ", Instant.now()));
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }
}
