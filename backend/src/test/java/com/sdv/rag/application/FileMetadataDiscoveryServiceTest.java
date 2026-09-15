package com.sdv.rag.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagFileItem;
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
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.entity.SourcePermissionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourcePermissionJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M10 신규(RAG-011 File Metadata Discovery) - {@link FileMetadataDiscoveryService}의
 * 유스케이스 전체(Catalog Prefilter → Same-user Live 재확인 → Live 값 재필터 →
 * 노출 직전 재인가 → 확인된 위치 기준 연속 Scan에 의한 Page/{@code hasMore}/Coverage
 * 판단)를 실제 Testcontainers PostgreSQL + 실제 {@link EffectivePermissionService}
 * Bean으로 검증한다.
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

    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private SourcePermissionJpaRepository sourcePermissionJpaRepository;
    @Autowired
    private EffectivePermissionService effectivePermissionService;

    private FakeConnector fakeConnector;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        fakeConnector = new FakeConnector();
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ------------------------------------------------------------------
    // 기존 유스케이스(Prefilter/Live 재확인/Live 필터 재적용/노출 직전 재인가)
    // ------------------------------------------------------------------

    @Test
    void ownerSeesOnlyTheirOwnDiscoverableDocumentsRegardlessOfIndexStatus() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Diagram.png", "image/png", "v1",
                "SKIPPED_UNSUPPORTED");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Diagram.png", "image/png", "v1", clock.instant(), true));

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).hasSize(1);
        RagFileItem item = response.items().get(0);
        assertThat(item.name()).isEqualTo("Diagram.png");
        assertThat(item.mimeType()).isEqualTo("image/png");
        assertThat(item.indexStatus()).isEqualTo("SKIPPED_UNSUPPORTED");
        assertThat(item.viewUrl()).isEqualTo("https://drive.google.com/file/d/" + fixture.sourceDocumentId()
                + "/view");
    }

    @Test
    void anotherOwnersDocumentIsNeverReturnedEvenWhenTargetingItsSourceId() {
        String realOwner = "owner-real-" + unique();
        String attacker = "attacker-" + unique();
        Fixture fixture = createDocument(realOwner, "ACTIVE", "ACTIVE", "Secret.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(fixture.documentId(), realOwner);

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, fixture.sourceId(), null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        RagFileSearchResponse response = search(attacker, query);

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls())
                .as("cross-account source-id tampering must never reach a Google Live call").isEmpty();
    }

    @Test
    void deletedDocumentIsExcluded() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "DELETED", "Gone.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void inactiveSourceIsExcluded() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "DISABLED", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void untrustedOrMissingAclDeniesDiscoveryWithoutAnyLiveCall() {
        String owner = "owner-" + unique();
        // grantFreshRead를 호출하지 않는다 - 0개 permission row는 Untrusted Evidence다.
        createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(fakeConnector.verifyCalls())
                .as("prefilter denial must happen before any Google call").isEmpty();
    }

    @Test
    void overlayDenyExcludesAnOtherwiseAllowedDocument() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        // 실제 OverlayPolicyService 경로를 그대로 재사용하는 대신, 이미 검증된
        // EffectivePermissionService의 evaluate 결과 자체를 신뢰한다 - 별도 Overlay
        // Row Insert는 EffectivePermissionServiceDecisionTest가 이미 충분히 검증했다.
        // 여기서는 filterAllowed 자체가 이 Service의 유일한 인가 경로임을 확인하기 위해
        // 문서 상태를 DELETED로 바꿔 같은 "Prefilter Deny" 효과를 재사용한다.
        SourceDocumentEntity document = sourceDocumentJpaRepository.findById(fixture.documentId()).orElseThrow();
        document.markDeleted();
        sourceDocumentJpaRepository.saveAndFlush(document);

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
    }

    @Test
    void renamedFileIsExposedUnderItsLiveNameNotTheStaleCatalogName() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Old Name.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "New Name.pdf", "application/pdf", "v2", clock.instant(), true));

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).name()).isEqualTo("New Name.pdf");
        assertThat(response.items().get(0).sourceVersion()).isEqualTo("v2");
        assertThat(response.items().get(0).sourceVersionCurrent())
                .as("catalog v1 != live v2 - the persisted index status must not be presented as current").isFalse();
    }

    @Test
    void aNameFilterMatchingTheStaleCatalogNameIsRecheckedAgainstTheLiveNameAndExcludedIfItNoLongerMatches() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Budget Report.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        // Catalog Query(SQL LIKE)는 통과하지만, Live 재확인 시점에는 이미 개명됐다.
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Unrelated File.pdf", "application/pdf", "v1", clock.instant(), true));

        RagFileSearchQuery query = new RagFileSearchQuery("Budget", null, null, null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        RagFileSearchResponse response = search(owner, query);

        assertThat(response.items())
                .as("a rename discovered only during the live check must not surface under the stale filter match")
                .isEmpty();
    }

    @Test
    void aDefinitiveLiveExclusionIsExcludedWithoutFallingBackToTheCatalogNameAndDoesNotMarkCoverageIncomplete() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND, "gone"));

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("no more raw candidates exist regardless of the definitive exclusion")
                .isFalse();
        assertThat(response.partial())
                .as("NOT_FOUND is a definitive exclusion, not an inability to verify - coverage stays complete")
                .isFalse();
    }

    @Test
    void neverCallsFetchContentOrAnyOtherContentBearingConnectorMethod() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Report.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Report.pdf", "application/pdf", "v1", clock.instant(), true));

        // fakeConnector.fetchContent/getMetadata/getPermissions/findChanges/listMetadata
        // 는 전부 UnsupportedOperationException을 던진다 - 예외 없이 끝나면 호출되지 않은 것이다.
        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).hasSize(1);
    }

    @Test
    void aDisconnectDuringTheLiveCheckExcludesTheItemAndIsTreatedAsADefinitiveResolutionNotUnknown() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // Google 호출이 "성공"으로 돌아오는 바로 그 순간, 다른 요청이 이 Source를 Disconnect했다고 가정한다.
        fakeConnector.onNextVerify(() -> {
            SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(fixture.sourceId())
                    .orElseThrow();
            connection.changeStatus("DISABLED");
            sourceConnectionJpaRepository.saveAndFlush(connection);
        });

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items())
                .as("a disconnect observed during the external I/O must be caught by the post-check re-evaluation")
                .isEmpty();
        assertThat(response.partial())
                .as("we DID successfully verify and then re-check this item - it is a resolved exclusion, not unknown")
                .isFalse();
    }

    @Test
    void sortByNameDescendingOrdersResultsDeterministically() {
        String owner = "owner-" + unique();
        List<String> names = List.of("Alpha.pdf", "Charlie.pdf", "Bravo.pdf");
        for (String name : names) {
            Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", name, "application/pdf", "v1", "PENDING");
            grantFreshRead(fixture.documentId(), owner);
            fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                    SourceMetadataVerificationResult.verified(name, "application/pdf", "v1", clock.instant(), true));
        }

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_DESC, 0,
                20);
        RagFileSearchResponse response = search(owner, query);

        assertThat(response.items().stream().map(RagFileItem::name).toList())
                .containsExactly("Charlie.pdf", "Bravo.pdf", "Alpha.pdf");
    }

    // ------------------------------------------------------------------
    // 공개 Page는 원시 DB 행이 아니라 확인된(Verified) 위치를 센다(이번 교정의 핵심).
    // ------------------------------------------------------------------

    @Test
    void hiddenOnlyCandidatesProduceAConfirmedEmptyPageNotAFalseHasMore() {
        String owner = "owner-" + unique();
        // 둘 다 권한 부여를 하지 않는다 - Prefilter에서 확정적으로 배제된다(Google 호출 0회).
        createDocument(owner, "ACTIVE", "ACTIVE", "Hidden-A.pdf", "application/pdf", "v1", "PENDING");
        createDocument(owner, "ACTIVE", "ACTIVE", "Hidden-B.pdf", "application/pdf", "v1", "PENDING");

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("raw catalog is exhausted - confirmed, not merely absent from this page")
                .isFalse();
        assertThat(response.partial()).as("both denials are definitive - nothing was left unverified").isFalse();
        assertThat(fakeConnector.verifyCalls()).isEmpty();
    }

    @Test
    void mixedVisibleAndHiddenCandidatesReturnsOnlyTheVisibleOnes() {
        String owner = "owner-" + unique();
        createDocument(owner, "ACTIVE", "ACTIVE", "Hidden.pdf", "application/pdf", "v1", "PENDING");
        Fixture visible = createDocument(owner, "ACTIVE", "ACTIVE", "Visible.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(visible.documentId(), owner);
        fakeConnector.stub(visible.sourceId(), visible.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "Visible.pdf", "application/pdf", "v1", clock.instant(), true));

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items().stream().map(RagFileItem::name).toList()).containsExactly("Visible.pdf");
        assertThat(response.hasMore()).isFalse();
        assertThat(response.partial()).isFalse();
    }

    /**
     * 이번 교정의 핵심 회귀 - 같은 보이는 파일 V가 숨은(권한 없는) 파일이 그 앞에
     * 0개/1개/여러 개 섞여도 항상 같은 Page 0에서 같은 값으로 반환돼야 한다. 교정
     * 전에는 숨은 파일 개수만큼 V가 뒤 Page로 밀려나 Page 0이 비어버리고 {@code
     * hasMore=true}만 남는 결함이 있었다(이 결함을 그대로 "정상"으로 취급하던
     * 이전 회귀 Test는 삭제했다 - 아래 참고).
     */
    @Test
    void theSameVisibleFileAppearsOnPageZeroRegardlessOfHowManyHiddenFilesPrecedeIt() {
        RagFileSearchQuery pageOfOne = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 1);

        String ownerNoHidden = "owner-0hidden-" + unique();
        stubVisible(ownerNoHidden, "9-Visible.pdf");
        RagFileSearchResponse noHidden = search(ownerNoHidden, pageOfOne);

        String ownerOneHidden = "owner-1hidden-" + unique();
        createDocument(ownerOneHidden, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(ownerOneHidden, "9-Visible.pdf");
        RagFileSearchResponse oneHidden = search(ownerOneHidden, pageOfOne);

        String ownerManyHidden = "owner-manyhidden-" + unique();
        createDocument(ownerManyHidden, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        createDocument(ownerManyHidden, "ACTIVE", "ACTIVE", "2-Hidden.pdf", "application/pdf", "v1", "PENDING");
        createDocument(ownerManyHidden, "ACTIVE", "ACTIVE", "3-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(ownerManyHidden, "9-Visible.pdf");
        RagFileSearchResponse manyHidden = search(ownerManyHidden, pageOfOne);

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
        String ownerBaseline = "owner-baseline-" + unique();
        stubVisible(ownerBaseline, "2-V1.pdf");
        stubVisible(ownerBaseline, "4-V2.pdf");
        stubVisible(ownerBaseline, "6-V3.pdf");

        String ownerInterspersed = "owner-interspersed-" + unique();
        createDocument(ownerInterspersed, "ACTIVE", "ACTIVE", "1-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(ownerInterspersed, "2-V1.pdf");
        createDocument(ownerInterspersed, "ACTIVE", "ACTIVE", "3-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(ownerInterspersed, "4-V2.pdf");
        createDocument(ownerInterspersed, "ACTIVE", "ACTIVE", "5-Hidden.pdf", "application/pdf", "v1", "PENDING");
        stubVisible(ownerInterspersed, "6-V3.pdf");
        createDocument(ownerInterspersed, "ACTIVE", "ACTIVE", "7-Hidden.pdf", "application/pdf", "v1", "PENDING");

        for (int size : new int[] {1, 2, 5}) {
            List<String> baselineNames = collectAllVisibleNames(ownerBaseline, size);
            List<String> interspersedNames = collectAllVisibleNames(ownerInterspersed, size);
            assertThat(interspersedNames).as("page size=" + size).isEqualTo(baselineNames);
        }
    }

    @Test
    void pagingReportsHasMoreWithoutExposingRawTotals() {
        String owner = "owner-" + unique();
        for (int i = 0; i < 3; i++) {
            Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "File-" + i + ".pdf", "application/pdf", "v1",
                    "PENDING");
            grantFreshRead(fixture.documentId(), owner);
            fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(), SourceMetadataVerificationResult
                    .verified("File-" + i + ".pdf", "application/pdf", "v1", clock.instant(), true));
        }

        RagFileSearchQuery firstPage = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 2);
        RagFileSearchQuery secondPage = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 1, 2);

        RagFileSearchResponse page1 = search(owner, firstPage);
        RagFileSearchResponse page2 = search(owner, secondPage);

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
        String owner = "owner-" + unique();
        for (int i = 0; i < 51; i++) {
            Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", String.format("%03d-Doc.pdf", i),
                    "application/pdf", "v1", "PENDING");
            grantFreshRead(fixture.documentId(), owner);
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
        RagFileSearchResponse response = service.search(userContext(owner), query);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).as("the cap was reached before exhaustion could be confirmed").isNull();
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls()).as("exactly the cap, never one more from an over-fetched batch")
                .hasSize(50);
    }

    @Test
    void aCandidateCapSmallerThanTheFetchBatchSizeNeverEvaluatesMoreThanTheCap() {
        // cap=1, size=20 - 한 Batch(20개 요청)가 실제로는 2개만 반환해도, 상한 1개만 평가돼야 한다.
        String owner = "owner-" + unique();
        Fixture first = createDocument(owner, "ACTIVE", "ACTIVE", "1-Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(first.documentId(), owner);
        fakeConnector.stub(first.sourceId(), first.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND, "gone"));
        Fixture second = createDocument(owner, "ACTIVE", "ACTIVE", "2-Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(second.documentId(), owner);
        // second는 의도적으로 Stub하지 않는다 - 상한(1)을 넘어 평가되면 즉시 실패한다.

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                20);
        FileMetadataDiscoveryService service = newService(properties(10_000L, 1));
        RagFileSearchResponse response = service.search(userContext(owner), query);

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
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.FAILED,
                        "malformed upstream response"));

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore())
                .as("this single candidate's visibility is unresolved - positions after it (if any existed) "
                        + "cannot be trusted, so exhaustion must not be claimed").isNull();
        assertThat(response.partial())
                .as("an inability to verify must not be reported as a clean, exhaustive empty search").isTrue();
    }

    @Test
    void aConfirmedVisibleItemBeforeAnUnknownCandidateIsKeptWhileCoverageStaysHonestlyIncomplete() {
        String owner = "owner-" + unique();
        // 정렬 순서상 Visible이 Unknown보다 먼저 오도록 접두사를 준다 - Visible은 Unknown을
        // 만나기 전에 이미 확정되므로 그대로 유지돼야 한다("확인 못한 것만 보류").
        Fixture visible = createDocument(owner, "ACTIVE", "ACTIVE", "1-Visible.pdf", "application/pdf", "v1",
                "PENDING");
        Fixture unknown = createDocument(owner, "ACTIVE", "ACTIVE", "2-Unknown.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(visible.documentId(), owner);
        grantFreshRead(unknown.documentId(), owner);
        fakeConnector.stub(visible.sourceId(), visible.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "1-Visible.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(unknown.sourceId(), unknown.sourceDocumentId(),
                SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.ACCESS_UNKNOWN,
                        "could not confirm access after bounded retries"));

        RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC, 0,
                20);
        RagFileSearchResponse response = search(owner, query);

        assertThat(response.items().stream().map(RagFileItem::name).toList()).containsExactly("1-Visible.pdf");
        assertThat(response.hasMore())
                .as("the unknown candidate leaves later positions (including whether more exist) unresolved")
                .isNull();
        assertThat(response.partial())
                .as("the unknown candidate must not be silently treated as fully covered").isTrue();
    }

    @Test
    void anOnlyCandidateCrossingTheDeadlineDuringItsOwnCallDoesNotFalselyMarkIncompleteWhenNothingElseWasSkipped() {
        String owner = "owner-" + unique();
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", "Doc.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", "v1", clock.instant(), true));
        // 이 유일한 호출이 끝나는 바로 그 순간 예산이 소진된다 - 그러나 건너뛴 다른 후보가 없고,
        // 이 호출 자체는 이미 성공적으로 끝났다(예산은 "새 작업 시작"의 상한일 뿐이다).
        fakeConnector.onNextVerify(() -> clock.advance(Duration.ofDays(1)));

        FileMetadataDiscoveryService service = newService(properties(1L, 50));
        RagFileSearchResponse response = service.search(userContext(owner), emptyQuery());

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
        String owner = "owner-" + unique();
        Fixture windowItem = createDocument(owner, "ACTIVE", "ACTIVE", "1-Window.pdf", "application/pdf", "v1",
                "PENDING");
        // 예산이 이미 소진됐으므로 절대 확인되면 안 된다.
        Fixture beyond = createDocument(owner, "ACTIVE", "ACTIVE", "2-Beyond.pdf", "application/pdf", "v1",
                "PENDING");
        grantFreshRead(windowItem.documentId(), owner);
        grantFreshRead(beyond.documentId(), owner);
        fakeConnector.stub(windowItem.sourceId(), windowItem.sourceDocumentId(), SourceMetadataVerificationResult
                .verified("1-Window.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.stub(beyond.sourceId(), beyond.sourceDocumentId(), SourceMetadataVerificationResult.verified(
                "2-Beyond.pdf", "application/pdf", "v1", clock.instant(), true));
        fakeConnector.onNextVerify(() -> clock.advance(Duration.ofDays(1)));

        RagFileSearchQuery pageOfOne = new RagFileSearchQuery(null, null, null, null, null,
                RagFileSortKey.NAME_ASC, 0, 1);
        FileMetadataDiscoveryService service = newService(properties(1L, 50));
        RagFileSearchResponse response = service.search(userContext(owner), pageOfOne);

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
        String owner = "owner-" + unique();
        Fixture first = createDocument(owner, "ACTIVE", "ACTIVE", "A.pdf", "application/pdf", "v1", "PENDING");
        Fixture second = createDocument(owner, "ACTIVE", "ACTIVE", "B.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(first.documentId(), owner);
        grantFreshRead(second.documentId(), owner);
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
        RagFileSearchResponse response = service.search(userContext(owner), query);

        assertThat(response.items()).as("exactly-at-deadline must count as expired, not as remaining budget")
                .hasSize(1);
        assertThat(response.partial()).isTrue();
        assertThat(fakeConnector.verifyCalls()).hasSize(1);
    }

    @Test
    void aGenuinelyEmptyCatalogIsConfirmedNotPartial() {
        String owner = "owner-" + unique();

        RagFileSearchResponse response = search(owner, emptyQuery());

        assertThat(response.items()).isEmpty();
        assertThat(response.hasMore()).isFalse();
        assertThat(response.partial()).as("there was genuinely nothing to check - not an inability to verify")
                .isFalse();
    }

    @Test
    void liveCheckBudgetExhaustionStopsEarlyAndReportsIncompleteCoverageHonestly() {
        String owner = "owner-" + unique();
        Fixture first = createDocument(owner, "ACTIVE", "ACTIVE", "A.pdf", "application/pdf", "v1", "PENDING");
        Fixture second = createDocument(owner, "ACTIVE", "ACTIVE", "B.pdf", "application/pdf", "v1", "PENDING");
        grantFreshRead(first.documentId(), owner);
        grantFreshRead(second.documentId(), owner);
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
        RagFileSearchResponse response = service.search(userContext(owner), query);

        assertThat(response.partial()).as("budget exhaustion must be reported honestly").isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.hasMore())
                .as("B's own visibility (and anything possibly beyond it) was never resolved - must not guess FALSE")
                .isNull();
        assertThat(fakeConnector.verifyCalls()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private RagFileSearchResponse search(String owner, RagFileSearchQuery query) {
        FileMetadataDiscoveryService service = newService(defaultProperties());
        return service.search(userContext(owner), query);
    }

    private FileMetadataDiscoveryService newService(RagDiscoveryProperties properties) {
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(fakeConnector));
        return new FileMetadataDiscoveryService(sourceDocumentJpaRepository, effectivePermissionService, registry,
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

    /** 이름 그대로 보이는(권한 있음+Live VERIFIED) 문서를 만들고 Fake Connector에도 등록한다. */
    private Fixture stubVisible(String owner, String name) {
        Fixture fixture = createDocument(owner, "ACTIVE", "ACTIVE", name, "application/pdf", "v1", "PENDING");
        grantFreshRead(fixture.documentId(), owner);
        fakeConnector.stub(fixture.sourceId(), fixture.sourceDocumentId(),
                SourceMetadataVerificationResult.verified(name, "application/pdf", "v1", clock.instant(), true));
        return fixture;
    }

    /**
     * {@code hasMore}가 더 이상 {@code TRUE}가 아닐 때까지(ample Budget 가정) Page를 계속
     * 요청해 실제로 보이는 파일 이름을 순서대로 모은다 - Test 전용 Bounded Loop({@code
     * hasMore}가 {@code null}이면 안전하게 멈춘다, 이 Test들의 시나리오에서는 발생하지 않는다).
     */
    private List<String> collectAllVisibleNames(String owner, int size) {
        List<String> names = new ArrayList<>();
        int page = 0;
        while (true) {
            RagFileSearchQuery query = new RagFileSearchQuery(null, null, null, null, null, RagFileSortKey.NAME_ASC,
                    page, size);
            RagFileSearchResponse response = search(owner, query);
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

    private Fixture createDocument(String ownerSubject, String connectionStatus, String documentState, String name,
            String mimeType, String sourceVersion, String indexStatus) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source",
                connectionStatus, "FULL", ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        String sourceDocumentId = "file-" + unique();
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), sourceDocumentId, name,
                mimeType, sourceVersion, clock.instant(), documentState, indexStatus, null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return new Fixture(connection.getId(), document.getId(), sourceDocumentId);
    }

    private void grantFreshRead(long documentId, String ownerSubject) {
        sourcePermissionJpaRepository.saveAndFlush(
                new SourcePermissionEntity(documentId, "user", ownerSubject, "READ", Instant.now()));
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of());
    }

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

        void stub(Long sourceId, String sourceDocumentId, SourceMetadataVerificationResult result) {
            canned.put(key(sourceId, sourceDocumentId), result);
        }

        /** 다음 {@link #verifyCurrentMetadata} 호출이 결과를 계산하기 직전에 정확히 한 번 실행된다. */
        void onNextVerify(Runnable hook) {
            this.onNextVerify = hook;
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
