package com.sdv.config;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 1: 실제 Spring Boot Flyway Auto-Configuration을 통해 Application Context가
 * 초기화되는 동안, afterMigrate 콜백(실제 파일, 수정 없음)이 sdv 역할 부재로 실패하면
 * Context Refresh 자체가 실패함을 증명한다.
 *
 * 이전 세션의 {@link RuntimeRoleGrantCallbackMissingRoleTest}는 org.flywaydb.core.Flyway
 * Java API를 직접 호출해 콜백 실패(FlywayException)를 증명했다 - Flyway 라이브러리
 * 자체의 동작 증거였다. 이 클래스는 그 한 단계 위, 실제 Spring Boot의
 * {@code DataSourceAutoConfiguration}/{@code FlywayAutoConfiguration}이 Application
 * Context Refresh 도중 호출하는 것과 동일한 경로(FlywayMigrationInitializer)를 그대로
 * 태워서, 같은 실패가 "Spring이 기동을 거부한다"는 결과로 실제 이어짐을 증명한다.
 *
 * 격리 방법(요구사항 그대로):
 *   - Web 서버를 전혀 띄우지 않는 순수 ApplicationContextRunner를 사용한다
 *     (Web Port 문제를 원천적으로 배제).
 *   - 이 부정 테스트에 한해, spring.datasource.*에는 Testcontainers 컨테이너의
 *     유효한 Bootstrap superuser 자격증명(옳은 URL/username/password)을 그대로 사용한다
 *     - 즉 "런타임 인증 자체가 먼저 실패해서 콜백 실패를 가려버리는" 상황을 배제한다.
 *     Flyway는 별도 spring.flyway.user/password를 지정하지 않아 동일한(유효한)
 *     Primary DataSource를 재사용하므로, 이 테스트에서 유일하게 실패할 수 있는 지점은
 *     콜백 SQL 자체뿐이다.
 *   - 다른 프로파일 YAML(application-local.yml 등)을 로드하지 않고 필요한 속성만 직접
 *     주입해, 무관한 속성/설정이 결과를 오염시키지 않게 한다.
 */
class SpringFlywayCallbackStartupFailureTest {

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

    private ApplicationContextRunner contextRunnerWithValidBootstrapDatasource() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withPropertyValues(
                        "spring.main.web-application-type=" + WebApplicationType.NONE,
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        // 실제 운영 콜백 파일을 수정 없이 그대로 사용한다.
                        "spring.flyway.locations=classpath:db/migration,classpath:db/callback");
    }

    @Test
    void contextRefreshFailsWhenSdvRoleIsMissing_thenSucceedsAfterRoleIsPrepared() throws SQLException {
        contextRunnerWithValidBootstrapDatasource().run(context -> {
            assertThat(context).as("context must fail to refresh").hasFailed();

            Throwable startupFailure = context.getStartupFailure();
            assertThat(startupFailure).isNotNull();

            // Root Cause가 정확히 콜백의 SQL 실패(FlywayException)인지 확인한다 -
            // 무관한 원인(연결 실패, Property 미해석, Web Port 문제)이 아님을 각각 배제한다.
            FlywayException flywayCause = findCause(startupFailure, FlywayException.class);
            assertThat(flywayCause)
                    .as("root cause of context refresh failure must be the Flyway afterMigrate callback, "
                            + "not an unrelated bean/connection/property/web-port failure")
                    .isNotNull();

            String fullChain = describeChain(startupFailure);
            assertThat(fullChain)
                    .as("the failure must be attributable to role sdv specifically")
                    .containsIgnoringCase("sdv");
            // 각각 다른 실패 원인들을 명시적으로 배제한다(요구사항: 이것들이 원인이 아님을 확인).
            assertThat(fullChain)
                    .as("must not be a bootstrap datasource authentication failure")
                    .doesNotContainIgnoringCase("password authentication failed");
            assertThat(fullChain)
                    .as("must not be an unresolved property placeholder failure")
                    .doesNotContain("PlaceholderResolutionException")
                    .doesNotContain("could not be resolved");
            assertThat(fullChain)
                    .as("must not be a web server port failure (this runner never starts a web server)")
                    .doesNotContain("Port")
                    .doesNotContain("BindException");
        });

        // V001/V002/V003/V004 자체는 콜백 이전에 각각 독립적으로 Commit되므로, 콜백이
        // 실패해도 이미 적용되어 있어야 한다("스키마는 있지만 sdv 권한은 없는" 상태가 재현된다).
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
            assertThat(versions).containsExactly("001", "002", "003", "004");
        }

        // Control: sdv 역할을 준비(수동 Reconciliation)한 뒤, 완전히 동일한 구성으로
        // 새 Context를 초기화하면 이번에는 정상적으로 기동되어야 한다.
        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = root.createStatement()) {
            st.execute("CREATE ROLE sdv WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-control'");
        }

        contextRunnerWithValidBootstrapDatasource().run((AssertableApplicationContext context) -> {
            assertThat(context)
                    .as("with sdv prepared, the equivalent context must initialize successfully")
                    .hasNotFailed();
        });

        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            try (var ps = root.prepareStatement(
                    "SELECT has_table_privilege('sdv', 'public.source_connections', 'SELECT')")) {
                var rs = ps.executeQuery();
                rs.next();
                assertThat(rs.getBoolean(1))
                        .as("the control run's callback must have actually granted privileges")
                        .isTrue();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return (T) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String describeChain(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 50) {
            sb.append(current.getClass().getName())
                    .append(": ")
                    .append(current.getMessage())
                    .append('\n');
            current = current.getCause();
        }
        return sb.toString();
    }
}
