package com.sdv.audit.infrastructure.persistence;

import com.sdv.audit.domain.AuditEvent;
import com.sdv.audit.infrastructure.persistence.entity.AuditLogEntity;
import com.sdv.audit.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-BE-120/121 + Adapter 검증: {@link AuditEvent}가 기존 {@code audit_logs}(V001)
 * 컬럼에 정확히 매핑되고, 이미 위생 처리된 metadata만 JSONB로 왕복됨을 확인한다.
 * Testcontainers PostgreSQL/pgvector(실제 Flyway V001~V003 적용 스키마) 위에서
 * 검증하며, 개발용 Postgres/Volume에는 의존하지 않는다.
 */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogPersistenceAdapterTest {

    @Autowired
    private AuditLogJpaRepository auditLogJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAuditEventAndRoundTripsAllFieldsIncludingSanitizedJsonbMetadata() {
        AuditLogPersistenceAdapter adapter = new AuditLogPersistenceAdapter(auditLogJpaRepository);
        AuditEvent event = new AuditEvent(
                "subject-001",
                "AUTHENTICATION_FAILURE",
                "GET /api/me",
                "DENIED",
                "AUTHENTICATION_REQUIRED",
                "trace-adapter-001",
                Map.of("note", "***MASKED***"),
                Instant.now());

        adapter.save(event);
        entityManager.flush();
        entityManager.clear();

        AuditLogEntity saved = auditLogJpaRepository.findAll().stream()
                .filter(e -> "trace-adapter-001".equals(e.getTraceId()))
                .findFirst()
                .orElseThrow();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getActor()).isEqualTo("subject-001");
        assertThat(saved.getAction()).isEqualTo("AUTHENTICATION_FAILURE");
        assertThat(saved.getTargetType()).isEqualTo("HTTP_REQUEST");
        assertThat(saved.getTargetId()).isEqualTo("GET /api/me");
        assertThat(saved.getResult()).isEqualTo("DENIED");
        assertThat(saved.getReasonCode()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(saved.getTraceId()).isEqualTo("trace-adapter-001");
        assertThat(saved.getMetadata()).containsEntry("note", "***MASKED***");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void savesEmptyMetadataWithoutError() {
        AuditLogPersistenceAdapter adapter = new AuditLogPersistenceAdapter(auditLogJpaRepository);
        AuditEvent event = new AuditEvent(
                "anonymous", "AUTHENTICATION_FAILURE", "GET /api/admin/health", "DENIED",
                "AUTHENTICATION_REQUIRED", "trace-adapter-002", Map.of(), Instant.now());

        adapter.save(event);
        entityManager.flush();
        entityManager.clear();

        AuditLogEntity saved = auditLogJpaRepository.findAll().stream()
                .filter(e -> "trace-adapter-002".equals(e.getTraceId()))
                .findFirst()
                .orElseThrow();

        assertThat(saved.getMetadata()).isEmpty();
    }
}
