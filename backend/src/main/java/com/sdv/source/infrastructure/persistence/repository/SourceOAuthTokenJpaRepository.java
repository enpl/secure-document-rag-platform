package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceOAuthTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * M08 MVP OAuth ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@code source_oauth_tokens}
 * (V007) Persistence. {@link com.sdv.source.infrastructure.google.GoogleTokenService}만
 * 이 Repository를 호출한다 - {@code source_id}가 유일한 실제 조회 Key다({@code
 * SourceTokenStore} Port 자체가 항상 {@code sourceId}로만 동작하므로, {@link
 * com.sdv.source.infrastructure.persistence.entity.SourceOAuthTokenEntity#getTokenRef()}는
 * PK/Cross-check 용도일 뿐 조회 Key가 아니다).
 */
public interface SourceOAuthTokenJpaRepository extends JpaRepository<SourceOAuthTokenEntity, java.util.UUID> {

    Optional<SourceOAuthTokenEntity> findBySourceId(Long sourceId);

    /**
     * MVP-17({@code docs/plan/SDV_MVP_DEFERRED.md}) - Source 목록 화면이 "생성됐지만
     * 아직 연결 안 됨"과 "연결됨"을 구분해 보여주기 위한, 부작용 없는(Side-Effect-Free)
     * 존재 확인 전용 조회다. {@link com.sdv.source.infrastructure.google.GoogleTokenService#load}와
     * 달리 복호화/Google Refresh Call/재암호화 쓰기를 전혀 하지 않는다 - 단순 목록 조회가
     * 매번 이런 부작용을 트리거해서는 안 된다는 이 작업 지시사항을 그대로 지킨다.
     */
    boolean existsBySourceId(Long sourceId);

    @Modifying
    @Query("DELETE FROM SourceOAuthTokenEntity e WHERE e.sourceId = :sourceId")
    int deleteBySourceId(@Param("sourceId") Long sourceId);

    /**
     * M08 후속 교정(Refresh/Disconnect 경합) - Bounded Refresh가 받아온 새 Credential을,
     * Refresh를 시작한 시점에 실제로 읽었던 바로 그 세대(Generation, {@code tokenRef}+
     * {@code rowVersion}으로 식별)가 지금도 여전히 그대로일 때만 발행한다. 한 Native
     * {@code UPDATE ... WHERE token_ref = ? AND row_version = ?}로 "그 사이 삭제됨"과
     * "그 사이 다른 쓰기로 이미 교체됨"을 같은 방식(영향받은 행 수 0)으로 함께 잡아낸다 -
     * JPA Entity를 거치지 않으므로 1차 캐시 Staleness 위험이 전혀 없다({@link
     * com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository#lockAndReadCurrentOwnershipState}와
     * 같은 이유).
     *
     * <p>반환값이 1이면 발행 성공(그 세대가 그대로였다) - {@code row_version}은 자동으로
     * 1 증가한다(기존 JPA {@code @Version} Entity와 동일한 낙관적 잠금 의미를 Native
     * SQL 수준에서 재현). 0이면 발행을 거부해야 한다 - 호출자가 "Discarded 새 Credential을
     * 절대 반환하지 않는다"는 계약을 지킨다.</p>
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE source_oauth_tokens SET owner_subject = :ownerSubject, format_version = :formatVersion, "
            + "key_id = :keyId, nonce = :nonce, ciphertext = :ciphertext, row_version = row_version + 1, "
            + "updated_at = :updatedAt "
            + "WHERE token_ref = :tokenRef AND row_version = :expectedRowVersion", nativeQuery = true)
    int replaceIfGenerationUnchanged(@Param("tokenRef") UUID tokenRef,
            @Param("expectedRowVersion") long expectedRowVersion, @Param("ownerSubject") String ownerSubject,
            @Param("formatVersion") int formatVersion, @Param("keyId") String keyId, @Param("nonce") byte[] nonce,
            @Param("ciphertext") byte[] ciphertext, @Param("updatedAt") Instant updatedAt);
}
