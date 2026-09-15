package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.DocumentShareRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * M10B 신규 - {@code document_share_recipients}(자식 테이블) 저장 전용 최소
 * Repository. 다른 Source 자식 테이블({@code SourceOAuthTokenJpaRepository},
 * {@code SourceSyncCursorJpaRepository})과 같은 관례 - {@code
 * DocumentShareJpaRepository}가 읽기/판단 질의를 전담하고, 이 Repository는
 * {@code SourceSharingService}가 새 수신자 행을 {@code saveAll}로 쓸 때만 쓴다.
 */
public interface DocumentShareRecipientJpaRepository extends JpaRepository<DocumentShareRecipientEntity, Long> {
}
