package com.sdv.policy.application;

import com.sdv.common.exception.NotFoundException;
import com.sdv.common.model.UserContext;
import com.sdv.policy.domain.SecurityLevel;
import com.sdv.policy.infrastructure.persistence.entity.DocumentSecurityLabelEntity;
import com.sdv.policy.infrastructure.persistence.repository.SecurityLabelJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * F-BE-132. 문서 보안 등급(Security Label) get/set(POL-002, POL-006).
 *
 * <p>{@code document_security_labels}에 행이 없으면 {@link #getSecurityLevel}은
 * {@link Optional#empty()}를 반환한다 - 호출자({@code EffectivePermissionService})가
 * 이를 "라벨 없음 = Fail Closed가 필요한 지점(AI Usage Policy 등)"으로 해석한다.
 * 이 Service 자체는 "라벨 없음"을 자동으로 특정 {@link SecurityLevel}로
 * 대체하지 않는다 - 가짜 데이터를 만들지 않는다.</p>
 *
 * <p><b>계정 경계(M05 후속 교정):</b> 이 Service는 재사용 가능한 공개(Public)
 * Application Service이므로, {@code documentId}만으로 원시(Raw) ID 조회를
 * 수행하지 않는다 - 모든 호출은 신뢰 가능한 {@link UserContext}를 넘겨야 하며,
 * {@code source_documents → source_connections.owner_subject} 소유자 경계로
 * 문서를 다시 검증한 뒤에만 라벨을 조회/변경한다({@code EffectivePermissionService}가
 * 자기 자신의 문서 조회를 이미 Owner-Scoped로 하는 것과 동일한 경계 -
 * {@code SourceDocumentJpaRepository.findByIdAndOwnerSubject}를 재사용한다).
 * 존재하지 않는 문서와 다른 계정 소유 문서는 외부에서 구분할 수 없다 - 둘 다
 * 동일한 {@link NotFoundException}이다(M04 {@code SourceConnectionService} 패턴과
 * 동일). 인가되지 않은 호출은 라벨을 절대 만들거나 바꾸지 못한다. 소유자
 * 확인은 이 시스템이 이미 갖고 있는 최소한의 계정 경계일 뿐이다 - 별도의
 * 라벨 관리 권한 모델(예: "라벨은 관리자만 변경 가능")은 v3.2가 정의하지
 * 않으므로 여기서 발명하지 않는다(그런 추가 제약이 필요하면 별도 SPEC GAP).</p>
 */
@Service
public class SecurityLabelService {

    private final SecurityLabelJpaRepository securityLabelJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;

    public SecurityLabelService(SecurityLabelJpaRepository securityLabelJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository) {
        this.securityLabelJpaRepository = securityLabelJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
    }

    /** {@code user}가 소유한 문서가 아니면(또는 존재하지 않으면) {@link NotFoundException}. */
    @Transactional(readOnly = true)
    public Optional<SecurityLevel> getSecurityLevel(UserContext user, Long documentId) {
        requireOwnedDocument(user, documentId);
        return securityLabelJpaRepository.findById(documentId)
                .map(entity -> SecurityLevel.valueOf(entity.getSecurityLevel()));
    }

    /**
     * 라벨을 생성하거나(Insert) 갱신한다(Update) - Origin은 {@code SOURCE_MAPPING}/{@code MANUAL}이다.
     * {@code user}가 소유한 문서가 아니면(또는 존재하지 않으면) {@link NotFoundException}이며,
     * 어떤 라벨도 만들거나 바꾸지 않는다.
     */
    @Transactional
    public void setLabel(UserContext user, Long documentId, SecurityLevel securityLevel, String origin,
            String sourceLabel) {
        requireOwnedDocument(user, documentId);
        DocumentSecurityLabelEntity entity = securityLabelJpaRepository.findById(documentId)
                .orElseGet(() -> new DocumentSecurityLabelEntity(documentId, securityLevel.name(), origin,
                        sourceLabel));
        entity.update(securityLevel.name(), origin, sourceLabel);
        securityLabelJpaRepository.save(entity);
    }

    private void requireOwnedDocument(UserContext user, Long documentId) {
        Objects.requireNonNull(user, "user must not be null");
        if (user.subject() == null || user.subject().isBlank()) {
            throw new IllegalArgumentException("user.subject must not be blank");
        }
        sourceDocumentJpaRepository.findByIdAndOwnerSubject(documentId, user.subject())
                .orElseThrow(() -> new NotFoundException("Document not found"));
    }
}
