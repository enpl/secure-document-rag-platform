package com.sdv.rag.application;

import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagFileItem;
import com.sdv.rag.api.dto.RagFileNameMatch;
import com.sdv.rag.api.dto.RagFileSearchQuery;
import com.sdv.rag.api.dto.RagFileSearchResponse;
import com.sdv.rag.api.dto.RagFileSortKey;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository.SharedDiscoveryCandidate;
import com.sdv.identity.domain.UserAuthorizationSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * M10 신규(RAG-011 File Metadata Discovery, `docs/spec/SDV_v3.2_CORE_SPEC.md`
 * §2A.4) - {@code GET /api/rag/files}의 유스케이스. v3.2 공식 File/Feature ID가
 * 아직 정의돼 있지 않은 신규 Application Service다(이 작업 지시가 승인한 "one
 * focused discovery use-case service").
 *
 * <h2>M10 후속 교정 - 공개 Page는 원시 DB 행이 아니라 확인된(Verified) 위치를 센다</h2>
 * <p>이전 구현은 {@code PageRequest.of(query.page(), query.size(), ...)}로 원시 DB
 * 행 오프셋에 Page를 매핑했다 - 권한 없거나 Live 검증에 실패한("숨은") 행이 정렬
 * 순서상 앞에 섞이면, 실제로 보이는 파일이 다른 Page로 밀리거나 빈 Page가 나올 수
 * 있었다({@code hasMore}만 Bounded Lookahead로 교정됐을 뿐, Page 자체의 내용은
 * 여전히 원시 오프셋 기준이었다).</p>
 *
 * <p>이제 {@link #search}는 정렬 순서 0번 위치부터 후보를 하나의 연속된 Scan으로
 * 훑는다(Page 경계마다 새로 시작하지 않는다). {@link EffectivePermissionService}
 * Prefilter와 {@link DocumentSourceConnector#verifyCurrentMetadata} Live 재확인을
 * 모두 통과해 "확정적으로 보인다"고 판정된 후보에만 순서상 위치(Visible Position)
 * 번호를 매긴다 - 그 번호가 요청한 {@code [page*size, page*size+size)} 구간 안에
 * 있을 때만 응답에 담는다. 그 구간 바로 다음 위치에서 보이는 후보를 하나 더 확인하면
 * {@code hasMore=TRUE}로 즉시 멈춘다(더 볼 필요 없음) - 요청한 Page + 확인용 1건
 * 이상은 절대 유지하지 않는다({@code page*size}에 비례하는 List를 미리 할당하거나
 * 전체 Catalog를 메모리에 올리지 않는다).</p>
 *
 * <h2>왜 "확인 불가(Unknown)"를 만나면 그 즉시 멈추는가</h2>
 * <p>Visible Position은 "지금까지 확정적으로 보인다고 판정된 개수"를 세는
 * 누적 카운터다. 어떤 후보가 정책/Live 어느 쪽이든 확인 자체에 실패하면(Network/
 * 자격증명 문제 등, {@link SourceMetadataVerificationOutcome#isDefinitiveExclusion()}이
 * {@code false}인 경우), 그 후보가 실제로는 보였을 수도 있다 - 그렇다면 그 뒤에 매기는
 * 모든 위치 번호가 실제보다 하나씩 밀려 있다는 뜻이 된다. 그래서 확인 불가 후보를
 * 만나면 그 뒤로는 위치를 신뢰할 수 없다고 보고 Scan을 즉시 멈춘다 - 계속 세면서
 * "아마 안 보였을 것"이라고 추측하지 않는다(Fail Closed). 반대로 확정적으로 배제된
 * 후보(정책 거부, {@link SourceMetadataVerificationOutcome#isDefinitiveExclusion()}이
 * {@code true}인 결과, Live 필터 불일치, 노출 직전 재인가 실패)는 "확실히 이 자리를
 * 차지하지 않는다"는 것이 확정됐으므로 위치를 건드리지 않고 계속 진행한다.</p>
 *
 * <h2>Bounded Candidate Scan</h2>
 * <p>{@link RagDiscoveryProperties#maxCandidateScan()}이 이번 요청에서 실제로
 * 정책/Live로 "평가를 시도"하는(허용/거부와 무관하게) 원시 후보 총수의 상한이다 -
 * 확정적으로 배제된 후보도 이 상한에 포함된다(허용된 것만 세지 않는다). 이 상한은
 * 다음 DB Batch를 가져오기 전뿐 아니라 Batch 안에서 후보 하나를 평가하기 직전에도
 * 확인한다 - 그래서 {@code cap}이 한 Batch 크기보다 작거나(예: cap=1, size=20)
 * Batch 크기로 나누어떨어지지 않아도(예: cap=50, size=30) 정확히 {@code cap}개만
 * 평가하고 그 이상 넘지 않는다(Batch 자체를 DB에서 읽는 것 자체는 이 평가 상한에
 * 포함되지 않는다 - 원시 Row를 몇 개 더 읽는 것은 정책/Live 호출이 아니므로 비용이
 * 사실상 없다). 한 Batch를 상한 때문에 일부만 평가했다면({@code trimmed}), 그
 * Batch의 나머지(DB에서 읽었지만 평가하지 않은 행)가 여전히 남아있으므로 원시
 * Catalog가 끝났는지({@code Slice.hasNext()})와 무관하게 상한 도달로 취급한다 -
 * "더 읽어왔다"와 "다 평가했다"를 혼동하지 않는다.</p>
 *
 * <p>Deadline(위 {@code liveCheckBudgetMs})은 다음 후보를 평가하기 시작하기 전,
 * 그리고 다음 Batch를 가져오기 시작하기 전에만 확인한다 - 이미 진행 중이거나 방금
 * 끝난 개별 Live 호출을 그 결과 도착 후에 소급해서 무효화하지 않는다(이 예산은
 * "새 작업을 시작할지"를 결정하는 상한이지, 이미 도착한 성공 결과를 취소하는 강제
 * Timeout이 아니다). 그래서 마지막(또는 유일한) 후보의 호출이 예산을 넘긴 순간에
 * 끝나더라도, 그 뒤에 실제로 더 할 일이 없었다면(요청한 Page를 이미 다 채웠거나
 * 원시 Catalog가 거기서 끝났다면) 응답은 그대로 완전한(Non-partial) 것으로
 * 취급된다 - 반대로 그 호출 이후 정말로 더 확인해야 할 후보가 남아있었다면, 다음
 * 후보/Batch를 시작하기 전의 확인이 정확히 그 지점에서 멈춘다(예산 소진 이후
 * 확인되지 않은 작업을 계속하지 않는다).</p>
 *
 * <p>상한이나 예산에 먼저 도달하거나, 확인 불가 후보를 만나면 Scan을 멈추고
 * 그때까지 확정된 것만 응답에 담은 뒤 {@code hasMore=null}(Unknown)/{@code
 * partial=true}로 정직하게 보고한다 - 깊은 Page(큰 {@code page*size})가 이 상한
 * 안에서 도달되지 않을 수 있다는 것은 알려진, 문서화된 한계다(전체 Catalog를
 * 끝까지 훑어서라도 채우려 하지 않는다).</p>
 *
 * <p>이 Service는 {@code @Transactional}이 아니다 - Google Live 호출을 DB
 * Transaction/Lock 밖에 유지하기 위해서다(내부에서 호출하는 {@code
 * EffectivePermissionService}의 각 호출은 그 자신의 짧은 Transaction 경계를
 * 쓴다). Content Fetch/Parser/LLM을 절대 호출하지 않는다 - {@link
 * DocumentSourceConnector#fetchContent}를 이 클래스 어디에서도 호출하지 않는다.
 * 매 요청마다 이 Scan을 처음부터 다시 수행한다 - 이전 요청의 권한 판단을 캐시해
 * 영구적 증거로 재사용하지 않는다(Reapply on every request).</p>
 */
@Service
public class FileMetadataDiscoveryService {

    private static final String DRIVE_VIEW_URL_PREFIX = "https://drive.google.com/file/d/";
    private static final String DRIVE_VIEW_URL_SUFFIX = "/view";
    /**
     * 필터가 없을 때 Repository에 넘기는 임의의 non-null Sentinel 값 - {@code hasXxx}
     * 플래그가 이를 무시하도록 강제하므로 결과에 영향을 주지 않는다. 실제 SQL
     * {@code NULL}을 절대 바인딩하지 않기 위한 값일 뿐이다({@code
     * SourceDocumentJpaRepository.searchDiscoverable} Javadoc 참고).
     */
    private static final String NO_FILTER_SENTINEL = "";
    private static final long NO_SOURCE_ID_SENTINEL = 0L;

    private final DocumentShareJpaRepository documentShareJpaRepository;
    private final EffectivePermissionService effectivePermissionService;
    private final SourceConnectorRegistry sourceConnectorRegistry;
    private final RagDiscoveryProperties properties;
    private final Clock clock;

    @Autowired
    public FileMetadataDiscoveryService(DocumentShareJpaRepository documentShareJpaRepository,
            EffectivePermissionService effectivePermissionService, SourceConnectorRegistry sourceConnectorRegistry,
            RagDiscoveryProperties properties) {
        this(documentShareJpaRepository, effectivePermissionService, sourceConnectorRegistry, properties,
                Clock.systemUTC());
    }

    /** 테스트가 Live 검증 예산(Budget) 소진을 결정론적으로 재현하기 위한 패키지 전용 생성자. */
    FileMetadataDiscoveryService(DocumentShareJpaRepository documentShareJpaRepository,
            EffectivePermissionService effectivePermissionService, SourceConnectorRegistry sourceConnectorRegistry,
            RagDiscoveryProperties properties, Clock clock) {
        this.documentShareJpaRepository = documentShareJpaRepository;
        this.effectivePermissionService = effectivePermissionService;
        this.sourceConnectorRegistry = sourceConnectorRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    public RagFileSearchResponse search(UserContext user, RagFileSearchQuery query) {
        java.util.Optional<UserAuthorizationSnapshot> admitted =
                effectivePermissionService.currentSharedAuthorization(user);
        if (admitted.isEmpty()) {
            return new RagFileSearchResponse(java.util.List.of(), Boolean.FALSE, false);
        }
        UserAuthorizationSnapshot authorization = admitted.get();
        int clearanceRank = authorization.maximumClassification().rank();
        long authorizationRevision = authorization.authorizationRevision();
        Instant deadline = clock.instant().plusMillis(properties.liveCheckBudgetMs());
        Optional<DocumentSourceConnector> connector = sourceConnectorRegistry.getConnector(SourceType.GOOGLE_DRIVE);

        long start = (long) query.page() * (long) query.size();
        long end = start + query.size();
        int cap = properties.maxCandidateScan();

        List<RagFileItem> items = new ArrayList<>(query.size());
        long visiblePosition = 0;
        int scanned = 0;
        int page = 0;
        StopReason stop = null;

        scan:
        while (true) {
            if (scanned >= cap) {
                stop = StopReason.CAP;
                break;
            }
            if (!clock.instant().isBefore(deadline)) {
                stop = StopReason.DEADLINE;
                break;
            }

            Pageable pageable = PageRequest.of(page, query.size(), buildSort(query.sort()));
            Slice<SharedDiscoveryCandidate> slice = fetchCandidates(user, clearanceRank, query, pageable);
            List<SharedDiscoveryCandidate> batch = slice.getContent();
            if (batch.isEmpty()) {
                stop = StopReason.RAW_END;
                break;
            }

            // 이번 Batch 전체를 평가하면 상한을 넘을 수 있으므로, 남은 평가 여력만큼만 자른다 -
            // DB에서 더 읽어온 나머지 행은 정책/Live 어느 쪽으로도 평가하지 않는다.
            int remaining = cap - scanned;
            boolean trimmed = batch.size() > remaining;
            List<SharedDiscoveryCandidate> toEvaluate = trimmed ? batch.subList(0, remaining) : batch;

            for (SharedDiscoveryCandidate candidate : toEvaluate) {
                if (scanned >= cap) {
                    stop = StopReason.CAP;
                    break scan;
                }
                if (!clock.instant().isBefore(deadline)) {
                    stop = StopReason.DEADLINE;
                    break scan;
                }
                scanned++;

                SourceDocumentEntity document = candidate.document();
                SourceAccessContext accessContext = new SourceAccessContext(user.subject(),
                        candidate.publisherSubject(), document.getSourceId(), document.getId(),
                        candidate.share().getId(), ShareAction.VIEW, candidate.share().getGeneration(),
                        candidate.connectionEpoch(), authorizationRevision);

                // 공유 기반 Prefilter - B의 SDV 인가(수신자/행위/등급/게시/차단/게시자 문서 상태)만
                // 확인한다. A(게시자)의 Provider 접근 자체는 아직 확인하지 않았다(§2A.4).
                if (!effectivePermissionService.evaluateSharedAccess(user, accessContext, null).isAllowed()) {
                    // 확정적으로 배제됨(Prefilter Deny) - 위치를 건드리지 않고 계속 진행한다.
                    continue;
                }
                if (connector.isEmpty()) {
                    // 등록된 Connector가 없어 이 이상 아무것도 확인할 수 없다 - 확인 불가로 즉시 멈춘다.
                    stop = StopReason.UNKNOWN;
                    break scan;
                }

                // Publisher-Bound Live 재확인 - B가 아니라 A(게시자, Source Owner)의 Credential로
                // 수행한다(GoogleDriveConnector는 requestingUser==Source Owner만 신뢰한다 - A가
                // 정확히 그 Owner이므로 그대로 재사용할 수 있다). Content Byte는 요청하지 않는다.
                UserContext publisherContext = new UserContext(candidate.publisherSubject(), null, Set.of(),
                        Set.of());
                SourceMetadataVerificationResult liveResult = connector.get().verifyCurrentMetadata(publisherContext,
                        document.getSourceId(), document.getSourceDocumentId());

                boolean visible = false;
                if (liveResult.outcome() == SourceMetadataVerificationOutcome.VERIFIED) {
                    // Live 값으로 요청 필터를 다시 적용 + 노출 직전 재인가(B의 공유 인가를 다시 한번,
                    // 게시자의 Live I/O가 진행되는 동안 Unshare/Admin Block/세대 변경이 없었는지) -
                    // 둘 다 지금 막 확인한 확정적 결론이다(Unknown이 아니다).
                    visible = matchesLiveFilters(query, liveResult) && effectivePermissionService
                            .evaluateSharedAccess(user, accessContext, null).isAllowed();
                } else if (!liveResult.outcome().isDefinitiveExclusion()) {
                    // Missing/Unknown/Malformed/Credential 문제 - "확인 못함"이지 "확인 결과 안
                    // 보인다"가 아니다. 이 뒤의 위치는 신뢰할 수 없으므로 즉시 멈춘다(Fail Closed).
                    stop = StopReason.UNKNOWN;
                    break scan;
                }
                // else: isDefinitiveExclusion()==true(TRASHED/NOT_FOUND/ACCESS_DENIED/
                // CREDENTIAL_NOT_BOUND_TO_USER) - 확정적으로 배제됐다, 위치를 건드리지 않는다.

                if (visible) {
                    if (visiblePosition >= start && visiblePosition < end) {
                        items.add(toItem(document, liveResult, candidate.share()));
                    } else if (visiblePosition == end) {
                        // 요청한 Page 바로 다음 위치에서 실제로 보이는 후보를 확인했다 - 더 볼 필요 없다.
                        stop = StopReason.FOUND_BEYOND;
                        break scan;
                    }
                    visiblePosition++;
                }
            }

            if (trimmed) {
                // 이번 Batch를 상한 때문에 일부만 평가했다 - DB에서 더 읽어왔지만 평가하지 않은
                // 나머지가 있으므로, 원시 Catalog가 끝났는지 여부(slice.hasNext())와 무관하게
                // 이미 상한에 도달한 것으로 취급한다(평가하지 않은 나머지를 "확인 끝났다"고
                // 잘못 간주하지 않는다).
                stop = StopReason.CAP;
                break;
            }
            if (!slice.hasNext()) {
                stop = StopReason.RAW_END;
                break;
            }
            page++;
        }

        RagFileSearchResponse response = buildResponse(items, stop);
        UserAuthorizationSnapshot current = effectivePermissionService.currentSharedAuthorization(user)
                .orElseThrow(RequesterAuthorizationChangedException::new);
        if (!authorization.equals(current)) {
            throw new RequesterAuthorizationChangedException();
        }
        return response;
    }

    /**
     * Scan이 멈춘 이유로부터 공개 계약({@code hasMore}/{@code partial})을 계산한다.
     *
     * <ul>
     *   <li>{@link StopReason#FOUND_BEYOND} - 요청한 Page를 완전히 채웠고, 그 바로
     *       다음에서 보이는 후보를 하나 더 확인했다 - {@code hasMore=TRUE},
     *       {@code partial=false}(완전히 확정됐다).</li>
     *   <li>{@link StopReason#RAW_END} - 확인 불가 후보를 한 번도 만나지 않은 채
     *       원시 Catalog 자체가 끝났다(권한/Live 검증 여부와 무관한 순수 DB 사실) -
     *       지금까지 모은 {@code items}가 이 요청의 최종 결과다(빈 Page일 수도, 마지막
     *       Page라 {@code size}보다 적을 수도 있다) - {@code hasMore=FALSE},
     *       {@code partial=false}(전부 확정적으로 판정했다).</li>
     *   <li>{@link StopReason#CAP}/{@link StopReason#DEADLINE}/{@link StopReason#UNKNOWN} -
     *       상한/예산에 도달했거나 확인 불가 후보를 만나 그 이상 신뢰할 수 있는 판정을
     *       계속할 수 없었다 - {@code hasMore=null}(Unknown), {@code partial=true}.
     *       지금까지 확정된 {@code items}는 그대로 유지한다(허위로 비우지 않는다) -
     *       모두 실제로 검증을 통과한 항목이기 때문이다.</li>
     * </ul>
     */
    private static RagFileSearchResponse buildResponse(List<RagFileItem> items, StopReason stop) {
        return switch (stop) {
            case FOUND_BEYOND -> new RagFileSearchResponse(List.copyOf(items), Boolean.TRUE, false);
            case RAW_END -> new RagFileSearchResponse(List.copyOf(items), Boolean.FALSE, false);
            case CAP, DEADLINE, UNKNOWN -> new RagFileSearchResponse(List.copyOf(items), null, true);
        };
    }

    /**
     * M10B 교정 - 이전에는 {@code sourceDocumentJpaRepository.searchDiscoverable(user.subject(),
     * ...)}로 "내가 소유한 문서"를 후보로 삼았다(Owner-Only Fallback). 이제 후보는
     * "나(recipient)에게 명시적으로 활성 공유된 문서"뿐이다 - 게시자 본인이 자신의 문서를
     * 검색해도, 스스로에게 공유하지 않은 이상 이 공통 검색에는 나타나지 않는다(§2A.4
     * "including the owner's common discovery") - 비공개 선택기({@code
     * SourceUserController#files})는 완전히 별도 경로다.
     */
    private Slice<SharedDiscoveryCandidate> fetchCandidates(UserContext user, int clearanceRank, RagFileSearchQuery query,
            Pageable pageable) {
        return documentShareJpaRepository.searchSharedDiscoverable(user.subject(), clearanceRank,
                query.sourceId() != null, query.sourceId() != null ? query.sourceId() : NO_SOURCE_ID_SENTINEL,
                query.mimeType() != null, query.mimeType() != null ? query.mimeType() : NO_FILTER_SENTINEL,
                query.q() != null, query.q() != null ? toLikePattern(query.q(), query.nameMatch()) : NO_FILTER_SENTINEL,
                query.modifiedFrom() != null, query.modifiedFrom() != null ? query.modifiedFrom() : Instant.EPOCH,
                query.modifiedTo() != null, query.modifiedTo() != null ? query.modifiedTo() : Instant.EPOCH,
                pageable);
    }

    private static Sort buildSort(RagFileSortKey sortKey) {
        return Sort.by(sortKey.direction(), sortKey.property()).and(Sort.by(sortKey.direction(), "id"));
    }

    private static boolean matchesLiveFilters(RagFileSearchQuery query, SourceMetadataVerificationResult live) {
        if (query.q() != null && !matchesName(live.name(), query.q(), query.nameMatch())) {
            return false;
        }
        if (query.mimeType() != null && !query.mimeType().equals(live.mimeType())) {
            return false;
        }
        if (live.modifiedAt() != null) {
            if (query.modifiedFrom() != null && live.modifiedAt().isBefore(query.modifiedFrom())) {
                return false;
            }
            if (query.modifiedTo() != null && live.modifiedAt().isAfter(query.modifiedTo())) {
                return false;
            }
        }
        return true;
    }

    private static RagFileItem toItem(SourceDocumentEntity candidate, SourceMetadataVerificationResult live,
            DocumentShareEntity share) {
        boolean sourceVersionCurrent = live.sourceVersion().equals(candidate.getSourceVersion());
        return new RagFileItem(candidate.getId(), candidate.getSourceId(), live.name(), live.mimeType(),
                live.sourceVersion(), live.modifiedAt(), candidate.getIndexStatus(), sourceVersionCurrent,
                Boolean.TRUE.equals(live.downloadable()), buildViewUrl(candidate.getSourceDocumentId()),
                share.getId(), parseAllowedActions(share.getAllowedActions()));
    }

    /** {@code DocumentShareEntity.getAllowedActions()}의 CSV(예: {@code "VIEW,DOWNLOAD"})를 Set으로 나눈다. */
    private static Set<String> parseAllowedActions(String csv) {
        Set<String> actions = new LinkedHashSet<>();
        for (String value : csv.split(",")) {
            actions.add(value);
        }
        return Set.copyOf(actions);
    }

    /** 검증된 File ID로만 만든 고정 스킴/호스트 URL - Export Link 등 임의 URL을 쓰지 않는다. */
    private static String buildViewUrl(String sourceDocumentId) {
        return DRIVE_VIEW_URL_PREFIX + UriUtils.encodePathSegment(sourceDocumentId, StandardCharsets.UTF_8)
                + DRIVE_VIEW_URL_SUFFIX;
    }

    /**
     * 소문자로 변환하고 {@code %}/{@code _}/{@code \}를 이스케이프해 SQL {@code LIKE}
     * Wildcard로 오인되지 않게 한다 - Repository의 {@code LOWER(d.name) LIKE :namePattern}과
     * 대소문자 기준을 맞추기 위해 컬럼이 아니라 여기(Java)에서 미리 소문자로 바꾼다
     * (Null 파라미터에 {@code LOWER(:namePattern)}을 직접 씌우면 Hibernate가 Postgres
     * Parameter Type을 잘못 추론하는 문제를 피한다).
     *
     * <h2>M17 자연어 파일 검색 교정 - PREFIX는 CONTAINS로 조용히 대체하지 않는다</h2>
     * <p>Wildcard {@code %}는 오직 이 자리에서만(escape 이후) 붙인다 - {@link
     * RagFileNameMatch#PREFIX}는 뒤쪽 {@code %}만, {@link RagFileNameMatch#CONTAINS}는
     * 양쪽 {@code %}를 붙인다. 리터럴 이스케이프는 두 모드가 완전히 동일하다.</p>
     */
    private static String toLikePattern(String q, RagFileNameMatch mode) {
        if (q == null) {
            return null;
        }
        String escaped = q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return mode == RagFileNameMatch.PREFIX ? escaped + "%" : "%" + escaped + "%";
    }

    /** {@link #matchesLiveFilters}의 이름 조건 - DB 쪽 {@link #toLikePattern}과 정확히 같은 의미로 재확인한다. */
    private static boolean matchesName(String liveName, String q, RagFileNameMatch mode) {
        String name = liveName.toLowerCase(Locale.ROOT);
        String needle = q.toLowerCase(Locale.ROOT);
        return mode == RagFileNameMatch.PREFIX ? name.startsWith(needle) : name.contains(needle);
    }

    /** {@link #search}의 연속 Scan이 멈춘 이유 - {@link #buildResponse}가 공개 계약으로 번역한다. */
    private enum StopReason {
        /** 요청한 Page를 다 채운 뒤, 그 다음 위치에서 보이는 후보를 하나 더 확인했다. */
        FOUND_BEYOND,
        /** 확인 불가 후보 없이 원시 Catalog 자체가 끝났다(순수 DB 사실). */
        RAW_END,
        /** {@link RagDiscoveryProperties#maxCandidateScan()} 상한에 도달했다. */
        CAP,
        /** Live 검증 예산({@link RagDiscoveryProperties#liveCheckBudgetMs()})을 넘겼다. */
        DEADLINE,
        /** 정책/Live 어느 쪽이든 확인 자체에 실패한 후보를 만나 더 이상 위치를 신뢰할 수 없다. */
        UNKNOWN
    }
}
