package com.sdv.rag.infrastructure.persistence.repository;

import com.sdv.rag.infrastructure.persistence.entity.ProcessedEventEntity;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M11 후속 교정 검증(2026-09-16, 이번 작업 지시사항 C, "Preserve completion history
 * under duplicates and partial failure") - 실제 Testcontainers PostgreSQL로 {@link
 * ProcessedEventJpaRepository#insertIfAbsent}의 원자적 First-Writer-Wins 보장을
 * 검증한다. 이 Test Class 자체는 {@code @Transactional}을 쓰지 않는다 - 각 Thread가
 * 독립적으로 실제 Commit되는 별도 Transaction을 만들어야 진짜 동시 삽입 경합을
 * 재현할 수 있다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class ProcessedEventJpaRepositoryTest {

    @Autowired
    private ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * "Concurrent duplicate completions have a stable, documented outcome" - 두 Thread가
     * 정확히 같은 (eventId, consumerName) Key로 동시에 완료를 기록하려 한다. 예외 없이
     * 정확히 하나만 실제로 삽입되고, 다른 하나는 조용히 0을 반환한다(First-Writer-Wins) -
     * 어느 쪽이 이겼는지는 결정하지 않지만, 결과가 항상 정확히 하나의 일관된 행이라는
     * 점은 결정적이다.
     */
    @Test
    void concurrentInsertAttemptsForTheSameKeyResultInExactlyOneRowWithoutExceptions() throws Exception {
        UUID eventId = UUID.randomUUID();
        String consumerName = "rag-index-orchestrator";
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Future<Integer> first = executor.submit(() -> attemptInsert(eventId, consumerName, "INDEXED", null, 1L,
                bothReady, go));
        Future<Integer> second = executor.submit(() -> attemptInsert(eventId, consumerName, "SKIPPED_NO_TEXT",
                "AI_SERVICE_NO_TEXT", 1L, bothReady, go));

        assertThat(bothReady.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        int firstResult = first.get(10, TimeUnit.SECONDS);
        int secondResult = second.get(10, TimeUnit.SECONDS);

        assertThat(firstResult + secondResult)
                .as("exactly one of the two concurrent attempts must have actually inserted the row")
                .isEqualTo(1);
        ProcessedEventEntity persisted = processedEventJpaRepository
                .findById(new ProcessedEventEntity.Key(eventId, consumerName)).orElseThrow();
        assertThat(persisted.getOutcome()).isIn("INDEXED", "SKIPPED_NO_TEXT");
    }

    /**
     * "A late terminal-failure attempt must not replace an existing successful disposition" -
     * First-Writer-Wins이 정확히 이 요구사항을 만족한다: 나중에 도착한 시도가 실패든
     * 성공이든 상관없이, 이미 기록된 행을 절대 덮어쓰지 않는다.
     */
    @Test
    void aLateAttemptCanNeverReplaceAnAlreadyRecordedDisposition() {
        UUID eventId = UUID.randomUUID();
        String consumerName = "rag-index-orchestrator";
        Instant now = Instant.now();
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        int firstInsert = template.execute(status ->
                processedEventJpaRepository.insertIfAbsent(eventId, consumerName, "INDEXED", null, 1L, now));
        int lateFailureAttempt = template.execute(status -> processedEventJpaRepository.insertIfAbsent(eventId,
                consumerName, "FAILED_TERMINAL", "BOUNDED_RETRY_EXHAUSTED", 1L, now.plusSeconds(30)));

        assertThat(firstInsert).isEqualTo(1);
        assertThat(lateFailureAttempt).as("a late terminal failure must be a silent no-op, not an overwrite")
                .isZero();
        ProcessedEventEntity persisted = processedEventJpaRepository
                .findById(new ProcessedEventEntity.Key(eventId, consumerName)).orElseThrow();
        assertThat(persisted.getOutcome())
                .as("the original successful disposition must remain exactly as first recorded")
                .isEqualTo("INDEXED");
    }

    private int attemptInsert(UUID eventId, String consumerName, String outcome, String reasonCode, Long documentId,
            CountDownLatch bothReady, CountDownLatch go) throws InterruptedException {
        bothReady.countDown();
        assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Integer result = template.execute(status -> processedEventJpaRepository.insertIfAbsent(eventId, consumerName,
                outcome, reasonCode, documentId, Instant.now()));
        return result == null ? 0 : result;
    }
}
