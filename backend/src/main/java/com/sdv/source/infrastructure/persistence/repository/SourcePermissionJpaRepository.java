package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F-BE-037. {@code source_permissions} Persistence/조회(SRC-005, SYN-003).
 *
 * <p>{@code findByDocumentId}/{@code deleteByDocumentId}는 Manifest가 지정한
 * canonical 메서드명을 그대로 사용한다. {@code deleteByDocumentId}는 M05에서
 * 아직 어떤 Sync/Replace 흐름도 호출하지 않지만(Sync는 M10 범위), Manifest가
 * 이 Repository의 대표 메서드로 명시적으로 지정했으므로 계약의 일부로
 * 포함한다 - 추측성 확장이 아니다.</p>
 */
public interface SourcePermissionJpaRepository extends JpaRepository<SourcePermissionEntity, Long> {

    List<SourcePermissionEntity> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
