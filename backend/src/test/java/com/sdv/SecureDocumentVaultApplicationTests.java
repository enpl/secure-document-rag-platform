package com.sdv;

import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Testcontainers PostgreSQL/pgvector 위에서 전체 애플리케이션 컨텍스트가
 * 정상적으로 기동되는지 검증한다(Flyway V001/V002 포함).
 *
 * 로컬에서 수동으로 띄운 PostgreSQL에 의존하지 않는다.
 *
 * M03이 추가한 SecurityConfig가 sdv.keycloak.issuer-uri/audience를 요구하므로
 * (application.yml 기본 프로파일에는 값이 없음), 전체 Context 기동을 위해 최소
 * Placeholder 값을 지정한다 - 이 테스트의 검증 대상(Context 기동 자체)은 바뀌지
 * 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SecureDocumentVaultApplicationTests {

    @Test
    void contextLoads() {
    }

}
