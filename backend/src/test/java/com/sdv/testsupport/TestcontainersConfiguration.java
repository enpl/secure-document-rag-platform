package com.sdv.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 백엔드 테스트 전용 PostgreSQL/pgvector Testcontainers 공용 설정.
 *
 * 개발자가 로컬에서 docker compose로 sdv-postgres를 직접 띄우지 않아도,
 * ./gradlew test 실행만으로 실제 PostgreSQL + pgvector 위에서
 * Flyway(V001, V002)까지 정상 적용된 상태로 테스트를 검증할 수 있도록 한다.
 *
 * 로컬 SDV 개발환경(infra/docker-compose.dev.yml)과 동일한 이미지를 사용해
 * pgvector 확장/HNSW 색인 생성까지 실제 환경과 동일하게 검증한다.
 *
 * {@code @ServiceConnection}이 DataSource 연결 정보를 자동으로 구성하므로,
 * application-local.yml / application-compose.yml의 datasource 값을
 * 테스트에서 별도로 지정하거나 복제하지 않는다.
 *
 * 여러 테스트 클래스에서 컨테이너 선언을 중복하지 않도록,
 * 이 설정 하나를 각 테스트에서 {@code @Import}하여 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.6-pg18"));
    }
}
