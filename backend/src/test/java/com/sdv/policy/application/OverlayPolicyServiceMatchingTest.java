package com.sdv.policy.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.application.OverlayPolicyService.OverlayVerdict;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.OverlayPolicyEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OverlayPolicyService#evaluateAgainst}에 대한 순수 단위 테스트 - Persistence 없이
 * 직접 구성한 {@link OverlayPolicyEntity}로 원칙/행동/등급 매칭과 Fail Closed 규칙을
 * 검증한다. {@code effect}가 DB {@code chk_overlay_effect} 제약(ALLOW/DENY만 허용)을
 * 우회한 값("MAYBE" 등)을 갖는 시나리오는 Testcontainers로 실제로 저장할 수 없으므로,
 * 이 순수 단위 테스트에서만 재현한다.
 */
class OverlayPolicyServiceMatchingTest {

    private static final UserContext USER = new UserContext("user-1", "user-1@example.com",
            Set.of(Role.USER), Set.of("group-a"));

    @Test
    void noCandidatesIsNeutral() {
        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    @Test
    void directUserMatchWithDenyEffectDenies() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    @Test
    void trustedGroupMatchWithDenyEffectDenies() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("GROUP", "group-a", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    @Test
    void trustedRoleMatchWithDenyEffectDenies() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("ROLE", "USER", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    @Test
    void unrelatedGroupAndRoleDoNotMatch() {
        OverlayPolicyEntity unrelatedGroup = new OverlayPolicyEntity("GROUP", "group-z", null, "VIEW", "DENY");
        OverlayPolicyEntity unrelatedRole = new OverlayPolicyEntity("ROLE", "ADMIN", null, "VIEW", "DENY");

        OverlayVerdict verdict =
                OverlayPolicyService.evaluateAgainst(List.of(unrelatedGroup, unrelatedRole), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    @Test
    void allowEffectIsNonAuthoritativeAndStaysNeutralWhenNoOtherRowApplies() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", null, "VIEW", "ALLOW");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    @Test
    void laterDenyRowStillWinsAfterAnEarlierApplicableAllowRow() {
        OverlayPolicyEntity allowRow = new OverlayPolicyEntity("USER", "user-1", null, "VIEW", "ALLOW");
        OverlayPolicyEntity denyRow = new OverlayPolicyEntity("GROUP", "group-a", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(allowRow, denyRow), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /**
     * 인식되지 않는 subjectType은 "아무에게도 일치하지 않음"(증명된 비일치)이
     * 아니라 "이 사용자를 대상으로 하는지 알 수 없음"이다 - effect가 DENY이면
     * Fail Closed로 DENY 해야 한다(이전에는 잘못 NEUTRAL로 무시했다 - M05 후속
     * 교정). 인식되지 않는 DENY 계열 선택자를 무시하면 이 사용자를 대상으로
     * 했을 제한을 놓쳐 잘못 허용(Fail Open)하므로, 인식되지 않는 ALLOW 계열
     * 선택자를 무시하는 것(비-authoritative이므로 안전)과 반대의 보안 효과를
     * 가진다.
     */
    @Test
    void unknownSubjectTypeWithDenyEffectFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("SERVICE_ACCOUNT", "user-1", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /**
     * 대조적으로, 인식되지 않는 subjectType의 행이 ALLOW이면 애초에
     * 비-authoritative하므로(Source 거부를 절대 뒤집지 못함) 대상 여부가
     * 불확실해도 안전에 영향이 없다 - NEUTRAL로 남는다.
     */
    @Test
    void unknownSubjectTypeWithAllowEffectStaysNeutral() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("SERVICE_ACCOUNT", "user-1", null, "VIEW", "ALLOW");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    /**
     * 등급 조건이 이미 "증명된 비일치"(문서의 알려진 등급과 다름)이면, subjectType이
     * 인식되지 않아도 그 불확실성과 무관하게 이 행은 무관하다 - 관련 없는 모든
     * 정책을 일괄 거부하지 않는다.
     */
    @Test
    void unrelatedSecurityLevelStaysNeutralEvenWithAnUnknownSubjectType() {
        OverlayPolicyEntity row =
                new OverlayPolicyEntity("SERVICE_ACCOUNT", "user-1", SecurityLevel.SECRET.name(), "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    @Test
    void unrecognizedEffectOnAFullyMatchingRowFailsClosedToDeny() {
        // chk_overlay_effect makes this unreachable via real persistence - defensive path only.
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", null, "VIEW", "MAYBE");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    @Test
    void securityLevelNullRowAppliesRegardlessOfDocumentLevel() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.SECRET);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    @Test
    void mismatchedSecurityLevelDoesNotApply() {
        OverlayPolicyEntity row =
                new OverlayPolicyEntity("USER", "user-1", SecurityLevel.SECRET.name(), "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    /**
     * 문서 라벨이 없으면(등급을 알 수 없음) 등급-특정 행이 이 문서에 적용되지
     * 않는다고 증명할 수 없다 - DENY이면 Fail Closed로 DENY 해야 한다(이전에는
     * 잘못 NEUTRAL로 조용히 사라졌다 - M05 후속 교정의 핵심 결함).
     */
    @Test
    void levelSpecificDenyFailsClosedWhenDocumentLabelIsUnknown() {
        OverlayPolicyEntity row =
                new OverlayPolicyEntity("USER", "user-1", SecurityLevel.SECRET.name(), "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /**
     * {@code overlay_policies.security_level}에는 DB CHECK 제약이 없으므로
     * 인식 불가 값("BOGUS" 등)이 실제로 저장될 수 있다 - 문서 등급을 알아도 그
     * 값과 비교할 수 없으므로 "적용 안 됨"으로 단정하지 않는다(Fail Closed).
     */
    @Test
    void malformedRowSecurityLevelWithDenyEffectFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", "BOGUS", "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /** 등급 적용 여부가 불확실해도, effect가 ALLOW면 비-authoritative이므로 안전하다. */
    @Test
    void malformedRowSecurityLevelWithAllowEffectStaysNeutral() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "user-1", "BOGUS", "VIEW", "ALLOW");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    // ------------------------------------------------------------------
    // M05 후속 교정 #2: 인식된 subjectType의 손상된(Malformed) 값 -
    // V001은 subject_value에 NOT NULL만 요구할 뿐 빈 값이나 인식되지 않는 ROLE
    // 이름을 막지 않는다. 이런 값은 "이 사용자와 무관한 다른 유효한 신원"이
    // 증명된 것이 아니라 신원 자체를 해석할 수 없는 것이므로, 이전처럼 조용히
    // 건너뛰지(NEUTRAL) 않고 Fail Closed로 처리해야 한다.
    // ------------------------------------------------------------------

    /**
     * {@link Role}은 {@code USER}/{@code ADMIN}만 정의한다 - {@code ADMINN}은
     * 오타/손상된 값이며, "사용자가 이 역할을 갖고 있지 않은 유효한 다른 역할"이
     * 아니다. 이전에는 {@code !hasRole(...)}가 항상 참이 되어 조용히 NEUTRAL로
     * 사라졌다 - 등급 제한이 없는 상태에서 이 결함만으로 ALLOW까지 이어질 수
     * 있었다.
     */
    @Test
    void malformedRoleNameWithDenyEffectFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("ROLE", "ADMINN", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /** 대조적으로, 손상된 ROLE 값의 행이 ALLOW이면 비-authoritative이므로 안전하다(NEUTRAL). */
    @Test
    void malformedRoleNameWithAllowEffectStaysNeutral() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("ROLE", "ADMINN", null, "VIEW", "ALLOW");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    /**
     * 값이 정확히 일치하는 유효한 {@link Role}인데 사용자가 그 역할을 갖고 있지
     * 않은 경우는 여전히 "증명된 비일치"로 남는다 - 이 회귀 방지 없이 malformed
     * 값 처리를 넓히면 유효하지만 무관한 역할까지 잘못 거부할 위험이 있다.
     */
    @Test
    void recognizedRoleTheUserDoesNotHoldStillDoesNotApply() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("ROLE", "ADMIN", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }

    /** 빈 문자열 {@code USER} 선택자는 "이 사용자가 아님"을 증명하지 못한다 - Fail Closed. */
    @Test
    void blankUserSelectorValueFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", "", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /** 공백만 있는 {@code GROUP} 선택자도 동일하게 손상된 값이다 - Fail Closed. */
    @Test
    void blankGroupSelectorValueFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("GROUP", "   ", null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /**
     * {@code subject_value}는 V001에서 {@code NOT NULL}이므로 실제 영속화된 행으로는
     * 재현할 수 없다 - 순수 단위 Fixture로 방어적 경로만 검증한다.
     */
    @Test
    void nullUserSelectorValueFailsClosedToDeny() {
        OverlayPolicyEntity row = new OverlayPolicyEntity("USER", null, null, "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, null);

        assertThat(verdict).isEqualTo(OverlayVerdict.DENY);
    }

    /**
     * 등급 조건이 이미 "증명된 비일치"이면, ROLE 값이 손상됐어도 그 불확실성과
     * 무관하게 이 행은 무관하다 - 관련 없는 모든 정책을 일괄 거부하지 않는다.
     */
    @Test
    void unrelatedSecurityLevelStaysNeutralEvenWithAMalformedRoleName() {
        OverlayPolicyEntity row =
                new OverlayPolicyEntity("ROLE", "ADMINN", SecurityLevel.SECRET.name(), "VIEW", "DENY");

        OverlayVerdict verdict = OverlayPolicyService.evaluateAgainst(List.of(row), USER, SecurityLevel.INTERNAL);

        assertThat(verdict).isEqualTo(OverlayVerdict.NEUTRAL);
    }
}
