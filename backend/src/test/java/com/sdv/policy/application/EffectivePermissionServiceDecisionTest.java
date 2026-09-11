package com.sdv.policy.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.entity.DocumentSecurityLabelEntity;
import com.sdv.policy.infrastructure.persistence.entity.OverlayPolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.policy.infrastructure.persistence.repository.OverlayPolicyJpaRepository;
import com.sdv.policy.infrastructure.persistence.repository.SecurityLabelJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05 판단 행렬(Decision Matrix) - 실제 Spring이 관리하는
 * {@link EffectivePermissionService} Bean과 실제 Testcontainers PostgreSQL을 사용한다.
 * ACL Freshness 기준({@code sdv.policy.permission-freshness-max-age})은 이 테스트가
 * 서버 측 설정으로 명시적으로 부여한다 - 어떤 값도 요청/클라이언트에서 오지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class EffectivePermissionServiceDecisionTest {

    private static final String ACTION = "VIEW";

    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Autowired
    private OverlayPolicyJpaRepository overlayPolicyJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;
    @Autowired
    private SecurityLabelJpaRepository securityLabelJpaRepository;

    @Test
    void explicitMatchingSourcePermissionProceedsToAllow() {
        String owner = "owner-allow-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.ALLOWED);
    }

    @Test
    void noMatchingSourcePermissionIsDenied() {
        String owner = "owner-nomatch-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", "someone-else");

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
    }

    @Test
    void zeroPermissionRowsIsDeniedAsUntrustedEvidence() {
        String owner = "owner-nopermrows-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.PERMISSION_DATA_UNTRUSTED);
    }

    @Test
    void stalePermissionEvidenceIsDeniedAsUntrusted() {
        String owner = "owner-stale-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        sourcePermissionJpaRepository.saveAndFlush(new SourcePermissionEntity(documentId, "user", owner, "READ",
                Instant.now().minus(Duration.ofHours(48))));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.PERMISSION_DATA_UNTRUSTED);
    }

    @Test
    void unknownPrincipalTypeNeverGrantsSourceAccess() {
        String owner = "owner-unkprincipal-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "service_account", owner, "READ", Instant.now()));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
    }

    @Test
    void unknownPermissionValueNeverGrantsSourceAccess() {
        String owner = "owner-unkperm-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", owner, "WRITE", Instant.now()));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
    }

    @Test
    void groupAndDomainAndAnyonePrincipalTypesCanGrantSourceAccess() {
        String owner = "owner-principals-" + unique();

        long groupDoc = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(groupDoc, "group", "trusted-group");
        PolicyDecision groupDecision = effectivePermissionService.evaluate(
                new UserContext(owner, owner + "@example.com", Set.of(Role.USER), Set.of("trusted-group")),
                groupDoc, ACTION, null);
        assertThat(groupDecision.isAllowed()).isTrue();

        long domainDoc = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(domainDoc, "domain", "example.com");
        PolicyDecision domainDecision = effectivePermissionService.evaluate(userContext(owner), domainDoc, ACTION,
                null);
        assertThat(domainDecision.isAllowed()).isTrue();

        long anyoneDoc = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(anyoneDoc, "anyone", "anyone");
        PolicyDecision anyoneDecision = effectivePermissionService.evaluate(userContext(owner), anyoneDoc, ACTION,
                null);
        assertThat(anyoneDecision.isAllowed()).isTrue();
    }

    @Test
    void sourceDenialCannotBeOverriddenByOverlayAllow() {
        String owner = "owner-srcdeny-overlayallow-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", "someone-else");
        overlayPolicyJpaRepository.saveAndFlush(new OverlayPolicyEntity("USER", owner, null, ACTION, "ALLOW"));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
    }

    @Test
    void matchingOverlayDenyOverridesSourcePermission() {
        String owner = "owner-overlaydeny-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        overlayPolicyJpaRepository.saveAndFlush(new OverlayPolicyEntity("USER", owner, null, ACTION, "DENY"));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.OVERLAY_DENIED);
    }

    @Test
    void deletedDocumentIsDenied() {
        String owner = "owner-deleted-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "DELETED");
        grantFreshRead(documentId, "user", owner);

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.DOCUMENT_DELETED);
    }

    @Test
    void disabledSourceIsDenied() {
        String owner = "owner-disabled-" + unique();
        long documentId = createDocument(owner, "DISABLED", "ACTIVE");
        grantFreshRead(documentId, "user", owner);

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.SOURCE_INACTIVE);
    }

    @Test
    void crossAccountAndNonexistentDocumentsExposeTheSameDenialReason() {
        String owner = "owner-real-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);

        PolicyDecision crossAccount =
                effectivePermissionService.evaluate(userContext("attacker-" + unique()), documentId, ACTION, null);
        PolicyDecision nonexistent =
                effectivePermissionService.evaluate(userContext("attacker-" + unique()), 987_654_321L, ACTION, null);

        assertThat(crossAccount.isDenied()).isTrue();
        assertThat(nonexistent.isDenied()).isTrue();
        assertThat(crossAccount.reasonCode()).isEqualTo(PolicyReasonCode.RESOURCE_NOT_FOUND);
        assertThat(nonexistent.reasonCode()).isEqualTo(PolicyReasonCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void invalidInternalInputsFailClosed() {
        String owner = "owner-invalid-" + unique();

        PolicyDecision blankAction = effectivePermissionService.evaluate(userContext(owner), 1L, "  ", null);
        PolicyDecision nullDocumentId = effectivePermissionService.evaluate(userContext(owner), null, ACTION, null);

        assertThat(blankAction.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
        assertThat(nullDocumentId.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
    }

    /**
     * Even when every other check would ALLOW (fresh matching READ ACL, ACTIVE owned
     * document/source, no restrictive overlay), an unsupported action must not slip
     * through as ALLOW - only the exact literal {@code VIEW} is a supported action
     * (M05 follow-up correction: EffectivePermissionService previously rejected only
     * null/blank actions).
     */
    @Test
    void unsupportedActionIsDeniedEvenWhenEverythingElseWouldAllow() {
        String owner = "owner-unsupported-action-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);

        PolicyDecision deleteDecision =
                effectivePermissionService.evaluate(userContext(owner), documentId, "DELETE", null);
        PolicyDecision typoDecision =
                effectivePermissionService.evaluate(userContext(owner), documentId, "TYPO", null);

        assertThat(deleteDecision.isDenied()).isTrue();
        assertThat(deleteDecision.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
        assertThat(typoDecision.isDenied()).isTrue();
        assertThat(typoDecision.reasonCode()).isEqualTo(PolicyReasonCode.INVALID_REQUEST);
    }

    // NOTE: V004 chk_source_document_state is NOT VALID - it preserves pre-existing
    // legacy `state` rows without validating them, but it does enforce ACTIVE/DELETED
    // on every new INSERT, so a legacy-state row can no longer be created through this
    // integration test's normal insert path. The boundary that a non-ACTIVE/non-DELETED
    // state must deny (not be treated as merely "not deleted") is instead covered by a
    // Mockito fixture in
    // EffectivePermissionServiceUnitTest.legacyUnrecognizedDocumentStateIsDeniedRatherThanTreatedAsUsable.

    @Test
    void aiDeniedModeDeniesAiUseEvenWithSourceAndOverlayAllowing() {
        String owner = "owner-ai-denied-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        label(documentId, SecurityLevel.SECRET);
        aiUsagePolicyJpaRepository.saveAndFlush(
                new AiUsagePolicyEntity(SecurityLevel.SECRET.name(), "AI_DENIED", false));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION,
                AiRequestContext.local());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }

    @Test
    void localOnlyModeAllowsLocalButDeniesExternal() {
        String owner = "owner-ai-local-" + unique();
        long allowDoc = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(allowDoc, "user", owner);
        label(allowDoc, SecurityLevel.CONFIDENTIAL);
        aiUsagePolicyJpaRepository.saveAndFlush(
                new AiUsagePolicyEntity(SecurityLevel.CONFIDENTIAL.name(), "LOCAL_ONLY", false));

        PolicyDecision localDecision = effectivePermissionService.evaluate(userContext(owner), allowDoc, ACTION,
                AiRequestContext.local());
        PolicyDecision externalDecision = effectivePermissionService.evaluate(userContext(owner), allowDoc, ACTION,
                AiRequestContext.external());

        assertThat(localDecision.isAllowed()).isTrue();
        assertThat(externalDecision.isDenied()).isTrue();
        assertThat(externalDecision.reasonCode()).isEqualTo(PolicyReasonCode.AI_EXTERNAL_PROVIDER_DENIED);
    }

    @Test
    void externalAllowedModeHonorsExternalProviderAllowedFlag() {
        String owner = "owner-ai-external-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        label(documentId, SecurityLevel.PUBLIC);
        aiUsagePolicyJpaRepository.saveAndFlush(
                new AiUsagePolicyEntity(SecurityLevel.PUBLIC.name(), "EXTERNAL_ALLOWED", true));

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION,
                AiRequestContext.external());

        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void externalAiIsDeniedByDefaultWhenPolicyIsMissing() {
        String owner = "owner-ai-missing-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        label(documentId, SecurityLevel.INTERNAL);
        // Deliberately no ai_usage_policies row for INTERNAL.

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION,
                AiRequestContext.external());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }

    @Test
    void missingSecurityLabelFailsClosedWhenAiIsRequested() {
        String owner = "owner-ai-nolabel-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        // Deliberately no document_security_labels row.

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION,
                AiRequestContext.local());

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.AI_USAGE_DENIED);
    }

    @Test
    void missingSecurityLabelDoesNotBlockNonAiAccess() {
        String owner = "owner-nolabel-nonai-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        // No label, and AI is not requested (null aiRequestContext).

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isAllowed()).isTrue();
    }

    /**
     * Exercises, through the central {@code evaluate()} path, the previously corrected
     * defect where a level-specific overlay {@code DENY} was silently skipped when the
     * document had no security label (M05 follow-up correction to
     * {@code OverlayPolicyService}). Previously only covered by a pure unit test on
     * {@code OverlayPolicyService.evaluateAgainst}; this closes the central-path gap.
     * The overlay row is scoped to this test's unique {@code owner} subject value, so
     * (unlike the malformed-selector tests below) it cannot affect any other test.
     */
    @Test
    void missingSecurityLabelWithMatchingLevelSpecificOverlayDenyFailsClosedThroughEvaluate() {
        String owner = "owner-nolabel-overlaydeny-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        overlayPolicyJpaRepository.saveAndFlush(
                new OverlayPolicyEntity("USER", owner, SecurityLevel.SECRET.name(), ACTION, "DENY"));
        // Deliberately no document_security_labels row.

        PolicyDecision decision = effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

        assertThat(decision.isDenied()).isTrue();
        assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.OVERLAY_DENIED);
    }

    /**
     * M05 follow-up correction #2 (final remaining defect): a real, persisted overlay
     * row with an unrecognized {@code ROLE} name ("ADMINN", a typo/malformed value -
     * {@link Role} only defines {@code USER}/{@code ADMIN}, and V001 has no CHECK
     * constraint rejecting it) must deny through the central {@code evaluate()} path
     * given an owned ACTIVE document/source, a configured test TTL, and a fresh
     * matching READ ACL - not be silently skipped as "proven unrelated" and fall
     * through to an earlier unrelated ALLOW.
     *
     * <p>{@code overlay_policies} has no owner scoping (global rows matched only by
     * action/subject/level) - a malformed {@code ROLE} row cannot be proven to concern
     * any specific subject, so it would otherwise deny every later {@code VIEW}-action
     * evaluation in this shared Testcontainers database. It is removed again in a
     * {@code finally} block so it cannot leak into any other test.</p>
     */
    @Test
    void malformedRoleSelectorPersistedForRealDeniesThroughTheCentralEvaluatePath() {
        String owner = "owner-malformed-role-" + unique();
        long documentId = createDocument(owner, "ACTIVE", "ACTIVE");
        grantFreshRead(documentId, "user", owner);
        OverlayPolicyEntity malformedRole =
                overlayPolicyJpaRepository.saveAndFlush(new OverlayPolicyEntity("ROLE", "ADMINN", null, ACTION,
                        "DENY"));

        try {
            PolicyDecision decision =
                    effectivePermissionService.evaluate(userContext(owner), documentId, ACTION, null);

            assertThat(decision.isDenied()).isTrue();
            assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.OVERLAY_DENIED);
        } finally {
            overlayPolicyJpaRepository.delete(malformedRole);
        }
    }

    /**
     * Same defect as above, for blank {@code USER}/{@code GROUP} selector values -
     * V001 requires {@code subject_value NOT NULL} but does not reject a blank string.
     * A blank value cannot be proven to identify someone other than the current user,
     * so it must deny rather than be treated as a definite non-match. Each row is
     * removed in a {@code finally} block for the same shared-database reason as above.
     */
    @Test
    void blankUserAndGroupSelectorsPersistedForRealDenyThroughTheCentralEvaluatePath() {
        String userOwner = "owner-blank-user-" + unique();
        long userDocumentId = createDocument(userOwner, "ACTIVE", "ACTIVE");
        grantFreshRead(userDocumentId, "user", userOwner);
        OverlayPolicyEntity blankUser =
                overlayPolicyJpaRepository.saveAndFlush(new OverlayPolicyEntity("USER", "", null, ACTION, "DENY"));
        try {
            PolicyDecision decision =
                    effectivePermissionService.evaluate(userContext(userOwner), userDocumentId, ACTION, null);

            assertThat(decision.isDenied()).isTrue();
            assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.OVERLAY_DENIED);
        } finally {
            overlayPolicyJpaRepository.delete(blankUser);
        }

        String groupOwner = "owner-blank-group-" + unique();
        long groupDocumentId = createDocument(groupOwner, "ACTIVE", "ACTIVE");
        grantFreshRead(groupDocumentId, "user", groupOwner);
        OverlayPolicyEntity blankGroup =
                overlayPolicyJpaRepository.saveAndFlush(new OverlayPolicyEntity("GROUP", "   ", null, ACTION,
                        "DENY"));
        try {
            PolicyDecision decision =
                    effectivePermissionService.evaluate(userContext(groupOwner), groupDocumentId, ACTION, null);

            assertThat(decision.isDenied()).isTrue();
            assertThat(decision.reasonCode()).isEqualTo(PolicyReasonCode.OVERLAY_DENIED);
        } finally {
            overlayPolicyJpaRepository.delete(blankGroup);
        }
    }

    private long createDocument(String ownerSubject, String connectionStatus, String documentState) {
        SourceConnectionEntity connection =
                new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", connectionStatus, "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "doc-" + unique(), "Doc",
                "text/plain", null, null, documentState, "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return document.getId();
    }

    private void grantFreshRead(long documentId, String principalType, String principalValue) {
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, principalType, principalValue, "READ", Instant.now()));
    }

    private void label(long documentId, SecurityLevel level) {
        securityLabelJpaRepository.saveAndFlush(
                new DocumentSecurityLabelEntity(documentId, level.name(), "MANUAL", null));
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }

    private static String unique() {
        return java.util.UUID.randomUUID().toString();
    }
}
