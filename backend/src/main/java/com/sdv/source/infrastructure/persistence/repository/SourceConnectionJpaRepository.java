package com.sdv.source.infrastructure.persistence.repository;

import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * source_connections에 대한 Spring Data JPA Repository.
 *
 * 이 단계에서는 커스텀 조회 메서드를 추가하지 않는다.
 */
public interface SourceConnectionJpaRepository extends JpaRepository<SourceConnectionEntity, Long> {
}
