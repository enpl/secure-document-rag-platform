package com.sdv.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application-local.yml / application-compose.yml / application.yml의 텍스트 내용을
 * 직접 검사하는 회귀 가드(Regression Guard).
 *
 * 목적:
 *   - 하드코딩된 비밀번호 기본값(예: sdv_password)이 다시 들어오지 않는지 확인한다.
 *   - Flyway 콜백 위치(classpath:db/callback)가 local/compose 프로파일에만 존재하고
 *     기본(no-profile) application.yml에는 존재하지 않는지 확인한다 - 테스트가
 *     `local`/`compose` 프로파일을 활성화하지 않는 한 sdv 역할을 필요로 하지 않는다는
 *     전제를 보장한다.
 *
 * Spring Context를 띄우지 않는 순수 파일 내용 검사이므로 빠르고, 별도 DB/Docker
 * 리소스가 필요 없다.
 */
class DatasourceProfileConfigurationTest {

    private static final Path RESOURCES = Paths.get("src", "main", "resources");

    @Test
    void localAndComposeProfilesDeclareCallbackLocationAndNoDefaultPassword() throws IOException {
        String local = read("application-local.yml");
        String compose = read("application-compose.yml");

        for (String yaml : new String[] {local, compose}) {
            assertThat(yaml).contains("locations: classpath:db/migration,classpath:db/callback");
            assertThat(yaml).contains("password: ${SPRING_DATASOURCE_PASSWORD}");
            assertThat(yaml).contains("password: ${SPRING_FLYWAY_PASSWORD}");
            assertThat(yaml).contains("user: ${SPRING_FLYWAY_USER:sdv_user}");
            // 하드코딩된 평문 기본 비밀번호가 다시 들어오면 실패한다.
            assertThat(yaml).doesNotContain("sdv_password");
        }

        assertThat(local).contains("url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/sdv}");
        assertThat(compose).contains("url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://postgres:5432/sdv}");
    }

    @Test
    void baseApplicationYamlDoesNotDeclareCallbackLocation() throws IOException {
        String base = read("application.yml");

        // 기본(no-profile) 프로파일에서는 db/callback이 로드되면 안 된다 - 그래야
        // (local/compose 프로파일을 활성화하지 않는) 기존 테스트들이 sdv 역할 없이
        // 계속 통과한다.
        assertThat(base).doesNotContain("db/callback");
        assertThat(base).doesNotContain("sdv_password");
    }

    private String read(String fileName) throws IOException {
        Path path = RESOURCES.resolve(fileName);
        assertThat(Files.isRegularFile(path)).as(path + " exists").isTrue();
        return Files.readString(path);
    }
}
