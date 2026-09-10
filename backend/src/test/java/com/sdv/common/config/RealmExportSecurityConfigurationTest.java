package com.sdv.common.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-INF-006 {@code infra/keycloak/realm-export.json}의 보안 관련 설정을
 * 정적으로 검증한다. JSON 문법 검사만으로는 Wildcard Redirect/webOrigins,
 * 상호작용 로그인 Flow 활성화 여부 같은 실제 보안 의미를 증명하지 못하므로,
 * 필드 값을 직접 읽어 확인한다 - 기존 의존성({@code tools.jackson.databind},
 * M03 SecurityConfig가 이미 사용 중)만 사용한다.
 *
 * <p><b>M03에는 승인된 Browser Callback/로그인 UI가 없다.</b> 이 realm의
 * {@code sdv-backend} Client는 의도적으로 모든 상호작용 로그인 Flow
 * (Standard/Implicit/Direct Access Grants)를 비활성화한 상태로 남는다 - 별도로
 * 범위가 정해진 작업에서 명시적인 로그인 Client/Callback Flow를 구성하기
 * 전까지는 활성화하지 않는다. 이 제한은 Backend Resource Server가 올바르게
 * 발급된 Bearer Token(서명/발급자/Audience/subject 검증)을 처리하는 능력과는
 * 무관하다 - Resource Server는 이 realm/client의 Flow 설정과 독립적으로
 * 동작한다.</p>
 *
 * <p>실행 중인 Keycloak Instance나 Volume을 전혀 건드리지 않는다 - Repository의
 * {@code infra/keycloak/realm-export.json} 파일 자체만 정적으로 읽는다.</p>
 */
class RealmExportSecurityConfigurationTest {

    private static final File REALM_EXPORT_FILE =
            new File("../infra/keycloak/realm-export.json").getAbsoluteFile();

    @Test
    void realmExportFileExists() {
        assertThat(REALM_EXPORT_FILE).exists();
    }

    @Test
    void realmIsSdvWithNoUsers() throws Exception {
        JsonNode root = readRealm();

        assertThat(root.path("realm").asString()).isEqualTo("sdv");
        assertThat(root.path("users").size()).isZero();
    }

    @Test
    void realmDefinesExactlyCanonicalUserAndAdminRoles() throws Exception {
        JsonNode root = readRealm();
        List<String> realmRoleNames = new ArrayList<>();
        for (JsonNode role : root.path("roles").path("realm")) {
            realmRoleNames.add(role.path("name").asString());
        }

        assertThat(realmRoleNames).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void backendClientDisablesAllInteractiveLoginFlows() throws Exception {
        JsonNode client = backendClientNode();

        assertThat(client.path("standardFlowEnabled").asBoolean(true))
                .as("standardFlowEnabled must be disabled - no approved browser callback exists yet")
                .isFalse();
        assertThat(client.path("implicitFlowEnabled").asBoolean(true))
                .as("implicitFlowEnabled must stay disabled")
                .isFalse();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean(true))
                .as("directAccessGrantsEnabled must be disabled - no approved local login flow exists yet")
                .isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean(true))
                .as("serviceAccountsEnabled must stay disabled")
                .isFalse();
    }

    @Test
    void backendClientHasNoWildcardRedirectUrisOrWebOrigins() throws Exception {
        JsonNode client = backendClientNode();

        assertThat(client.path("redirectUris").size())
                .as("redirectUris must be empty - no approved browser callback exists yet")
                .isZero();
        assertThat(client.path("webOrigins").size())
                .as("webOrigins must be empty")
                .isZero();
    }

    @Test
    void backendClientIsPublicAndCarriesNoSecret() throws Exception {
        JsonNode client = backendClientNode();

        assertThat(client.path("publicClient").asBoolean(false)).isTrue();
        assertThat(client.has("secret")).as("no client secret may be committed").isFalse();
    }

    @Test
    void backendClientKeepsAudienceAndGroupsProtocolMappers() throws Exception {
        JsonNode client = backendClientNode();
        List<String> mapperNames = new ArrayList<>();
        for (JsonNode mapper : client.path("protocolMappers")) {
            mapperNames.add(mapper.path("name").asString());
        }

        assertThat(mapperNames).contains("sdv-backend-audience", "groups");
    }

    private JsonNode backendClientNode() throws Exception {
        JsonNode root = readRealm();
        for (JsonNode client : root.path("clients")) {
            if ("sdv-backend".equals(client.path("clientId").asString())) {
                return client;
            }
        }
        throw new AssertionError("sdv-backend client not found in realm-export.json");
    }

    private JsonNode readRealm() throws Exception {
        return new ObjectMapper().readTree(REALM_EXPORT_FILE);
    }
}
