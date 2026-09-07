package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * source_connections JPA persistence 최소 검증.
 *
 * local 프로파일의 실제 PostgreSQL(Flyway V001 적용 스키마)을 사용하며,
 * 테스트용 임베디드 DataSource로 교체하지 않는다.
 * Hibernate 스키마 자동 생성은 application.yml의 ddl-auto=none으로 비활성화한다.
 */
@DataJpaTest
@ActiveProfiles("local")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SourceConnectionJpaRepositoryTest {

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndFindsSourceConnectionById() {
        SourceConnectionEntity entity = new SourceConnectionEntity(
                "GOOGLE_DRIVE",
                "Marketing Google Drive",
                "ACTIVE",
                "INCREMENTAL"
        );

        SourceConnectionEntity saved = sourceConnectionJpaRepository.saveAndFlush(entity);

        assertThat(saved.getId()).isNotNull();
        entityManager.clear();

        SourceConnectionEntity found = sourceConnectionJpaRepository.findById(saved.getId())
                .orElseThrow();

        assertThat(found.getType()).isEqualTo("GOOGLE_DRIVE");
        assertThat(found.getDisplayName()).isEqualTo("Marketing Google Drive");
        assertThat(found.getStatus()).isEqualTo("ACTIVE");
        assertThat(found.getSyncMode()).isEqualTo("INCREMENTAL");
    }
}
