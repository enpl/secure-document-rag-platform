package com.sdv.testbed;

import com.sdv.common.model.UserContext;
import com.sdv.source.domain.DocumentIndexStatus;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceOAuthTokenEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M16A follow-up local testbed diagnostic - {@code testbed} Profile +
 * {@code sdv.testbed.diagnostics.enabled=true}가 모두 켜진 상태에서의 HTTP 경계
 * 검증. {@link GoogleDriveConnector}는 {@link MockitoBean}으로 대체해 실제
 * Google을 호출하지 않고도 각 분기를 결정론적으로 재현한다 - "거부 시 Connector
 * 호출 0회"는 {@link org.mockito.Mockito#verifyNoInteractions}로 직접 증명한다.
 */
@SpringBootTest
@ActiveProfiles("testbed")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.testbed.diagnostics.enabled=true",
        "sdv.testbed.diagnostics.allowed-file-id=allowed-file-1",
        // testbed Profile은 SPRING_DATASOURCE_PASSWORD/SPRING_FLYWAY_PASSWORD를 기본값 없이
        // 요구한다 - @ServiceConnection이 실제 DataSource는 대체하지만, 이 값 자체는 여전히
        // Placeholder 해석 대상이므로 Test 환경에서 실제로 쓰이지 않는 값으로 명시한다.
        "spring.datasource.password=unused-in-test",
        "spring.flyway.password=unused-in-test",
        // db/callback(afterMigrate__grant_runtime_privileges.sql)은 실제 testbed Compose의
        // initdb 스크립트가 미리 만들어 둔 `sdv` Role의 존재를 전제한다 - 이 Test의 순정
        // Testcontainers Postgres에는 그 Role이 없다. 기존 테스트 전체가 이미 따르는 관례와
        // 동일하게(그 SQL 파일 자체의 주석 참고) db/migration만 적용한다 - 이 진단 기능
        // 검증과 Runtime 권한 부여 자체는 무관하다.
        "spring.flyway.locations=classpath:db/migration"
})
class TestbedReadDiagnosticControllerWebTest {

    private static final String PATH = "/api/admin/testbed/diagnostics/read-check";
    private static final String ALLOWED_FILE_ID = "allowed-file-1";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    // CALLS_REAL_METHODS - SourceConnectorRegistry의 생성자가 Context 기동 시점에
    // (아직 어떤 @Test/@BeforeEach도 이 Mock을 stub하기 전에) supportedType()을 호출한다 -
    // 기본 Mockito 동작(Unstub된 메서드는 null 반환)은 그 값을 Registry의 Map Key로 못 쓰게
    // 해 기동 자체를 실패시킨다. supportedType()의 실제 구현은 고정된 GOOGLE_DRIVE
    // 반환뿐이라 실제 메서드를 그대로 호출해도 안전하다 - getMetadata/fetchContent는 각
    // Test가 명시적으로 stub하므로(Mockito는 명시적 Stub을 항상 우선한다) 실제
    // Google Client/DB 호출로 이어지지 않는다.
    @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
    private GoogleDriveConnector googleDriveConnector;

