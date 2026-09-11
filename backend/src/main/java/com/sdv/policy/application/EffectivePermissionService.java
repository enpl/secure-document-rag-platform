package com.sdv.policy.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.model.UserContext;
import com.sdv.policy.application.OverlayPolicyService.OverlayVerdict;
import com.sdv.policy.domain.AiRequestContext;
import com.sdv.policy.domain.PolicyDecision;
import com.sdv.policy.domain.PolicyReasonCode;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourcePrincipal;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * F-BE-081. 하나로 중앙화된, 재사용 가능한 최종 접근 판단 경로(v3.2 §17, §18):
 *
 * <pre>
 * Source Permission AND SDV Overlay Policy AND Document State
 * AND (AI 요청 시) AI Usage Policy
 * </pre>
 *
 * <p>이 판단 로직은 Controller, Repository 질의, RAG Controller, Google Drive
 * Adapter 등 다른 곳에 중복되면 안 된다(v3.2 §18) - 이후 모든 M06+ 코드는
 * {@link #evaluate}/{@link #filterAllowed}만 호출해야 한다.</p>
 *
 * <h2>판단 순서</h2>
 * <ol>
 *   <li>내부 입력(documentId/action) 검증 - 불완전하거나 지원되지 않는 action이면
 *       {@link PolicyReasonCode#INVALID_REQUEST}(아래 "지원되는 action" 참고).</li>
 *   <li>Owner-Scoped Source 경계로 문서를 로드한다({@code source_documents →
 *       source_connections.owner_subject}) - 존재하지 않거나 다른 계정 소유면
 *       동일하게 {@link PolicyReasonCode#RESOURCE_NOT_FOUND}(구분하지 않음).</li>
 *   <li>문서 상태가 명시적으로 {@code ACTIVE}인지 확인한다 - {@code DELETED}면
 *       {@link PolicyReasonCode#DOCUMENT_DELETED}, 그 밖의 인식되지 않는 상태(V004
 *       {@code chk_source_document_state}가 NOT VALID로 보존하는 레거시 값 등)는
 *       {@link PolicyReasonCode#DOCUMENT_STATE_UNRECOGNIZED} - "DELETED가 아니니
 *       사용 가능"으로 해석하지 않는다. Source 연결이 비활성이면 거부한다.</li>
 *   <li>ACL Freshness를 평가한다({@link PermissionFreshnessPolicy}) - 증거가
 *       없거나 모두 Stale이면 {@link PolicyReasonCode#PERMISSION_DATA_UNTRUSTED}.</li>
 *   <li>Fresh한 ACL 중 현재 사용자에게 일치하는 Source 권한을 찾는다 - 없으면
 *       {@link PolicyReasonCode#SOURCE_PERMISSION_DENIED}(INV-SRC-001: 부재 =
 *       거부).</li>
 *   <li>Overlay 제한을 적용한다({@link OverlayPolicyService}) - 일치하는 DENY가
 *       있으면 {@link PolicyReasonCode#OVERLAY_DENIED}. Overlay ALLOW는
 *       비-authoritative이므로 Source 거부를 절대 뒤집을 수 없다(이 Service가
 *       Overlay를 Source 허용 이후에만 평가하므로 구조적으로 불가능하다).</li>
 *   <li>AI 사용이 요청된 경우에만 AI Usage Policy를 적용한다
 *       ({@link AiUsagePolicyService}).</li>
 *   <li>위를 모두 통과하면 {@link PolicyDecision#allow()}.</li>
 *   <li>정확히 하나의 최종 판단 감사 이벤트를 기록한다({@link AuditService}) -
 *       내부 단계별로 별도 감사를 남기지 않는다.</li>
 * </ol>
 *
 * <p><b>Audit과 Transaction:</b> {@link #evaluate}/{@link #filterAllowed}는
 * {@code @Transactional}이다. 감사 기록이 실패하면(예: 감사 저장소 장애) 그
 * 예외가 그대로 전파되어 호출자는 {@link PolicyDecision}을 절대 받지 못한다 -
 * "감사되지 않았는데 성공한 것처럼 보이는" 판단을 만들지 않는다(M04
 * {@code SourceConnectionService}에서 확립한 것과 동일한 정책). 이 Service
 * 자체는 어떤 Source/Overlay/AI/Label 데이터도 변경하지 않으므로, 여기서
 * Rollback되는 유일한 쓰기는 이번 호출이 남기려던 감사 행 자체다.</p>
 *
 * <p><b>Retrieval Handoff:</b> {@link #filterAllowed}는 문서 ID 목록을 입력
 * 순서 그대로 유지한 채 허용된 것만 남긴다 - 빈 결과를 "제한 없음"으로 절대
 * 해석하지 않는다(권한 부재의 기본값은 거부다). Vector 검색은 M05 범위 밖이며
 * 여기서 실행하지 않는다.</p>
 *
 * <p><b>지원되는 action(M05 후속 교정): {@code VIEW}뿐이다.</b> v3.2가 업무 전체를
 * 아우르는 canonical action Enum을 아직 정의하지 않으므로, 이 Service가 임의로
 * 새 action 집합을 발명하지 않는다 - 지금까지 실제로 테스트/사용되는 유일한
 * action인 {@code VIEW}만 정확히 일치할 때 통과시키고, 그 밖의 모든 값(빈
 * 문자열, {@code DELETE}/{@code WRITE} 같은 쓰기 계열 action, 대소문자/공백이
 * 다른 변형 포함)은 {@link PolicyReasonCode#INVALID_REQUEST}로 거부한다.
 * {@code action} 문자열은 {@link OverlayPolicyService}가 {@code overlay_policies.action}
 * 과 정확한 문자열로 비교하는 값이기도 하므로, 여기서 대소문자 정규화 등을
 * 시도하면 실제 저장된 Overlay 행과 더 이상 정확히 일치하지 않게 되어 의도치
 * 않게 그 행의 규칙(특히 DENY)을 우회(Bypass)시킬 수 있다 - 그래서 정규화하지
 * 않고 알려지지 않은 값을 그대로 거부한다. 향후 v3.2가 업무 전체를 아우르는
 * action 목록을 확정하면 이 집합을 그에 맞춰 넓힌다.</p>
 */
@Service
public class EffectivePermissionService {

    private static final String READ_PERMISSION = "READ";
    private static final String ACTIVE_STATE = "ACTIVE";
    private static final String DELETED_STATE = "DELETED";
    private static final String SUPPORTED_ACTION = "VIEW";
    private static final String AUDIT_ACTION = "POLICY_DECISION";

    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourcePermissionJpaRepository sourcePermissionJpaRepository;
    private final PermissionFreshnessPolicy permissionFreshnessPolicy;
    private final SecurityLabelService securityLabelService;
    private final OverlayPolicyService overlayPolicyService;
    private final AiUsagePolicyService aiUsagePolicyService;
    private final AuditService auditService;

    public EffectivePermissionService(
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository,
            PermissionFreshnessPolicy permissionFreshnessPolicy,
            SecurityLabelService securityLabelService,
            OverlayPolicyService overlayPolicyService,
            AiUsagePolicyService aiUsagePolicyService,
            AuditService auditService) {
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourcePermissionJpaRepository = sourcePermissionJpaRepository;
        this.permissionFreshnessPolicy = permissionFreshnessPolicy;
        this.securityLabelService = securityLabelService;
        this.overlayPolicyService = overlayPolicyService;
        this.aiUsagePolicyService = aiUsagePolicyService;
        this.auditService = auditService;
    }

    /**
     * 문서 하나에 대한 최종 접근 판단. {@code user}는 반드시
     * {@code CurrentUserProvider}가 만든 신뢰 가능한 Context여야 한다(null이거나
     * subject가 비어있으면 방어적으로 예외를 던진다 - 감사 기록에 쓸 신뢰
     * 가능한 actor 자체가 없으므로 DENY 판단조차 만들 수 없다). {@code aiRequestContext}가
     * {@code null}이면 AI 사용이 요청되지 않은 것으로 취급하고 7단계를 건너뛴다.
     */
    @Transactional
    public PolicyDecision evaluate(UserContext user, Long documentId, String action,
            AiRequestContext aiRequestContext) {
        requireValidUser(user);
        PolicyDecision decision = decide(user, documentId, action, aiRequestContext);
        recordAudit(user, documentId, action, decision);
        return decision;
    }

    /**
     * 후보 문서 ID 목록을 입력 순서 그대로 유지하면서 허용된 것만 필터링한다
     * (Retrieval Handoff). 문서별 인가 로직을 이 Service 밖에 중복하지 않기
     * 위해 내부적으로 {@link #evaluate}를 문서마다 호출한다 - 이 메서드는
     * {@code @Transactional}이므로, 배치 안에서 한 문서의 감사 기록이
     * 실패하면 그 예외가 전파되어 이번 배치 전체가 Rollback된다(부분적으로만
     * 감사된 "허용 목록"을 호출자에게 절대 반환하지 않는다).
     */
    @Transactional
    public List<Long> filterAllowed(UserContext user, List<Long> documentIds, String action,
            AiRequestContext aiRequestContext) {
        requireValidUser(user);
        Objects.requireNonNull(documentIds, "documentIds must not be null");
        List<Long> allowed = new ArrayList<>();
        for (Long documentId : documentIds) {
            PolicyDecision decision = evaluate(user, documentId, action, aiRequestContext);
            if (decision.isAllowed()) {
                allowed.add(documentId);
            }
        }
        return List.copyOf(allowed);
    }

    private static void requireValidUser(UserContext user) {
        Objects.requireNonNull(user, "user must not be null");
        if (user.subject() == null || user.subject().isBlank()) {
            throw new IllegalArgumentException("user.subject must not be blank");
        }
    }

    private PolicyDecision decide(UserContext user, Long documentId, String action,
            AiRequestContext aiRequestContext) {
        // 1. 필수 내부 입력 검증 - action은 반드시 지원되는 값(현재는 VIEW뿐)과
        // 정확히 일치해야 한다(대소문자/공백 변형을 정규화해 통과시키지 않는다 -
        // 클래스 Javadoc "지원되는 action" 참고).
        if (documentId == null || action == null || action.isBlank() || !SUPPORTED_ACTION.equals(action)) {
            return PolicyDecision.deny(PolicyReasonCode.INVALID_REQUEST);
        }

        // 2~3. Owner-Scoped 문서 로드 + 존재하지 않음/삭제됨/인식되지 않는 상태 거부
        Optional<SourceDocumentEntity> maybeDocument =
                sourceDocumentJpaRepository.findByIdAndOwnerSubject(documentId, user.subject());
        if (maybeDocument.isEmpty()) {
            return PolicyDecision.deny(PolicyReasonCode.RESOURCE_NOT_FOUND);
        }
        SourceDocumentEntity document = maybeDocument.get();
        String documentState = document.getState();
        if (DELETED_STATE.equals(documentState)) {
            return PolicyDecision.deny(PolicyReasonCode.DOCUMENT_DELETED);
        }
        if (!ACTIVE_STATE.equals(documentState)) {
            // V004 chk_source_document_state(NOT VALID)가 그대로 보존하는 레거시
            // 상태값 등 - "DELETED가 아니니 사용 가능"으로 해석하지 않는다(Fail Closed).
            return PolicyDecision.deny(PolicyReasonCode.DOCUMENT_STATE_UNRECOGNIZED);
        }

        Optional<SourceConnectionEntity> maybeConnection =
                sourceConnectionJpaRepository.findByIdAndOwnerSubject(document.getSourceId(), user.subject());
        if (maybeConnection.isEmpty()) {
            // 방어적 재확인 - 위 JOIN이 이미 같은 소유자를 보장하지만, 단일 확인만
            // 신뢰하지 않는다(요구된 보안 원칙: 사용자 제공 sourceId/owner는 인가
            // 증거가 아니다 - 여기서는 서버가 로드한 sourceId를 다시 소유자
            // 기준으로 검증한다).
            return PolicyDecision.deny(PolicyReasonCode.RESOURCE_NOT_FOUND);
        }
        if (!SourceConnection.STATUS_ACTIVE.equals(maybeConnection.get().getStatus())) {
            return PolicyDecision.deny(PolicyReasonCode.SOURCE_INACTIVE);
        }

        // 4. ACL Freshness
        List<SourcePermissionEntity> allRows = sourcePermissionJpaRepository.findByDocumentId(documentId);
        if (allRows.isEmpty()) {
            return PolicyDecision.deny(PolicyReasonCode.PERMISSION_DATA_UNTRUSTED);
        }
        List<SourcePermissionEntity> freshRows = allRows.stream()
                .filter(row -> permissionFreshnessPolicy.isFresh(row.getSyncedAt()))
                .toList();
        if (freshRows.isEmpty()) {
            return PolicyDecision.deny(PolicyReasonCode.PERMISSION_DATA_UNTRUSTED);
        }

        // 5. Fresh한 ACL 중 일치하는 Source 권한
        boolean sourceGranted = freshRows.stream().anyMatch(row -> READ_PERMISSION.equals(row.getPermission())
                && matchesPrincipal(new SourcePrincipal(row.getPrincipalType(), row.getPrincipalValue()), user));
        if (!sourceGranted) {
            return PolicyDecision.deny(PolicyReasonCode.SOURCE_PERMISSION_DENIED);
        }

        // 6. Overlay 제한 (문서 보안 등급을 알 수 있으면 함께 사용한다 - 없어도
        // Overlay 평가 자체는 계속할 수 있다. 단, 등급을 모른다고 등급-특정 DENY
        // 규칙이 조용히 빠지지는 않는다 - OverlayPolicyService가 그 적용 가능성을
        // 알 수 없는 경우를 Fail Closed로 처리한다)
        SecurityLevel securityLevel = securityLabelService.getSecurityLevel(user, documentId).orElse(null);
        if (overlayPolicyService.evaluate(user, action, securityLevel) == OverlayVerdict.DENY) {
            return PolicyDecision.deny(PolicyReasonCode.OVERLAY_DENIED);
        }

        // 7. AI 사용이 요청된 경우에만 AI Usage Policy
        if (aiRequestContext != null) {
            if (securityLevel == null) {
                // AI Usage Policy는 문서 보안 등급 없이는 절대 평가할 수 없다 - Fail Closed.
                return PolicyDecision.deny(PolicyReasonCode.AI_USAGE_DENIED);
            }
            PolicyDecision aiDecision = aiUsagePolicyService.evaluate(securityLevel, aiRequestContext);
            if (aiDecision.isDenied()) {
                return aiDecision;
            }
        }

        // 8. 모든 적용 가능한 검사를 통과했다
        return PolicyDecision.allow();
    }

    private static boolean matchesPrincipal(SourcePrincipal principal, UserContext user) {
        String type = principal.type();
        String value = principal.value();
        if ("user".equals(type)) {
            return value != null && value.equals(user.subject());
        }
        if ("group".equals(type)) {
            return value != null && user.groups().contains(value);
        }
        if ("domain".equals(type)) {
            return matchesDomain(value, user.email());
        }
        if ("anyone".equals(type)) {
            return true;
        }
        // 인식되지 않는 principal 유형 - 아무에게도 일치시키지 않는다(Fail Closed).
        return false;
    }

    private static boolean matchesDomain(String domain, String email) {
        if (domain == null || email == null) {
            return false;
        }
        int at = email.indexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return false;
        }
        return domain.equalsIgnoreCase(email.substring(at + 1));
    }

    private void recordAudit(UserContext user, Long documentId, String action, PolicyDecision decision) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (action != null) {
            metadata.put("action", action);
        }
        auditService.record(
                user.subject(),
                AUDIT_ACTION,
                "document:" + documentId,
                decision.isAllowed() ? "ALLOW" : "DENY",
                decision.reasonCode().name(),
                metadata);
    }
}
