package com.sdv.policy.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 순수 단위 테스트(Mockito) - Testcontainers 없이 {@link EffectivePermissionService}의
 * 두 가지 Fail-Closed 경계를 검증한다:
 * <ol>
 *   <li>지원되지 않는 action은 어떤 Repository도 조회하기 전에 즉시 거부된다.</li>
 *   <li>V004 {@code chk_source_document_state}(NOT VALID)가 보존하는 레거시
 *       {@code state} 값("SYNCED" 등, ACTIVE도 DELETED도 아님)을 가진 문서는
 *       거부된다 - 이 상태는 그 CHECK 제약이 신규/변경 행에 적용되므로 실제
 *       Testcontainers 행으로는 재현할 수 없어(위반 시 INSERT 자체가 실패한다),
 *       Mockito로 구성한 순수 단위 Fixture로만 재현할 수 있다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class EffectivePermissionServiceUnitTest {

    private static final String ACTION = "VIEW";

    @Mock
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Mock
    private PermissionFreshnessPolicy permissionFreshnessPolicy;
    @Mock
    private SecurityLabelService securityLabelService;
    @Mock
    private OverlayPolicyService overlayPolicyService;
    @Mock
    private AiUsagePolicyService aiUsagePolicyService;
    @Mock
    private AuditService auditService;

    private EffectivePermissionService newService() {
        return new EffectivePermissionService(sourceDocumentJpaRepository, sourceConnectionJpaRepository,
                sourcePermissionJpaRepository, permissionFreshnessPolicy, securityLabelService,
                overlayPolicyService, aiUsagePolicyService, auditService);
    }

    @Test
    void unsupportedActionIsRejectedBeforeAnyDocumentLookup() {
        EffectivePermissionService service = newService();

        PolicyDecision decision = service.evaluate(userContext("owner-1"), 1L, "DELETE", null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
        Mockito.verifyNoInteractions(sourceDocumentJpaRepository, sourceConnectionJpaRepository,
                sourcePermissionJpaRepository);
    }

    @Test
    void caseVariantOfTheSupportedActionIsRejectedRatherThanNormalized() {
        EffectivePermissionService service = newService();

        PolicyDecision decision = service.evaluate(userContext("owner-1"), 1L, "view", null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
    }

    @Test
    void legacyUnrecognizedDocumentStateIsDeniedRatherThanTreatedAsUsable() {
        String owner = "owner-legacy-1";
        SourceDocumentEntity legacyDocument = new SourceDocumentEntity(10L, "doc-legacy", "Doc", "text/plain", null,
                null, "SYNCED", "PENDING", null);
        when(sourceDocumentJpaRepository.findByIdAndOwnerSubject(1L, owner)).thenReturn(Optional.of(legacyDocument));
        EffectivePermissionService service = newService();

        PolicyDecision decision = service.evaluate(userContext(owner), 1L, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.DOCUMENT_STATE_UNRECOGNIZED);
        // A document in an unrecognized legacy state must never reach ACL/overlay evaluation.
        Mockito.verifyNoInteractions(sourceConnectionJpaRepository, sourcePermissionJpaRepository,
                overlayPolicyService, aiUsagePolicyService);
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }
}
