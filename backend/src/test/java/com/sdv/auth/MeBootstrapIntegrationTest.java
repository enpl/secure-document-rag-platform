package com.sdv.auth;

import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class MeBootstrapIntegrationTest {
    private static final String ISSUER = "http://localhost:8180/realms/sdv";

    @Autowired private MockMvc mockMvc;
    @Autowired private IdentityRegistryService identities;

    @Test
    void validatedMeBootstrapMaterializesNoClearanceAndRepeatLoginPreservesAdminManagedDisabledState() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String subject = "bootstrap-subject-" + suffix;
        String login = "sdv-user-" + suffix;

        mockMvc.perform(get("/api/me").with(validatedUser(subject, login, "First Label")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryStatus").value("CLEARANCE_UNSET"));
        AdminUserResponse observed = identities.adminSearch(login, 0, 20).items().stream()
                .filter(user -> user.loginId().equals(login)).findFirst().orElseThrow();
        assertThat(observed.maximumClassification()).isNull();

        AdminUserResponse disabled = identities.updateAccess("admin-test", observed.id(), observed.version(),
                "INTERNAL", false);
        mockMvc.perform(get("/api/me").with(validatedUser(subject, login, "Refreshed Label")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryStatus").value("DISABLED"));

        AdminUserResponse afterRepeat = identities.adminGet(disabled.id());
        assertThat(afterRepeat.maximumClassification()).isEqualTo("INTERNAL");
        assertThat(afterRepeat.active()).isFalse();
        assertThat(afterRepeat.authorizationRevision()).isEqualTo(disabled.authorizationRevision());
        assertThat(afterRepeat.displayName()).isEqualTo("Refreshed Label");
    }

    @Test
    void missingLoginClaimDoesNotFabricateAUsableRegistryEntry() throws Exception {
        String subject = "missing-login-" + UUID.randomUUID();

        mockMvc.perform(get("/api/me").with(jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registryStatus").value("REGISTRY_UNAVAILABLE"));

        assertThat(identities.currentAuthorization(ISSUER, subject)).isEmpty();
        assertThat(identities.adminSearch(subject.substring(0, Math.min(subject.length(), 50)), 0, 20).items())
                .noneMatch(user -> user.loginId().equals(subject));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor validatedUser(
            String subject, String login, String displayName) {
        return jwt().jwt(builder -> builder.issuer(ISSUER).subject(subject)
                        .claim("preferred_username", login).claim("name", displayName))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
