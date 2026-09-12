package com.sdv.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V005(Content Processing - Extracted Text Storage) Flyway 마이그레이션 검증 -
 * {@code com.sdv.migration.V004SourceAccountIsolationMigrationTest}와 동일한
 * 방식(격리된 Testcontainers PostgreSQL, Flyway Java API 직접 사용).
 */
class V005ExtractedContentMigrationTest {

    private static final String PGVECTOR_IMAGE = "pgvector/pgvector:0.8.6-pg18";

    private PostgreSQLContainer container;

    @BeforeEach
    void startContainer() {
        container = new PostgreSQLContainer(DockerImageName.parse(PGVECTOR_IMAGE));
        container.start();
    }

    @AfterEach
    void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void emptyDatabaseMigratesThroughV005() throws SQLException {
        flyway().migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003", "004", "005");
            assertThat(tableExists(connection, "document_extracted_content")).isTrue();
            assertThat(constraintExists(connection, "fk_extracted_content_document")).isTrue();
            assertThat(constraintExists(connection, "chk_extracted_content_published_together")).isTrue();
        }
    }

    @Test
    void unpublishedClaimOnlyRowIsAllowed() throws SQLException {
        flyway().migrate();

        try (Connection connection = connect()) {
            long documentId = insertActiveDocument(connection, "Doc A");
            // Claim만 있고(attempt_id) 발행 컬럼은 전부 NULL - CHECK 제약을 통과해야 한다.
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO document_extracted_content (document_id, attempt_id, attempt_started_at) "
                            + "VALUES (?, gen_random_uuid(), now())")) {
                ps.setLong(1, documentId);
                ps.execute();
            }
        }
    }

    @Test
    void publishedRowRequiresAllPublishedColumnsTogether() throws SQLException {
        flyway().migrate();

        try (Connection connection = connect()) {
            long documentId = insertActiveDocument(connection, "Doc B");

            // published_at만 채우고 나머지 발행 컬럼을 비우면 CHECK 제약 위반.
            assertThatThrownBy(() -> {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO document_extracted_content (document_id, published_at) VALUES (?, now())")) {
                    ps.setLong(1, documentId);
                    ps.execute();
                }
            }).isInstanceOf(SQLException.class);

            // 전부 함께 채우면 성공한다.
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO document_extracted_content "
                            + "(document_id, source_version, content_hash, parser_name, parser_version, "
                            + "normalization_version, normalized_text, locations, published_at) "
                            + "VALUES (?, 'v1', 'hash', 'plaintext', '1', '1', 'hello', '[]', now())")) {
                ps.setLong(1, documentId);
                ps.execute();
            }
        }
    }

    @Test
    void extractedContentIsCascadeDeletedWithItsDocument() throws SQLException {
        flyway().migrate();

        try (Connection connection = connect()) {
            long documentId = insertActiveDocument(connection, "Doc C");
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO document_extracted_content "
                            + "(document_id, source_version, content_hash, parser_name, parser_version, "
                            + "normalization_version, normalized_text, locations, published_at) "
                            + "VALUES (?, 'v1', 'hash', 'plaintext', '1', '1', 'hello', '[]', now())")) {
                ps.setLong(1, documentId);
                ps.execute();
            }

            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM source_documents WHERE id = " + documentId);
            }

            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM document_extracted_content WHERE document_id = ?")) {
                ps.setLong(1, documentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isFalse();
                }
            }
        }
    }

    private Flyway flyway() {
        // M07A(V006)이 document_extracted_content 자체를 제거했으므로, 이
        // Test 파일은 V005가 그 테이블을 만든 시점의 Schema를 검증하기 위해
        // 명시적으로 V005까지만 Migrate한다("latest" = 이제 V006이라 이
        // 테이블이 없다) - V001~V005 자체는 여전히 수정하지 않는다.
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("5"));
        return configuration.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private long insertActiveDocument(Connection connection, String name) throws SQLException {
        long connectionId;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_connections (type, display_name, status, sync_mode, owner_subject) "
                        + "VALUES ('GOOGLE_DRIVE', 'Test Source', 'ACTIVE', 'FULL', 'owner-1') RETURNING id")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                connectionId = rs.getLong(1);
            }
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_documents (source_id, source_document_id, name, state) "
                        + "VALUES (?, ?, ?, 'ACTIVE') RETURNING id")) {
            ps.setLong(1, connectionId);
            ps.setString(2, "doc-" + name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private List<String> appliedVersions(Connection connection) throws SQLException {
        List<String> versions = new java.util.ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history "
                             + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString("version"));
            }
        }
        return versions;
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean constraintExists(Connection connection, String constraintName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
