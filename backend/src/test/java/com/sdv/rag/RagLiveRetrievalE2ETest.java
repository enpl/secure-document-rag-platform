package com.sdv.rag;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.policy.infrastructure.persistence.entity.AiUsagePolicyEntity;
import com.sdv.policy.infrastructure.persistence.repository.AiUsagePolicyJpaRepository;
import com.sdv.rag.application.LiveEvidenceRetrievalService;
import com.sdv.rag.application.RagRetrievalService;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.PgVectorSearchAdapter;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.infrastructure.google.GoogleDriveConnector;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * M12 승인 범위(`docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.4/§2A.5) - Focused Acceptance
 * Test Group A(인가된 흐름)/B(거부)/C(신선도/경합). 실제 Testcontainers PostgreSQL +
 * 실제 {@link RagRetrievalService}/{@link LiveEvidenceRetrievalService}/{@code
 * SourceConsistencyGuard}/{@code EffectivePermissionService} Bean을 그대로 쓴다 -
 * Google/Python 외부 전송만 {@code @MockitoSpyBean}/{@code @MockitoBean}으로
 * 대체한다(Repository/인가/Transaction 자체는 절대 Mock하지 않는다).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class RagLiveRetrievalE2ETest {

    private static final long BOUND_SECONDS = 10;
    private static final String ISSUER = "http://localhost:8180/realms/sdv";

    @Autowired
    private RagRetrievalService ragRetrievalService;
    @Autowired
    private LiveEvidenceRetrievalService liveEvidenceRetrievalService;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private AiUsagePolicyJpaRepository aiUsagePolicyJpaRepository;
    @Autowired
    private IdentityRegistryService identities;

    @MockitoSpyBean
    private GoogleDriveConnector googleDriveConnector;
    @MockitoSpyBean
    private PgVectorSearchAdapter pgVectorSearchAdapter;
    @MockitoBean
    private DocumentParsingClient documentParsingClient;

    private final ExecutorService executor = Executors.newFixedThreadPool(1);
    private final Map<String, String> conversations = new HashMap<>();

    private String conversation(UserContext requester, String label) {
        return conversations.computeIfAbsent(requester.subject() + ":" + label,
                ignored -> ragRetrievalService.openEvidenceConversation(requester));
    }

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @BeforeEach
    void ensureLocalAiUsageIsAllowedForInternal() {
        if (aiUsagePolicyJpaRepository.findById("INTERNAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("INTERNAL", "LOCAL_ONLY", false));
        }
        if (aiUsagePolicyJpaRepository.findById("CONFIDENTIAL").isEmpty()) {
            aiUsagePolicyJpaRepository.saveAndFlush(new AiUsagePolicyEntity("CONFIDENTIAL", "AI_DENIED", false));
        }
    }

    // ------------------------------------------------------------------
    // Group A - 인가된 흐름
    // ------------------------------------------------------------------

    @Test
    void authorizedRecipientRetrievesVersionBoundVerifiedEvidenceForASharedFile() {
        Fixture fixture = createFixture("publisher-a-" + UUID.randomUUID(), "recipient-b-" + UUID.randomUUID());
        stubSingleLocationPdf(fixture, "Company policy text.");

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-1"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        assertThat(result.locatorType()).isEqualTo(LocatorType.PAGE);
        assertThat(result.locatorValue()).isEqualTo("1");
        assertThat(result.partialCoverage()).isFalse();

        String released = ragRetrievalService.releaseEvidence(fixture.recipientContext(), fixture.documentId(),
                conversation(fixture.recipientContext(), "conv-1"), result.evidenceHandle()).orElseThrow();
        assertThat(released).isEqualTo("Company policy text.");
    }

    @Test
    void authorizedDirectlySelectedFileWithNoVectorCandidateStillPassesTheSameLiveGate() {
        Fixture fixture = createFixture("publisher-direct-" + UUID.randomUUID(), "recipient-direct-" + UUID.randomUUID());
        stubSingleLocationPdf(fixture, "Directly selected content.");

        // 직접 선택 - Vector Candidate 없음(candidate == null)이지만 완전히 동일한 Live Gate를 거친다.
        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-direct"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        verify(googleDriveConnector).fetchForAi(any(), eq("v1"), anyLong());
    }

    @Test
    void aVectorCandidateLocatorIsUsedToSelectTheMatchingPageFromFreshlyParsedContent() {
        Fixture fixture = createFixture("publisher-multi-" + UUID.randomUUID(), "recipient-multi-" + UUID.randomUUID());
        String page1 = "page one text.";
        String page2 = "page two text.";
        String fullText = page1 + page2;
        stubFetch(fixture, "v1");
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", fullText,
                List.of(new ExtractedLocation(LocatorType.PAGE, "1", 0, page1.length()),
                        new ExtractedLocation(LocatorType.PAGE, "2", page1.length(), fullText.length())), "2",
                "bge-m3:567m", List.of(new com.sdv.rag.domain.ExtractedChunk(0, LocatorType.PAGE, "1", 0,
                        page1.length()), new com.sdv.rag.domain.ExtractedChunk(1, LocatorType.PAGE, "2", page1.length(),
                        fullText.length()))));
        VectorCandidate candidate = new VectorCandidate(fixture.documentId(), 1, LocatorType.PAGE, "2", "v1", "1",
                "2", "bge-m3:567m");

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-multi"), candidate);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        assertThat(result.locatorValue()).isEqualTo("2");
        // 여러 위치로 이뤄진 문서에서 하나만 담았다는 사실을 정직하게 알린다.
        assertThat(result.partialCoverage()).isTrue();
        String released = ragRetrievalService.releaseEvidence(fixture.recipientContext(), fixture.documentId(),
                conversation(fixture.recipientContext(), "conv-multi"), result.evidenceHandle()).orElseThrow();
        assertThat(released).isEqualTo(page2);
    }

    // ------------------------------------------------------------------
    // Group B - 거부
    // ------------------------------------------------------------------

    @Test
    void anUnsharedFileNeverExposesEvidenceAndNeverTriggersAContentFetch() {
        Fixture fixture = createUnsharedDocument("owner-unshared-" + UUID.randomUUID());
        UserContext stranger = userContext("stranger-" + UUID.randomUUID());

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(stranger, fixture.documentId(),
                conversation(stranger, "conv-x"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
        verifyNoInteractions(documentParsingClient);
        verify(googleDriveConnector, never()).fetchForAi(any(), any(), anyLong());
    }

    @Test
    void aNonRecipientCannotAccessAFileSharedWithSomeoneElse() {
        Fixture fixture = createFixture("publisher-c-" + UUID.randomUUID(), "recipient-real-" + UUID.randomUUID());
        UserContext nonRecipient = userContext("nonrecipient-c-" + UUID.randomUUID());

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(nonRecipient, fixture.documentId(),
                conversation(nonRecipient, "conv-c"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
        verify(googleDriveConnector, never()).fetchForAi(any(), any(), anyLong());
    }

    @Test
    void anAdminSubjectWithoutAnExplicitGrantCannotAccessTheFile() {
        Fixture fixture = createFixture("publisher-admin-" + UUID.randomUUID(), "recipient-admin-" + UUID.randomUUID());
        UserContext admin = userContext("admin-" + UUID.randomUUID());

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(admin, fixture.documentId(),
                conversation(admin, "conv-admin"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
    }

    @Test
    void aForgedOrNonexistentDocumentIdFailsClosedWithoutLeakingWhichCaseItWas() {
        UserContext someone = userContext("someone-" + UUID.randomUUID());

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(someone, 999_999_999L, "conv-forged",
                null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
    }

    @Test
    void revokedShareCanNoLongerExposeEvidence() {
        Fixture fixture = createFixture("publisher-revoke-" + UUID.randomUUID(), "recipient-revoke-" + UUID.randomUUID());
        stubSingleLocationPdf(fixture, "will be revoked");
        sourceSharingService.unshare(fixture.publisher(), fixture.shareId());

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-revoke"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
    }

    @Test
    void adminBlockedShareCanNoLongerExposeEvidence() {
        Fixture fixture = createFixture("publisher-block-" + UUID.randomUUID(), "recipient-block-" + UUID.randomUUID());
        stubSingleLocationPdf(fixture, "will be blocked");
        sourceSharingService.adminSetBlocked("admin-subject", fixture.shareId(), true, "policy review");

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-block"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
    }

    @Test
    void aiDeniedClassificationBlocksEvidenceEvenWithAValidActiveShare() {
        String publisher = "publisher-denied-" + UUID.randomUUID();
        String recipient = "recipient-denied-" + UUID.randomUUID();
        Fixture fixture = createFixtureWithClassification(publisher, recipient, "CONFIDENTIAL");
        stubSingleLocationPdf(fixture, "confidential content");

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-denied"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
        verify(googleDriveConnector, never()).fetchForAi(any(), any(), anyLong());
    }

    @Test
    void anEmptyAllowedSetProducesNoCandidatesAndNeverCallsVectorSearch() {
        UserContext noShares = userContext("no-shares-" + UUID.randomUUID());

        List<VectorCandidate> candidates = ragRetrievalService.retrieveCandidates(noShares, "any query text", 10);

        assertThat(candidates).isEmpty();
        verifyNoInteractions(pgVectorSearchAdapter);
    }

    @Test
    void candidateResolutionIsScopedByRealSqlToOnlyTheRequestersOwnSharedDocuments() {
        Fixture mine = createFixture("publisher-mine-" + UUID.randomUUID(), "recipient-scoped-" + UUID.randomUUID());
        Fixture someoneElses = createFixture("publisher-other-" + UUID.randomUUID(), "recipient-other-" + UUID.randomUUID());
        float[] embedding = new float[1024];
        embedding[0] = 0.1f;
        when(documentParsingClient.embedQuery(any()))
                .thenReturn(com.sdv.rag.domain.QueryEmbeddingOutcome.success(embedding));

        ragRetrievalService.retrieveCandidates(mine.recipientContext(), "find the policy", 5);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<Long>> allowedIdsCaptor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(pgVectorSearchAdapter).searchAllowed(allowedIdsCaptor.capture(), any(), eq(5));
        assertThat(allowedIdsCaptor.getValue()).containsExactly(mine.documentId());
        assertThat(allowedIdsCaptor.getValue()).doesNotContain(someoneElses.documentId());
    }

    // ------------------------------------------------------------------
    // Group C - 신선도/경합
    // ------------------------------------------------------------------

    @Test
    void unshareWhileTheFetchIsInFlightInvalidatesTheAttemptAndRunsOutsideAnAmbientTransaction() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("this test itself must not be running inside a transaction").isFalse();
        Fixture fixture = createFixture("publisher-race-" + UUID.randomUUID(), "recipient-race-" + UUID.randomUUID());
        stubVerifyForAi(fixture, "v1");

        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch unshareCompleted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean transactionActiveDuringFetch =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActiveDuringFetch.set(TransactionSynchronizationManager.isActualTransactionActive());
            fetchStarted.countDown();
            assertThat(unshareCompleted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();
            return SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", "v1", false);
        }).when(googleDriveConnector).fetchForAi(any(), eq("v1"), anyLong());
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", "text", List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 4))));

        Future<LiveRetrievalResult> future = executor.submit(() -> liveEvidenceRetrievalService
                .retrieveLive(fixture.recipientContext(), fixture.documentId(),
                        conversation(fixture.recipientContext(), "conv-race"), null));
        assertThat(fetchStarted.await(BOUND_SECONDS, TimeUnit.SECONDS)).isTrue();

        sourceSharingService.unshare(fixture.publisher(), fixture.shareId());
        unshareCompleted.countDown();

        LiveRetrievalResult result = future.get(BOUND_SECONDS, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
        assertThat(transactionActiveDuringFetch.get())
                .as("provider fetch must run with no active DB transaction").isFalse();
    }

    @Test
    void oneSourceVersionChangeIsRetriedOnceButARepeatedChangeStopsAsDocumentChanged() {
        Fixture fixture = createFixture("publisher-version-" + UUID.randomUUID(), "recipient-version-" + UUID.randomUUID());
        stubVerifyForAi(fixture, "v1");
        // 매 Fetch 시도마다 Version이 계속 바뀐다(1회 재시도 후에도 다시 바뀜) - DOCUMENT_CHANGED로 끝나야 한다.
        when(googleDriveConnector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.failed(com.sdv.source.domain.SourceContentOutcome.DOCUMENT_CHANGED,
                        "version changed"));

        LiveRetrievalResult result = liveEvidenceRetrievalService.retrieveLive(fixture.recipientContext(),
                fixture.documentId(), conversation(fixture.recipientContext(), "conv-version"), null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.DOCUMENT_CHANGED);
        // verifyForAi(pre-check)가 정확히 2번 호출됐다 - 최초 시도 + 정확히 1회 재시도.
        verify(googleDriveConnector, org.mockito.Mockito.times(2)).verifyForAi(any(), anyLong());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Fixture createFixture(String publisher, String recipient) {
        return createFixtureWithClassification(publisher, recipient, "INTERNAL");
    }

    private Fixture createFixtureWithClassification(String publisher, String recipient, String classification) {
        ensureRequester(recipient);
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", publisher);
        // EffectivePermissionService.evaluateSharedAccess는 검증된 Provider Identity가
        // 없으면 SOURCE_IDENTITY_UNVERIFIED로 Fail Closed한다(FileMetadataDiscoveryServiceTest와
        // 동일한 Fixture 관례) - 이 Slice의 정상 인가 흐름 Fixture는 항상 채택해 둔다.
        connection.adoptProviderAccountId("verified-account-" + publisher);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "ext-" + UUID.randomUUID(),
                "Doc.pdf", "application/pdf", "v1", null, "ACTIVE", "INDEXED", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        sourceSharingService.createShare(publisher, connection.getId(), document.getId(), classification,
                Set.of("VIEW"), Set.of(recipient));
        DocumentShareEntity share = documentShareJpaRepository.findByDocumentIdAndRevokedAtIsNull(document.getId())
                .orElseThrow();
        return new Fixture(publisher, recipient, connection.getId(), document.getId(), share.getId());
    }

    private Fixture createUnsharedDocument(String owner) {
        SourceConnectionEntity connection = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE",
                "FULL", owner);
        sourceConnectionJpaRepository.saveAndFlush(connection);
        SourceDocumentEntity document = new SourceDocumentEntity(connection.getId(), "ext-" + UUID.randomUUID(),
                "Doc.pdf", "application/pdf", "v1", null, "ACTIVE", "INDEXED", null);
        sourceDocumentJpaRepository.saveAndFlush(document);
        return new Fixture(owner, null, connection.getId(), document.getId(), null);
    }

    private void stubFetch(Fixture fixture, String version) {
        stubVerifyForAi(fixture, version);
        when(googleDriveConnector.fetchForAi(any(), eq(version), anyLong()))
                .thenReturn(SourceContentResult.verified("pdf bytes".getBytes(), "application/pdf", version, false));
    }

    private void stubVerifyForAi(Fixture fixture, String version) {
        when(googleDriveConnector.verifyForAi(any(), anyLong()))
                .thenReturn(SourceMetadataVerificationResult.verified("Doc.pdf", "application/pdf", version,
                        Instant.now(), true));
    }

    private void stubSingleLocationPdf(Fixture fixture, String text) {
        stubFetch(fixture, "v1");
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", text, List.of(new ExtractedLocation(LocatorType.PAGE, "1", 0, text.length()))));
    }

    private void ensureRequester(String subject) {
        if (identities.currentAuthorization(ISSUER, subject).isPresent()) return;
        identities.observeValidatedLogin(ISSUER, subject, subject, subject);
        String query = subject.substring(0, Math.min(subject.length(), 50));
        AdminUserResponse row = identities.adminSearch(query, 0, 50).items().stream()
                .filter(candidate -> candidate.loginId().equals(subject)).findFirst().orElseThrow();
        identities.updateAccess("admin-test", row.id(), row.version(), "SECRET", true);
    }

    private static UserContext userContext(String subject) {
        return new UserContext(subject, subject + "@example.com", Set.of(Role.USER), Set.of(), ISSUER, subject);
    }

    private record Fixture(String publisher, String recipient, Long sourceId, Long documentId, Long shareId) {
        UserContext recipientContext() {
            return userContext(recipient);
        }
    }
}
