package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * source_connections에 대한 Spring Data JPA Repository.
 *
 * 이 작업(M04)이 실제로 필요로 하는 최소한의 소유자 범위(Owner-Scoped) 조회만
 * 추가한다 - {@code SourceConnectionRepository}/
 * {@code SourceConnectionPersistenceAdapter}는 v3.2 Manifest가 정의하지 않으므로
 * 만들지 않는다.
 */
public interface SourceConnectionJpaRepository extends JpaRepository<SourceConnectionEntity, Long> {

    List<SourceConnectionEntity> findAllByOwnerSubject(String ownerSubject);

    Optional<SourceConnectionEntity> findByIdAndOwnerSubject(Long id, String ownerSubject);

    /**
     * M08 MVP OAuth({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md}) - {@code
     * GoogleTokenService.store/revoke}가 {@code source_oauth_tokens}(자식) 행을
     * 쓰기 전에 이 Source(부모) 행을 먼저 {@code SELECT ... FOR UPDATE}로 잠그기
     * 위한 조회다. 이 Lock 순서(부모 Source 먼저, 자식 Token 나중)를 Token
     * 관련 모든 쓰기 경로가 일관되게 지키면, 서로 다른 Transaction이 두 테이블을
     * 반대 순서로 잠가 경합하는 옛 M06류 Lock Cycle이 재현되지 않는다
     * ({@code docs/plan/SDV_MVP_DEFERRED.md} MVP-06).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM SourceConnectionEntity e WHERE e.id = :id")
    Optional<SourceConnectionEntity> findByIdForUpdate(@Param("id") Long id);

    /**
     * M08 후속 교정(Refresh/Disconnect 경합) - {@link #findByIdForUpdate}만으로는 부족한
     * 경우를 위한 Native Query다. {@code GoogleTokenService.load}는 Network 호출(Google
     * Refresh) 동안 어떤 Lock도 잡지 않은 채 이 Source 행을 먼저(Unlocked) 읽는다 - 그
     * 호출 안에서 이미 이 Entity가 이 Transaction의 1차 캐시(Persistence Context)에
     * 올라가 있으므로, Network 호출이 끝난 뒤 같은 Transaction 안에서 {@code
     * findByIdForUpdate}를 다시 불러도 Hibernate가 Lock만 새로 걸고 Java 필드 값은
     * Refresh하지 않을 수 있다("Account for JPA's already-managed entities: acquiring a
     * DB lock alone is not evidence that a cached entity was refreshed" - 이 작업
     * 지시사항). {@code SourceConnectionEntity}는 {@code @Version}도 없어(이번 작업이
     * 관찰한 사실 그대로) Hibernate가 이를 자동으로 감지해주지도 않는다.
     *
     * <p>이 Native Query는 Entity Mapping/1차 캐시를 완전히 우회한다 - 항상 실제 DB
     * Column 값을 그대로 반환하고(Postgres {@code FOR UPDATE}로 Lock까지 한 번에 건다),
     * Refresh 발행 직전 재확인처럼 "방금 잠근 행이 실제로 지금 무슨 상태인지"를 절대
     * 의심할 필요가 없게 만든다. {@code SourceConnectionService.disconnect}처럼 Network
     * 호출 없이 곧바로 잠그는 경로(예: {@code GoogleTokenService.store}, 최초/재인증
     * 발행)는 이 Unlocked-Gap이 없으므로 기존 {@link #findByIdForUpdate}만으로 충분하다
     * - 이 Method는 그 Gap이 실제로 존재하는 호출자만 쓴다.</p>
     */
    @Query(value = "SELECT type, status, owner_subject AS ownerSubject, token_ref AS tokenRef "
            + "FROM source_connections WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<OwnershipStateView> lockAndReadCurrentOwnershipState(@Param("id") Long id);

    /** {@link #lockAndReadCurrentOwnershipState}의 Native Query Projection - Column Alias가 이 Getter 이름과 일치해야 한다. */
    interface OwnershipStateView {
        String getType();

        String getStatus();

        String getOwnerSubject();

        String getTokenRef();
    }
}
