package com.sdv.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M16A follow-up 안전성 교정(2026-09-14) - {@code infra/keycloak/realm-export.json}
 * / {@code infra/testbed/realm-export.testbed.json}의 {@code sdv-frontend} Client
 * 텍스트 내용을 직접 검사하는 회귀 가드({@link DatasourceProfileConfigurationTest}와
 * 같은 방식 - Spring Context/실제 Keycloak 없이 순수 파일 내용만 검사한다).
 *
 * <h2>이 Test가 증명하는 것과 증명하지 못하는 것</h2>
 * <p>실제 관찰된 문제(발급된 Access Token에 issuer/audience/ADMIN Role은 맞는데
 * {@code sub}가 없었다 - {@code SecurityConfig}는 이를 올바르게 거부했다)의 원인은
 * {@code sdv-frontend}가 Keycloak의 기본 제공 {@code basic} Default Client
 * Scope(공식 문서상 Subject/{@code sub} Mapper를 포함한다)를 요청하지 않았던 것이다.
 * 이 Test는 두 Realm Import 파일이 이제 그 Scope를 실제로 선언하는지만 확인한다 -
 * 이 JSON을 Keycloak에 실제로 Import해서 나온 Token에 {@code sub}가 실제로 담기는지
 * 증명하지 않는다(그건 실제 Keycloak 기동+로그인이 필요하다 - 이 Test의 범위 밖).
 * 또한 이미 실행 중인 Keycloak Realm(예: 기존 {@code sdv} Realm)은 이 File을 고쳐도
 * 자동으로 갱신되지 않는다(Keycloak의 {@code --import-realm}은 이미 존재하는 Realm을
 * 덮어쓰지 않는다) - 그 사실도 이 Test로는 증명/반증되지 않는다.
 */
class KeycloakRealmExportClientScopeTest {

    private static final Path REPO_ROOT = Paths.get("..");

    @Test
    void canonicalRealmFrontendClientRequestsBasicScopeAndBackendClientIsUnchanged() throws IOException {
        assertFrontendHasBasicScopeAndBackendUnchanged(
                REPO_ROOT.resolve(Paths.get("infra", "keycloak", "realm-export.json")));
    }

    @Test
    void testbedRealmFrontendClientRequestsBasicScopeAndBackendClientIsUnchanged() throws IOException {
        assertFrontendHasBasicScopeAndBackendUnchanged(
                REPO_ROOT.resolve(Paths.get("infra", "testbed", "realm-export.testbed.json")));
    }

    @Test
    void neitherRealmFileReintroducesTheUnrecognizedCommentFieldThatCrashedKeycloakImport() throws IOException {
        // MVP-24(docs/plan/SDV_MVP_DEFERRED.md) 회귀 가드 - "_comment" 필드가 다시
        // 들어오면 Keycloak의 Strict JSON Parser가 --import-realm을 매번 실패시켜
        // sdv-keycloak을 무한 재시작 Loop에 빠뜨린다(실제로 관찰/재현/수정됐다).
        for (Path path : new Path[] {
                REPO_ROOT.resolve(Paths.get("infra", "keycloak", "realm-export.json")),
                REPO_ROOT.resolve(Paths.get("infra", "testbed", "realm-export.testbed.json"))
        }) {
            assertThat(read(path)).as(path + " must not contain _comment").doesNotContain("_comment");
        }
    }

    private static void assertFrontendHasBasicScopeAndBackendUnchanged(Path path) throws IOException {
        String json = read(path);

        assertThat(json).as(path + " must still declare sdv-frontend").contains("\"clientId\": \"sdv-frontend\"");
        assertThat(json).as(path + " must still declare sdv-backend").contains("\"clientId\": \"sdv-backend\"");

        // sdv-frontend: basic Scope가 추가됐다 - 다른 Scope 순서/구성은 그대로다.
        assertThat(json).as(path + " sdv-frontend must request the basic default client scope")
                .contains("\"defaultClientScopes\": [\"basic\", \"web-origins\", \"profile\", \"roles\"]");

        // sdv-backend: 원래 형태(basic 없음) 그대로 남아있다 - 이 교정이 sdv-backend에는
        // 영향을 주지 않는다는 것을 문자 그대로 증명한다.
        assertThat(json).as(path + " sdv-backend's defaultClientScopes must remain untouched")
                .contains("\"defaultClientScopes\": [\"web-origins\", \"profile\", \"roles\"]");

        // 기존 필드가 보존됐는지도 함께 확인한다(Redirect URI/PKCE/Audience Mapper).
        assertThat(json).contains("pkce.code.challenge.method");
        assertThat(json).contains("\"included.client.audience\": \"sdv-backend\"");
    }

    private static String read(Path path) throws IOException {
        assertThat(Files.isRegularFile(path)).as(path + " exists").isTrue();
        return Files.readString(path);
    }
}
