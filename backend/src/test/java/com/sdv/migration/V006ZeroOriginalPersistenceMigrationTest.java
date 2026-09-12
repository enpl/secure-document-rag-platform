package com.sdv.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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

/**
 * V006(Zero-Original-Persistence Correction, M07A) Flyway 마이그레이션 검증 -
 * {@code com.sdv.migration.V005ExtractedContentMigrationTest}와 동일한 방식
 * (격리된 Testcontainers PostgreSQL, Flyway Java API 직접 사용).
 *
 * <p>V001~V005는 이 테스트에서도 절대 수정하지 않는다 - {@code target()}으로
 * V005까지만 먼저 적용해 레거시 평문 데이터를 실제로 만들어 넣은 뒤, 그
 * 위에서 V006까지 마저 적용해 "실제 평문이 있던 상태에서의 업그레이드"를
 * 검증한다(단순히 빈 DB에 처음부터 V006까지 적용하는 것과는 다른, 더 강한
 * 증거다).</p>
 */
class V006ZeroOriginalPersistenceMigrationTest {

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
    void emptyDatabaseMigratesThroughV006() throws SQLException {
        flyway(null).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003", "004", "005", "006");
            assertThat(tableExists(connection, "document_extracted_content"))
                    .as("V006 must drop document_extracted_content (structure and data)")
                    .isFalse();
            assertThat(tableExists(connection, "document_chunks"))
                    .as("V006 must drop the legacy plaintext document_chunks")
                    .isFalse();
            assertThat(tableExists(connection, "document_embedding_index")).isTrue();
            assertThat(constraintExists(connection, "fk_document_embedding_document")).isTrue();
            assertThat(constraintExists(connection, "chk_document_embedding_locator_type")).isTrue();
            assertThat(constraintExists(connection, "uq_document_embedding_generation_chunk")).isTrue();
            assertThat(indexExists(connection, "idx_document_embedding_hnsw")).isTrue();
        }
    }

    /**
     * 핵심 회귀 - 실제로 평문(레거시 {@code document_extracted_content.normalized_text},
     * {@code document_chunks.content})이 들어있는 V005 상태의 DB가 V006으로
     * 업그레이드될 때, 그 구조와 데이터가 함께 사라지고, Metadata/ACL/Account/
     * Policy 등 Content와 무관한 데이터는 그대로 보존된다.
     */
    @Test
    void v005DatabaseWithLegacyPlaintextRowsUpgradesThroughV006AndRemovesThem() throws SQLException {
        flyway(MigrationVersion.fromVersion("5")).migrate();

        long documentId;
        try (Connection connection = connect()) {
            documentId = insertActiveDocument(connection, "Doc With Legacy Plaintext");
            insertLegacyExtractedContent(connection, documentId, "this is real plaintext that must not survive V006");
            insertLegacyChunk(connection, documentId, "this chunk plaintext must not survive V006 either");
            // Content와 무관한 데이터 - V006 이후에도 그대로 남아야 한다.
            insertPermission(connection, documentId, "owner-1");

            assertThat(tableExists(connection, "document_extracted_content")).isTrue();
            assertThat(rowCount(connection, "document_extracted_content")).isEqualTo(1);
            assertThat(tableExists(connection, "document_chunks")).isTrue();
            assertThat(rowCount(connection, "document_chunks")).isEqualTo(1);
        }

        // 같은 DB 위에서 나머지(V006)를 마저 적용한다 - V001~V005는 다시 실행되지
        // 않는다(Flyway는 이미 적용된 Version을 재실행하지 않는다).
        flyway(null).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003", "004", "005", "006");
            assertThat(tableExists(connection, "document_extracted_content")).isFalse();
            assertThat(tableExists(connection, "document_chunks")).isFalse();

            // Content와 무관한 데이터는 보존된다.
            assertThat(rowCount(connection, "source_permissions")).isEqualTo(1);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM source_documents WHERE id = ?")) {
                ps.setLong(1, documentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("source_documents row must survive V006").isTrue();
                }
            }
        }
    }

    @Test
    void documentEmbeddingIndexCascadeDeletesWithItsDocument() throws SQLException {
        flyway(null).migrate();

        try (Connection connection = connect()) {
            long documentId = insertActiveDocument(connection, "Doc D");
            insertEmbeddingRow(connection, documentId, 0);

            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM source_documents WHERE id = " + documentId);
            }

            assertThat(rowCount(connection, "document_embedding_index")).isZero();
        }
    }

    @Test
    void duplicateChunkWithinTheSameGenerationIsRejected() throws SQLException {
        flyway(null).migrate();

        try (Connection connection = connect()) {
            long documentId = insertActiveDocument(connection, "Doc E");
            insertEmbeddingRow(connection, documentId, 0);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> insertEmbeddingRow(connection, documentId, 0))
                    .as("same document/source_version/parser_version/embedding_model/chunk_index must be rejected")
                    .isInstanceOf(SQLException.class);
        }
    }

    private Flyway flyway(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
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
                "INSERT INTO source_documents (source_id, source_document_id, name, source_version, state) "
                        + "VALUES (?, ?, ?, 'v1', 'ACTIVE') RETURNING id")) {
            ps.setLong(1, connectionId);
            ps.setString(2, "doc-" + name);
            ps.setString(3, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertPermission(Connection connection, long documentId, String principal) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_permissions (document_id, principal_type, principal_value, permission) "
                        + "VALUES (?, 'user', ?, 'READ')")) {
            ps.setLong(1, documentId);
            ps.setString(2, principal);
            ps.execute();
        }
    }

    private void insertLegacyExtractedContent(Connection connection, long documentId, String normalizedText)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_extracted_content "
                        + "(document_id, source_version, content_hash, parser_name, parser_version, "
                        + "normalization_version, normalized_text, locations, published_at) "
                        + "VALUES (?, 'v1', 'hash', 'plaintext', '1', '1', ?, '[]', now())")) {
            ps.setLong(1, documentId);
            ps.setString(2, normalizedText);
            ps.execute();
        }
    }

    private void insertLegacyChunk(Connection connection, long documentId, String content) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_chunks (document_id, chunk_index, content, embedding, source_version) "
                        + "VALUES (?, 0, ?, CAST(? AS vector), 'v1')")) {
            ps.setLong(1, documentId);
            ps.setString(2, content);
            ps.setString(3, zeroVectorLiteral());
            ps.execute();
        }
    }

    private void insertEmbeddingRow(Connection connection, long documentId, int chunkIndex) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_embedding_index "
                        + "(document_id, chunk_index, locator_type, locator_value, embedding, source_version, "
                        + "content_hmac, parser_version, embedding_model) "
                        + "VALUES (?, ?, 'DOCUMENT', '1', CAST(? AS vector), 'v1', ?, '1', 'bge-m3:567m')")) {
            ps.setLong(1, documentId);
            ps.setInt(2, chunkIndex);
            ps.setString(3, zeroVectorLiteral());
            ps.setString(4, "0".repeat(64));
            ps.execute();
        }
    }

    private static String zeroVectorLiteral() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('0');
        }
        return sb.append(']').toString();
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

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM pg_indexes WHERE indexname = ?")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int rowCount(Connection connection, String table) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
