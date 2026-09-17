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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceUserControllerWebTest {

    @Autowired MockMvc mockMvc;
    @Autowired SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired SourceDocumentJpaRepository sourceDocumentJpaRepository;

    @Test
    void filenameQueryFiltersTheWholeCatalogBeforePagingAndIsCaseInsensitive() throws Exception {
        String owner = unique("owner-search");
        Long sourceId = createSource(owner);
        for (int index = 0; index < 55; index++) {
            createDocument(sourceId, "noise-" + index + ".txt");
        }
        createDocument(sourceId, "Quarterly Board Report.txt");

        mockMvc.perform(get("/api/sources/{id}/files", sourceId)
                        .queryParam("page", "0").queryParam("size", "20").queryParam("q", "quarterly board")
                        .with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Quarterly Board Report.txt"))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void literalLikeCharactersAreNotWildcards() throws Exception {
        String owner = unique("owner-literal");
        Long sourceId = createSource(owner);
        createDocument(sourceId, "Budget%_2026\\Final.txt");
        createDocument(sourceId, "BudgetXX2026-Final.txt");

        mockMvc.perform(get("/api/sources/{id}/files", sourceId)
                        .queryParam("q", "%_2026\\").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Budget%_2026\\Final.txt"));

        mockMvc.perform(get("/api/sources/{id}/files", sourceId)
                        .queryParam("q", "%' OR 1=1 --").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void omittedAndBlankQueriesRemainBackwardCompatible() throws Exception {
        String owner = unique("owner-blank");
        Long sourceId = createSource(owner);
        createDocument(sourceId, "A.txt");

        mockMvc.perform(get("/api/sources/{id}/files", sourceId).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "   ").with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void queryLengthIsBounded() throws Exception {
        String owner = unique("owner-bounds");
        Long sourceId = createSource(owner);

        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "x".repeat(201)).with(user(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "report\nname").with(user(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void userAndAdminRolesCannotSearchAnotherOwnersPrivateCatalog() throws Exception {
        String owner = unique("owner-private");
        Long sourceId = createSource(owner);
        createDocument(sourceId, "Private.txt");

        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "Private")
                        .with(user(unique("user-b"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "Private")
                        .with(user(unique("user-c"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sources/{id}/files", sourceId).queryParam("q", "Private")
                        .with(admin(unique("admin"))))
                .andExpect(status().isNotFound());
    }

    private Long createSource(String owner) {
        return sourceConnectionJpaRepository.saveAndFlush(new SourceConnectionEntity(
                "GOOGLE_DRIVE", "Test Drive", "ACTIVE", "FULL", owner)).getId();
    }

    private void createDocument(Long sourceId, String name) {
        sourceDocumentJpaRepository.saveAndFlush(new SourceDocumentEntity(sourceId, UUID.randomUUID().toString(),
                name, "text/plain", "v1", null, "ACTIVE", "PENDING", null));
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor admin(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
