package com.sdv.security.api;

import com.sdv.audit.application.AuditQueryService;
import com.sdv.security.application.SecurityFindingService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend" })
class AdminAuditAndFindingsWebTest {
    @Autowired MockMvc mvc;
    @MockitoBean AuditQueryService audits;
    @MockitoBean SecurityFindingService findings;

    @Test
    void ordinaryUserCannotQueryAuditOrPrivateFindings() throws Exception {
        mvc.perform(get("/api/admin/security/findings")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/admin/security/findings/1")
                .contentType("application/json").content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/audits").with(user("user-b"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/security/findings").with(user("user-b"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/security/findings/1")
                .contentType("application/json").content("{\"status\":\"ACKNOWLEDGED\"}")
                .with(user("user-b"))).andExpect(status().isForbidden());
    }

    @Test
    void adminGetsBoundedNoStoreDtosAndInvalidBoundsAreRejected() throws Exception {
        when(audits.query(any(), any(), any(), any(), any(), eq(0), eq(50)))
                .thenReturn(new AuditQueryService.Page(List.of(), 0, 50, false));
        when(findings.list(isNull(), eq(0), eq(50)))
                .thenReturn(new SecurityFindingService.Page(List.of(), 0, 50, false));
        mvc.perform(get("/api/admin/audits").with(admin("admin-a"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/admin/security/findings").with(admin("admin-a"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/admin/audits").param("size", "101").with(admin("admin-a")))
                .andExpect(status().isBadRequest());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor user(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
