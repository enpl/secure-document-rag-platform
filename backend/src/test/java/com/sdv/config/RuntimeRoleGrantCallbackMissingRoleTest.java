package com.sdv.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * afterMigrate__grant_runtime_privileges.sql(실제 운영 콜백 파일, 수정 없음)이 sdv
 * 역할이 아직 존재하지 않는 상태에서 실행되면 실패해야 함을 검증한다.
 *
 * 이는 "콜백 실패가 Spring Startup을 막아야 한다"는 요구사항의 근거가 되는 실패 모드다:
 * 콜백의 마지막 문장(REVOKE ALL ON TABLE public.flyway_schema_history FROM sdv)은 sdv
 * 역할이 존재하지 않으면 PostgreSQL 자체가 오류를 낸다. Flyway.migrate()는 그 예외를
 * FlywayException으로 전파하며, Spring Boot의 Flyway 통합에서 migrate()는 Application
 * Context Refresh 도중(FlywayMigrationInitializer / Flyway AutoConfiguration) 호출되므로
 * 이 예외는 Context Refresh 실패 = Application Startup 실패로 이어진다.
 *
 * 별도의 disposable Postgres 컨테이너를 사용하며, 이 클래스가 시작한 컨테이너는
 * 클래스 종료 시 자동으로 제거된다. 기존 sdv-postgres 개발 컨테이너/볼륨은 사용하지 않는다.
 */
class RuntimeRoleGrantCallbackMissingRoleTest {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"));
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void callbackFailsClosedWhenSdvRoleIsMissingAndRecoversAfterCreatingIt() throws SQLException {
        Flyway withCallback = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/callback")
                .load();

        // sdv 역할이 존재하지 않는 상태에서 migrate()를 호출하면(V001/V002/V003 자체는
        // 성공하지만, 콜백의 REVOKE ... FROM sdv가 실패해) FlywayException이 던져져야 한다.
        // 이는 Spring Boot 통합에서 Application Context Refresh 실패, 즉
        // "정상적인 Application Startup 실패"에 그대로 대응한다.
        assertThatThrownBy(withCallback::migrate)
                .as("callback failure (missing sdv role) must propagate out of migrate(), "
                        + "which is what Spring Boot's Flyway integration calls during context refresh")
                .isInstanceOf(FlywayException.class);

        // V001/V002/V003 자체는 콜백 이전에 각각 독립적으로 Commit되므로 이미 적용되어 있어야 한다 -
        // 즉 "스키마는 적용됐지만 Runtime Grant는 없는" 상태가 실제로 재현된다.
        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = root.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
            java.util.List<String> versions = new java.util.ArrayList<>();
            while (rs.next()) {
                assertThat(rs.getBoolean("success")).isTrue();
                versions.add(rs.getString("version"));
            }
            assertThat(versions).containsExactly("001", "002", "003");

            assertThat(st.executeQuery(
                            "SELECT count(*) FROM pg_roles WHERE rolname = 'sdv'").next())
                    .isTrue();
        }

        // 복구: sdv 역할을 생성한 뒤(수동 Reconciliation) 같은 Flyway 인스턴스를 다시
        // migrate()하면(대기 중인 Migration 0개) 이번에는 콜백이 성공해야 한다.
        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = root.createStatement()) {
            st.execute("CREATE ROLE sdv WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-recovery'");
        }

        MigrateResult recovered = withCallback.migrate();
        assertThat(recovered.migrationsExecuted).isEqualTo(0);
        assertThat(recovered.success).isTrue();

        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var ps = root.prepareStatement(
                    "SELECT has_table_privilege('sdv', 'public.source_connections', 'SELECT')")) {
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
        }
    }
}
