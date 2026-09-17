package com.sdv.identity.infrastructure.persistence.repository;

import com.sdv.identity.infrastructure.persistence.entity.SdvUserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SdvUserJpaRepository extends JpaRepository<SdvUserEntity, Long> {
    Optional<SdvUserEntity> findByIssuerAndSubject(String issuer, String subject);

    List<SdvUserEntity> findAllByIdInAndIssuer(Collection<Long> ids, String issuer);

    long countByIssuerAndNormalizedLoginIdAndActiveTrue(String issuer, String normalizedLoginId);

    @Modifying
    @Query(value = "INSERT INTO sdv_users (issuer, subject, login_id, normalized_login_id, display_name) "
            + "VALUES (:issuer, :subject, :loginId, :normalizedLoginId, :displayName) "
            + "ON CONFLICT (issuer, subject) DO UPDATE SET login_id = EXCLUDED.login_id, "
            + "normalized_login_id = EXCLUDED.normalized_login_id, display_name = EXCLUDED.display_name, "
            + "last_seen_at = CURRENT_TIMESTAMP", nativeQuery = true)
    void observeValidatedIdentity(@Param("issuer") String issuer, @Param("subject") String subject,
            @Param("loginId") String loginId, @Param("normalizedLoginId") String normalizedLoginId,
            @Param("displayName") String displayName);

    @Query("SELECT u FROM SdvUserEntity u WHERE u.issuer = :issuer AND u.active = true "
            + "AND LOWER(u.loginId) LIKE :pattern ESCAPE '\\' "
            + "AND (SELECT COUNT(c) FROM SdvUserEntity c WHERE c.issuer = u.issuer "
            + "AND c.normalizedLoginId = u.normalizedLoginId AND c.active = true) = 1 "
            + "ORDER BY u.normalizedLoginId ASC, u.id ASC")
    List<SdvUserEntity> searchDirectory(@Param("issuer") String issuer, @Param("pattern") String pattern,
            Pageable pageable);

    @Query("SELECT u FROM SdvUserEntity u WHERE u.issuer = :issuer "
            + "AND (:pattern = '' OR LOWER(u.loginId) LIKE :pattern ESCAPE '\\') "
            + "ORDER BY u.normalizedLoginId ASC, u.id ASC")
    Page<SdvUserEntity> searchAdmin(@Param("issuer") String issuer, @Param("pattern") String pattern,
            Pageable pageable);
}
