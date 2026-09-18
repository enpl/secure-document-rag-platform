package com.sdv.rag.application;

import com.sdv.ai.application.NaturalLanguageFileQueryParser;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagFileItem;
import com.sdv.rag.api.dto.RagFileNameMatch;
import com.sdv.rag.api.dto.RagFileSearchQuery;
import com.sdv.rag.api.dto.RagFileSearchResponse;
import com.sdv.rag.api.dto.RagFileSortKey;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareRecipientEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRecipientJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M10B 교정(SHR-001, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13) - {@link
 * FileMetadataDiscoveryService}의 유스케이스 전체(공유 기반 Prefilter →
 * Publisher-Bound Live 재확인 → Live 값 재필터 → 노출 직전 재인가 → 확인된 위치
 * 기준 연속 Scan에 의한 Page/{@code hasMore}/Coverage 판단)를 실제 Testcontainers
 * PostgreSQL + 실제 {@link EffectivePermissionService} Bean으로 검증한다.
 *
 * <h2>M10 → M10B: Owner-Only Prefilter를 공유 기반 Prefilter로 교체</h2>
 * <p>이 파일은 M10 시점에는 "문서 소유자 본인이 자신의 ACL Read 권한을 스스로
 * 부여받아 자기 문서를 검색"하는 시나리오로 Scan 알고리즘(연속 위치 기반 Page,
 * Bounded Cap, Deadline Soft-Stop, Unknown 처리)을 검증했다. M10B는 공통 검색의
 * 인가 모델 자체를 B안(명시적 공유)으로 바꿨다 - 이제 "게시자(Publisher)가 정확한
 * 파일을 지정 수신자(Recipient)에게 명시적으로 공유해야만" 그 수신자의 검색에
 * 나타난다. 이 Test는 정확히 같은 Scan 알고리즘 회귀를 그대로 보존하면서, Fixture만
 * "소유자가 스스로에게 ACL Read를 부여"에서 "게시자가 지정 수신자에게 공유를
 * 게시"로 바꿨다 - 알고리즘 자체(Cap/Deadline/Unknown/Pagination)는 이번 교정
 * 대상이 아니다(FileMetadataDiscoveryService.search의 Scan Loop는 손대지 않았다).</p>
 *
 * <p><b>격리 방식(M10B):</b> 이전에는 검색이 항상 Owner로 Scope됐으므로 매 Test가
 * 고유한 Owner 문자열 하나만으로 서로 격리됐다. 이제 검색은 수신자(Recipient) 기준
 * (Owner와 무관)이므로, 이 Class(Rollback 없이 같은 PostgreSQL을 재사용한다)의 매
 * Test는 게시자뿐 아니라 수신자도 매번 새로 발급한다({@link #recipient()}) - 그래야
 * 앞선 Test가 남긴 공유가 뒤 Test의 무필터 검색 결과에 섞이지 않는다.</p>
 *
 * <p>Google Drive는 이 클래스가 직접 구성한 {@link FakeConnector}(Spring Bean이
 * 아니다)로 대체한다 - {@code GoogleDriveConnector}와 함께 두면 {@link
 * SourceConnectorRegistry}가 같은 {@link SourceType#GOOGLE_DRIVE}에 대해 중복
 * 등록 예외를 던지므로, Spring이 관리하는 Registry Bean 대신 매 Test마다 새
 * Registry를 직접 만들어 Service에 주입한다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class FileMetadataDiscoveryServiceTest {

    private static final String ISSUER = "http://localhost:8180/realms/sdv";

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private DocumentShareRecipientJpaRepository documentShareRecipientJpaRepository;
    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private IdentityRegistryService identityRegistryService;

    private FakeConnector fakeConnector;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        fakeConnector = new FakeConnector();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ------------------------------------------------------------------
    // 기존 유스케이스(공유 기반 Prefilter/Publisher-Bound Live 재확인/Live 필터
    // 재적용/노출 직전 재인가)
    // ------------------------------------------------------------------

    @Test
    void recipientSeesExplicitlySharedDocumentsRegardlessOfIndexStatus() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Diagram.png", "image/png", "v1",
                "SKIPPED_UNSUPPORTED");
        DocumentShareEntity share = shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Diagram.png", "image/png", "v1", clock.instant(), true));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).hasSize(1);
        RagFileItem item = response.items().get(0);
        assertThat(item.name()).isEqualTo("Diagram.png");
        assertThat(item.mimeType()).isEqualTo("image/png");
        assertThat(item.indexStatus()).isEqualTo("SKIPPED_UNSUPPORTED");
        assertThat(item.viewUrl()).isEqualTo("https://drive.google.com/file/d/" + fixture.sourceDocumentId()
                + "/view");
        // M16C - documentId와 shareId를 절대 혼동하지 않는다(둘 다 우연히 다른 값이어야
        // 이 Assertion이 실제로 shareId를 검증한다는 뜻이 된다).
        assertThat(item.shareId()).isEqualTo(share.getId());
        assertThat(item.shareId()).isNotEqualTo(item.documentId());
        assertThat(item.allowedActions()).containsExactly("VIEW");
    }

    /**
     * M16C 신규 - 응답의 {@code shareId}/{@code allowedActions}가 정확히 "이 항목을
     * 노출시킨 그 공유"에 결합돼 있는지 확인한다(다른 공유/다른 수신자의 값이
     * 섞이지 않는다). DOWNLOAD Action이 부여된 공유는 그 값을 그대로 노출하고,
     * VIEW만 부여된 공유는 DOWNLOAD를 포함하지 않는다.
     */
    @Test
    void shareIdAndAllowedActionsAreBoundToTheExactSurvivingShareNotAnotherOne() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture viewOnly = createDocument(publisher, "ACTIVE", "ACTIVE", "ViewOnly.pdf", "application/pdf", "v1",
                "PENDING");
        Fixture downloadable = createDocument(publisher, "ACTIVE", "ACTIVE", "Downloadable.pdf", "application/pdf",
                "v1", "PENDING");
        DocumentShareEntity viewOnlyShare = shareWith(viewOnly, publisher, recipient);
        DocumentShareEntity downloadableShare = shareWithActions(downloadable, publisher, recipient,
                "VIEW,DOWNLOAD");
        fakeConnector.stub(viewOnly.sourceId(), viewOnly.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("ViewOnly.pdf", "application/pdf", "v1", clock.instant(),
                        true));
        fakeConnector.stub(downloadable.sourceId(), downloadable.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Downloadable.pdf", "application/pdf", "v1",
                        clock.instant(), true));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).hasSize(2);
        RagFileItem viewOnlyItem = response.items().stream()
                .filter(item -> item.documentId().equals(viewOnly.documentId())).findFirst().orElseThrow();
        RagFileItem downloadableItem = response.items().stream()
                .filter(item -> item.documentId().equals(downloadable.documentId())).findFirst().orElseThrow();
        assertThat(viewOnlyItem.shareId()).isEqualTo(viewOnlyShare.getId());
        assertThat(viewOnlyItem.allowedActions()).containsExactly("VIEW");
        assertThat(downloadableItem.shareId()).isEqualTo(downloadableShare.getId());
        assertThat(downloadableItem.allowedActions()).containsExactlyInAnyOrder("VIEW", "DOWNLOAD");
    }

    /**
     * M16C 신규 - C(공유받지 않은 제3자)에게는 애초에 이 문서 자체가 노출되지
     * 않는다(shareId를 확인할 응답 항목 자체가 없다) - 그룹 기반 회귀와 별개로,
     * 이번에 추가한 shareId/allowedActions 노출 자체가 새로운 유출 경로를 열지
     * 않았음을 직접 확인한다.
     */
    @Test
    void shareIdIsNeverExposedToAThirdPartyWhoWasNotAnAuthorizedRecipient() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        String outsider = "outsider-" + unique();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Secret.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Secret.pdf", "application/pdf", "v1", clock.instant(),
                        true));

        RagFileSearchResponse outsiderResponse = search(outsider, emptyQuery());

        assertThat(outsiderResponse.items()).isEmpty();
    }

    /**
     * §2A.4 "including the owner's common discovery" - 게시자 본인도 자신이
     * 스스로에게 공유하지 않은 이상 공통 검색에서 자신의 문서를 볼 수 없다(비공개
     * 선택기는 별도 경로 - {@code SourceUserController}).
     */
    @Test
    void publisherDoesNotSeeTheirOwnUnsharedDocumentInCommonDiscovery() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Private.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient); // 다른 사람에게는 공유했지만 자기 자신에게는 아니다.

        RagFileSearchResponse response = search(publisher, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void anUnauthorizedThirdPartyNeverSeesTheDocumentEvenWhenTargetingItsSourceIdDirectly() {
        String publisher = "publisher-real-" + unique();
        String recipient = recipient();
        String thirdParty = "third-party-C-" + unique();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Secret.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient); // C는 수신자가 아니다.

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, fixture.sourceId(), null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        RagFileSearchResponse response = search(thirdParty, query);

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls())
                .as("an unauthorized third party's source-id targeting must never reach a Google Live call")
                .isEmpty();
    }

    @Test
    void deletedDocumentIsExcludedEvenWithAnActiveShare() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "DELETED", "Gone.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void inactiveSourceIsExcludedEvenWithAnActiveShare() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "DISABLED", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls())
                .as("disconnect must exclude the document without any Google call, but must not have erased the share")
                .isEmpty();
        assertThat(documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(fixture.documentId()))
                .as("a paused connection must not silently unshare the document")
                .isPresent();
    }

    @Test
    void noActiveShareDeniesDiscoveryWithoutAnyLiveCall() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        // shareWith를 호출하지 않는다 - 공유 자체가 없으면 Prefilter에서 확정적으로 배제된다.
        createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls())
                .as("prefilter denial must happen before any Google call").isEmpty();
    }

    @Test
    void overlayDenyExcludesAnOtherwiseAllowedDocument() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        // 실제 OverlayPolicyService 경로를 그대로 재사용하는 대신, 이미 검증된
        // EffectivePermissionService의 evaluateSharedAccess 결과 자체를 신뢰한다 - 별도 Overlay
        // Row Insert는 EffectivePermissionServiceDecisionTest가 이미 충분히 검증했다.
        // 여기서는 evaluateSharedAccess 자체가 이 Service의 유일한 인가 경로임을 확인하기 위해
        // 문서 상태를 DELETED로 바꿔 같은 "Prefilter Deny" 효과를 재사용한다.
        SourceDocumentEntity document = sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow();
        document.markDeleted();
        sourceDocumentJpaRepository.saveAndFlush(document);

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
    }

    @Test
    void renamedFileIsExposedUnderItsLiveNameNotTheStaleCatalogName() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Old Name.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "New Name.pdf", "application/pdf", "v2", clock.instant(), true));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).name()).isEqualTo("New Name.pdf");
        assertThat(response.items().get(0).sourceVersion()).isEqualTo("v2");
        assertThat(response.items().get(0).sourceVersionCurrent())
                .as("catalog v1 != live v2 - the persisted index status must not be presented as current").isFalse();
    }

    @Test
    void aNameFilterMatchingTheStaleCatalogNameIsRecheckedAgainstTheLiveNameAndExcludedIfItNoLongerMatches() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Budget Report.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        // Catalog Query(SQL LIKE)는 통과하지만, Live 재확인 시점에는 이미 개명됐다.
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Unrelated File.pdf", "application/pdf", "v1", clock.instant(), true));

        RagFileSearchQuery query = new RagFileSearchQuery("Budget", null, null, null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        RagFileSearchResponse response = search(recipient, query);

        assertThat(response.items())
                .as("a rename discovered only during the live check must not surface under the stale filter match")
                .isEmpty();
    }

    /**
     * M17 자연어 파일 검색 교정 - PREFIX는 CONTAINS로 조용히 대체되지 않는다.
     * "sdv"로 시작하는 것과 어디엔가 "sdv"를 포함하는 것은 서로 다른 결과 집합을
     * 내야 한다 - 두 검사(Catalog LIKE + Live 재확인의 {@code matchesLiveFilters})
     * 모두 대상이다.
     */
    @Test
    void prefixMatchOnlyReturnsNamesStartingWithTheValueNotMerelyContainingIt() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        stubVisible(publisher, recipient, "sdv_report.pdf");
        stubVisible(publisher, recipient, "SDV_Other.pdf");
        stubVisible(publisher, recipient, "old_sdv_report.pdf");

        RagFileSearchQuery prefixQuery = new RagFileSearchQuery("sdv", RagFileNameMatch.PREFIX, null, null, null,
                null, RagFileSortKey.NAME_ASC, 0, 20);
        RagFileSearchResponse prefixResponse = search(recipient, prefixQuery);
        assertThat(prefixResponse.items()).extracting(RagFileItem::name)
                .as("PREFIX excludes a name that merely contains the value later in the string")
                .containsExactlyInAnyOrder("SDV_Other.pdf", "sdv_report.pdf");

        RagFileSearchQuery containsQuery = new RagFileSearchQuery("sdv", RagFileNameMatch.CONTAINS, null, null, null,
                null, RagFileSortKey.NAME_ASC, 0, 20);
        RagFileSearchResponse containsResponse = search(recipient, containsQuery);
        assertThat(containsResponse.items()).extracting(RagFileItem::name)
                .as("CONTAINS (unchanged, existing meaning) still matches all three")
                .containsExactlyInAnyOrder("SDV_Other.pdf", "sdv_report.pdf", "old_sdv_report.pdf");
    }

    /**
     * 검색 조건은 허용된 구조화 값으로만 전달된다 - parameter binding과 SQL LIKE
     * Wildcard({@code %}/{@code _}) 및 이스케이프 문자({@code \}) 자체의 리터럴
     * escaping은 PREFIX/CONTAINS 두 모드 모두 동일하게 유지된다(둘 다 같은
     * {@code toLikePattern}을 거친다 - Wildcard 배치 위치만 다르다).
     */
    @Test
    void literalWildcardCharactersInTheQueryAreEscapedForBothMatchModes() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        stubVisible(publisher, recipient, "100%_done.pdf");
        stubVisible(publisher, recipient, "100X done.pdf");

        RagFileSearchQuery containsLiteral = new RagFileSearchQuery("100%_done", RagFileNameMatch.CONTAINS, null,
                null, null, null, RagFileSortKey.NAME_ASC, 0, 20);
        assertThat(search(recipient, containsLiteral).items()).extracting(RagFileItem::name)
                .as("% and _ must be treated as literal characters, not SQL LIKE wildcards")
                .containsExactly("100%_done.pdf");

        RagFileSearchQuery prefixLiteral = new RagFileSearchQuery("100%_done", RagFileNameMatch.PREFIX, null, null,
                null, null, RagFileSortKey.NAME_ASC, 0, 20);
        assertThat(search(recipient, prefixLiteral).items()).extracting(RagFileItem::name)
                .containsExactly("100%_done.pdf");
    }

    /**
     * 결과를 가져온 뒤 현재 Page에서만 PREFIX를 적용하지 않는다 - 인가된 전체
     * 검색 범위(연속 Scan)에서 조건을 먼저 적용한 뒤에도 Page 경계를 넘어
     * 안정적으로 이어진다({@code size=1}로 강제해 여러 Page를 거치게 한다).
     */
    @Test
    void prefixFilterAppliesAcrossTheFullAuthorizedScanNotJustTheCurrentPage() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        stubVisible(publisher, recipient, "sdv_a.pdf");
        stubVisible(publisher, recipient, "not_matching_b.pdf");
        stubVisible(publisher, recipient, "sdv_c.pdf");
        stubVisible(publisher, recipient, "also_not_matching_d.pdf");
        stubVisible(publisher, recipient, "sdv_e.pdf");

        List<String> collected = new ArrayList<>();
        int page = 0;
        while (true) {
            RagFileSearchQuery query = new RagFileSearchQuery("sdv", RagFileNameMatch.PREFIX, null, null, null, null,
                    RagFileSortKey.NAME_ASC, page, 1);
            RagFileSearchResponse response = search(recipient, query);
            response.items().forEach(item -> collected.add(item.name()));
            if (!Boolean.TRUE.equals(response.hasMore())) {
                break;
            }
            page++;
        }
        assertThat(collected).containsExactlyInAnyOrder("sdv_a.pdf", "sdv_c.pdf", "sdv_e.pdf");
    }

    @Test
    void aDefinitiveLiveExclusionIsExcludedWithoutFallingBackToTheCatalogNameAndDoesNotMarkCoverageIncomplete() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND, "gone"));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("no more raw candidates exist regardless of the definitive exclusion")
                .isFalse();
        assertThat(response.partial())
                .as("NOT_FOUND is a definitive exclusion, not an inability to verify - coverage stays complete")
                .isFalse();
    }

    @Test
    void neverCallsFetchContentOrAnyOtherContentBearingConnectorMethod() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Report.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Report.pdf", "application/pdf", "v1", clock.instant(), true));

        // fakeConnector.fetchContent/getMetadata/getPermissions/findChanges/listMetadata
        // 는 전부 UnsupportedOperationException을 던진다 - 예외 없이 끝나면 호출되지 않은 것이다.
        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void aDisconnectDuringTheLiveCheckExcludesTheItemAndIsTreatedAsADefinitiveResolutionNotUnknown() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // Google 호출이 "성공"으로 돌아오는 바로 그 순간, 다른 요청이 이 Source를 Disconnect했다고 가정한다.
        fakeConnector.onNextVerify(() -> {
            SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(fixture.sourceId())
                    .orElseThrow();
            connection.changeStatus("DISABLED");
            sourceConnectionJpaRepository.saveAndFlush(connection);
        });

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items())
                .as("a disconnect observed during the external I/O must be caught by the post-check re-evaluation")
                .isEmpty();
        assertThat(response.partial())
                .as("we DID successfully verify and then re-check this item - it is a resolved exclusion, not unknown")
                .isFalse();
    }

    @Test
    void aRevocationObservedDuringTheLiveCheckExcludesTheItemAndIsTreatedAsADefinitiveResolutionNotUnknown() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        DocumentShareEntity share = shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // Google 호출이 성공으로 돌아오는 바로 그 순간, 게시자가 명시적으로 unshare했다고 가정한다.
        fakeConnector.onNextVerify(() -> {
            DocumentShareEntity current = documentShareJpaRepository.findById(share.getId()).orElseThrow();
            current.revoke(clock.instant());
            documentShareJpaRepository.saveAndFlush(current);
        });

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items())
                .as("an unshare observed during the external I/O must be caught by the post-check re-evaluation")
                .isEmpty();
        assertThat(response.partial()).isFalse();
    }

    /**
     * M10B 보안 교정(그룹 C) - "don't enable sharing for legacy connection with unknown
     * provider identity." {@code providerAccountId}가 null인 연결(M10B 이전 Legacy
     * 연결, 또는 아직 한 번도 재인증을 완료하지 않은 연결)은 Token/Status가 모두
     * 정상이어도 공유 기반 검색을 허용하지 않는다 - 이메일이나 SDV Owner 동일성만으로
     * 같은 Google 계정이라고 추정하지 않는다. 소유자가 실제로 재인증해 Identity가
     * 채택된 뒤에만 검색이 열린다.
     */
    @Test
    void anUnverifiedLegacyProviderIdentityCannotGrantSharedDiscoveryUntilVerifiedBindingIsEstablished() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        // createDocument() Helper와 달리 adoptProviderAccountId를 절대 호출하지 않는다 -
        // 이 Connection의 providerAccountId는 그대로 null(Legacy/미확인 상태)이다.
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", publisher);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        String sourceDocumentId = "file-" + unique();
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), sourceDocumentId, "Doc.pdf",
                "application/pdf", "v1", clock.instant(), "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        Fixture fixture = new Fixture(connection.getId(), document.getId(), sourceDocumentId);
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items())
                .as("an unverified legacy provider identity must not grant shared discovery")
                .isEmpty();
        assertThat(response.partial())
                .as("this is a resolved/definitive exclusion, not an inability to verify")
                .isFalse();

        // 소유자가 실제로 재인증해 Identity를 채택하면(이 Test는 GoogleDriveOAuthService를
        // 거치지 않고 그 결과만 직접 반영한다) 같은 공유가 그제서야 발견된다.
        connection.adoptProviderAccountId("account-" + publisher);
        sourceConnectionJpaRepository.saveAndFlush(connection);

        RagFileSearchResponse afterVerification = search(recipient, emptyQuery());
        assertThat(afterVerification.items())
                .as("once a verified identity is established, the pre-existing share becomes discoverable")
                .hasSize(1);
    }

    /**
     * M10B 보안 교정(그룹 C) - "A disconnect/reconnect cycle must not make a
     * pre-disconnect result valid again just because status is ACTIVE again."
     * Live 확인이 진행되는 바로 그 순간 Disconnect와 재연결이 모두 끝난다 - 재확인
     * 시점에는 Status가 다시 ACTIVE로 보이므로 예전 Status 검사만으로는 이 요청을
     * 잡아내지 못했다. {@code connection_epoch}가 그 사이 올라갔다는 사실이 이
     * Context를 낡은 것으로 판정해야 한다.
     */
    @Test
    void aDisconnectReconnectCycleDuringTheLiveCheckExcludesTheItemEvenThoughStatusReadsActiveAgain() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // Google 호출이 성공으로 돌아오는 바로 그 순간, 다른 요청이 이 Source를 Disconnect한 뒤
        // 곧바로 재연결한다고 가정한다 - Status는 최종적으로 다시 ACTIVE지만 Epoch는 올라갔다.
        fakeConnector.onNextVerify(() -> {
            SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(fixture.sourceId())
                    .orElseThrow();
            connection.changeStatus("DISABLED");
            connection.bumpConnectionEpoch();
            sourceConnectionJpaRepository.saveAndFlush(connection);
            connection.changeStatus("ACTIVE");
            sourceConnectionJpaRepository.saveAndFlush(connection);
        });

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items())
                .as("status reading ACTIVE again after a disconnect/reconnect cycle must not resurrect a decision "
                        + "made before it")
                .isEmpty();
        assertThat(response.partial()).isFalse();
    }

    @Test
    void sortByNameDescendingOrdersResultsDeterministically() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        List<String> names = List.of("Alpha.pdf", "Charlie.pdf", "Bravo.pdf");
        for (String name : names) {
            Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", name, "application/pdf", "v1", "PENDING");
            shareWith(fixture, publisher, recipient);
            fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                    SourceMetadataVerificationResult.verified(name, "application/pdf", "v1", clock.instant(), true));
        }

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_DESC, 0,
                20);
        RagFileSearchResponse response = search(recipient, query);

        assertThat(response.items().stream().map(RagFileItem::name).toList())
                .containsExactly("Charlie.pdf", "Bravo.pdf", "Alpha.pdf");
    }

    // ------------------------------------------------------------------
    // 공개 Page는 원시 DB 행이 아니라 확인된(Verified) 위치를 센다.
    // ------------------------------------------------------------------

    @Test
    void hiddenOnlyCandidatesProduceAConfirmedEmptyPageNotAFalseHasMore() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        // 둘 다 공유하지 않는다 - Prefilter에서 확정적으로 배제된다(Google 호출 0회).
        createDocument(publisher, "ACTIVE", "ACTIVE", "Hidden-A.pdf", "application/pdf", "v1", "PENDING");
        createDocument(publisher, "ACTIVE", "ACTIVE", "Hidden-B.pdf", "application/pdf", "v1", "PENDING");

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("raw catalog is exhausted - confirmed, not merely absent from this page")
                .isFalse();
        assertThat(response.partial()).as("both denials are definitive - nothing was left unverified").isFalse();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void mixedVisibleAndHiddenCandidatesReturnsOnlyTheVisibleOnes() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        createDocument(publisher, "ACTIVE", "ACTIVE", "Hidden.pdf", "application/pdf", "v1", "PENDING");
        Fixture visible = createDocument(publisher, "ACTIVE", "ACTIVE", "Visible.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(visible, publisher, recipient);
        fakeConnector.stub(visible.sourceId(), visible.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Visible.pdf", "application/pdf", "v1", clock.instant(), true));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items().stream().map(RagFileItem::name).toList()).containsExactly("Visible.pdf");
        assertThat(response.hasMore()).isFalse();
        assertThat(response.partial()).isFalse();
    }

    /**
     * 이번 교정의 핵심 회귀 - 같은 보이는 파일 V가 숨은(공유되지 않은) 파일이 그 앞에
     * 0개/1개/여러 개 섞여도 항상 같은 Page 0에서 같은 값으로 반환돼야 한다. 세 시나리오
     * 모두 독립된 수신자를 써서 서로 격리한다(공유되지 않은 파일이 검색 결과에 섞이지
     * 않는다는 것과, 서로 다른 시나리오의 수신자가 섞이지 않는다는 것은 별개다).
     */
    @Test
    void theSameVisibleFileAppearsOnPageZeroRegardlessOfHowManyHiddenFilesPrecedeIt() {
        RagFileSearchQuery pageOfOne = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 1);

        String recipientNoHidden = recipient();
        stubVisible("publisher-0hidden-" + unique(), recipientNoHidden, "9-Visible.pdf");
        RagFileSearchResponse noHidden = search(recipientNoHidden, pageOfOne);

        String recipientOneHidden = recipient();
        String publisherOneHidden = "publisher-1hidden-" + unique();
        createDocument(publisherOneHidden, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(publisherOneHidden, recipientOneHidden, "9-Visible.pdf");
        RagFileSearchResponse oneHidden = search(recipientOneHidden, pageOfOne);

        String recipientManyHidden = recipient();
        String publisherManyHidden = "publisher-manyhidden-" + unique();
        createDocument(publisherManyHidden, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        createDocument(publisherManyHidden, "ACTIVE", "ACTIVE", "2-Hidden.pdf", "application/pdf", "v1", "PENDING");
        createDocument(publisherManyHidden, "ACTIVE", "ACTIVE", "3-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(publisherManyHidden, recipientManyHidden, "9-Visible.pdf");
        RagFileSearchResponse manyHidden = search(recipientManyHidden, pageOfOne);

        List<String> expectedNames = List.of("9-Visible.pdf");
        assertThat(noHidden.items().stream().map(RagFileItem::name).toList()).isEqualTo(expectedNames);
        assertThat(oneHidden.items().stream().map(RagFileItem::name).toList()).isEqualTo(expectedNames);
        assertThat(manyHidden.items().stream().map(RagFileItem::name).toList()).isEqualTo(expectedNames);
        assertThat(oneHidden.hasMore()).isEqualTo(noHidden.hasMore());
        assertThat(manyHidden.hasMore()).isEqualTo(noHidden.hasMore());
        assertThat(oneHidden.partial()).isEqualTo(noHidden.partial()).isFalse();
        assertThat(manyHidden.partial()).isEqualTo(noHidden.partial());
        assertThat(noHidden.hasMore()).as("no more raw rows exist beyond the single visible file").isFalse();
    }

    /**
     * 보이는 파일이 여럿(2개 이상)이고 숨은 파일이 그 앞/사이/뒤에 흩어져 있어도,
     * Page Size와 무관하게 "보이는 파일들의 순서 목록" 자체는 숨은 파일이 전혀 없는
     * 기준선과 완전히 같아야 한다 - 빈 Raw Page, 중복, 누락이 없어야 한다.
     */
    @Test
    void multipleVisibleFilesInterspersedWithHiddenFilesMatchTheNoHiddenBaselineAtVariousPageSizes() {
        String recipientBaseline = recipient();
        String publisherBaseline = "publisher-baseline-" + unique();
        stubVisible(publisherBaseline, recipientBaseline, "2-V1.pdf");
        stubVisible(publisherBaseline, recipientBaseline, "4-V2.pdf");
        stubVisible(publisherBaseline, recipientBaseline, "6-V3.pdf");

        String recipientInterspersed = recipient();
        String publisherInterspersed = "publisher-interspersed-" + unique();
        createDocument(publisherInterspersed, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(publisherInterspersed, recipientInterspersed, "2-V1.pdf");
        createDocument(publisherInterspersed, "ACTIVE", "ACTIVE", "3-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(publisherInterspersed, recipientInterspersed, "4-V2.pdf");
        createDocument(publisherInterspersed, "ACTIVE", "ACTIVE", "5-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(publisherInterspersed, recipientInterspersed, "6-V3.pdf");
        createDocument(publisherInterspersed, "ACTIVE", "ACTIVE", "7-Hidden.pdf", "application/pdf", "v1", "PENDING");

        for (int size : new int[] {1, 2, 5}) {
            List<String> baselineNames = collectAllVisibleNames(recipientBaseline, size);
            List<String> interspersedNames = collectAllVisibleNames(recipientInterspersed, size);
            assertThat(interspersedNames).as("page size=" + size).isEqualTo(baselineNames);
        }
    }

    @Test
    void pagingReportsHasMoreWithoutExposingRawTotals() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        for (int i = 0; i < 3; i++) {
            Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "File-" + i + ".pdf", "application/pdf",
                    "v1", "PENDING");
            shareWith(fixture, publisher, recipient);
            fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult
                    .verified("File-" + i + ".pdf", "application/pdf", "v1", clock.instant(), true));
        }

        RagFileSearchQuery firstPage = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 2);
        RagFileSearchQuery secondPage = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 1, 2);

        RagFileSearchResponse page1 = search(recipient, firstPage);
        RagFileSearchResponse page2 = search(recipient, secondPage);

        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.hasMore()).isFalse();
    }

    // ------------------------------------------------------------------
    // Bounded Candidate Scan 상한(cap) - Batch 크기와 나누어떨어지지 않거나(50/30)
    // Batch보다 작아도(1/20) 절대 상한을 넘어 평가하지 않는다.
    // ------------------------------------------------------------------

    @Test
    void aCandidateCapNotDivisibleByTheFetchBatchSizeNeverEvaluatesBeyondTheCap() {
        // cap=50, size=30 - 두 번째 DB Batch(offset 30, size 30)는 남은 21개를 읽어오지만
        // 남은 평가 여력은 20개뿐이다(50-30) - 정확히 20개만 평가되고 21번째는 평가되지 않아야 한다.
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        for (int i = 0; i < 51; i++) {
            Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", String.format("%03d-Doc.pdf", i),
                    "application/pdf", "v1", "PENDING");
            shareWith(fixture, publisher, recipient);
            if (i < 50) {
                // 51번째(Index 50)는 의도적으로 Stub하지 않는다 - 상한(50)을 넘어 평가되면
                // FakeConnector가 IllegalStateException을 던져 이 Test 자체가 실패한다.
                fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                        SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND, "gone"));
            }
        }

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                30);
        FileMetadataDiscoveryService service = newService(properties(10_000L, 50));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), query);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("the cap was reached before exhaustion could be confirmed").isNull();
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls()).as("exactly the cap, never one more from an over-fetched batch")
                .hasSize(50);
    }

    @Test
    void aCandidateCapSmallerThanTheFetchBatchSizeNeverEvaluatesMoreThanTheCap() {
        // cap=1, size=20 - 한 Batch(20개 요청)가 실제로는 2개만 반환해도, 상한 1개만 평가돼야 한다.
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture first = createDocument(publisher, "ACTIVE", "ACTIVE", "1-Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(first, publisher, recipient);
        fakeConnector.stub(first.sourceId(), first.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND, "gone"));
        Fixture second = createDocument(publisher, "ACTIVE", "ACTIVE", "2-Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(second, publisher, recipient);
        // second는 의도적으로 Stub하지 않는다 - 상한(1)을 넘어 평가되면 즉시 실패한다.

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                20);
        FileMetadataDiscoveryService service = newService(properties(10_000L, 1));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), query);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isNull();
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Unknown 확인 실패와 Deadline 소진을 정직하게 보고한다.
    // ------------------------------------------------------------------

    @Test
    void anOnlyCandidateThatFailsVerificationMarksCoverageAsUnknownNotConfirmedEmpty() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.FAILED,
                        "malformed upstream response"));

        RagFileSearchResponse response = search(recipient, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore())
                .as("this single candidate's visibility is unresolved - positions after it (if any existed) "
                        + "cannot be trusted, so exhaustion must not be claimed").isNull();
        assertThat(response.partial())
                .as("an inability to verify must not be reported as a clean, exhaustive empty search").isTrue();
    }

    @Test
    void aConfirmedVisibleItemBeforeAnUnknownCandidateIsKeptWhileCoverageStaysHonestlyIncomplete() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        // 정렬 순서상 Visible이 Unknown보다 먼저 오도록 접두사를 준다 - Visible은 Unknown을
        // 만나기 전에 이미 확정되므로 그대로 유지돼야 한다("확인 못한 것만 보류").
        Fixture visible = createDocument(publisher, "ACTIVE", "ACTIVE", "1-Visible.pdf", "application/pdf", "v1",
                "PENDING");
        Fixture unknown = createDocument(publisher, "ACTIVE", "ACTIVE", "2-Unknown.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(visible, publisher, recipient);
        shareWith(unknown, publisher, recipient);
        fakeConnector.stub(visible.sourceId(), visible.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "1-Visible.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(unknown.sourceId(), unknown.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.ACCESS_UNKNOWN,
                        "could not confirm access after bounded retries"));

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                20);
        RagFileSearchResponse response = search(recipient, query);

        assertThat(response.items().stream().map(RagFileItem::name).toList()).containsExactly("1-Visible.pdf");
        assertThat(response.hasMore())
                .as("the unknown candidate leaves later positions (including whether more exist) unresolved")
                .isNull();
        assertThat(response.partial())
                .as("the unknown candidate must not be silently treated as fully covered").isTrue();
    }

    @Test
    void anOnlyCandidateCrossingTheDeadlineDuringItsOwnCallDoesNotFalselyMarkIncompleteWhenNothingElseWasSkipped() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // 이 유일한 호출이 끝나는 바로 그 순간 예산이 소진된다 - 그러나 건너뛴 다른 후보가 없고,
        // 이 호출 자체는 이미 성공적으로 끝났다(예산은 "새 작업 시작"의 상한일 뿐이다).
        fakeConnector.onNextVerify(() -> clock.advance(Duration.ofDays(1)));

        FileMetadataDiscoveryService service = newService(properties(1L, 50));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), emptyQuery());

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasMore()).as("no more raw rows exist - nothing was left to check").isFalse();
        assertThat(response.partial())
                .as("the only candidate was fully processed - crossing the deadline afterwards is not a gap")
                .isFalse();
    }

    @Test
    void theLastCallCrossingTheDeadlineBlocksSubsequentWorkInsteadOfContinuingUncheckedWork() {
        // 이름에 순서 접두사를 둔다 - NAME_ASC 정렬로 첫 Batch(Page 0)가 먼저, 그 다음 Raw
        // 후보(Beyond)가 그 다음이 되도록 강제한다.
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture windowItem = createDocument(publisher, "ACTIVE", "ACTIVE", "1-Window.pdf", "application/pdf", "v1",
                "PENDING");
        // 예산이 이미 소진됐으므로 절대 확인되면 안 된다.
        Fixture beyond = createDocument(publisher, "ACTIVE", "ACTIVE", "2-Beyond.pdf", "application/pdf", "v1",
                "PENDING");
        shareWith(windowItem, publisher, recipient);
        shareWith(beyond, publisher, recipient);
        fakeConnector.stub(windowItem.sourceId(), windowItem.sourceDocumentId(), SourceMetadataVerificationResult
                .verified("1-Window.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(beyond.sourceId(), beyond.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "2-Beyond.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.onNextVerify(() -> clock.advance(Duration.ofDays(1)));

        RagFileSearchQuery pageOfOne = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 1);
        FileMetadataDiscoveryService service = newService(properties(1L, 50));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), pageOfOne);

        assertThat(response.items()).hasSize(1);
        assertThat(response.hasMore())
                .as("budget was already exhausted before starting the next batch - must not guess FALSE").isNull();
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls())
                .as("the beyond-page candidate must never be checked once the budget is spent")
                .doesNotContain(beyond.sourceId() + ":" + beyond.sourceDocumentId());
    }

    @Test
    void theExactDeadlineInstantIsTreatedAsExpiredNotAsRemainingBudget() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture first = createDocument(publisher, "ACTIVE", "ACTIVE", "A.pdf", "application/pdf", "v1", "PENDING");
        Fixture second = createDocument(publisher, "ACTIVE", "ACTIVE", "B.pdf", "application/pdf", "v1", "PENDING");
        shareWith(first, publisher, recipient);
        shareWith(second, publisher, recipient);
        fakeConnector.stub(first.sourceId(), first.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("A.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(second.sourceId(), second.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("B.pdf", "application/pdf", "v1", clock.instant(), true));
        // budget=10ms: 첫 호출 직후 시계를 정확히 Deadline(now+10ms)까지만 이동시킨다(그 이상이 아니다).
        Instant start = clock.instant();
        fakeConnector.onNextVerify(() -> clock.set(start.plusMillis(10)));

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                20);
        FileMetadataDiscoveryService service = newService(properties(10L, 50));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), query);

        assertThat(response.items()).as("exactly-at-deadline must count as expired, not as remaining budget")
                .hasSize(1);
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls()).hasSize(1);
    }

    @Test
    void aGenuinelyEmptyCatalogIsConfirmedNotPartial() {
        RagFileSearchResponse response = search(recipient(), emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.partial()).as("there was genuinely nothing to check - not an inability to verify")
                .isFalse();
    }

    @Test
    void liveCheckBudgetExhaustionStopsEarlyAndReportsIncompleteCoverageHonestly() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture first = createDocument(publisher, "ACTIVE", "ACTIVE", "A.pdf", "application/pdf", "v1", "PENDING");
        Fixture second = createDocument(publisher, "ACTIVE", "ACTIVE", "B.pdf", "application/pdf", "v1", "PENDING");
        shareWith(first, publisher, recipient);
        shareWith(second, publisher, recipient);
        fakeConnector.stub(first.sourceId(), first.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("A.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(second.sourceId(), second.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("B.pdf", "application/pdf", "v1", clock.instant(), true));
        // 첫 후보의 Live 호출이 끝나는 순간 예산을 모두 소진시킨다 - 두 번째 후보(B)는 검증되지
        // 않는다. B는 요청한 Page(size=20) 범위 안에 있었을 수도 있는 위치이므로, B의 상태를
        // 모르는 채로는 이 Page가 완전하다고도, hasMore가 없다고도 확정할 수 없다.
        fakeConnector.onNextVerify(() -> clock.advance(Duration.ofDays(1)));

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 20);
        FileMetadataDiscoveryService service = newService(properties(1L, 50));
        RagFileSearchResponse response = service.search(identifiedAndClearedUser(recipient), query);

        assertThat(response.partial()).as("budget exhaustion must be reported honestly").isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.hasMore())
                .as("B's own visibility (and anything possibly beyond it) was never resolved - must not guess FALSE")
                .isNull();
        assertThat(fakeConnector.verifyCalls()).hasSize(1);
    }

    @Test
    void naturalLanguageZipDiscoveryUsesTheRealMetadataServiceWithoutContentProcessing() {
        String publisher = "publisher-" + unique();
        String recipient = recipient();
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", "release-bundle.zip",
                "application/zip", "v1", "SKIPPED_UNSUPPORTED");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "release-bundle.zip", "application/zip", "v1", clock.instant(), true));
        var parsed = new NaturalLanguageFileQueryParser().parse("ZIP 파일 찾아줘", 20);

        assertThat(parsed.failureReason()).isNull();
        assertThat(parsed.query().mimeType()).isEqualTo("application/zip");
        RagFileSearchResponse response = search(recipient, parsed.query());
        assertThat(response.items()).extracting(RagFileItem::name).containsExactly("release-bundle.zip");
        assertThat(fakeConnector.verifyCalls()).containsExactly(fixture.sourceId() + ":" + fixture.sourceDocumentId());
    }

    @ParameterizedTest
    @EnumSource(AuthorizationMutation.class)
    void wholeDiscoveryResponseIsDiscardedWhenRequesterAuthorizationChangesAfterAnEarlierRowWasCollected(
            AuthorizationMutation mutation) throws Exception {
        String publisher = "publisher-final-fence-" + unique();
        String recipient = "recipient-final-fence-" + unique();
        AdminUserResponse registered = registerAndAssign(recipient, "INTERNAL", true);
        Fixture first = stubVisible(publisher, recipient, "A-first.pdf");
        Fixture second = stubVisible(publisher, recipient, "B-second.pdf");
        CountDownLatch secondCheckStarted = new CountDownLatch(1);
        CountDownLatch resumeSecondCheck = new CountDownLatch(1);
        fakeConnector.onVerify(second.sourceId(), second.sourceDocumentId(), () -> {
            secondCheckStarted.countDown();
            await(resumeSecondCheck);
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<RagFileSearchResponse> response = executor.submit(() -> newService(defaultProperties()).search(
                    identifiedUser(recipient), new RagFileSearchQuery(null, null, null, null, null,
                            RagFileSortKey.NAME_ASC, 0, 20)));
            assertThat(secondCheckStarted.await(2, TimeUnit.SECONDS)).isTrue();
            mutateAuthorization(registered, mutation);
            resumeSecondCheck.countDown();

            assertThatThrownBy(response::get)
                    .hasCauseInstanceOf(RequesterAuthorizationChangedException.class);
        }
        assertThat(fakeConnector.verifyCalls()).contains(
                first.sourceId() + ":" + first.sourceDocumentId(),
                second.sourceId() + ":" + second.sourceDocumentId());
    }

    @Test
    void unchangedRequesterAuthorizationStillReleasesTheCompleteDiscoveryResponse() {
        String publisher = "publisher-unchanged-fence-" + unique();
        String recipient = "recipient-unchanged-fence-" + unique();
        registerAndAssign(recipient, "INTERNAL", true);
        stubVisible(publisher, recipient, "A-first.pdf");
        stubVisible(publisher, recipient, "B-second.pdf");

        RagFileSearchResponse response = newService(defaultProperties()).search(identifiedUser(recipient),
                new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0, 20));

        assertThat(response.items()).extracting(RagFileItem::name)
                .containsExactly("A-first.pdf", "B-second.pdf");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private RagFileSearchResponse search(String subject, RagFileSearchQuery query) {
        FileMetadataDiscoveryService service = newService(defaultProperties());
        return service.search(identifiedAndClearedUser(subject), query);
    }

    private FileMetadataDiscoveryService newService(RagDiscoveryProperties properties) {
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(fakeConnector));
        return new FileMetadataDiscoveryService(documentShareJpaRepository, effectivePermissionService, registry,
                properties, clock);
    }

    private static RagDiscoveryProperties defaultProperties() {
        return properties(10_000L, 50);
    }

    private static RagDiscoveryProperties properties(long liveCheckBudgetMs, int maxCandidateScan) {
        return new RagDiscoveryProperties(20, 50, 500, liveCheckBudgetMs, maxCandidateScan);
    }

    private static RagFileSearchQuery emptyQuery() {
        return new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
    }

    /** 이름 그대로 보이는(공유됨+Live VERIFIED) 문서를 만들고 Fake Connector에도 등록한다. */
    private Fixture stubVisible(String publisher, String recipient, String name) {
        Fixture fixture = createDocument(publisher, "ACTIVE", "ACTIVE", name, "application/pdf", "v1", "PENDING");
        shareWith(fixture, publisher, recipient);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified(name, "application/pdf", "v1", clock.instant(), true));
        return fixture;
    }

    /**
     * {@code hasMore}가 더 이상 {@code TRUE}가 아닐 때까지(ample Budget 가정) Page를 계속
     * 요청해 실제로 보이는 파일 이름을 순서대로 모은다 - Test 전용 Bounded Loop({@code
     * hasMore}가 {@code null}이면 안전하게 멈춘다, 이 Test들의 시나리오에서는 발생하지 않는다).
     */
    private List<String> collectAllVisibleNames(String recipient, int size) {
        List<String> names = new ArrayList<>();
        int page = 0;
        while (true) {
            RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC,
                    page, size);
            RagFileSearchResponse response = search(recipient, query);
            for (RagFileItem item : response.items()) {
                names.add(item.name());
            }
            if (!Boolean.TRUE.equals(response.hasMore())) {
                break;
            }
            page++;
        }
        return names;
    }

    private Fixture createDocument(String publisherSubject, String connectionStatus, String documentState,
            String name, String mimeType, String sourceVersion, String indexStatus) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source",
                connectionStatus, "FULL", publisherSubject);
        // M10B 보안 교정(그룹 C) - 이 Fixture는 "이미 정상적으로 재인증을 거쳐 Provider
        // 신원이 확인된" 일반적인 연결을 나타낸다(EffectivePermissionService.decideShared의
        // SOURCE_IDENTITY_UNVERIFIED 검사를 통과시킨다). Identity 미확인 자체를 검증하는
        // 것이 목적인 Test는 아래 anUnverifiedLegacyProviderIdentity...Test처럼 이 Helper를
        // 쓰지 않고 직접 만든다.
        connection.adoptProviderAccountId("verified-account-" + publisherSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        String sourceDocumentId = "file-" + unique();
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), sourceDocumentId, name,
                mimeType, sourceVersion, clock.instant(), documentState, indexStatus, null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return new Fixture(connection.getId(), document.getId(), sourceDocumentId);
    }

    /** M10B - ACL grant 대신 명시적 공유(VIEW, INTERNAL 등급)를 지정 수신자에게 게시한다. */
    private DocumentShareEntity shareWith(Fixture fixture, String publisherSubject, String recipientSubject) {
        return shareWithActions(fixture, publisherSubject, recipientSubject, "VIEW");
    }

    /** M16C - Action Set을 직접 지정한다(예: {@code "VIEW,DOWNLOAD"}) - shareId/allowedActions 노출 검증용. */
    private DocumentShareEntity shareWithActions(Fixture fixture, String publisherSubject, String recipientSubject,
            String actionsCsv) {
        DocumentShareEntity share = new DocumentShareEntity(publisherSubject, fixture.sourceId(),
                fixture.documentId(), "INTERNAL", actionsCsv, clock.instant());
        documentShareJpaRepository.saveAndFlush(share);
        documentShareRecipientJpaRepository
                .saveAndFlush(new DocumentShareRecipientEntity(share.getId(), recipientSubject));
        return share;
    }

    /**
     * M10B - 검색이 더 이상 Owner Scope가 아니라 Recipient Scope이므로(같은
     * PostgreSQL을 Rollback 없이 재사용하는 이 Class에서), 매 Test가 고유한 수신자를
     * 새로 발급해 앞선 Test가 남긴 공유가 섞이지 않게 한다({@code publisher}가
     * 이전에 이미 그랬던 것과 같은 격리 원칙).
     */
    private static String recipient() {
        return "recipient-" + unique();
    }

    private UserContext identifiedUser(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of(), ISSUER, subject);
    }

    private UserContext identifiedAndClearedUser(String subject) {
        if (identityRegistryService.currentAuthorization(ISSUER, subject).isEmpty()) {
            registerAndAssign(subject, "SECRET", true);
        }
        return identifiedUser(subject);
    }

    private AdminUserResponse registerAndAssign(String subject, String level, boolean active) {
        identityRegistryService.observeValidatedLogin(ISSUER, subject, subject, subject);
        String boundedQuery = subject.substring(0, Math.min(subject.length(), 50));
        AdminUserResponse user = identityRegistryService.adminSearch(boundedQuery, 0, 50).items().stream()
                .filter(candidate -> candidate.loginId().equals(subject)).findFirst().orElseThrow();
        return identityRegistryService.updateAccess("admin-test", user.id(), user.version(), level, active);
    }

    private void mutateAuthorization(AdminUserResponse user, AuthorizationMutation mutation) {
        switch (mutation) {
            case DOWNGRADE -> identityRegistryService.updateAccess("admin-test", user.id(), user.version(),
                    "PUBLIC", true);
            case RESET -> identityRegistryService.updateAccess("admin-test", user.id(), user.version(), null, true);
            case DISABLE -> identityRegistryService.updateAccess("admin-test", user.id(), user.version(),
                    "INTERNAL", false);
            case ABA -> {
                AdminUserResponse downgraded = identityRegistryService.updateAccess("admin-test", user.id(),
                        user.version(), "PUBLIC", true);
                identityRegistryService.updateAccess("admin-test", downgraded.id(), downgraded.version(),
                        "INTERNAL", true);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private enum AuthorizationMutation { DOWNGRADE, RESET, DISABLE, ABA }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(Long sourceId, Long documentId, String sourceDocumentId) {
    }

    /** 실제 Google 호출 없이 결과를 미리 정하고, 호출 여부/횟수/시점 부수효과(Hook)를 검증하기 위한 Test Double. */
    private static final class FakeConnector implements DocumentSourceConnector {
        private final Map<String, SourceMetadataVerificationResult> canned = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private Runnable onNextVerify;
        private final Map<String, Runnable> verifyHooks = new HashMap<>();

        void stub(Long sourceId, String sourceDocumentId, SourceMetadataVerificationResult result) {
            canned.put(key(sourceId, sourceDocumentId), result);
        }

        /** 다음 {@link #verifyCurrentMetadata} 호출이 결과를 계산하기 직전에 정확히 한 번 실행된다. */
        void onNextVerify(Runnable hook) {
            this.onNextVerify = hook;
        }

        void onVerify(Long sourceId, String sourceDocumentId, Runnable hook) {
            verifyHooks.put(key(sourceId, sourceDocumentId), hook);
        }

        List<String> verifyCalls() {
            return calls;
        }

        @Override
        public SourceType supportedType() {
            return SourceType.GOOGLE_DRIVE;
        }

        @Override
        public SourceDocument getMetadata(Long sourceId, String sourceDocumentId) {
            throw new UnsupportedOperationException("File Metadata Discovery must never call Catalog Sync methods");
        }

        @Override
        public SourceMetadataPage listMetadata(Long sourceId, String pageToken) {
            throw new UnsupportedOperationException("File Metadata Discovery must never call Catalog Sync methods");
        }

        @Override
        public SourceContentResult fetchContent(UserContext requestingUser, Long sourceId, String sourceDocumentId,
                String expectedSourceVersion) {
            throw new UnsupportedOperationException(
                    "File Metadata Discovery must never fetch content, export, or otherwise download bytes");
        }

        @Override
        public SourcePermissionsResult getPermissions(Long sourceId, String sourceDocumentId) {
            throw new UnsupportedOperationException("File Metadata Discovery must never call Catalog Sync methods");
        }

        @Override
        public SourceChangePage findChanges(Long sourceId, String pageToken) {
            throw new UnsupportedOperationException("File Metadata Discovery must never call Catalog Sync methods");
        }

        @Override
        public SourceMetadataVerificationResult verifyCurrentMetadata(UserContext requestingUser, Long sourceId,
                String sourceDocumentId) {
            calls.add(key(sourceId, sourceDocumentId));
            Runnable keyedHook = verifyHooks.remove(key(sourceId, sourceDocumentId));
            if (keyedHook != null) keyedHook.run();
            if (onNextVerify != null) {
                Runnable hook = onNextVerify;
                onNextVerify = null;
                hook.run();
            }
            SourceMetadataVerificationResult result = canned.get(key(sourceId, sourceDocumentId));
            if (result == null) {
                throw new IllegalStateException("no canned verifyCurrentMetadata result for " + key(sourceId,
                        sourceDocumentId));
            }
            return result;
        }

        private static String key(Long sourceId, String sourceDocumentId) {
            return sourceId + ":" + sourceDocumentId;
        }
    }

    /** 예산(Budget) 소진을 Sleep 없이 결정론적으로 재현하기 위한 Test Double. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        /** 특정 순간(예: Deadline 경계 정확히)으로 직접 이동한다 - 상대 이동이 아니라 절대 지정. */
        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
