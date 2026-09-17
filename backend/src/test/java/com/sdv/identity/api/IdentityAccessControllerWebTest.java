package com.sdv.identity.api;

import com.sdv.identity.api.dto.AdminUserPageResponse;
import com.sdv.identity.api.dto.DirectoryUserResponse;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class IdentityAccessControllerWebTest {
    private static final String ISSUER = "http://localhost:8180/realms/sdv";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private IdentityRegistryService identities;

    @Test
    void anonymousAndOrdinaryUsersCannotManageClearance() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").with(user("user-b"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/7/access")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":0,\"maximumClassification\":\"SECRET\",\"active\":true}")
                        .with(user("user-b")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminListingWorksButAMissingConcurrencyVersionIsRejected() throws Exception {
        when(identities.adminSearch("", 0, 20)).thenReturn(new AdminUserPageResponse(List.of(), false));

        mvc.perform(get("/api/admin/users").with(admin("admin-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(patch("/api/admin/users/7/access")
                        .contentType("application/json")
                        .content("{\"maximumClassification\":\"INTERNAL\",\"active\":true}")
                        .with(admin("admin-a")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryDirectoryReturnsOnlyMinimalServerResolvedLabels() throws Exception {
        when(identities.searchDirectory(ISSUER, "sdv-us"))
                .thenReturn(List.of(new DirectoryUserResponse(7L, "sdv-user-b", "User B")));

        mvc.perform(get("/api/directory/users").param("q", "sdv-us").with(user("owner-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].loginId").value("sdv-user-b"))
                .andExpect(jsonPath("$[0].displayName").value("User B"))
                .andExpect(jsonPath("$[0].maximumClassification").doesNotExist());
        verify(identities).searchDirectory(ISSUER, "sdv-us");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String subject) {
        return token(subject, "ROLE_USER");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin(String subject) {
        return token(subject, "ROLE_ADMIN");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(String subject,
            String role) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                        .claim("preferred_username", subject))
                .authorities(new SimpleGrantedAuthority(role));
    }
}
