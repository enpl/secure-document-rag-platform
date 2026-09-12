package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.policy.domain.PolicyDecision;
import org.springframework.stereotype.Service;

/**
 * M06 원안 - Content Processing의 Application Service.
 *
 * <h2>M07A 교정(v1.4 §2A.3, {@code V006__zero_original_persistence.sql}) -
 * Extract 경로를 명시적으로 비활성화함</h2>
 * <p>이 Class는 원래 {@code document_extracted_content}(V005)에 Claim을
 * 원자적으로 걸고, Python AI Service로 Parsing한 뒤, 완전한 정규화 텍스트
 * ({@code normalized_text})를 그 테이블에 발행(Publish)했다. V006이 v1.4
 * 원본/평문 비보관 규칙에 따라 {@code document_extracted_content} 자체를
 * (구조와 데이터 모두) 제거했으므로, 그 테이블을 대상으로 하던 Claim/Fetch/
 * Parse/Publish/Invalidate 로직 전체({@code claim}, {@code loadFetchPlan},
 * {@code finalizePublish}, {@code invalidateAndRecordFailure},
 * {@code releaseClaim} 및 관련 Record)를 제거했다 - 더 이상 존재하지 않는
 * 테이블을 향한 호출을 남겨두지 않는다.</p>
 *
 * <p><b>왜 다시 구현하지 않고 비활성화만 하는가:</b> 이 작업(M07A)의 범위는
 * "영속 모델을 교정하고 Application이 안전하게 기동하게 만드는 것"이지,
 * 실제 Google Drive Connector·Embedding/Indexing Orchestration·Live
 * Retrieval을 구현하는 것이 아니다(M08/M11 범위). 그 재구현이 끝나기
 * 전까지 이 진입점은 평문을 저장하는 예전 경로로 절대 되돌아가지 않고,
 * 문서를 근거 없이 {@code INDEXED}로 표시하지도 않는다 - 대신 처리
 * 자체를 하지 않았다는 사실을 명시적으로 반환한다.</p>
 *
 * <p>인가(Authorization) 판단({@link EffectivePermissionService#evaluate},
 * action {@code "VIEW"})은 그대로 유지한다 - 제거된 테이블과 무관하게
 * 항상 유효한 보안 경계이며, 거부된 사용자는 계속 {@link ExtractionOutcome.Kind#DENIED}를
 * 받는다. 인가를 통과한 요청만 아래의 명시적 "미구현" 결과를 받는다 -
 * Fail Closed 원칙(허용되지 않은 것은 기본적으로 거부/미처리)을 그대로
 * 지킨다.</p>
 *
 * <p>실제 Production 진입점(Controller/Kafka Consumer)이 이 Class를 호출하는
 * 경로는 아직 없다(M10/M11에서 배선 예정) - 이 교정은 기존 동작을 깨지
 * 않는다.</p>
 */
@Service
public class ContentExtractionService {

    private static final String VIEW_ACTION = "VIEW";
    private static final String NOT_YET_IMPLEMENTED_REASON =
            "embedding index pipeline not yet implemented (V006 removed the legacy plaintext store; "
                    + "pending M08 real Google Drive connector and M11 embedding/indexing orchestration)";

    private final EffectivePermissionService effectivePermissionService;

    public ContentExtractionService(EffectivePermissionService effectivePermissionService) {
        this.effectivePermissionService = effectivePermissionService;
    }

    /**
     * 인가를 확인한 뒤, 항상 명시적인 "아직 사용할 수 없음"(비-{@code SUCCESS})
     * 결과를 반환한다 - V006 이후 이 Class가 쓸 수 있는 평문 저장소가 더 이상
     * 없기 때문이다. 어떤 경로도 Byte를 Fetch하거나 Parser를 호출하거나
     * {@code document_embedding_index}에 쓰지 않는다(그 실제 구현은 M08/M11).
     */
    public ExtractionOutcome extract(UserContext user, Long documentId) {
        PolicyDecision decision = effectivePermissionService.evaluate(user, documentId, VIEW_ACTION, null);
        if (decision.isDenied()) {
            return ExtractionOutcome.denied(decision.reasonCode().name());
        }
        return ExtractionOutcome.rejected(NOT_YET_IMPLEMENTED_REASON);
    }

    public record ExtractionOutcome(Kind kind, String detail) {

        public enum Kind {
            SUCCESS,
            DENIED,
            CONFLICT,
            SUPERSEDED,
            REJECTED,
            FAILED
        }

        static ExtractionOutcome denied(String reasonCode) {
            return new ExtractionOutcome(Kind.DENIED, reasonCode);
        }

        static ExtractionOutcome rejected(String detail) {
            return new ExtractionOutcome(Kind.REJECTED, detail);
        }
    }
}
