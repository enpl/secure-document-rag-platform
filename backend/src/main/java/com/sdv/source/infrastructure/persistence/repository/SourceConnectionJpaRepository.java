package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
