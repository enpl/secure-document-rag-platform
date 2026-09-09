package com.sdv.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 3: SPRING_DATASOURCE_PASSWORD / SPRING_FLYWAY_PASSWORD가 누락되거나 빈 값일 때
 * 실제 {@code application-local.yml}의 {@code ${SPRING_DATASOURCE_PASSWORD}} /
 * {@code ${SPRING_FLYWAY_PASSWORD}} placeholder(기본값 없음)가 실제로 기동을 막는지
 * 검증한다.
 *
 * <p><b>이 클래스를 작성하며 실제로 확인된, 처음 가정과 달랐던 사실</b> (추측이 아니라
 * 아래 4개 테스트가 직접 관찰한 실제 예외에 근거함 - 단, 아래 서술은 관찰된 "결과"이지
 * Binder 내부 구현을 직접 들여다본 것은 아니다. 즉시 예외가 나지 않고 실제 PostgreSQL
 * Connection 시도까지 진행된다는 것, 그리고 그 시도가 거부된다는 것은 관찰된 사실이다.
 * 그 Connection 시도에 실제로 어떤 값이 전달됐는지(빈 문자열인지, null인지, Binder가
 * Property 자체를 완전히 건너뛴 것인지)는 이 테스트가 직접 확인하지 않았고, SQLState
 * 코드만으로 그 내부 값이 무엇이었는지 증명되지는 않는다 - 아래 서술은 "Property 해석
 * 실패라는 별도의 독립적 실패 지점은 관찰되지 않았다"는 사실까지만 주장한다):
 * <ul>
 *   <li>기본값 없는 {@code ${SPRING_..._PASSWORD}}가 어디에서도 해석되지 않아도, Spring
 *       Boot는 "Could not resolve placeholder" 류의 예외를 즉시 던지지 않는다 - 대신 실제
 *       PostgreSQL Connection 시도까지 진행되고, 그 시도가 거부되는 것이 관찰됐다. 즉
 *       "Property 해석 실패"라는 별도의 독립적 실패 지점은 실제로 관찰되지 않았다 -
 *       Missing과 Empty 둘 다 결국 실제 Connection 계층에서 막힌다(다만 서로 다른
 *       방식으로: 아래 참고).
 *   <li>Password가 없음(Missing, Property 자체가 어디에도 없음) → PostgreSQL
 *       JDBC 드라이버가 서버로 연결을 시도하고, 서버가 거부한다: {@code PSQLException},
 *       SQLState {@code 28P01}("password authentication failed for user ...").
 *   <li>Password가 빈 문자열(Empty, {@code SPRING_..._PASSWORD=}) → 드라이버가 인증
 *       과정 도중 거부한다: {@code PSQLException}, SQLState {@code 08004}("서버가 SCRAM
 *       기반 인증을 요청했지만, 비밀번호가 빈 문자열입니다" 류의, 로케일에 따라 문구가
 *       달라지는 메시지). 이 메시지 자체가 "서버가 SCRAM 인증을 요청했다"고 말하므로,
 *       서버와의 통신은 이미 있었다는 뜻이다 - 드라이버가 그 이후 SCRAM 절차를 빈
 *       비밀번호로는 진행하지 않고 Client 단에서 중단하는 것으로 보이며, "서버에 연결을
 *       시도하기도 전에" 거부한다는 뜻은 아니다. 정확한 Network Sequence까지는 이 테스트가
 *       검증하지 않았으므로 그 이상은 주장하지 않는다. 메시지 텍스트는 JVM 기본
 *       로케일에 종속적이므로(이 머신은 ko_KR), 테스트 Assertion은 로케일 독립적인
 *       SQLState 코드와 예외 클래스만 사용한다.
 *   <li>Primary Datasource(spring.datasource, 역할 sdv)와 Flyway 전용 Connection
 *       (spring.flyway.*, 역할 sdv_user)은 서로 다른 Bean이며, 하나의 Context 안에
 *       둘 다 구성돼 있으면 어느 한쪽 자격증명 문제가 있어도 그 Bean이 먼저 실패해
 *       버려 다른 쪽을 가려버릴 수 있다. 그래서 Datasource 쪽만 순수하게 격리해서
 *       보는 두 테스트는 {@link FlywayAutoConfiguration}을 아예 Source에서 제외한다
 *       (Flyway Bean 자체가 존재하지 않음) - Flyway 쪽만 보는 두 테스트는 반대로
 *       Datasource 쪽에 항상 유효한 값을 명시적으로 채워 넣는다.
 * </ul>
 *
 * 이 재설계는 처음 가정(Missing은 Property 해석 단계에서, Empty는 Connection
 * 단계에서 실패할 것)이 실제 관찰과 달랐기 때문에 이루어졌다 - 재설계 자체가 바로
 * "가정하지 말고 실제 동작을 테스트하라"는 요구사항이 실제로 걸러낸 결과다.
 */
class SpringDatasourceCredentialFailureTest {

    private static PostgreSQLContainer postgres;

    @BeforeAll
    static void startContainer() throws SQLException {
        postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"));
        postgres.start();

        // application-local.yml의 기본 username 값(sdv / sdv_user)과 실제로 일치하는
        // 역할을 미리 만들어 둔다. 두 값 모두 이 테스트에서만 쓰는 임시 값이다.
        try (Connection root = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = root.createStatement()) {
            st.execute("CREATE ROLE sdv_user WITH LOGIN SUPERUSER PASSWORD 'test-only-bootstrap'");
            st.execute("CREATE ROLE sdv WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE "
                    + "NOREPLICATION NOBYPASSRLS PASSWORD 'test-only-runtime'");
        }

        // V001/V002를 sdv_user로 미리 적용해 둔다(콜백은 사용하지 않는다 - Task 1에서
        // 이미 별도로 검증했으며, 이 테스트는 순수하게 자격증명 해석/연결 경로만 본다).
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), "sdv_user", "test-only-bootstrap")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Configuration
    static class MarkerConfig {
    }

    private void assumeNoCompetingOsEnvironmentValue(String key) {
        Assumptions.assumeTrue(System.getenv(key) == null,
                () -> "OS environment already defines " + key
                        + " - this would compete with the property under test; skipping to avoid a "
                        + "misleading result rather than silently masking it");
    }

    private void runAndClose(ConfigurableApplicationContext context) {
        // run()이 예외 없이 반환되면(즉 "성공"하면) 이는 이번 Task의 요구사항 위반이지
        // 예상된 결과가 아니므로, 호출부의 assertThatThrownBy가 실패로 보고하도록
        // Context만 정리한다.
        SpringApplication.exit(context);
        context.close();
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

    /** SQLException 체인에서 첫 SQLState를 찾는다(로케일에 영향받지 않는 코드). */
    private static String sqlState(Throwable throwable) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 50) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean chainContainsType(Throwable throwable, String fullyQualifiedClassName) {
        Throwable current = throwable;
        int guard = 0;
        while (current != null && guard++ < 50) {
            if (current.getClass().getName().equals(fullyQualifiedClassName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ---------------------------------------------------------------
    // SPRING_DATASOURCE_PASSWORD - Flyway는 이 두 테스트의 Source 목록에서
    // 아예 제외한다(Flyway Bean 자체가 존재하지 않음). Primary Datasource(sdv)만
    // 순수하게 격리해서 본다.
    // ---------------------------------------------------------------

    private SpringApplicationBuilder datasourceOnlyBuilder() {
        return new SpringApplicationBuilder(MarkerConfig.class, DataSourceAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("local");
    }

    /**
     * 실제로 관찰된 사실(가정 아님): {@code DataSourceAutoConfiguration}만 단독으로 두면
     * (아무것도 {@code DataSource} Bean의 {@code getConnection()}을 실제로 호출하지
     * 않으면) Spring Boot가 만드는 {@code HikariDataSource}는 Lazy하다 - Pool은 첫
     * 실제 사용 시점에야 초기화된다. 즉 이 좁은 구성에서는 Context Refresh 자체가
     * "성공"해 버린다(자격증명이 잘못됐는데도). 이는 이 Task가 명시적으로 경고한
     * "Lazy Pool 생성이 False Pass를 만들 수 있다"는 바로 그 상황이며, 실제로 관찰됐다.
     *
     * 그래서 이 두 테스트는 (1) Context Refresh 자체는 실패하지 않음을 먼저 확인하고
     * (거짓으로 "기동이 거부됐다"고 보고하지 않기 위해), (2) 이어서 Context에서 실제로
     * {@code DataSource#getConnection()}을 호출해 - 즉 이 Task가 요구한 대로 "실제
     * 사용"을 강제해 - 자격증명이 정말로 거부됨을 증명한다.
     *
     * 실제 SDV 애플리케이션에서는 Flyway가 항상 별도의 sdv_user 자격증명으로 자체
     * Connection을 즉시 시도하므로 그 경로는 이렇게 조용히 넘어가지 않는다(아래
     * {@code missingFlywayPassword}/{@code emptyFlywayPassword} 참고). 하지만 이
     * 순수 Datasource-only 구성 자체는 "Context가 성공적으로 초기화됐다"는 것만으로는
     * sdv 자격증명이 유효하다는 증거가 되지 않음을 보여준다 - 이 한계를 그대로 보고한다.
     */
    @Test
    void missingDatasourcePassword_contextRefreshSucceedsButFirstRealUseIsRejected() {
        assumeNoCompetingOsEnvironmentValue("SPRING_DATASOURCE_PASSWORD");

        ConfigurableApplicationContext context = datasourceOnlyBuilder().run(
                "--spring.datasource.url=" + postgres.getJdbcUrl());
        try {
            assertThat(context.isActive())
                    .as("empirically: context refresh itself does NOT fail here - Hikari's pool is lazy "
                            + "and nothing in this minimal configuration forces an eager connection")
                    .isTrue();

            javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
            assertThatThrownBy(dataSource::getConnection)
                    .as("actually exercising the runtime datasource (role sdv, no password) must be "
                            + "rejected by real PostgreSQL authentication - this is the evidence that a "
                            + "missing SPRING_DATASOURCE_PASSWORD does not silently work, even though "
                            + "context refresh alone did not catch it in this configuration")
                    .satisfies(ex -> {
                        assertThat(chainContainsType(ex, "org.postgresql.util.PSQLException"))
                                .as("must be a real PostgreSQL connection rejection, not a client-side no-op")
                                .isTrue();
                        String chain = describeChain(ex);
                        assertThat(chain).contains("sdv").doesNotContain("sdv_user");
                        assertThat(sqlState(ex))
                                .as("empirically: server-side rejection (SQLState 28P01) - checked via "
                                        + "SQLException.getSQLState(), not text matching (message is locale-dependent)")
                                .isEqualTo("28P01");
                    });
        } finally {
            runAndClose(context);
        }
    }

    @Test
    void emptyDatasourcePassword_contextRefreshSucceedsButFirstRealUseIsRejected() {
        ConfigurableApplicationContext context = datasourceOnlyBuilder().run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--SPRING_DATASOURCE_PASSWORD=");
        try {
            assertThat(context.isActive())
                    .as("empirically: context refresh itself does NOT fail here either, same lazy-pool reason")
                    .isTrue();

            javax.sql.DataSource dataSource = context.getBean(javax.sql.DataSource.class);
            assertThatThrownBy(dataSource::getConnection)
                    .as("actually exercising the runtime datasource (role sdv, empty password) must be "
                            + "rejected by real PostgreSQL/pgjdbc authentication")
                    .satisfies(ex -> {
                        assertThat(chainContainsType(ex, "org.postgresql.util.PSQLException"))
                                .as("must be a real PostgreSQL/pgjdbc connection rejection")
                                .isTrue();
                        // 참고: 이 Empty 케이스의 실제 메시지("서버가 SCRAM 기반 인증을
                        // 요청했지만, 비밀번호가 제공되지 않았습니다")는 사용자명을 담고
                        // 있지 않다(Missing 케이스의 서버측 28P01 거부와 다름 - 위 참고).
                        // 그 메시지 자체가 "서버가 SCRAM 인증을 요청했다"고 말하므로 서버와의
                        // 통신은 있었다는 뜻이며, 드라이버가 인증 절차 도중 빈 비밀번호를
                        // 거부하는 것으로 보인다 - 정확한 시점/Network Sequence까지는
                        // 주장하지 않는다. 그래서 사용자명 텍스트가 아니라 SQLState로
                        // "Postgres/pgjdbc가 실제로 거부했다"는 사실만 확인한다.
                        assertThat(sqlState(ex))
                                .as("empirically: a real PostgreSQL/pgjdbc rejection carries a SQLState "
                                        + "(08004) - observed during authentication, not necessarily before any "
                                        + "network contact with the server")
                                .isEqualTo("08004");
                    });
        } finally {
            runAndClose(context);
        }
    }

    // ---------------------------------------------------------------
    // SPRING_FLYWAY_PASSWORD - Primary Datasource 쪽은 항상 유효한 값을 명시적으로
    // 채워, Datasource 쪽 실패가 Flyway 쪽 실패를 가리지 않게 한다.
    // ---------------------------------------------------------------

    private SpringApplicationBuilder withFlywayBuilder() {
        return new SpringApplicationBuilder(MarkerConfig.class, DataSourceAutoConfiguration.class,
                FlywayAutoConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("local");
    }

    @Test
    void missingFlywayPassword_realConnectionRejectedByServer() {
        assumeNoCompetingOsEnvironmentValue("SPRING_FLYWAY_PASSWORD");

        assertThatThrownBy(() -> runAndClose(withFlywayBuilder().run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--SPRING_DATASOURCE_PASSWORD=test-only-runtime",
                        "--spring.flyway.locations=classpath:db/migration")))
                .as("a missing SPRING_FLYWAY_PASSWORD must not lead to successful startup, "
                        + "even though the primary runtime datasource credential is valid")
                .satisfies(ex -> {
                    assertThat(chainContainsType(ex, "org.postgresql.util.PSQLException"))
                            .as("must be a real PostgreSQL connection rejection for the Flyway connection")
                            .isTrue();
                    String chain = describeChain(ex);
                    assertThat(chain)
                            .as("must reference the Flyway migration identity sdv_user specifically, "
                                    + "not be masked by a runtime datasource (sdv) failure")
                            .contains("sdv_user")
                            .contains("flyway");
                    assertThat(chain)
                            .as("empirically: server-side rejection (SQLState 28P01), same as the missing "
                                    + "datasource-password case")
                            .contains("28P01");
                });
    }

    @Test
    void emptyFlywayPassword_rejectedBeforeOrByServer() {
        assertThatThrownBy(() -> runAndClose(withFlywayBuilder().run(
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--SPRING_DATASOURCE_PASSWORD=test-only-runtime",
                        "--spring.flyway.locations=classpath:db/migration",
                        "--SPRING_FLYWAY_PASSWORD=")))
                .as("an empty SPRING_FLYWAY_PASSWORD must not lead to successful startup")
                .satisfies(ex -> {
                    assertThat(chainContainsType(ex, "org.postgresql.util.PSQLException"))
                            .as("must be a real PostgreSQL/pgjdbc connection rejection for the Flyway connection")
                            .isTrue();
                    String chain = describeChain(ex);
                    // 참고: 이 Empty 케이스의 실제 메시지("서버가 SCRAM 기반 인증을
                    // 요청했지만, 비밀번호가 빈 문자열입니다")는 사용자명을 담고 있지
                    // 않다(Missing 케이스의 서버측 28P01 거부와 다름). 그 메시지 자체가
                    // "서버가 SCRAM 인증을 요청했다"고 말하므로 서버와의 통신은 있었다는
                    // 뜻이며, 드라이버가 인증 절차 도중 거부하는 것으로 보인다 - "서버에
                    // 연결을 시도하기도 전"이라고까지는 이 테스트가 주장하지 않는다.
                    // 그래서 "sdv_user" 문자열이 아니라 Flyway Bean 이름과 SQL State로
                    // Flyway 경로임을 확인한다.
                    assertThat(chain)
                            .as("must be attributable to the flywayInitializer bean, not the runtime datasource")
                            .contains("flywayInitializer")
                            .contains("flyway");
                    assertThat(chain)
                            .as("empirically: rejection observed during authentication (SQLState 08004), "
                                    + "distinct from the missing-password server rejection (28P01)")
                            .contains("08004");
                });
    }
}
