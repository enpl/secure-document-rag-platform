package com.sdv.event.infrastructure;

import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M09A 초점 검증(Section 7, 카테고리 8) - Kafka 없이 실제 Testcontainers
 * PostgreSQL + 실제 Spring이 관리하는(진짜 Proxy 경유, @Transactional이 실제로
 * 적용된) {@link OutboxPublishWriter} Bean으로 경계 있는(Bounded) 재시도/
 * 종결 실패/복구 상태 전이 + Claim 소유권 Fencing(V009)을 검증한다({@link
 * OutboxEventPublisher} 자체(실제 Broker 필요)와 분리 - 이 Test는 그
 * Publisher가 호출하는 DB Writer 절반만 본다).
 *
 * <p>모든 Test는 먼저 {@link OutboxPublishWriter#claimBatch}로 행을
 * {@code PUBLISHING}으로 전이시키고 그 결과로 받은(Claim Token이 채워진)
 * Entity를 이후 {@code handleFailure}/{@code markPublished}에 그대로
 * 넘긴다 - 실제 {@link OutboxEventPublisher#publishOne}도 항상 이 순서다.
 * Claim 전의(Token이 없는) Entity를 재사용하면 Fencing 때문에 아무 효과가
 * 없다는 것 자체가 이 Test들이 증명하는 계약이다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class OutboxPublishWriterTest {

    @Autowired
    private OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired
    private OutboxPublishWriter writer;

    @Test
    void aFailureWithAttemptsRemainingRetriesWithABackoffDelayInsteadOfFailingTerminally() {
        Instant before = Instant.now();
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent());
        OutboxEventEntity claimed = claimViaWriter(saved.getId());

        boolean terminal = writer.handleFailure(claimed, "kafka send failed: TimeoutException", 5);

        assertThat(terminal).isFalse();
        OutboxEventEntity reloaded = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).as("bounded backoff must push the retry into the future")
                .isAfter(before);
        assertThat(reloaded.getLastError()).contains("TimeoutException");
        assertThat(reloaded.getClaimToken()).as("PENDING again - the claim token is cleared").isNull();
    }

    @Test
    void theFinalAllowedFailureBecomesTerminallyFailedAndStaysVisible() {
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent());
        OutboxEventEntity claimed = claimViaWriter(saved.getId());

        boolean terminal = writer.handleFailure(claimed, "kafka send failed: TimeoutException", 1); // maxAttempts=1

        assertThat(terminal).as("the 1st and only allowed attempt reaches maxAttempts=1 and must become terminal")
                .isTrue();
        OutboxEventEntity reloaded = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventEntity.STATUS_FAILED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("TimeoutException");
        // 종결 실패는 삭제되지 않는다 - 여전히 findAll/조회로 보인다(숨겨지지도, 사라지지도 않는다).
        assertThat(outboxEventJpaRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void claimBatchNeverReturnsAnAlreadyTerminallyFailedRow() {
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent());
        OutboxEventEntity claimed = claimViaWriter(saved.getId());
        writer.handleFailure(claimed, "boom", 1); // maxAttempts=1 -> 즉시 종결.
        assertThat(outboxEventJpaRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventEntity.STATUS_FAILED);

        List<OutboxEventEntity> claimedAgain = writer.claimBatch(50);

        assertThat(claimedAgain).extracting(OutboxEventEntity::getId).doesNotContain(saved.getId());
    }

    @Test
    void staleClaimsAreRecoveredForReclaimByAnotherPublisherInstance() {
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent());
        claimViaWriter(saved.getId());
        assertThat(outboxEventJpaRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventEntity.STATUS_PUBLISHING);

        // 이 시점에 Publisher Instance가 죽었다고 가정 - 아무도 markPublished/handleFailure를 부르지 않는다.
        // staleClaimSeconds=0 - "0초보다 오래된" 조건은 방금 Commit된 claimed_at보다 항상 참이다
        // (실제 벽시계 흐름만으로 충분하다 - 이 Test는 통제된 Clock 주입 없이도 결정론적이다).
        writer.recoverStaleClaims(0);

        OutboxEventEntity recovered = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PENDING);
        assertThat(recovered.getClaimToken()).as("복구 시 원래 Token도 지운다").isNull();

        List<OutboxEventEntity> secondClaim = writer.claimBatch(50);
        assertThat(secondClaim).as("a recovered row must be reclaimable again")
                .extracting(OutboxEventEntity::getId).contains(saved.getId());
    }

    @Test
    void markPublishedIsANoOpIfTheRowIsNoLongerInThePublishingState() {
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent()); // status=PENDING, not PUBLISHING

        writer.markPublished(saved.getId(), UUID.randomUUID()); // 어떤 Token을 넘겨도 PENDING 행에는 효과 없음

        OutboxEventEntity reloaded = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("markPublished only transitions rows actually in PUBLISHING (idempotent guard)")
                .isEqualTo(OutboxEventEntity.STATUS_PENDING);
    }

    /**
     * Claim 소유권 Fencing(V009) 핵심 시나리오 - Publisher A가 Claim한 뒤 죽고,
     * 복구된 행을 Publisher B가 재Claim(새 Token)한다. A가 (자신은 아직 죽은 줄
     * 모르고) 옛 Token으로 보내는 뒤늦은 markPublished/handleFailure Callback은
     * B의 진행 중인 Claim을 전혀 건드리지 못해야 한다(0행 영향, 조용한 No-op).
     */
    @Test
    void aStaleClaimTokenCanNeverMutateAReclaimedRow() {
        OutboxEventEntity saved = outboxEventJpaRepository.save(newEvent());
        OutboxEventEntity claimedByA = claimViaWriter(saved.getId());
        UUID staleTokenFromA = claimedByA.getClaimToken();
        assertThat(staleTokenFromA).isNotNull();

        writer.recoverStaleClaims(0); // A가 죽은 것으로 간주 - 복구
        OutboxEventEntity claimedByB = claimViaWriter(saved.getId()); // B가 재Claim(새 Token)
        assertThat(claimedByB.getClaimToken()).isNotEqualTo(staleTokenFromA);

        // A의 뒤늦은 성공 Callback - 옛(Stale) Token으로 markPublished를 시도한다.
        writer.markPublished(saved.getId(), staleTokenFromA);
        OutboxEventEntity afterStaleSuccess = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterStaleSuccess.getStatus())
                .as("A's stale-token success callback must not mutate B's in-progress claim")
                .isEqualTo(OutboxEventEntity.STATUS_PUBLISHING);
        assertThat(afterStaleSuccess.getClaimToken()).isEqualTo(claimedByB.getClaimToken());

        // A의 뒤늦은 실패 Callback도 마찬가지로 무시돼야 한다 - attempts가 A 때문에 늘지 않는다.
        boolean terminalFromStaleFailure = writer.handleFailure(claimedByA, "kafka send failed: TimeoutException", 1);
        assertThat(terminalFromStaleFailure)
                .as("a stale terminal callback that updates zero rows must not report terminal ownership")
                .isFalse();
        OutboxEventEntity afterStaleFailure = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterStaleFailure.getStatus())
                .as("A's stale-token failure callback must not mutate B's in-progress claim either")
                .isEqualTo(OutboxEventEntity.STATUS_PUBLISHING);
        assertThat(afterStaleFailure.getAttempts()).isZero();

        // B는 정상적으로 자신의(현재) Token으로 완료할 수 있다.
        writer.markPublished(saved.getId(), claimedByB.getClaimToken());
        OutboxEventEntity finalState = outboxEventJpaRepository.findById(saved.getId()).orElseThrow();
        assertThat(finalState.getStatus()).isEqualTo(OutboxEventEntity.STATUS_PUBLISHED);
    }

    /** {@link OutboxPublishWriter#claimBatch}로 방금 저장한(즉시 Claim 가능한) 행을 PUBLISHING으로 전이시키고, Token이 채워진 결과 Entity를 돌려준다. */
    private OutboxEventEntity claimViaWriter(Long id) {
        List<OutboxEventEntity> claimed = writer.claimBatch(50);
        return claimed.stream().filter(event -> event.getId().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("expected id " + id + " to be claimable"));
    }

    private static OutboxEventEntity newEvent() {
        UUID eventId = UUID.randomUUID();
        return new OutboxEventEntity(eventId, "SOURCE_DOCUMENT_CHANGED",
                Map.of("eventId", eventId.toString(), "sourceId", "1"), "source:1:doc:x", Instant.now());
    }
}
