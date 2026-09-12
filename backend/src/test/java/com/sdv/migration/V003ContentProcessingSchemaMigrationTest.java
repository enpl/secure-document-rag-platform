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
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V003(Content Processing Schema) Flyway 마이그레이션 검증.
 *
 * <p>이 테스트는 Spring 컨텍스트를 부트하지 않고, 테스트 메서드마다 새로
 * 시작하는 격리된 Testcontainers PostgreSQL/pgvector 컨테이너 위에서
 * Flyway Java API를 직접 사용해 마이그레이션 순서를 정밀하게 제어한다.
 * ({@code TestcontainersConfiguration}을 쓰는 다른 테스트들은 Spring Boot
 * 자동 Flyway 통합으로 항상 최신 버전까지 한 번에 적용하므로, "V002까지만
 * 적용된 상태에서 V003만 적용" 같은 단계별 시나리오를 검증할 수 없다.)</p>
 *
 * <p>개발용 PostgreSQL이나 기존 Docker Volume은 전혀 사용하지 않는다.</p>
 */
class V003ContentProcessingSchemaMigrationTest {

    private static final String PGVECTOR_IMAGE = "pgvector/pgvector:0.8.6-pg18";
    private static final int VECTOR_DIMENSIONS = 1024;

    private static final List<String> VALID_INDEX_STATUSES = List.of(
            "PENDING", "INDEXED", "SKIPPED_UNSUPPORTED", "SKIPPED_NO_TEXT", "FAILED", "STALE");

    private static final List<String> VALID_LOCATOR_TYPES = List.of(
            "PAGE", "SLIDE", "SHEET_RANGE", "LINE_RANGE", "SECTION", "DOCUMENT");

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

    // ------------------------------------------------------------------
    // 1. Empty database migrates from V001 through V003
    // ------------------------------------------------------------------

    @Test
    void emptyDatabaseMigratesThroughV003() throws SQLException {
        // V004(M04)가 이후에 추가되었으므로, "latest"가 아니라 V003까지로 명시적으로
        // 고정한다 - 이 테스트의 의도(V001~V003 검증)는 이후 Migration과 무관해야 한다.
        flywayTo(MigrationVersion.fromVersion("3")).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003");

            assertThat(columnExists(connection, "source_documents", "index_status")).isTrue();
            assertThat(columnExists(connection, "source_documents", "index_reason")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "locator_type")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "locator_value")).isTrue();

            // V001/V002 columns must still exist after V003
            assertThat(columnExists(connection, "source_documents", "state")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "page")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "section")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "embedding")).isTrue();

            assertThat(indexExists(connection, "idx_document_chunks_embedding_hnsw")).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 2. A populated V002 database migrates to V003 without losing
    //    existing records or relationships; V001/V002 checksums unchanged.
    // ------------------------------------------------------------------

    @Test
    void populatedV002DatabaseMigratesToV003WithoutDataLoss() throws SQLException {
        flywayTo(MigrationVersion.fromVersion("2")).migrate();

        long sourceConnectionId;
        long sourceDocumentId;
        long chunkId;
        String embeddingLiteral = zeroVectorLiteral();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002");

            sourceConnectionId = insertSourceConnection(connection);
            sourceDocumentId = insertSourceDocument(connection, sourceConnectionId, "existing-doc-1");
            chunkId = insertChunkPreV003(connection, sourceDocumentId, 0, 1, "Introduction", embeddingLiteral);
        }

        Map<String, Integer> checksumsBeforeV003;
        try (Connection connection = connect()) {
            checksumsBeforeV003 = schemaHistoryChecksums(connection);
        }

        // V004(M04)가 이후에 추가되었으므로, "latest"가 아니라 V003까지로 명시적으로
        // 고정한다 - 이 테스트의 의도(V002→V003 검증)는 이후 Migration과 무관해야 한다.
        flywayTo(MigrationVersion.fromVersion("3")).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003");

            // V001/V002 checksums must be byte-identical before and after V003
            Map<String, Integer> checksumsAfterV003 = schemaHistoryChecksums(connection);
            assertThat(checksumsAfterV003.get("001")).isEqualTo(checksumsBeforeV003.get("001"));
            assertThat(checksumsAfterV003.get("002")).isEqualTo(checksumsBeforeV003.get("002"));

            // no rows lost
            assertThat(countRows(connection, "source_connections")).isEqualTo(1);
            assertThat(countRows(connection, "source_documents")).isEqualTo(1);
            assertThat(countRows(connection, "document_chunks")).isEqualTo(1);

            // existing source_document row preserved; new index_status defaults to PENDING, never INDEXED
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT name, state, index_status, index_reason FROM source_documents WHERE id = ?")) {
                ps.setLong(1, sourceDocumentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("name")).isEqualTo("Existing Document.pdf");
                    assertThat(rs.getString("state")).isEqualTo("ACTIVE");
                    assertThat(rs.getString("index_status")).isEqualTo("PENDING");
                    assertThat(rs.getString("index_reason")).isNull();
                }
            }

            // existing chunk row preserved; new locator columns are NULL (no backfill); embedding intact
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT page, section, locator_type, locator_value, embedding::text "
                            + "FROM document_chunks WHERE id = ?")) {
                ps.setLong(1, chunkId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt("page")).isEqualTo(1);
                    assertThat(rs.getString("section")).isEqualTo("Introduction");
                    assertThat(rs.getString("locator_type")).isNull();
                    assertThat(rs.getString("locator_value")).isNull();
                    assertThat(rs.getString("embedding")).isEqualTo(embeddingLiteral);
                }
            }

            // FK relationship (document_chunks.document_id -> source_documents.id) still intact
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT dc.id FROM document_chunks dc "
                            + "JOIN source_documents sd ON dc.document_id = sd.id WHERE dc.id = ?")) {
                ps.setLong(1, chunkId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }

            // pgvector HNSW index still intact
            assertThat(indexExists(connection, "idx_document_chunks_embedding_hnsw")).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // 3. index_status: valid canonical values accepted, invalid rejected,
    //    NOT NULL enforced.
    // ------------------------------------------------------------------

    @Test
    void indexStatusAcceptsCanonicalValuesAndRejectsInvalidOrNull() throws SQLException {
        flywayTo(null).migrate();

        try (Connection connection = connect()) {
            long sourceConnectionId = insertSourceConnectionWithOwner(connection);

            for (String validStatus : VALID_INDEX_STATUSES) {
                long id = insertSourceDocumentWithIndexStatus(
                        connection, sourceConnectionId, validStatus, "sdoc-" + validStatus);
                assertThat(id).isPositive();
            }

            assertThatThrownBy(() -> insertSourceDocumentWithIndexStatus(
                    connection, sourceConnectionId, "BOGUS_STATUS", "sdoc-invalid"))
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(() -> insertSourceDocumentWithIndexStatus(
                    connection, sourceConnectionId, null, "sdoc-null-status"))
                    .isInstanceOf(SQLException.class);

            // default still applies when the column is omitted entirely
            long defaultedId = insertSourceDocument(connection, sourceConnectionId, "sdoc-default");
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT index_status FROM source_documents WHERE id = ?")) {
                ps.setLong(1, defaultedId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("index_status")).isEqualTo("PENDING");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 4/6. locator_type: NULL and canonical values accepted, invalid
    //      rejected; locator_value remains independently nullable.
    // ------------------------------------------------------------------

    @Test
    void locatorTypeAcceptsNullAndCanonicalValuesAndRejectsInvalid() throws SQLException {
        // M07A(V006)이 document_chunks 자체를 제거했으므로, 이 Test가 검증하려는
        // 테이블은 더 이상 "latest"에 존재하지 않는다 - V006 이전(V005까지)으로
        // 명시적으로 고정한다. V003까지만으로는 부족하다: 아래에서 쓰는
        // insertSourceConnectionWithOwner()가 V004가 추가한 owner_subject
        // 컬럼을 요구한다(Class Javadoc 참고) - 그래서 "3"이 아니라 "5"다.
        flywayTo(MigrationVersion.fromVersion("5")).migrate();

        try (Connection connection = connect()) {
            long sourceConnectionId = insertSourceConnectionWithOwner(connection);
            long sourceDocumentId = insertSourceDocument(connection, sourceConnectionId, "sdoc-locator");
            String embeddingLiteral = zeroVectorLiteral();
            int chunkIndex = 0;

            // nullable: both NULL is allowed
            long nullLocatorChunkId = insertChunkWithLocator(
                    connection, sourceDocumentId, chunkIndex++, null, null, embeddingLiteral);
            assertThat(nullLocatorChunkId).isPositive();

            for (String validType : VALID_LOCATOR_TYPES) {
                long id = insertChunkWithLocator(
                        connection, sourceDocumentId, chunkIndex++, validType, "value-" + validType, embeddingLiteral);
                assertThat(id).isPositive();
            }

            int invalidLocatorChunkIndex = chunkIndex + 100;
            assertThatThrownBy(() -> insertChunkWithLocator(
                    connection, sourceDocumentId, invalidLocatorChunkIndex, "INVALID_LOCATOR", "x", embeddingLiteral))
                    .isInstanceOf(SQLException.class);

            // locator_value nullability is independent of locator_type
            long typeOnlyChunkId = insertChunkWithLocator(
                    connection, sourceDocumentId, chunkIndex + 200, "PAGE", null, embeddingLiteral);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT locator_type, locator_value FROM document_chunks WHERE id = ?")) {
                ps.setLong(1, typeOnlyChunkId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("locator_type")).isEqualTo("PAGE");
                    assertThat(rs.getString("locator_value")).isNull();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Flyway flywayTo(MigrationVersion target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
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

    private Map<String, Integer> schemaHistoryChecksums(Connection connection) throws SQLException {
        Map<String, Integer> checksums = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, checksum FROM flyway_schema_history "
                             + "WHERE version IS NOT NULL ORDER BY installed_rank")) {
            while (rs.next()) {
                checksums.put(rs.getString("version"), rs.getInt("checksum"));
            }
        }
        return checksums;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
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

    private int countRows(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** V001/V002-only columns - used only by the pre-V004 (owner_subject does not exist yet) scenario. */
    private long insertSourceConnection(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_connections (type, display_name, status, sync_mode) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, "GOOGLE_DRIVE");
            ps.setString(2, "M01 Migration Test Source");
            ps.setString(3, "ACTIVE");
            ps.setString(4, "INCREMENTAL");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * M04(V004) 이후 스키마용 - owner_subject가 NOT VALID CHECK로 신규 행에
     * 강제되므로, 최신 스키마를 대상으로 하는 테스트(index_status/locator_type
     * 제약 검증)는 이 Helper를 사용한다.
     */
    private long insertSourceConnectionWithOwner(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_connections (type, display_name, status, sync_mode, owner_subject) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, "GOOGLE_DRIVE");
            ps.setString(2, "M03 Migration Test Source");
            ps.setString(3, "ACTIVE");
            ps.setString(4, "INCREMENTAL");
            ps.setString(5, "owner-subject-migration-test");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Inserts a source_documents row using only V001-baseline columns (no index_status reference). */
    private long insertSourceDocument(Connection connection, long sourceConnectionId, String externalId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_documents (source_id, source_document_id, name, mime_type, state) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
            ps.setLong(1, sourceConnectionId);
            ps.setString(2, externalId);
            ps.setString(3, "Existing Document.pdf");
            ps.setString(4, "application/pdf");
            ps.setString(5, "ACTIVE");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Inserts a source_documents row with an explicit index_status (nullable, to test NOT NULL too). */
    private long insertSourceDocumentWithIndexStatus(
            Connection connection, long sourceConnectionId, String indexStatus, String externalId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_documents "
                        + "(source_id, source_document_id, name, mime_type, state, index_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id")) {
            ps.setLong(1, sourceConnectionId);
            ps.setString(2, externalId);
            ps.setString(3, "Existing Document.pdf");
            ps.setString(4, "application/pdf");
            ps.setString(5, "ACTIVE");
            ps.setString(6, indexStatus);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** Inserts a document_chunks row using only V002-baseline columns (no locator_* reference). */
    private long insertChunkPreV003(
            Connection connection, long documentId, int chunkIndex, Integer page, String section,
            String embeddingLiteral) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_chunks (document_id, chunk_index, page, section, content, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?::vector) RETURNING id")) {
            ps.setLong(1, documentId);
            ps.setInt(2, chunkIndex);
            if (page != null) {
                ps.setInt(3, page);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            ps.setString(4, section);
            ps.setString(5, "Sample chunk content for migration test.");
            ps.setString(6, embeddingLiteral);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertChunkWithLocator(
            Connection connection, long documentId, int chunkIndex, String locatorType, String locatorValue,
            String embeddingLiteral) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO document_chunks "
                        + "(document_id, chunk_index, content, embedding, locator_type, locator_value) "
                        + "VALUES (?, ?, ?, ?::vector, ?, ?) RETURNING id")) {
            ps.setLong(1, documentId);
            ps.setInt(2, chunkIndex);
            ps.setString(3, "Sample chunk content for locator test.");
            ps.setString(4, embeddingLiteral);
            ps.setString(5, locatorType);
            ps.setString(6, locatorValue);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String zeroVectorLiteral() {
        StringBuilder literal = new StringBuilder(VECTOR_DIMENSIONS * 2);
        literal.append('[');
        for (int i = 0; i < VECTOR_DIMENSIONS; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append('0');
        }
        literal.append(']');
        return literal.toString();
    }
}
