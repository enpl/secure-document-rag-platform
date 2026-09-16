package com.sdv.rag.application;

import com.sdv.common.trace.TraceIdFilter;
import com.sdv.event.domain.IndexRequestedEvent;
import com.sdv.event.infrastructure.persistence.entity.OutboxEventEntity;
import com.sdv.event.infrastructure.persistence.repository.OutboxEventJpaRepository;
import com.sdv.rag.infrastructure.persistence.repository.ProcessedEventJpaRepository;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * M11 신규 - "A bounded, idempotent way to schedule already-published eligible
 * files when the pipeline is eventually enabled"(이 작업 지시사항 3번). Publisher/
 * Consumer가 기본 비활성화 상태로 시작하는 동안 이미 만들어졌던 활성 공유는
 * {@code SourceSharingService.createShare}가 그 시점에 존재하지도 않았던 이
 * 이벤트를 받을 기회가 전혀 없었다 - 이 Service는 그 간극을 운영자가 나중에
 * Pipeline을 켤 때 메꾸기 위한 것이다.
 *
 * <p>{@link #runOnce}는 매 호출마다 최대 {@code batchSize}개의 공유만 처리한다
 * (경계 있음, Bounded) - {@code afterShareId} Keyset Pagination으로 이미 확인한
 * 구간을 다시 스캔하지 않는다. {@link ProcessedEventJpaRepository#findSharesNeedingBackfill}이
 * "지금 현재 {@code source_version}에 대응하는 Embedding Generation이 실제로 존재하는"
 * 문서만 후보에서 제외한다(M11 후속 교정 - 과거 {@code processed_events} 이력이
 * 아니라 실제 현재 상태를 확인한다, 그 Method Javadoc 참고) - 이미 유효하게 색인된
 * 문서를 다시 큐에 넣지 않는다(멱등). 같은 문서에 대해 아직 소비되지 않은 Outbox
 * 요청이 이미 있으면 추가로 적재하지 않는다({@link
 * OutboxEventJpaRepository#existsPendingByPartitionKeyAndEventType}) - 겹치거나
 * 반복되는 호출이 중복 작업을 쌓지 않는다. 이 Service 자체는 자동으로 실행되지
 * 않는다(운영자가 명시적으로 호출) - {@code docs/runbooks/M11_INDEXING_ACTIVATION.md}
 * 참고.</p>
 */
@Service
public class IndexBackfillService {

    private final ProcessedEventJpaRepository processedEventJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final Clock clock;

    @Autowired
    public IndexBackfillService(ProcessedEventJpaRepository processedEventJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository) {
        this(processedEventJpaRepository, sourceDocumentJpaRepository, outboxEventJpaRepository, Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 주입하기 위한 패키지 전용 생성자. */
    IndexBackfillService(ProcessedEventJpaRepository processedEventJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            OutboxEventJpaRepository outboxEventJpaRepository, Clock clock) {
        this.processedEventJpaRepository = processedEventJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.clock = clock;
    }

    /**
     * @param afterShareId 이전 호출이 반환한 {@link BackfillResult#lastShareId()}(처음
     *         호출은 0) - 그 이후의 공유만 스캔한다.
     * @param batchSize 이번 호출에서 실제로 발행할 최대 Outbox 이벤트 수.
     * @return 이번 호출이 실제로 발행한 이벤트 수와 다음 호출에 넘길 Cursor.
     */
    @Transactional
    public BackfillResult runOnce(long afterShareId, int batchSize) {
        if (batchSize <= 0) {
            return new BackfillResult(0, afterShareId);
        }
        List<ProcessedEventJpaRepository.BackfillCandidate> candidates = processedEventJpaRepository
                .findSharesNeedingBackfill(afterShareId, batchSize);
        if (candidates.isEmpty()) {
            return new BackfillResult(0, afterShareId);
        }
        Instant now = clock.instant();
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        long lastShareId = afterShareId;
        int published = 0;
        for (ProcessedEventJpaRepository.BackfillCandidate candidate : candidates) {
            lastShareId = Math.max(lastShareId, candidate.getShareId());
            SourceDocumentEntity document = sourceDocumentJpaRepository.findById(candidate.getDocumentId())
                    .orElse(null);
            if (document == null) {
                continue;
            }
            IndexRequestedEvent event = new IndexRequestedEvent(UUID.randomUUID(), document.getSourceId(), null,
                    document.getId(), document.getSourceDocumentId(), document.getSourceVersion(), now, traceId);
            String partitionKey = "source:" + event.sourceId() + ":doc:" + event.externalDocumentId();
            // M11 후속 교정 - 겹치거나 반복되는 runOnce 호출이 아직 소비되지 않은 같은
            // 문서의 요청을 중복으로 쌓지 않는다(이 작업 지시사항 1번 "prevent
            // overlapping/repeated backfill calls from enqueueing duplicate work for the
            // same current generation").
            if (outboxEventJpaRepository.existsPendingByPartitionKeyAndEventType(partitionKey, event.eventType())) {
                continue;
            }
            outboxEventJpaRepository.save(new OutboxEventEntity(event.eventId(), event.eventType(),
                    event.toPayload(), partitionKey, now));
            published++;
        }
        return new BackfillResult(published, lastShareId);
    }

    public record BackfillResult(int publishedCount, long lastShareId) {
    }
}
