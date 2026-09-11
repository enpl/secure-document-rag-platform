package com.sdv.policy.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.OverlayPolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.OverlayPolicyJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F-BE-082. Source 권한 위에 적용되는 추가 SDV 제한 정책(POL-003).
 *
 * <p>불변식(v3.2 §17, §19 INV-SRC-001):</p>
 * <ul>
 *   <li>일치하는 {@code DENY}가 있으면 항상 이긴다.</li>
 *   <li>{@code ALLOW}는 비-authoritative다 - Source 거부를 절대 뒤집지 않는다
 *       ({@code EffectivePermissionService}가 Overlay를 Source 허용 이후에만
 *       평가하므로, 이 Service는 애초에 "ALLOW로 넓히는" 능력을 갖지 않는다).</li>
 *   <li>일치하는 규칙이 전혀 없으면 중립({@link OverlayVerdict#NEUTRAL})이다.</li>
 * </ul>
 *
 * <p>원칙 일치는 신뢰 가능한 {@link UserContext} 필드({@code subject},
 * {@code groups}, {@code roles})만 사용한다 - {@code USER}/{@code GROUP}/
 * {@code ROLE} 세 유형만 인식한다(이 작업 지시가 명시).</p>
 *
 * <p><b>증명된 비일치(Non-match) vs 알 수 없는 적용 가능성(Unknown
 * Applicability)을 구분한다(M05 후속 교정).</b> {@code USER}/{@code GROUP}/
 * {@code ROLE}은 신뢰 가능한 {@link UserContext} 필드로 정확히 판단할 수 있으므로,
 * 관련 없는 사용자/그룹/역할에 대한 행은 "증명된 비일치"로 안전하게 건너뛴다.
 * 등급-특정 행({@code security_level}이 지정됨)인데 문서 등급을 알 수 없거나
 * (라벨 없음), 행의 {@code security_level} 값 자체가 인식 가능한 {@link SecurityLevel}
 * 이름이 아니면(DB에 CHECK 제약이 없어 실제로 저장될 수 있다) - 이 행이 이
 * 문서에 적용되는지 여부를 증명할 수 없다. 마찬가지로 {@code subjectType}이
 * {@code USER}/{@code GROUP}/{@code ROLE} 중 하나로 인식되지 않으면, 이 행이
 * 현재 사용자를 대상으로 하는지 여부를 증명할 수 없다.
 *
 * <p>이런 "알 수 없는 적용 가능성"은 {@code NEUTRAL}/{@code ALLOW}로 조용히
 * 사라지면 안 된다 - {@code effect}가 {@code DENY}(또는 {@code chk_overlay_effect}를
 * 우회한 인식 불가 값)이면 Fail Closed로 {@code DENY}를 반환한다. 단,
 * {@code effect}가 {@code ALLOW}이면 애초에 비-authoritative하므로(Source 거부를
 * 절대 뒤집지 못함) 적용 가능성이 불확실해도 보안에 영향이 없다 - 계속 스캔한다.
 * <b>인식되지 않는 DENY 계열 선택자를 무시하는 것과 인식되지 않는 ALLOW 계열
 * 선택자를 무시하는 것은 정반대의 보안 효과를 가진다</b> - 전자를 무시하면
 * 실제로 이 사용자를 대상으로 했을 수도 있는 제한을 놓쳐 잘못 허용(Fail Open)하고,
 * 후자를 무시해도 어차피 ALLOW는 아무것도 허용을 확장하지 않으므로 안전하다.
 * 이 둘을 동일하게 취급하지 않는다.</p>
 *
 * <p><b>인식된 subjectType이라도 값 자체가 손상(Malformed)됐을 수 있다(M05
 * 후속 교정 #2).</b> V001은 {@code subject_value}에 {@code NOT NULL}만 요구할 뿐,
 * 빈 문자열이나 인식되지 않는 {@code ROLE} 이름을 막지 않는다({@link Role}은
 * {@code USER}/{@code ADMIN}만 정의한다). {@code USER}/{@code GROUP} 행의
 * {@code subject_value}가 null이거나 빈 값이면, 또는 {@code ROLE} 행의 값이
 * {@link Role} 상수 중 어느 것과도 정확히 일치하지 않으면 - 이는 "이 사용자와
 * 무관한 다른 유효한 신원"이 증명된 것이 아니라 신원 자체를 해석할 수 없는
 * 것이다. 이런 손상된 선택자를 조용히 건너뛰면(과거 결함) 실제로 어떤 사용자를
 * 겨냥했을 수도 있는 제한이 사라진다 - 손상된 값을 다른 식으로 정규화하거나
 * 추측하지 않고, 그대로 "적용 가능성을 알 수 없음"으로 취급해 위 문단의
 * Fail-Closed 규칙을 따른다. 값이 정확히 일치하는 유효한 {@link Role}인데
 * 사용자가 그 역할을 갖고 있지 않은 경우는 여전히 "증명된 비일치"로 남는다.</p>
 *
 * <p>반대로, 등급 또는 원칙 중 어느 하나라도 "증명된 비일치"이면(예: 알려진
 * 두 등급이 서로 다름, 또는 인식된 값을 가진 {@code GROUP}/{@code ROLE}이 이
 * 사용자의 소속과 무관함) 그 행은 이 문서/사용자와 전혀 무관하므로 다른 쪽이
 * 불확실해도 안전하게 건너뛴다 - 무관한 모든 정책을 일괄 거부하지 않는다.</p>
 */
@Service
public class OverlayPolicyService {

    public enum OverlayVerdict {
        NEUTRAL,
        DENY
    }

    private final OverlayPolicyJpaRepository overlayPolicyJpaRepository;

    public OverlayPolicyService(OverlayPolicyJpaRepository overlayPolicyJpaRepository) {
        this.overlayPolicyJpaRepository = overlayPolicyJpaRepository;
    }

    @Transactional(readOnly = true)
    public OverlayVerdict evaluate(UserContext user, String action, SecurityLevel securityLevel) {
        List<OverlayPolicyEntity> candidates = overlayPolicyJpaRepository.findByAction(action);
        return evaluateAgainst(candidates, user, securityLevel);
    }

    /** 순수 판단 로직 - Persistence 없이 직접 단위 테스트할 수 있도록 패키지 전용으로 분리했다. */
    static OverlayVerdict evaluateAgainst(List<OverlayPolicyEntity> candidates, UserContext user,
            SecurityLevel securityLevel) {
        for (OverlayPolicyEntity row : candidates) {
            // 등급 또는 원칙 중 하나라도 "증명된 비일치"이면 이 행은 무관하다 - 다른
            // 쪽의 적용 가능성이 불확실해도 안전하게 건너뛴다.
            if (securityLevelDefinitelyDoesNotApply(row, securityLevel)) {
                continue;
            }
            if (principalDefinitelyDoesNotApply(row, user)) {
                continue;
            }
            // 여기 도달하면 이 행은 "확실히 적용됨" 또는 "적용 가능성을 알 수 없음"
            // 둘 중 하나다 - 어느 쪽이든 ALLOW가 아니면 Fail Closed로 DENY 한다
            // (ALLOW는 비-authoritative이므로 불확실성이 안전에 영향을 주지 않는다).
            if (!"ALLOW".equals(row.getEffect())) {
                return OverlayVerdict.DENY;
            }
            // ALLOW: 비-authoritative하므로 계속 스캔해 나중 행의 DENY를 찾는다.
        }
        return OverlayVerdict.NEUTRAL;
    }

    /** {@code true}는 이 행의 원칙이 이 사용자와 무관하다고 증명됐음을 뜻한다. */
    private static boolean principalDefinitelyDoesNotApply(OverlayPolicyEntity row, UserContext user) {
        String type = row.getSubjectType();
        String value = row.getSubjectValue();
        if ("USER".equals(type)) {
            if (value == null || value.isBlank()) {
                // 손상된(Malformed) 선택자 - 어떤 사용자를 가리켰는지 알 수 없으므로
                // "이 사용자가 아님"을 증명하지 못한다(Fail Closed).
                return false;
            }
            return !value.equals(user.subject());
        }
        if ("GROUP".equals(type)) {
            if (value == null || value.isBlank()) {
                // 손상된 선택자 - 위와 동일한 이유로 Fail Closed.
                return false;
            }
            return !user.groups().contains(value);
        }
        if ("ROLE".equals(type)) {
            Role role = parseRole(value);
            if (role == null) {
                // 인식되지 않는(예: 오타) 역할 이름 - "사용자가 이 역할을 갖고
                // 있지 않음"이 아니라 "이 값이 가리키는 역할 자체를 알 수 없음"이다
                // (Fail Closed - 비슷한 실제 역할 이름으로 추측/정규화하지 않는다).
                return false;
            }
            return !user.roles().contains(role);
        }
        // 인식되지 않는 subjectType - 이 사용자를 대상으로 하는지 증명할 수 없다
        // (Fail Closed - 비일치로 단정하지 않는다).
        return false;
    }

    /** {@code value}가 정확히 일치하는 {@link Role} 상수가 있을 때만 그것을 반환한다 - 없으면 {@code null}. */
    private static Role parseRole(String value) {
        if (value == null) {
            return null;
        }
        for (Role role : Role.values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        return null;
    }

    /** {@code true}는 이 행의 등급 조건이 이 문서에 적용되지 않는다고 증명됐음을 뜻한다. */
    private static boolean securityLevelDefinitelyDoesNotApply(OverlayPolicyEntity row, SecurityLevel securityLevel) {
        String rowLevelRaw = row.getSecurityLevel();
        if (rowLevelRaw == null) {
            return false; // 등급과 무관하게 적용되는 규칙 - 항상 적용 가능하다.
        }
        SecurityLevel rowLevel = parseSecurityLevel(rowLevelRaw);
        if (rowLevel == null) {
            // chk_overlay_effect와 달리 overlay_policies.security_level에는 DB CHECK
            // 제약이 없다 - 인식 불가 값은 이 행이 적용되지 않는다고 증명하지 못한다
            // (Fail Closed).
            return false;
        }
        if (securityLevel == null) {
            // 문서 등급을 알 수 없으면 등급-특정 규칙의 적용 여부를 증명할 수 없다
            // (Fail Closed - 예전처럼 "적용 안 됨"으로 단정하지 않는다).
            return false;
        }
        return rowLevel != securityLevel;
    }

    private static SecurityLevel parseSecurityLevel(String rawLevel) {
        try {
            return SecurityLevel.valueOf(rawLevel);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
