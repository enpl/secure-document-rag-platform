package com.sdv.source.application;

import com.sdv.audit.application.AuditService;
import com.sdv.common.exception.NotFoundException;
import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceConnectionAccessRevokedEvent;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.mapper.SourcePersistenceMapper;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * F-BE-025. Source 등록/연결 해제 Use Case(SRC-002, SRC-010).
 *
 * <p>계정 격리(Account Isolation)와 Disconnect의 Fail-Closed Token 제거를
 * 여기서 강제한다 - Controller는 HTTP 관심사만 다룬다. 이 클래스를 다른
 * Service 이름으로 대체하지 않는다.</p>
 *
 * <p><b>Transaction/Audit 정책(M04 후속 교정):</b> Source DB 변경과 그 성공
 * 감사(Audit) 기록은 반드시 같은 {@code @Transactional} 경계 안에서 함께
 * Commit되거나 함께 Rollback된다 - {@code auditService.record(...)}를 직접
 * 호출하고 예외를 삼키지 않는다. Audit 저장이 실패하면(예:
 * {@code AuditLogPersistenceAdapter}가 DB 예외를 던지면) 그 예외가 그대로
 * 전파되어 이 메서드가 실패하고, Source 행 변경을 포함한 Transaction
 * 전체가 Rollback된다 - "DB는 바뀌었는데 감사 기록은 없는" 상태도,
 * "감사에 실패했지만 이미 성공한 것으로 보고하는" 상태도 만들지 않는다.
 * 클라이언트에는 {@code GlobalExceptionHandler}의 기존 범용 예외 처리로
 * 도달한다(원본 예외 메시지/Stack Trace 미노출) - 이 Service가 별도 응답
 * 경로를 만들지 않는다. M03의 인증/인가 거부 감사(Security Filter 단계,
 * 아직 Transaction/Persistence Context가 시작되기 전)는 이 정책과 무관하며
 * 재설계하지 않는다.</p>
 *
 * <p><b>Token 삭제와 Commit/Rollback 경계의 근본적 한계:</b>
 * {@link SourceTokenStore#delete(Long)}가 외부 Token Provider 호출에
 * 성공한 뒤, 그 이후 단계(문서 전이, 감사 기록 등)에서 이 Transaction이
 * Rollback되면, DB의 {@code token_ref}는 Rollback으로 예전 값으로
 * 복원되지만 외부 Token은 이미 폐기되어 더 이상 유효하지 않을 수 있다 -
 * DB Rollback은 이미 완료된 외부(Cross-System) 부작용을 되돌리지 못한다.
 * 이는 단일 Local Transaction 경계의 근본적 한계이며, 이 Service는 여러
 * System에 걸친 원자성(Atomicity)을 보장한다고 주장하지 않는다. 향후 실제
 * Token Store 구현체는 {@code delete()}가 멱등적으로 재시도 가능해야
 * 한다(이미 없는 Token을 다시 지우려는 시도가 에러가 되지 않아야 한다) -
 * 그래야 이런 상황에서 다음 Disconnect 재시도가 안전하다.</p>
 */
@Service
public class SourceConnectionService {

    private static final String SUCCESS = "SUCCESS";
    private static final String OK = "OK";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceDocumentJpaRepository sourceDocumentJpaRepository;
    private final SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    private final SourcePersistenceMapper sourcePersistenceMapper;
    private final Optional<SourceTokenStore> sourceTokenStore;
    private final AuditService auditService;
    private final ApplicationEventPublisher applicationEventPublisher;

    public SourceConnectionService(
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceDocumentJpaRepository sourceDocumentJpaRepository,
            SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository,
            SourcePersistenceMapper sourcePersistenceMapper,
            Optional<SourceTokenStore> sourceTokenStore,
            AuditService auditService, ApplicationEventPublisher applicationEventPublisher) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceDocumentJpaRepository = sourceDocumentJpaRepository;
        this.sourceOAuthTokenJpaRepository = sourceOAuthTokenJpaRepository;
        this.sourcePersistenceMapper = sourcePersistenceMapper;
        this.sourceTokenStore = sourceTokenStore;
        this.auditService = auditService;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * 새 Source 연결을 만든다. {@code toCreate.getOwnerSubject()}는 호출자
     * (Controller/Mapper)가 이미 인증된 subject로 채운 값이어야 한다 - 이
     * Service는 그 값을 그대로 신뢰하고 저장할 뿐, Request에서 직접 값을
     * 받지 않는다(그 경계는 Controller/{@code SourceApiMapper}에 있다).
     *
     * <p>정상 반환은 Source 행 생성과 그 성공 감사 기록이 같은
     * Transaction으로 Commit될 예정임을 뜻한다 - 감사 실패는 이 메서드
     * 자체를 실패시킨다(클래스 문서의 Transaction/Audit 정책 참고).</p>
     */
    @Transactional
    public SourceConnection create(SourceConnection toCreate) {
        SourceConnectionEntity entity = sourcePersistenceMapper.toEntity(toCreate);
        SourceConnectionEntity saved = sourceConnectionJpaRepository.save(entity);
        SourceConnection created = sourcePersistenceMapper.toDomain(saved);

        auditService.record(created.getOwnerSubject(), "SOURCE_CREATED", "source:" + created.getId(), SUCCESS, OK,
                Map.of());

        return created;
    }

    /**
     * MVP-17({@code docs/plan/SDV_MVP_DEFERRED.md}) - 각 Source에 대해
     * {@code credentialPresent}(로컬 암호화 Credential 존재 여부)를 함께 계산해
     * 반환한다. {@link SourceOAuthTokenJpaRepository#existsBySourceId}만
     * 사용한다 - {@link SourceTokenStore#load}는 절대 호출하지 않는다(그 쪽은
     * 만료 시 실제 Google Refresh Call + 재암호화 DB 쓰기까지 수행하는 부작용이
     * 있어, 단순 목록 조회가 매번 그것을 트리거하면 안 된다).
     */
    @Transactional(readOnly = true)
    public List<SourceListItem> list(String ownerSubject) {
        return sourceConnectionJpaRepository.findAllByOwnerSubject(ownerSubject).stream()
                .map(entity -> new SourceListItem(
                        sourcePersistenceMapper.toDomain(entity),
                        sourceOAuthTokenJpaRepository.existsBySourceId(entity.getId())))
                .toList();
    }

    /**
     * Source 연결을 Disconnect한다(소프트 Lifecycle 전이 - 행을 지우지 않는다):
     * <ol>
     *   <li>{@code owner_subject}로 범위를 좁혀 조회한다 - 존재하지만 다른 계정
     *       소유이면 존재하지 않을 때와 동일한 {@link NotFoundException}을
     *       던진다(Cross-Account 존재 노출 금지) - Token 삭제를 포함해 어떤
     *       변경도 일어나지 않는다.</li>
     *   <li>{@link SourceTokenStore}로 항상 제거를 시도한다(실제로 지울 것이
     *       있는지는 그 구현체 자신이 Lock을 잡은 뒤 다시 확인한다 - 아래 "M08
     *       후속 교정" 참고). 사용 가능한 구현체가 없으면 Fail Closed(성공을
     *       보고하지 않고 예외를 던져 Transaction 전체를 Rollback한다).</li>
     *   <li>연결 상태를 DISABLED로 전이한다 - 문서({@code source_documents.state})와
     *       공유 의도({@code document_shares}, V010)는 건드리지 않는다(M10B 교정,
     *       클래스 하단 "M10B 교정" 참고 - Provider 삭제와 SDV 연결 중단을
     *       혼동하지 않는다).</li>
     *   <li>성공 감사를 기록한다 - 실패하면 위 1~4단계를 포함해 이 Transaction
     *       전체가 Rollback된다(클래스 문서의 Transaction/Audit 정책 참고 -
     *       단, 이미 실행된 외부 Token 삭제 자체는 DB Rollback으로 되돌릴 수
     *       없다는 한계도 함께 참고).</li>
     * </ol>
     * 반복 호출해도 안전하다 - 이미 Disconnect된 연결에 다시 호출하면 {@link
     * SourceTokenStore#delete}가 멱등적으로 아무 일도 하지 않는다.
     *
     * <h2>M08 후속 교정 - "이 Read 시점에 Token이 없었다"만으로 Token Store 호출을
     * 건너뛰지 않는다</h2>
     * <p>이전 구현은 이 메서드 맨 위의(Unlocked) 조회가 보여주는 {@code tokenRef}가
     * {@code null}이면 {@link SourceTokenStore#delete}를 아예 호출하지 않았다 - 그
     * 조회와 이 Transaction의 최종 Commit(Status 갱신 Flush) 사이에, 같은 Source에
     * 대한 최초 인증({@code GoogleDriveOAuthService.commitCredential} → {@code
     * GoogleTokenService.store})이 동시에 끝나 새 Credential 행을 만들었다면, 이
     * Disconnect는 그 새 행을 절대 알지 못한 채 Source만 DISABLED로 바꾸고 끝나
     * Credential 행이 Revoke도, 삭제도 되지 않은 채로 고아처럼 남을 수 있었다
     * ("ensure disconnect coordinates even when no token was present at its
     * initial read" - 이 작업 지시사항). 이제 {@link SourceTokenStore#delete}를
     * 항상 호출한다 - 실제로 지울 것이 있는지는 그 Method 자신이(부모 Source를 먼저
     * 잠근 뒤, 1차 캐시를 우회하는 Native Query로) 다시 확인한다({@code
     * GoogleTokenService.revoke}) - 그래서 이 시점의 Stale한 Java 값이 아니라 Lock
     * 시점의 실제 DB 상태를 기준으로 판단한다.</p>
     */
    @Transactional
    public void disconnect(Long id, String ownerSubject) {
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findByIdAndOwnerSubject(id, ownerSubject)
                .orElseThrow(() -> new NotFoundException("Source connection not found"));

        if (sourceTokenStore.isEmpty()) {
            // Fail Closed: Token Store 자체가 없으면 이 Source에 지금 실제로 Credential이
            // 있는지 안전하게 확인할 방법이 없다 - 이 Read 시점의 Stale한 tokenRef 값만
            // 보고 "없다"고 넘겨짚지 않는다. 성공을 보고하지 않고 이 예외로 Transaction
            // 전체를 Rollback시킨다.
            throw new IllegalStateException(
                    "No SourceTokenStore implementation available; refusing to disconnect source " + id);
        }
        sourceTokenStore.get().delete(id);

        // M10B 보안 교정(V011) - 연결 인가 세대(Connection Epoch)를 올린다. 이 시점
        // 이전에 시작된 OAuth 재인증 시도(이미 State Store에서 consume돼 Token 교환/
        // Identity 확인을 기다리고 있었을 수도 있다)나 이 시점 이전에 계산된 공유 접근
        // 판단(SourceAccessContext.connectionGeneration)을 모두 무효화한다 - 나중에
        // Status가 다시 ACTIVE가 되더라도(재연결), 그 이전 시점 기준으로 이미 진행 중이던
        // 작업이 "아무 일도 없었던 것처럼" 완료/재사용되지 않는다.
        //
        // M10B 후속 교정 - 겹치는 두 Disconnect 아래에서도 증가분을 잃지 않는다.
        // 이 Method 맨 위의 `entity`는 Unlocked 조회로 읽었으므로, 여기서 그 Java
        // 값에 그대로 +1(entity.bumpConnectionEpoch())하면 두 Transaction이 똑같이
        // 낡은 시작 값을 기준으로 계산해 한쪽 증가가 유실될 수 있었다(SourceConnectionEntity
        // Javadoc 참고). 위 sourceTokenStore.get().delete(id) -> GoogleTokenService.revoke가
        // 이미 같은 Transaction 안에서 이 Source 행을 SELECT ... FOR UPDATE로 잠가
        // 뒀으므로, 이제 그 잠긴 행 자체를 기준으로 원자적으로 증가시키고(Java 값을
        // 신뢰하지 않는다) 그 결과를 이 Managed Entity에 동기화한다.
        //
        // 이 호출을 반드시 아래 entity.updateTokenRef/changeStatus보다 먼저 해야 한다 -
        // 그 두 Mutation을 먼저 해 두면 entity가 이미 Dirty해진 상태이므로, Hibernate의
        // Flush-Before-Query(같은 source_connections 테이블을 건드리는 Native Query를
        // 실행하기 직전 자동으로 대기 중인 변경을 먼저 Flush하는 기본 동작)가 이 Native
        // Query보다 먼저 실행되면서 그 Stale한 Java epoch 값을 DB에 앞서 써 버려, 방금
        // 다른 Transaction이 이미 Commit해 둔 증가분을 그 Auto-Flush 자체가 덮어써
        // 버린다(실제로 처음 이 순서로 구현했을 때 이 정확한 재발을 관찰했다 - 아래
        // 두 줄을 이 지점보다 뒤로 옮긴 것이 그 교정이다).
        sourceConnectionJpaRepository.incrementConnectionEpoch(id);
        entity.syncConnectionEpoch(sourceConnectionJpaRepository.findCurrentConnectionEpoch(id));

        entity.updateTokenRef(null);
        entity.changeStatus(SourceConnection.STATUS_DISABLED);
        // M10B 교정(CORE_SPEC §2A.14) - "Disconnect pauses use... it does not
        // silently erase share settings." 이전 구현은 여기서
        // sourceDocumentJpaRepository.markAllActiveAsDeletedForSource(id)를 호출해
        // 이 Source의 모든 문서를 SourceDocumentState.DELETED로 전이시켰다 - 이는
        // "SDV 연결이 끊겼다"와 "Google에서 실제로 파일이 지워졌다"를 혼동한
        // 결함이었다(Provider 삭제는 Live 재확인이 NOT_FOUND/TRASHED로 실제로
        // 관측할 때만 판단할 문제이지, SDV 연결 상태로 지어낼 문제가 아니다).
        // 이제 disconnect는 source_documents.state를 전혀 건드리지 않는다 -
        // 이 Source에 대한 접근은 이미 status=DISABLED 하나만으로 충분히
        // 차단된다(EffectivePermissionService의 SOURCE_INACTIVE 판단,
        // GoogleDriveConnector.verifyCurrentMetadata/fetchContent의 ACTIVE 상태
        // 확인). 문서 행과 그에 걸린 document_shares(V010)는 그대로 보존되므로,
        // 같은 인증된 소유자가 동일 검증된 Provider 계정으로 재연결하면(같은
        // GoogleDriveOAuthService Flow) 다음 Live 재확인부터 다시 보인다 -
        // 별도 복구 절차가 필요 없다.
        //
        // Embedding Index 행(document_embedding_index, V006)은 여전히 정리한다 -
        // 이는 문서 생명주기(State)가 아니라 색인 결과물 정리이며, 이번 교정의
        // 대상이 아니다(M11 색인이 아직 없어 현재 이 삭제는 사실상 no-op이다).
        sourceDocumentJpaRepository.deleteEmbeddingIndexForSource(id);

        auditService.record(ownerSubject, "SOURCE_DISCONNECTED", "source:" + id, SUCCESS, OK, Map.of());
        applicationEventPublisher.publishEvent(new SourceConnectionAccessRevokedEvent(id));
    }

    /**
     * M10B 신규(SHR-001, {@code GET /api/sources/{id}/files}) - 소유자 전용 비공개
     * Metadata 선택기. 이 조회 결과를 보는 것 자체는 공유가 아니다 - 여기 나타난
     * 문서를 다른 사용자가 발견하려면 {@code SourceSharingService.createShare}로
     * 명시적 공유를 만들어야 한다. Content Fetch/색인 트리거 없음(순수 Catalog
     * 조회) - Count Query 없는 {@link Slice}로 원시 총계를 노출하지 않는다.
     */
    @Transactional(readOnly = true)
    public FilesPage listFiles(Long sourceId, String ownerSubject, int page, int size) {
        return listFiles(sourceId, ownerSubject, page, size, null);
    }

    @Transactional(readOnly = true)
    public FilesPage listFiles(Long sourceId, String ownerSubject, int page, int size, String filenameQuery) {
        sourceConnectionJpaRepository.findByIdAndOwnerSubject(sourceId, ownerSubject)
                .orElseThrow(() -> new NotFoundException("Source connection not found"));
        Pageable pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "name").and(Sort.by(Sort.Direction.ASC, "id")));
        String pattern = toLiteralContainsPattern(filenameQuery);
        Slice<SourceDocumentEntity> slice = sourceDocumentJpaRepository.findOwnedForPicker(ownerSubject, sourceId,
                pattern != null, pattern == null ? "" : pattern, pageable);
        return new FilesPage(slice.getContent(), slice.hasNext());
    }

    private static String toLiteralContainsPattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** {@link #listFiles}의 반환 항목 - Repository의 원시 Count Query 부재를 그대로 반영한다(원시 총계 비노출). */
    public record FilesPage(List<SourceDocumentEntity> items, boolean hasMore) {
    }

    /** MVP-17 - {@link #list(String)}의 반환 항목. {@code credentialPresent}는 존재 여부일 뿐 유효성 증거가 아니다. */
    public record SourceListItem(SourceConnection connection, boolean credentialPresent) {
    }
}
