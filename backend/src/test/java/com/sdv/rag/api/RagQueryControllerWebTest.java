package com.sdv.rag.api;

import com.sdv.rag.api.dto.RagFileSearchResponse;
import com.sdv.rag.application.FileMetadataDiscoveryService;
import com.sdv.rag.application.RagAnswerService;
import com.sdv.rag.api.dto.RagAnswerResponse;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M10 신규(RAG-011 File Metadata Discovery) - {@code GET /api/rag/files}의 HTTP
 * 경계(인증/인가/입력 검증) 검증. {@link FileMetadataDiscoveryService}는 {@code
 * @MockBean}으로 대체한다 - 유스케이스 자체(Prefilter/Live 재확인/Live 필터
 * 재적용/노출 직전 재인가/Budget)는 {@code FileMetadataDiscoveryServiceTest}(실제
 * Testcontainers PostgreSQL + 실제 {@code EffectivePermissionService})가 이미
 * 검증했다 - 이 Test는 그 로직을 반복하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class RagQueryControllerWebTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private FileMetadataDiscoveryService fileMetadataDiscoveryService;
    @MockitoBean
    private RagAnswerService ragAnswerService;

    @Test
    void askAllowsNormalMultilineWhitespaceAndDisablesCaching() throws Exception {
        when(ragAnswerService.ask(any(), any(), any())).thenReturn(RagAnswerResponse.failed("NO_EVIDENCE", "NO_EVIDENCE"));
        mockMvc.perform(post("/api/rag/ask").with(userSubject("user-a")).contentType(APPLICATION_JSON)
                        .content("{\"question\":\"첫 줄\\n둘째 줄\\t조건\",\"selectedDocumentIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.reasonCode").value("NO_EVIDENCE"));
    }

    @Test
    void askRejectsDisallowedControlCharacter() throws Exception {
        mockMvc.perform(post("/api/rag/ask").with(userSubject("user-a")).contentType(APPLICATION_JSON)
                        .content("{\"question\":\"bad\\u0001input\",\"selectedDocumentIds\":[1]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/rag/files")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTokenWithNoRecognizedRoleReturns403() throws Exception {
        mockMvc.perform(get("/api/rag/files").with(jwt().jwt(builder -> builder.subject("no-role-user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAuthenticatedUserGetsTheDelegatedResult() throws Exception {
        when(fileMetadataDiscoveryService.search(any(), any()))
                .thenReturn(new RagFileSearchResponse(List.of(), false, false));

        mockMvc.perform(get("/api/rag/files").with(userSubject("user-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.partial").value(false));

        ArgumentCaptor<com.sdv.common.model.UserContext> userCaptor =
                ArgumentCaptor.forClass(com.sdv.common.model.UserContext.class);
        verify(fileMetadataDiscoveryService).search(userCaptor.capture(), any());
        assertThat(userCaptor.getValue().subject()).isEqualTo("user-a");
    }

    @Test
    void anUnknownHasMoreIsSerializedAsJsonNullNotAsFalse() throws Exception {
        // M10 후속 교정 - hasMore는 Tri-state다. Unknown(예산/Lookahead 상한 도달)을 false로
        // 뭉개면 "더 없다"는 확정 주장이 돼버린다 - HTTP 경계까지 null로 그대로 전달돼야 한다.
        when(fileMetadataDiscoveryService.search(any(), any()))
                .thenReturn(new RagFileSearchResponse(List.of(), null, true));

        mockMvc.perform(get("/api/rag/files").with(userSubject("user-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                .andExpect(jsonPath("$.partial").value(true));
    }

    @Test
    void anAdminRoleIsAlsoAllowed() throws Exception {
        when(fileMetadataDiscoveryService.search(any(), any()))
                .thenReturn(new RagFileSearchResponse(List.of(), false, false));

        mockMvc.perform(get("/api/rag/files").with(adminSubject("admin-a"))).andExpect(status().isOk());
    }

    @Test
    void anOversizedNameQueryIsRejected() throws Exception {
        String oversized = "a".repeat(201);

        mockMvc.perform(get("/api/rag/files").param("q", oversized).with(userSubject("user-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void aNonNumericSourceIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("sourceId", "not-a-number").with(userSubject("user-a")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void aNonPositiveSourceIdIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("sourceId", "0").with(userSubject("user-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnrecognizedSortKeyIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("sort", "PRICE_ASC").with(userSubject("user-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aMalformedModifiedFromIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("modifiedFrom", "not-a-date").with(userSubject("user-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOversizedPageSizeIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("size", "10000").with(userSubject("user-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNegativePageIsRejected() throws Exception {
        mockMvc.perform(get("/api/rag/files").param("page", "-1").with(userSubject("user-a")))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor userSubject(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminSubject(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
