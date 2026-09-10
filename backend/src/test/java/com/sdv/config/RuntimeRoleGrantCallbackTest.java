package com.sdv.config;

import org.flywaydb.core.Flyway;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * backend/src/main/resources/db/callback/afterMigrate__grant_runtime_privileges.sql
 * (실제 운영 콜백 파일, 수정 없이 그대로 사용)를 수정되지 않은 V001/V002/V003과 함께
 * 격리된 disposable Testcontainers PostgreSQL/pgvector 위에서 검증한다.
 *
 * 기존 sdv-postgres 개발 컨테이너/볼륨은 절대 사용하지 않는다 - 이 클래스가 시작하는
 * 컨테이너는 이 테스트 전용이며 클래스 종료 시 자동으로 제거된다.
 *
 * 시나리오(요구된 "Existing-schema path"를 그대로 재현):
 *   1. classpath:db/migration 만으로 Flyway 실행 - V001/V002/V003이 적용되지만
 *      아직 sdv 런타임 권한 부여(Grant)는 없다("기존 스키마, 아직 Runtime Grant 없음" 상태).
 *   2. sdv 역할을 생성한다(최소권한 - NOSUPERUSER/NOCREATEDB/NOCREATEROLE/
 *      NOREPLICATION/NOBYPASSRLS. 실제 fresh-volume 경로에서는
 *      infra/initdb/10-create-runtime-role.sh가 이 역할을 생성하며, 그 스크립트
 *      자체의 동작(Windows bind mount, 실행/sourcing, 비밀 파일 처리)은 별도로
 *      disposable Docker 컨테이너에서 직접 실행하여 확인했다 - 이 JUnit 클래스는
 *      Flyway 콜백 자체의 동작을 검증한다).
 *   3. classpath:db/migration,classpath:db/callback으로 Flyway를 다시 실행한다.
 *      대기 중인 Versioned Migration은 0개이지만, 콜백은 그래도 실행되어야 한다 -
 *      이전 handoff에서 "unverified"로 남아있던 정확히 그 동작을 여기서 확정한다.
 * ============================================================
 */
class RuntimeRoleGrantCallbackTest {

    private static final String SDV_PASSWORD = "test-only-" + System.nanoTime();

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

