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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V004(Source Core - Account Isolation & Document Lifecycle) Flyway
 * 마이그레이션 검증. {@code com.sdv.migration.V003ContentProcessingSchemaMigrationTest}와
 * 동일한 방식 - 테스트 메서드마다 새로 시작하는 격리된 Testcontainers
 * PostgreSQL 컨테이너 위에서 Flyway Java API를 직접 사용해 마이그레이션
 * 단계를 정밀하게 제어한다. 개발용 PostgreSQL/Volume은 전혀 사용하지 않는다.
 */
class V004SourceAccountIsolationMigrationTest {

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
    void emptyDatabaseMigratesThroughV004() throws SQLException {
        // V005(M06)가 추가된 뒤에도 이 테스트가 V004 자체의 동작만 안정적으로
        // 검증하도록 target을 V004로 고정한다(이전에는 null=최신이었는데, V005가
        // 추가되자 "latest"가 V005까지 포함하게 되어 이 Assertion이 깨졌다).
        flywayTo(org.flywaydb.core.api.MigrationVersion.fromVersion("4")).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003", "004");

            assertThat(columnExists(connection, "source_connections", "owner_subject")).isTrue();
            assertThat(indexExists(connection, "idx_source_connections_owner_subject")).isTrue();
            assertThat(constraintExists(connection, "chk_source_connection_owner_subject")).isTrue();
            assertThat(constraintExists(connection, "chk_source_document_state")).isTrue();

            // V001/V002/V003 columns must still exist after V004
            assertThat(columnExists(connection, "source_documents", "index_status")).isTrue();
            assertThat(columnExists(connection, "document_chunks", "embedding")).isTrue();
        }
    }

    @Test
    void populatedV003DatabasePreservesLegacyRowsWhileEnforcingConstraintsOnNewWrites() throws SQLException {
        flywayTo(MigrationVersion.fromVersion("3")).migrate();

        long legacyConnectionId;
        long legacyDocumentId;

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003");

            // 레거시 행 재현: V004 이전에는 owner_subject 컬럼 자체가 없었고,
            // source_documents.state에 대한 값 제약도 없었다(구 값 SYNCED 허용).
            legacyConnectionId = insertLegacySourceConnection(connection, "Legacy Drive");
            legacyDocumentId = insertLegacySourceDocument(connection, legacyConnectionId, "legacy-doc", "SYNCED");
        }

        Map<String, Integer> checksumsBeforeV004;
        try (Connection connection = connect()) {
            checksumsBeforeV004 = schemaHistoryChecksums(connection);
        }

        // V005(M06) 추가 이후에도 V004 자체 동작만 검증하도록 target을 고정한다
        // (위 emptyDatabaseMigratesThroughV004와 동일한 이유).
        flywayTo(org.flywaydb.core.api.MigrationVersion.fromVersion("4")).migrate();

        try (Connection connection = connect()) {
            assertThat(appliedVersions(connection)).containsExactly("001", "002", "003", "004");

            // V001/V002/V003 checksum은 바뀌지 않는다.
            Map<String, Integer> checksumsAfterV004 = schemaHistoryChecksums(connection);
            assertThat(checksumsAfterV004.get("001")).isEqualTo(checksumsBeforeV004.get("001"));
            assertThat(checksumsAfterV004.get("002")).isEqualTo(checksumsBeforeV004.get("002"));
            assertThat(checksumsAfterV004.get("003")).isEqualTo(checksumsBeforeV004.get("003"));

            // 레거시 행은 그대로 보존된다 - owner_subject는 NULL, state는 예전 값 그대로.
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT display_name, owner_subject FROM source_connections WHERE id = ?")) {
                ps.setLong(1, legacyConnectionId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("display_name")).isEqualTo("Legacy Drive");
                    assertThat(rs.getString("owner_subject")).isNull();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT state FROM source_documents WHERE id = ?")) {
                ps.setLong(1, legacyDocumentId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("state")).isEqualTo("SYNCED");
                }
            }

            // 레거시(owner_subject NULL) 행에 대한 UPDATE는 실패한다(Fail Closed) -
            // 애플리케이션이 owner_subject로 조회하므로 이 행은 어차피 보이지 않는다.
            try (Connection root = connect(); Statement st = root.createStatement()) {
                assertThatThrownBy(() -> st.execute(
                        "UPDATE source_connections SET display_name = 'Renamed' WHERE id = " + legacyConnectionId))
                        .isInstanceOf(SQLException.class);
            }

            // owner_subject 없이 신규 INSERT도 실패한다.
            try (Connection root = connect(); Statement st = root.createStatement()) {
                assertThatThrownBy(() -> st.execute(
                        "INSERT INTO source_connections (type, display_name, status, sync_mode) "
                                + "VALUES ('GOOGLE_DRIVE', 'No Owner', 'ACTIVE', 'FULL')"))
                        .isInstanceOf(SQLException.class);
            }

            // 유효한 owner_subject가 있으면 신규 INSERT는 성공한다.
            try (Connection root = connect(); Statement st = root.createStatement()) {
                st.execute("INSERT INTO source_connections (type, display_name, status, sync_mode, owner_subject) "
                        + "VALUES ('GOOGLE_DRIVE', 'Has Owner', 'ACTIVE', 'FULL', 'subject-001')");
            }

            // 레거시(state='SYNCED') 행에 대한 UPDATE도 실패한다(그 값 자체가 이미
            // 제약을 위반하므로, 다른 컬럼을 바꾸는 UPDATE라도 재평가되어 막힌다).
            try (Connection root = connect(); Statement st = root.createStatement()) {
                assertThatThrownBy(() -> st.execute(
                        "UPDATE source_documents SET name = 'Renamed' WHERE id = " + legacyDocumentId))
                        .isInstanceOf(SQLException.class);
            }

            // 구 lifecycle 값으로 신규 INSERT는 실패한다.
            try (Connection root = connect(); Statement st = root.createStatement()) {
                assertThatThrownBy(() -> st.execute(
                        "INSERT INTO source_documents (source_id, source_document_id, name, state) "
                                + "VALUES (" + legacyConnectionId + ", 'new-bad-state', 'x', 'FAILED')"))
                        .isInstanceOf(SQLException.class);
            }

            // ACTIVE/DELETED로 신규 INSERT는 성공한다.
            try (Connection root = connect(); Statement st = root.createStatement()) {
                st.execute("INSERT INTO source_documents (source_id, source_document_id, name, state) "
                        + "VALUES (" + legacyConnectionId + ", 'new-good-state', 'x', 'ACTIVE')");
            }
        }
    }

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
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    private long insertLegacySourceConnection(Connection connection, String displayName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_connections (type, display_name, status, sync_mode) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, "GOOGLE_DRIVE");
            ps.setString(2, displayName);
            ps.setString(3, "ACTIVE");
            ps.setString(4, "INCREMENTAL");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long insertLegacySourceDocument(Connection connection, long sourceId, String externalId, String state)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO source_documents (source_id, source_document_id, name, state) "
                        + "VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setLong(1, sourceId);
            ps.setString(2, externalId);
            ps.setString(3, "Legacy Doc");
            ps.setString(4, state);
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
