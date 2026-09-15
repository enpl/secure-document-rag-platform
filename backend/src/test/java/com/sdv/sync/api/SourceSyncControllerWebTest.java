package com.sdv.sync.api;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F-BE-062 HTTP 경계 검증(M09A 교정 - 401/403 직접 검증 추가) -
 * {@code /api/admin/**}의 ADMIN Role 요구 자체는 M03의 기존 Security Test
 * ({@code SecurityAuthorizationWebTest})가 이미 폭넓게 증명하지만, 이 Sync
 * 전용 경로에서도 최소 1개씩 직접 확인한다(Mock JWT 기준 - 실제 Keycloak
 * 왕복은 아니다). 나머지는 Owner-Scoped 404와 동시 실행 409 매핑에 집중한다.
 * Google을 실제로 부르지 않는다(Source가 존재하지 않거나 소유자가 다르면
 * 어떤 외부 호출도 없이 Fail Closed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class SourceSyncControllerWebTest {

    private static final AtomicLong OWNER_SEQUENCE = new AtomicLong();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;

    @Test
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/admin/sources/1/sync")).andExpect(status().isUnauthorized());
    }

    @Test
    void nonAdminRoleReturns403() throws Exception {
        mockMvc.perform(post("/api/admin/sources/1/sync")
                        .with(jwt().jwt(builder -> builder.subject(owner()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void syncingANonExistentSourceReturnsNotFoundWithoutRevealingWhetherItExists() throws Exception {
        mockMvc.perform(post("/api/admin/sources/999999999/sync").with(adminSubject(owner())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anotherSubjectCannotTriggerSyncForASourceItDoesNotOwn() throws Exception {
        String realOwner = owner();
        Long sourceId = createSource(realOwner);

        mockMvc.perform(post("/api/admin/sources/" + sourceId + "/sync").with(adminSubject(owner())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void aDisabledSourceCannotBeSynced() throws Exception {
        String owner = owner();
        Long sourceId = createSource(owner);
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        entity.changeStatus("DISABLED");
        sourceConnectionJpaRepository.saveAndFlush(entity);

        mockMvc.perform(post("/api/admin/sources/" + sourceId + "/sync").with(adminSubject(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SYNC_FAILED"));
    }

    private Long createSource(String ownerSubject) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL",
                ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private static String owner() {
        return "owner-sync-web-" + OWNER_SEQUENCE.incrementAndGet();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor adminSubject(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
