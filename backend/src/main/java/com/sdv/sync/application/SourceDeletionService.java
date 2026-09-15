package com.sdv.sync.application;

import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import org.springframework.stereotype.Service;

/**
 * F-BE-066 (M09A 신규). Source 문서 한 건을 "더 이상 유효한 Catalog 후보 아님"으로
 * 은퇴(Retire)시키는 Use Case(SYN-003) - v1.4 교정(`docs/spec/SDV_v3.2_CORE_SPEC.md`
 * §2A.11): 논리적 삭제(State 전이)뿐 아니라 그 문서의 {@code document_embedding_index}
 * (V006) 행까지 함께 정리해야 한다 - V006 이후에는 평문 Content 행 자체가 존재하지
 * 않으므로({@code document_extracted_content}는 V006이 제거했다), 이 Service는
 * 어떤 평문 Content 행에도 의존하지 않는다(존재 자체를 가정하지 않는다).
 *
 * <p>{@link AbstractSourceSyncJob}이 REMOVED_OR_ACCESS_LOST(삭제/접근상실 - 구분
 * 불가)와 TRASHED(Google 휴지통) 두 경로 모두에서 이 Method 하나를 재사용한다 -
 * "실제로 지워졌는가"의 최종 판단(사유 문자열)은 호출자가 넘기고, 이 Service는
 * 그 사유와 무관하게 동일한 은퇴 절차만 담당한다. 이 Class 자체는 Transaction
 * 경계를 갖지 않는다 - 호출자({@link AbstractSourceSyncJob#applyPage})의
 * {@code @Transactional} 범위 안에서 호출된다.</p>
 */
@Service
public class SourceDeletionService {

    private static final String STATE_DELETED = "DELETED";

    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourcePermissionJpaRepository sourcePermissionJpaRepository;

    public SourceDeletionService(SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourcePermissionJpaRepository sourcePermissionJpaRepository) {
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourcePermissionJpaRepository = sourcePermissionJpaRepository;
    }

    /**
     * 이미 이미 DELETED 상태면 아무것도 하지 않고 {@code false}를 반환한다(멱등 -
     * 중복 Trigger/재시도가 같은 문서를 두 번 은퇴시키거나 중복 알림을 만들지
     * 않는다). 실제로 은퇴시켰으면 {@code true} - 호출자가 그때만 Outbox 알림을
     * 기록한다.
     */
    public boolean handleDeleted(SourceDocumentEntity entity) {
        if (STATE_DELETED.equals(entity.getState())) {
            return false;
        }
        entity.markDeleted();
        sourcePermissionJpaRepository.deleteByDocumentId(entity.getId());
        sourceDocumentJpaRepository.deleteEmbeddingIndexForDocument(entity.getId());
        return true;
    }
}
