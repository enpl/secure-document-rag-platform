package com.sdv.security.infrastructure.persistence.repository;

import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SecurityFindingJpaRepository extends JpaRepository<SecurityFindingEntity, Long> {
    Optional<SecurityFindingEntity> findFirstByTypeAndDocumentId(String type, Long documentId);
    Page<SecurityFindingEntity> findByStatus(String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SecurityFindingEntity> findAllByTypeAndDocumentIdOrderByIdAsc(String type, Long documentId);

    @Query("SELECT f FROM SecurityFindingEntity f WHERE EXISTS (SELECT s.id FROM DocumentShareEntity s "
            + "WHERE s.documentId = f.documentId AND s.revokedAt IS NULL)")
    Slice<SecurityFindingEntity> findPublished(Pageable pageable);

    @Query("SELECT f FROM SecurityFindingEntity f WHERE f.status = :status "
            + "AND EXISTS (SELECT s.id FROM DocumentShareEntity s "
            + "WHERE s.documentId = f.documentId AND s.revokedAt IS NULL)")
    Slice<SecurityFindingEntity> findPublishedByStatus(@Param("status") String status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM SecurityFindingEntity f WHERE f.id = :id "
            + "AND EXISTS (SELECT s.id FROM DocumentShareEntity s "
            + "WHERE s.documentId = f.documentId AND s.revokedAt IS NULL)")
    Optional<SecurityFindingEntity> findPublishedByIdForUpdate(@Param("id") Long id);
}
