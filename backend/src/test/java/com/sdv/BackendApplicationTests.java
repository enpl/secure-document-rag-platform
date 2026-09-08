package com.sdv;

import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Testcontainers PostgreSQL/pgvector 위에서 전체 애플리케이션 컨텍스트가
 * 정상적으로 기동되는지 검증한다(Flyway V001/V002 포함).
 *
 * 로컬에서 수동으로 띄운 PostgreSQL에 의존하지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
