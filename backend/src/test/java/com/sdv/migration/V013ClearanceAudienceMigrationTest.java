package com.sdv.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class V013ClearanceAudienceMigrationTest {
    private static PostgreSQLContainer container;

    @BeforeAll
    static void start() {
        container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"));
        container.start();
    }

    @AfterAll
    static void stop() {
        if (container != null) container.stop();
    }

    @Test
    void legacyNamedShareIsNotWidenedAndUsersReceiveNoImplicitClearance() throws Exception {
        flyway("12").migrate();
        long shareId;
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            long sourceId = returning(statement, "INSERT INTO source_connections "
                    + "(type, display_name, status, sync_mode, owner_subject, provider_account_id) VALUES "
                    + "('GOOGLE_DRIVE','Legacy','ACTIVE','FULL','owner-a','provider-a') RETURNING id");
            long documentId = returning(statement, "INSERT INTO source_documents "
                    + "(source_id, source_document_id, name, source_version, state) VALUES (" + sourceId
                    + ",'legacy-doc','Legacy.txt','v1','ACTIVE') RETURNING id");
            shareId = returning(statement, "INSERT INTO document_shares "
                    + "(publisher_subject, source_id, document_id, classification, allowed_actions) VALUES "
                    + "('owner-a'," + sourceId + "," + documentId + ",'INTERNAL','VIEW') RETURNING id");
            statement.execute("INSERT INTO document_share_recipients (share_id, recipient_subject) VALUES ("
                    + shareId + ",'recipient-b')");
        }

        flyway("13").migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT audience FROM document_shares WHERE id=" + shareId)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("NAMED_USERS");
            }
            try (ResultSet result = statement.executeQuery(
                    "SELECT recipient_subject, recipient_user_id FROM document_share_recipients WHERE share_id=" + shareId)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("recipient-b");
                assertThat(result.getObject(2)).isNull();
            }
            statement.execute("INSERT INTO sdv_users (issuer,subject,login_id,normalized_login_id) "
                    + "VALUES ('issuer','subject-c','sdv-user-c','sdv-user-c')");
            try (ResultSet result = statement.executeQuery(
                    "SELECT max_classification, active FROM sdv_users WHERE subject='subject-c'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1)).isNull();
                assertThat(result.getBoolean(2)).isTrue();
            }
        }
    }

    private static long returning(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion(target)).load();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
