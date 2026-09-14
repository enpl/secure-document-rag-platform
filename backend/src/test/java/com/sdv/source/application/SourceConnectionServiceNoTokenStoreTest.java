package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 순수 단위 테스트(Mockito, Spring Context 없음) - M08 MVP OAuth
 * ({@code docs/spec/SDV_M08_TOKEN_CONTRACT.md})가 실제 {@code SourceTokenStore}
 * Bean({@code GoogleTokenStoreAdapter})을 항상 Component Scan으로 등록하게 되면서,
 * "Token Store Bean 자체가 없다"는 상태는 더 이상 {@code @SpringBootTest} Full
 * Context로는 재현할 수 없다.
 *
 * <p>그래도 {@link SourceConnectionService#disconnect}의 이 Fail-Closed 분기(Token
 * Store 자체가 없는데 지울 {@code tokenRef}가 있으면 예외를 던져 전체 Transaction을
 * Rollback시킨다)는 여전히 유효한 방어 코드다 - Optional Port가 비어있을 수 있는
 * 모든 경우를 이 하나의 분기가 계속 안전하게 막아야 한다. Spring Context 없이
 * {@code Optional.empty()}를 생성자에 직접 주입해 계속 검증한다(과거 이 시나리오를
 * {@code @SpringBootTest}로 담당하던 {@code SourceConnectionServiceAuditTransactionTest
 * #nonBlankTokenRefWithNoStoreAvailableFailsClosedLeavingStateUnchanged}는 이제 실제로
 * 항상 등록되는 Store가 있는 현실을 반영하도록 교정됐다 - 그 파일의 {@code
 * nonBlankTokenRefWithNoMatchingEncryptedRowStillDisconnectsSafely} 참고).</p>
 */
@ExtendWith(MockitoExtension.class)
class SourceConnectionServiceNoTokenStoreTest {

    @Mock
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Mock
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Mock
    private SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    @Mock
    private SourcePersistenceMapper sourcePersistenceMapper;
    @Mock
    private AuditService auditService;

    @Test
    void nonBlankTokenRefWithNoStoreBeanAtAllFailsClosedWithoutMutatingOrAuditing() {
        SourceConnectionService service = new SourceConnectionService(sourceConnectionJpaRepository,
                sourceDocumentJpaRepository, sourceOAuthTokenJpaRepository, sourcePersistenceMapper, Optional.empty(),
                auditService);
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL",
                "owner-no-store-bean");
        entity.updateTokenRef("pre-existing-token-ref");
        when(sourceConnectionJpaRepository.findByIdAndOwnerSubject(1L, "owner-no-store-bean"))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.disconnect(1L, "owner-no-store-bean")).isInstanceOf(IllegalStateException.class);

        // 예외가 sourceTokenStore Empty-Check 시점에 즉시 던져져야 한다 - 그 이후 단계
        // (문서 전이/감사 기록)는 절대 실행되지 않아야 한다.
        verifyNoInteractions(sourceDocumentJpaRepository, auditService);
    }
}
