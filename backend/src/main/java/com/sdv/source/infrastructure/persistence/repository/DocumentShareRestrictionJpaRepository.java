package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.DocumentShareRestrictionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * M10B 보안 교정 신규(V011) - {@code document_share_restrictions} 조회/저장.
 * 파일의 정규 신원({@code source_id}+{@code document_id})으로만 조회한다 -
 * {@code document_shares.id}로는 절대 조회하지 않는다(그 값은 공유를 다시 게시할
 * 때마다 바뀐다 - 이 Table이 존재하는 바로 그 이유).
 */
public interface DocumentShareRestrictionJpaRepository extends JpaRepository<DocumentShareRestrictionEntity, Long> {

    Optional<DocumentShareRestrictionEntity> findBySourceIdAndDocumentId(Long sourceId, Long documentId);
}
