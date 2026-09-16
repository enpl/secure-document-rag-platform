package com.sdv.rag.application;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceKey;
import com.sdv.rag.domain.ExtractedLocation;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LiveRetrievalStatus;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.ParseOutcome;
import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.rag.infrastructure.ai.DocumentParsingClient;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M12 Focused Acceptance Test Group D(근거 정확성) - 순수 단위 테스트(Mockito). 실제
 * 인가/DB/Transaction은 {@code RagLiveRetrievalE2ETest}(Testcontainers)가 검증한다 -
 * 이 Test는 {@link SourceConsistencyGuard}/{@link DocumentParsingClient}/{@link
 * EphemeralEvidenceStore}를 Mock해 {@link LiveEvidenceRetrievalService}의 근거 선택/
 * 버전 변경 재시도 로직만 좁게 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LiveEvidenceRetrievalServiceTest {

    private static final Long DOCUMENT_ID = 42L;
    private static final UserContext REQUESTER = new UserContext("requester", "requester@example.com",
            Set.of(Role.USER), Set.of());

    @Mock
    private SourceConsistencyGuard sourceConsistencyGuard;
    @Mock
    private DocumentParsingClient documentParsingClient;
    @Mock
    private EphemeralEvidenceStore ephemeralEvidenceStore;
    @Mock
    private DocumentSourceConnector connector;
    @Mock
    private EvidenceConversationLifecycle conversationLifecycle;
    @Mock
    private EvidenceConversationLifecycle.Lease conversationLease;

    private LiveEvidenceRetrievalService service;

    @BeforeEach
    void setUp() {
        LiveRetrievalProperties properties = new LiveRetrievalProperties(5, 20_000, 60_000, 4_000, 10, 500);
        service = new LiveEvidenceRetrievalService(sourceConsistencyGuard, documentParsingClient,
                ephemeralEvidenceStore, properties, conversationLifecycle);
        lenient().when(conversationLifecycle.requireActive(any(), any())).thenReturn(conversationLease);
        lenient().when(conversationLifecycle.isActive(conversationLease)).thenReturn(true);
        lenient().when(ephemeralEvidenceStore.putEncryptedFenced(any(), any(), anyLong()))
                .thenAnswer(invocation -> ephemeralEvidenceStore.putEncrypted(invocation.getArgument(0),
                        invocation.getArgument(1)));
        lenient().when(ephemeralEvidenceStore.putEncryptedFenced(any(), any(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> ephemeralEvidenceStore.putEncrypted(invocation.getArgument(0),
                        invocation.getArgument(1)));
        lenient().when(ephemeralEvidenceStore.putEncrypted(any(), any()))
                .thenAnswer(invocation -> new EvidenceHandle(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(300)));
    }

    private SourceConsistencyGuard.LiveIdentity identity(String version, String mimeType) {
        SourceAccessContext context = new SourceAccessContext("requester", "publisher", 1L, DOCUMENT_ID, 9L,
                ShareAction.VIEW, 1L, 1L);
        SourceConsistencyGuard.Snapshot snapshot = new SourceConsistencyGuard.Snapshot(context,
                com.sdv.source.domain.SourceType.GOOGLE_DRIVE);
        return new SourceConsistencyGuard.LiveIdentity(REQUESTER, snapshot, connector, version, mimeType, "Doc.pdf");
    }

    @Test
    void aVectorCandidateLocatorThatStillExistsProducesATruthfulSingleLocationExcerpt() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v1", false));
        String page1 = "page one content.";
        String page2 = "page two content.";
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", page1 + page2,
                List.of(new ExtractedLocation(LocatorType.PAGE, "1", 0, page1.length()),
                        new ExtractedLocation(LocatorType.PAGE, "2", page1.length(), page1.length() + page2.length())),
                "2", "bge-m3:567m", List.of(new com.sdv.rag.domain.ExtractedChunk(0, LocatorType.PAGE, "1", 0,
                        page1.length()), new com.sdv.rag.domain.ExtractedChunk(1, LocatorType.PAGE, "2", page1.length(),
                        page1.length() + page2.length()))));
        VectorCandidate candidate = new VectorCandidate(DOCUMENT_ID, 1, LocatorType.PAGE, "2", "v1", "1", "2",
                "bge-m3:567m");
        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-1", candidate);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        assertThat(result.locatorValue()).isEqualTo("2");
        assertThat(result.partialCoverage()).isTrue();
        ArgumentCaptor<byte[]> textCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(ephemeralEvidenceStore).putEncrypted(any(), textCaptor.capture());
        assertThat(new String(textCaptor.getValue(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(page2);
    }

    @Test
    void aStaleCandidateLocatorNoLongerPresentInTheFreshParseIsRejected() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(
                ParseOutcome.success("pdfminer.six", "1", "1", "only one page now",
                        List.of(new ExtractedLocation(LocatorType.PAGE, "1", 0, "only one page now".length()))));

        // Candidate가 가리키던 "PAGE 9"는 더 이상 존재하지 않는다(형식/버전 변화) - 근거 없는 매칭을 지어내지 않는다.
        VectorCandidate staleCandidate = new VectorCandidate(DOCUMENT_ID, 5, LocatorType.PAGE, "9", "v1", "1", "2",
                "bge-m3:567m");
        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-2", staleCandidate);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NO_EVIDENCE);
    }

    @Test
    void noExtractableTextProducesNoEvidenceWithoutWritingToTheStore() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong()))
                .thenReturn(ParseOutcome.failure(ParseOutcomeKind.NO_TEXT, "no extractable text"));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-3", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NO_EVIDENCE);
        verify(ephemeralEvidenceStore, never()).putEncrypted(any(), any());
    }

    @Test
    void anUnsupportedFormatNeverReachesTheParserOrTheStore() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/zip"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.UNSUPPORTED_FORMAT, "not core format"));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-4", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.UNSUPPORTED_FORMAT);
        verify(documentParsingClient, never()).parse(any(), any(), any());
    }

    @Test
    void exportLimitIsNotCollapsedIntoUnavailableOrNoEvidence() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/vnd.google-apps.document"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.EXPORT_LIMIT_EXCEEDED, "bounded export"));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-export-limit", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.EXPORT_LIMIT_EXCEEDED);
        verify(documentParsingClient, never()).parse(any(), any(), any(), anyLong());
    }

    @Test
    void aSingleVersionChangeIsRetriedExactlyOnceAndSucceedsOnTheFreshAttempt() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"), identity("v2", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.DOCUMENT_CHANGED, "changed"));
        when(connector.fetchForAi(any(), eq("v2"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v2", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", "text", List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 4))));
        when(ephemeralEvidenceStore.putEncrypted(any(), any()))
                .thenReturn(new EvidenceHandle(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(300)));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-5", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        verify(sourceConsistencyGuard, times(2)).verifyBefore(any(), eq(DOCUMENT_ID), anyLong());
    }

    @Test
    void aRepeatedVersionChangeAfterOneRetryStopsAsDocumentChanged() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"), identity("v2", "application/pdf"));
        when(connector.fetchForAi(any(), any(), anyLong()))
                .thenReturn(SourceContentResult.failed(SourceContentOutcome.DOCUMENT_CHANGED, "changed"));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-6", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.DOCUMENT_CHANGED);
        verify(sourceConsistencyGuard, times(2)).verifyBefore(any(), eq(DOCUMENT_ID), anyLong());
        verify(documentParsingClient, never()).parse(any(), any(), any());
    }

    @Test
    void capacityExhaustionOnTheEphemeralStoreIsMappedToACapacityExhaustedStatus() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong())).thenReturn(ParseOutcome.success("pdfminer.six", "1",
                "1", "text", List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 4))));
        when(ephemeralEvidenceStore.putEncrypted(any(), any()))
                .thenThrow(new EphemeralEvidenceCapacityExceededException());

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-7", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.CAPACITY_EXHAUSTED);
    }

    @Test
    void rawContentAdmissionExhaustionIsMappedAtTheServiceBoundary() {
        LiveRetrievalProperties properties = new LiveRetrievalProperties(5, 20_000, 60_000, 4_000, 10, 500);
        LiveContentAdmission admission = new LiveContentAdmission(1, 25L * 1024 * 1024);
        service = new LiveEvidenceRetrievalService(sourceConsistencyGuard, documentParsingClient,
                ephemeralEvidenceStore, properties, conversationLifecycle, admission);
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        LiveRetrievalDeadline heldDeadline = LiveRetrievalDeadline.startingNow(20_000);

        try (LiveContentAdmission.Reservation ignored = admission.acquire(25L * 1024 * 1024, heldDeadline)) {
            LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-admission", null);

            assertThat(result.status()).isEqualTo(LiveRetrievalStatus.CAPACITY_EXHAUSTED);
            verify(connector, never()).fetchForAi(any(), any(), anyLong());
        }
    }

    @Test
    void parserTimeoutReleasesRawContentAdmissionForTheNextOperation() {
        LiveRetrievalProperties properties = new LiveRetrievalProperties(5, 20_000, 60_000, 4_000, 10, 500);
        LiveContentAdmission admission = new LiveContentAdmission(1, 25L * 1024 * 1024);
        service = new LiveEvidenceRetrievalService(sourceConsistencyGuard, documentParsingClient,
                ephemeralEvidenceStore, properties, conversationLifecycle, admission);
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenReturn(identity("v1", "application/pdf"));
        when(connector.fetchForAi(any(), eq("v1"), anyLong()))
                .thenReturn(SourceContentResult.verified("bytes".getBytes(), "application/pdf", "v1", false));
        when(documentParsingClient.parse(any(), any(), any(), anyLong()))
                .thenReturn(ParseOutcome.failure(ParseOutcomeKind.TIMEOUT, "request deadline expired"),
                        ParseOutcome.success("pdfminer.six", "1", "1", "text",
                                List.of(new ExtractedLocation(LocatorType.DOCUMENT, "1", 0, 4))));

        LiveRetrievalResult timedOut = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-timeout", null);
        LiveRetrievalResult subsequent = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-after-timeout", null);

        assertThat(timedOut.status()).isEqualTo(LiveRetrievalStatus.REQUEST_TIMEOUT);
        assertThat(subsequent.status()).isEqualTo(LiveRetrievalStatus.VERIFIED);
        verify(documentParsingClient, times(2)).parse(any(), any(), any(), anyLong());
    }

    @Test
    void aDenialFromSourceConsistencyGuardBeforeAnyFetchProducesNotAuthorizedWithoutTouchingTheParser() {
        when(sourceConsistencyGuard.verifyBefore(any(), eq(DOCUMENT_ID), anyLong()))
                .thenThrow(new LiveRetrievalException(LiveRetrievalException.Reason.NOT_AUTHORIZED));

        LiveRetrievalResult result = service.retrieveLive(REQUESTER, DOCUMENT_ID, "conv-8", null);

        assertThat(result.status()).isEqualTo(LiveRetrievalStatus.NOT_AUTHORIZED);
        verify(documentParsingClient, never()).parse(any(), any(), any());
    }
}
