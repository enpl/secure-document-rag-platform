package com.sdv.source.api;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-BE-025/027 종단 검증: 실제 Testcontainers PostgreSQL 위에서 전체
 * Application Context를 부트하고, {@code /api/admin/sources}에 대한 생성/조회/
 * Disconnect를 실제 HTTP 요청으로 검증한다 - 계정 격리, Cross-Account 비노출,
 * 소프트 Lifecycle 전이, 문서 Cascade, Entity 비노출을 포함한다.
 *
 * <p>인가 자체(ADMIN Role 요구)는 M03의 {@code SecurityAuthorizationWebTest}가
 * 이미 증명한다 - 여기서는 모든 요청을 ADMIN으로 인증하고, M04의 소유자
 * 범위(Owner-Scoped) 로직에 집중한다.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceAdminControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Test
    void sourceIsCreatedForTheAuthenticatedSubject() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/sources")
                        .with(adminSubject("owner-create-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GOOGLE_DRIVE\",\"name\":\"My Drive\",\"syncMode\":\"FULL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("GOOGLE_DRIVE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();

        Long id = extractId(result);
        SourceConnectionEntity saved = sourceConnectionJpaRepository.findById(id).orElseThrow();
        assertThat(saved.getOwnerSubject()).isEqualTo("owner-create-1");
    }

    @Test
    void requestCannotChooseOrOverrideTheOwner() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/sources")
                        .with(adminSubject("owner-create-2"))
                        .contentType(MediaType.APPLICATION_JSON)
                        // "owner"/"ownerSubject"는 CreateSourceRequest에 존재하지 않는 필드다 -
                        // 위조 시도가 무시되고 인증된 Subject만 반영되어야 한다.
                        .content("{\"type\":\"GOOGLE_DRIVE\",\"name\":\"Spoofed\",\"syncMode\":\"FULL\","
                                + "\"owner\":\"attacker\",\"ownerSubject\":\"attacker\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        Long id = extractId(result);
        SourceConnectionEntity saved = sourceConnectionJpaRepository.findById(id).orElseThrow();
        assertThat(saved.getOwnerSubject()).isEqualTo("owner-create-2");
    }

    @Test
    void listingReturnsOnlyCurrentSubjectsSources() throws Exception {
        createSource("owner-list-a", "Source A1");
        createSource("owner-list-a", "Source A2");
        createSource("owner-list-b", "Source B1");

        mockMvc.perform(get("/api/admin/sources").with(adminSubject("owner-list-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/admin/sources").with(adminSubject("owner-list-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anotherSubjectCannotViewOrDisconnectASource() throws Exception {
        Long id = createSource("owner-cross-1", "Owned By owner-cross-1");

        mockMvc.perform(delete("/api/admin/sources/" + id).with(adminSubject("owner-cross-2")))
                .andExpect(status().isNotFound());

        SourceConnectionEntity stillActive = sourceConnectionJpaRepository.findById(id).orElseThrow();
        assertThat(stillActive.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void crossAccountLookupDoesNotRevealWhetherTheIdExists() throws Exception {
        Long realIdOwnedByOther = createSource("owner-exist-1", "Real Source");
        long definitelyNonExistentId = realIdOwnedByOther + 999_999L;

        MvcResult existingButNotOwned = mockMvc.perform(
                        delete("/api/admin/sources/" + realIdOwnedByOther).with(adminSubject("owner-exist-2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn();

        MvcResult trulyNonExistent = mockMvc.perform(
                        delete("/api/admin/sources/" + definitelyNonExistentId).with(adminSubject("owner-exist-2")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn();

        // 두 응답의 Body 형태(코드/메시지)가 동일해야 한다 - "존재하지만 다른 계정 소유"와
        // "존재하지 않음"을 구분할 수 있는 단서가 없어야 한다.
        assertThat(bodyWithoutTraceId(existingButNotOwned)).isEqualTo(bodyWithoutTraceId(trulyNonExistent));
    }

    @Test
    void disconnectChangesLifecycleStateWithoutDeletingTheRow() throws Exception {
        Long id = createSource("owner-disconnect-1", "To Disconnect");

        mockMvc.perform(delete("/api/admin/sources/" + id).with(adminSubject("owner-disconnect-1")))
                .andExpect(status().isNoContent());

        SourceConnectionEntity afterDisconnect = sourceConnectionJpaRepository.findById(id).orElseThrow();
        assertThat(afterDisconnect.getStatus()).isEqualTo("DISABLED");
        assertThat(sourceConnectionJpaRepository.findById(id)).as("row must still exist").isPresent();
    }

    @Test
    void disconnectMarksAssociatedNonDeletedDocumentsAsDeleted() throws Exception {
        Long id = createSource("owner-disconnect-docs", "With Documents");
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(id, "doc-a", "Doc A", "text/plain", null, null, "ACTIVE", "PENDING", null));
        sourceDocumentJpaRepository.saveAndFlush(
                new SourceDocumentEntity(id, "doc-b", "Doc B", "text/plain", null, null, "ACTIVE", "PENDING", null));

        mockMvc.perform(delete("/api/admin/sources/" + id).with(adminSubject("owner-disconnect-docs")))
                .andExpect(status().isNoContent());

        assertThat(sourceDocumentJpaRepository.findBySourceIdAndStateNot(id, "DELETED")).isEmpty();
    }

    @Test
    void controllerResponseNeverExposesJpaEntityInternalFields() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/sources")
                        .with(adminSubject("owner-entity-leak"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LOCAL_VAULT\",\"name\":\"No Leak\",\"syncMode\":\"FULL\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("ownerSubject");
        assertThat(body).doesNotContainIgnoringCase("owner_subject");
        assertThat(body).doesNotContainIgnoringCase("tokenRef");
        assertThat(body).doesNotContainIgnoringCase("token_ref");
        assertThat(body).doesNotContainIgnoringCase("owner-entity-leak");
    }

    private Long createSource(String ownerSubject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/sources")
                        .with(adminSubject(ownerSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"GOOGLE_DRIVE\",\"name\":\"" + name + "\",\"syncMode\":\"FULL\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return extractId(result);
    }

    private Long extractId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new AssertionError("no id field found in response body");
        }
        return Long.valueOf(matcher.group(1));
    }

    private String bodyWithoutTraceId(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString().replaceAll("\"traceId\"\\s*:\\s*\"[^\"]*\"", "\"traceId\":\"X\"");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminSubject(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