    private Connection rootConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private Connection sdvConnection(String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), "sdv", password);
    }

    @Test
    void existingSchemaReconciliationGrantsExactRuntimePrivileges() throws Exception {
        // 1. 기존 스키마 상태 재현: 콜백 없이 V001~V004만 적용한다(수정 없는 실제 파일 사용).
        Flyway migrationOnly = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
        MigrateResult firstResult = migrationOnly.migrate();
        assertThat(firstResult.migrationsExecuted).isEqualTo(4);

        // 2. sdv 역할 생성 (기존 DB에 대한 수동 Reconciliation 단계).
        try (Connection root = rootConnection(); Statement st = root.createStatement()) {
            st.execute("CREATE ROLE sdv WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD '" + SDV_PASSWORD.replace("'", "''") + "'");
        }

        // 3. 콜백 위치를 추가해 다시 실행 - 대기 중인 Versioned Migration은 0개.
        Flyway withCallback = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration", "classpath:db/callback")
                .load();
        MigrateResult secondResult = withCallback.migrate();
        assertThat(secondResult.migrationsExecuted)
                .as("V001/V002/V003 already applied - zero pending versioned migrations")
                .isEqualTo(0);

        assertRuntimeGrantsAreExactlyAsExpected();
        assertRuntimeDmlWorksWithMeaningfulRowCounts();
        assertForbiddenOperationsAreDenied();
        assertAuthenticationEnforcesPassword();
        assertPublicConnectAndTemporaryRemainUnrestricted();

        // 4. 콜백이 "Pending Migration 0개"에도 실제로 재실행됨을 직접 증명한다:
        // 권한 하나를 수동으로 회수한 뒤 같은 Flyway 인스턴스를 다시 migrate() 하면
        // 콜백이 그 권한을 다시 부여해야 한다.
        try (Connection root = rootConnection(); Statement st = root.createStatement()) {
            st.execute("REVOKE SELECT ON public.source_connections FROM sdv");
        }
        try (Connection root = rootConnection()) {
            assertThat(hasPrivilege(root, "source_connections", "SELECT")).isFalse();
        }

        MigrateResult thirdResult = withCallback.migrate();
        assertThat(thirdResult.migrationsExecuted).isEqualTo(0);
        try (Connection root = rootConnection()) {
            assertThat(hasPrivilege(root, "source_connections", "SELECT"))
                    .as("afterMigrate 콜백은 Pending Migration이 없어도 재실행되어 권한을 복구해야 한다")
                    .isTrue();
        }
    }

    private void assertRuntimeGrantsAreExactlyAsExpected() throws SQLException {
        try (Connection root = rootConnection()) {
            // 콜백이 발견해 부여해야 하는 public 스키마의 모든 일반 테이블(관리 대장 제외).
            java.util.List<String> tables = new java.util.ArrayList<>();
            try (Statement st = root.createStatement();
                 var rs = st.executeQuery(
                         "SELECT c.relname FROM pg_class c "
                                 + "WHERE c.relnamespace = 'public'::regnamespace "
                                 + "AND c.relkind = 'r' "
                                 + "AND c.relname <> 'flyway_schema_history' "
                                 + "ORDER BY c.relname")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertThat(tables).isNotEmpty();

            for (String table : tables) {
                assertThat(hasPrivilege(root, table, "SELECT")).as(table + " SELECT").isTrue();
                assertThat(hasPrivilege(root, table, "INSERT")).as(table + " INSERT").isTrue();
                assertThat(hasPrivilege(root, table, "UPDATE")).as(table + " UPDATE").isTrue();
                assertThat(hasPrivilege(root, table, "DELETE")).as(table + " DELETE").isTrue();
            }

            // 관리 대장(Ledger)에는 어떤 권한도 없어야 한다 - 개별 권한 각각 확인
            // (콤마로 묶은 has_table_privilege 호출은 OR 의미이므로 증거로 삼지 않는다).
            for (String priv : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                assertThat(hasPrivilege(root, "flyway_schema_history", priv))
                        .as("flyway_schema_history " + priv)
                        .isFalse();
            }

            // Sequence 권한은 전혀 부여되지 않아야 한다(Identity 컬럼은 내부 의존 Sequence 사용).
            try (Statement st = root.createStatement();
                 var rs = st.executeQuery(
                         "SELECT count(*) FROM pg_class c "
                                 + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                                 + "WHERE c.relkind = 'S' AND n.nspname = 'public' "
                                 + "AND c.relacl IS NOT NULL "
                                 + "AND array_to_string(c.relacl, ',') LIKE '%sdv%'")) {
                rs.next();
                assertThat(rs.getInt(1)).as("no sequence grants to sdv").isZero();
            }
        }
    }

    private boolean hasPrivilege(Connection root, String table, String privilege) throws SQLException {
        try (var ps = root.prepareStatement(
                "SELECT has_table_privilege('sdv', 'public.' || ?, ?)")) {
            ps.setString(1, table);
            ps.setString(2, privilege);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private void assertRuntimeDmlWorksWithMeaningfulRowCounts() throws SQLException {
        try (Connection sdv = sdvConnection(SDV_PASSWORD)) {
            long generatedId;
            try (Statement st = sdv.createStatement()) {
                // Identity 컬럼 INSERT - sdv에는 어떤 Sequence 권한도 없다.
                // owner_subject: V004(M04)의 chk_source_connection_owner_subject를 만족시킨다
                // (이 테스트의 목적은 DML 권한 검증이지 M04 비즈니스 로직 검증이 아니다).
                var rs = st.executeQuery(
                        "INSERT INTO source_connections (type, display_name, status, sync_mode, owner_subject) "
                                + "VALUES ('LOCAL_VAULT', 'callback-test', 'ACTIVE', 'FULL', 'owner-callback-test') "
                                + "RETURNING id");
                rs.next();
                generatedId = rs.getLong(1);
                assertThat(generatedId).isPositive();
            }

            try (Statement st = sdv.createStatement()) {
                int updated = st.executeUpdate(
                        "UPDATE source_connections SET status = 'DISABLED' WHERE id = " + generatedId);
                assertThat(updated).isEqualTo(1);
            }

            try (Statement st = sdv.createStatement()) {
                var rs = st.executeQuery(
                        "SELECT status FROM source_connections WHERE id = " + generatedId);
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("DISABLED");
            }

            try (Statement st = sdv.createStatement()) {
                int deleted = st.executeUpdate(
                        "DELETE FROM source_connections WHERE id = " + generatedId);
                assertThat(deleted).isEqualTo(1);
            }
        }
    }

    private void assertForbiddenOperationsAreDenied() throws SQLException {
        try (Connection sdv = sdvConnection(SDV_PASSWORD)) {
            assertDenied(sdv, "CREATE TABLE hax_" + System.nanoTime() + " (id int)");
            assertDenied(sdv, "ALTER ROLE sdv SUPERUSER");
            assertDenied(sdv, "CREATE ROLE hax_role_" + System.nanoTime() + " LOGIN");
            assertDenied(sdv, "SELECT * FROM flyway_schema_history");
            assertDenied(sdv, "INSERT INTO flyway_schema_history (installed_rank, version) VALUES (999, 'x')");
            assertDenied(sdv, "DROP TABLE source_connections");
        }
    }

    private void assertDenied(Connection sdv, String sql) {
        assertThatThrownBy(() -> {
            try (Statement st = sdv.createStatement()) {
                st.execute(sql);
            }
        }).as(sql).isInstanceOf(SQLException.class);
    }

    private void assertAuthenticationEnforcesPassword() {
        assertThatThrownBy(() -> sdvConnection("definitely-wrong-password"))
                .as("wrong password must be rejected over the real TCP/password route")
                .isInstanceOf(SQLException.class);

        assertThatCode(() -> {
            try (Connection ok = sdvConnection(SDV_PASSWORD)) {
                assertThat(ok.isValid(2)).isTrue();
            }
        }).as("correct password must succeed over the real TCP/password route")
                .doesNotThrowAnyException();
    }

    private void assertPublicConnectAndTemporaryRemainUnrestricted() throws SQLException {
        // 문서화된 대로 이 정책은 PUBLIC CONNECT/TEMPORARY를 건드리지 않는다 -
        // sdv는 다른 데이터베이스에도 연결할 수 있고 임시 테이블도 만들 수 있다.
        String postgresDbUrl = postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/postgres$1");
        try (Connection toPostgresDb = DriverManager.getConnection(postgresDbUrl, "sdv", SDV_PASSWORD)) {
            assertThat(toPostgresDb.isValid(2)).isTrue();
        }

        try (Connection sdv = sdvConnection(SDV_PASSWORD); Statement st = sdv.createStatement()) {
            st.execute("CREATE TEMP TABLE t_tmp_" + System.nanoTime() + " (x int)");
        }
    }
}