    @Test
    void statusEndpointReportsEnabledWhenBothGatesAreOpen() throws Exception {
        mockMvc.perform(get("/api/admin/testbed/diagnostics/status")
                        .with(subject("admin-status-check", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void anonymousRequestsAreRejectedBeforeReachingTheDiagnostic() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, ALLOWED_FILE_ID)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void userRoleAloneIsForbidden() throws Exception {
        mockMvc.perform(post(PATH).with(subject("user-1", "USER")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L, ALLOWED_FILE_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void nonAllowlistedFileIdIsDeniedWithoutAnyConnectorCall() throws Exception {
        Long sourceId = createActiveGoogleSource("admin-file-deny", true).getId();

        mockMvc.perform(post(PATH).with(subject("admin-file-deny", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, "some-other-file-id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("FILE_NOT_ALLOWLISTED"))
                .andExpect(jsonPath("$.bytesRead").value(0));

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void anotherAdminsSourceIsDeniedWithoutAnyConnectorCall() throws Exception {
        Long ownedByA = createActiveGoogleSource("admin-a-cross", true).getId();

        mockMvc.perform(post(PATH).with(subject("admin-b-cross", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(ownedByA, ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("SOURCE_NOT_OWNED_OR_NOT_FOUND"));

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void aDisabledSourceIsDeniedWithoutAnyConnectorCall() throws Exception {
        SourceConnectionEntity disabled = createActiveGoogleSource("admin-disabled-source", true);
        disabled.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(disabled);

        mockMvc.perform(post(PATH).with(subject("admin-disabled-source", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(disabled.getId(), ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("SOURCE_NOT_ACTIVE"));

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void missingCredentialIsDeniedWithoutAnyConnectorCall() throws Exception {
        Long sourceId = createActiveGoogleSource("admin-no-credential", false).getId();

        mockMvc.perform(post(PATH).with(subject("admin-no-credential", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("MISSING_CREDENTIAL"));

        verifyNoInteractions(googleDriveConnector);
    }

    @Test
    void aSafeConnectorFailureIsTranslatedWithoutARawProviderMessage() throws Exception {
        Long sourceId = createActiveGoogleSource("admin-connector-fail", true).getId();
        // doThrow(...).when(mock)... - when(mock.method()) 형태는 CALLS_REAL_METHODS 기본
        // Answer 때문에 Stub을 등록하기 전에 실제(필드 없는 Mock에서는 NPE 나는) 구현을 한 번
        // 먼저 호출한다 - doThrow/doReturn 형태는 그 호출 없이 즉시 Stub만 등록한다.
        doThrow(new com.sdv.source.application.port.SourceSyncException(
                com.sdv.source.application.port.SourceSyncException.Reason.ACCESS_UNKNOWN,
                "internal detail that must never reach the client"))
                .when(googleDriveConnector).getMetadata(eq(sourceId), eq(ALLOWED_FILE_ID));

        mockMvc.perform(post(PATH).with(subject("admin-connector-fail", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("ACCESS_UNKNOWN"))
                .andExpect(jsonPath("$.reason", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal detail"))));
    }

    @Test
    void successIsBasedOnAnActualVerifiedContentResultNotMetadataAlone() throws Exception {
        Long sourceId = createActiveGoogleSource("admin-success", true).getId();
        SourceDocument metadata = new SourceDocument(null, sourceId, ALLOWED_FILE_ID, "test.txt", "text/plain", "v1",
                Instant.now(), SourceDocumentState.ACTIVE, DocumentIndexStatus.PENDING, null);
        doReturn(metadata).when(googleDriveConnector).getMetadata(eq(sourceId), eq(ALLOWED_FILE_ID));
        byte[] fakeContent = "hello testbed".getBytes();
        doReturn(SourceContentResult.verified(fakeContent, "text/plain", "v1", false)).when(googleDriveConnector)
                .fetchContent(any(UserContext.class), eq(sourceId), eq(ALLOWED_FILE_ID), eq("v1"));

        mockMvc.perform(post(PATH).with(subject("admin-success", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.outcome").value("VERIFIED"))
                .andExpect(jsonPath("$.bytesRead").value(fakeContent.length))
                .andExpect(jsonPath("$.versionVerified").value(true));
    }

    @Test
    void aMetadataOnlyOutcomeWithoutVerifiedContentIsNotReportedAsSuccess() throws Exception {
        Long sourceId = createActiveGoogleSource("admin-not-verified", true).getId();
        SourceDocument metadata = new SourceDocument(null, sourceId, ALLOWED_FILE_ID, "test.txt", "text/plain", "v1",
                Instant.now(), SourceDocumentState.ACTIVE, DocumentIndexStatus.PENDING, null);
        doReturn(metadata).when(googleDriveConnector).getMetadata(eq(sourceId), eq(ALLOWED_FILE_ID));
        doReturn(SourceContentResult.failed(SourceContentOutcome.VERSION_MISMATCH, "changed mid-flight"))
                .when(googleDriveConnector)
                .fetchContent(any(UserContext.class), eq(sourceId), eq(ALLOWED_FILE_ID), eq("v1"));

        mockMvc.perform(post(PATH).with(subject("admin-not-verified", "ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(sourceId, ALLOWED_FILE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.outcome").value("VERSION_MISMATCH"));
    }

    private SourceConnectionEntity createActiveGoogleSource(String ownerSubject, boolean withCredential) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Testbed Source", "ACTIVE", "FULL",
                ownerSubject);
        SourceConnectionEntity saved = sourceConnectionJpaRepository.saveAndFlush(entity);
        if (withCredential) {
            // 실제 암호화는 필요 없다 - 이 Test는 존재 여부(existsBySourceId)만 확인하고,
            // Connector 자체는 Mock으로 대체되어 이 값을 복호화하지 않는다.
            sourceOAuthTokenJpaRepository.saveAndFlush(new SourceOAuthTokenEntity(UUID.randomUUID(), saved.getId(),
                    ownerSubject, 1, "unused-key-id", new byte[]{1, 2, 3}, new byte[]{4, 5, 6}, Instant.now()));
        }
        return saved;
    }

    private static String body(Long sourceId, String fileId) {
        return "{\"sourceId\":" + sourceId + ",\"fileId\":\"" + fileId + "\"}";
    }

    private static RequestPostProcessor subject(String subject, String role) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }
}
