package com.sdv.rag.application;

import com.sdv.ai.application.AssistantIntent;
import com.sdv.ai.application.AssistantProperties;
import com.sdv.ai.application.AssistantRouter;
import com.sdv.ai.application.NaturalLanguageFileQueryParser;
import com.sdv.ai.application.PolicyEnforcedLlmGateway;
import com.sdv.ai.application.PromptComposer;
import com.sdv.ai.application.port.LlmPort;
import com.sdv.audit.application.RagAuditRecorder;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagAnswerResponse;
import com.sdv.rag.api.dto.RagCitation;
import com.sdv.rag.domain.CandidateSelectionResult;
import com.sdv.rag.domain.EvidenceBatchResult;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceProvenance;
import com.sdv.rag.domain.EvidenceReleaseResult;
import com.sdv.rag.domain.LiveRetrievalResult;
import com.sdv.rag.domain.LocatorType;
import com.sdv.rag.domain.VectorCandidate;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend"
})
class RagAnswerAuthorizationFenceIntegrationTest {
    private static final String ISSUER = "http://localhost:8180/realms/sdv";
    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "test", 8192, 1024,
            2000, 5, 10, 24000, 2, 10_000, 1000, 5000);

    @Autowired private IdentityRegistryService identities;
    @Autowired private EffectivePermissionService permissions;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @EnumSource(AuthorizationMutation.class)
    void committedRequesterChangeWhileGenerationIsPausedReleasesNoAnswerAnalysisOrCitation(
            AuthorizationMutation mutation) throws Exception {
        String subject = "answer-reader-" + UUID.randomUUID();
        AdminUserResponse reader = register(subject, "INTERNAL", true);
        BlockingPort port = new BlockingPort();
        RagAnswerService service = service(port);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<RagAnswerResponse> future = executor.submit(() -> service.ask(user(subject),
                    "analyze the approved policy", List.of(11L)));
            assertThat(port.started.await(2, TimeUnit.SECONDS)).isTrue();
            mutate(reader, mutation);
            port.resume.countDown();

            RagAnswerResponse response = future.get(3, TimeUnit.SECONDS);
            assertDeniedWithoutGeneratedOutput(response);
        }
    }

    @Test
    void anotherUsersChangeDoesNotInvalidateTheRequestersGeneratedResponse() throws Exception {
        String subject = "answer-reader-" + UUID.randomUUID();
        register(subject, "INTERNAL", true);
        AdminUserResponse other = register("answer-other-" + UUID.randomUUID(), "INTERNAL", true);
        BlockingPort port = new BlockingPort();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<RagAnswerResponse> future = executor.submit(() -> service(port).ask(user(subject),
                    "analyze the approved policy", List.of(11L)));
            assertThat(port.started.await(2, TimeUnit.SECONDS)).isTrue();
            identities.updateAccess("admin-test", other.id(), other.version(), "PUBLIC", true);
            port.resume.countDown();

            RagAnswerResponse response = future.get(3, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.answer()).isEqualTo("approved policy");
            assertThat(response.generatedAnalysis()).isEqualTo("generated analysis");
            assertThat(response.citations()).hasSize(1);
        }
    }

    @Test
    void rolledBackClearanceChangeDoesNotCreateAFalseAuthorizationRevisionFence() {
        String subject = "answer-reader-" + UUID.randomUUID();
        AdminUserResponse reader = register(subject, "INTERNAL", true);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            identities.updateAccess("admin-test", reader.id(), reader.version(), "PUBLIC", true);
            status.setRollbackOnly();
        });

        BlockingPort port = new BlockingPort();
        port.resume.countDown();
        RagAnswerResponse response = service(port).ask(user(subject), "analyze the approved policy", List.of(11L));
        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.answer()).isEqualTo("approved policy");
    }

    private RagAnswerService service(BlockingPort port) {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        when(retrieval.openEvidenceConversation(any())).thenReturn("conversation");
        VectorCandidate candidate = new VectorCandidate(11L, 0, LocatorType.SECTION, "policy", "v1",
                "parser", "chunker", "model");
        when(retrieval.retrieveCandidatesForDocuments(any(), anyString(), anyList(), anyInt(), anyLong()))
                .thenReturn(CandidateSelectionResult.success(List.of(candidate)));
        Instant created = Instant.now();
        EvidenceHandle handle = new EvidenceHandle(UUID.randomUUID(), created, created.plusSeconds(300));
        LiveRetrievalResult live = LiveRetrievalResult.verified(11L, handle, LocatorType.SECTION, "policy", false);
        when(retrieval.retrieveVerifiedEvidenceCandidates(any(), anyString(), anyList(), anyLong()))
                .thenReturn(new EvidenceBatchResult(List.of(live), false));
        EvidenceProvenance provenance = new EvidenceProvenance(11L, 1L, "publisher", 2L, 3L, 4L,
                "v1", LocatorType.SECTION, "policy", created, created.plusSeconds(300), created);
        when(retrieval.releaseVerifiedEvidence(any(), any(), anyString(), anyLong()))
                .thenReturn(EvidenceReleaseResult.released("approved policy", provenance));
        when(retrieval.validateEvidenceForResponse(any(), anyString(), any(), any())).thenReturn(true);

        PolicyEnforcedLlmGateway gateway = new PolicyEnforcedLlmGateway(port, PROPERTIES);
        AssistantRouter router = mock(AssistantRouter.class);
        when(router.route(anyString(), anyBoolean(), any()))
                .thenReturn(new AssistantRouter.Route(AssistantIntent.GROUNDED_ANALYSIS, "OK"));
        CitationAssembler citations = mock(CitationAssembler.class);
        when(citations.assemble(any(), any())).thenReturn(new RagCitation(11L, "SECTION", "policy", "v1",
                created, null));
        return new RagAnswerService(router, new NaturalLanguageFileQueryParser(),
                mock(FileMetadataDiscoveryService.class), retrieval, new PromptComposer(PROPERTIES), gateway,
                citations, PROPERTIES, RagAuditRecorder.noop(), permissions);
    }

    private AdminUserResponse register(String subject, String level, boolean active) {
        identities.observeValidatedLogin(ISSUER, subject, subject, subject);
        String query = subject.substring(0, Math.min(subject.length(), 50));
        AdminUserResponse row = identities.adminSearch(query, 0, 50).items().stream()
                .filter(item -> item.loginId().equals(subject)).findFirst().orElseThrow();
        return identities.updateAccess("admin-test", row.id(), row.version(), level, active);
    }

    private void mutate(AdminUserResponse user, AuthorizationMutation mutation) {
        switch (mutation) {
            case DOWNGRADE -> identities.updateAccess("admin-test", user.id(), user.version(), "PUBLIC", true);
            case RESET -> identities.updateAccess("admin-test", user.id(), user.version(), null, true);
            case DISABLE -> identities.updateAccess("admin-test", user.id(), user.version(), "INTERNAL", false);
            case ABA -> {
                AdminUserResponse changed = identities.updateAccess("admin-test", user.id(), user.version(),
                        "PUBLIC", true);
                identities.updateAccess("admin-test", changed.id(), changed.version(), "INTERNAL", true);
            }
        }
    }

    private static UserContext user(String subject) {
        return new UserContext(subject, subject + "@example.test", Set.of(Role.USER), Set.of(), ISSUER, subject);
    }

    private static void assertDeniedWithoutGeneratedOutput(RagAnswerResponse response) {
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.reasonCode()).isEqualTo("NOT_AUTHORIZED");
        assertThat(response.answer()).isNull();
        assertThat(response.generatedAnalysis()).isNull();
        assertThat(response.citations()).isEmpty();
        assertThat(response.files()).isNull();
    }

    private enum AuthorizationMutation { DOWNGRADE, RESET, DISABLE, ABA }

    private static final class BlockingPort implements LlmPort {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        @Override
        public ClassificationResult classify(String question, String model, long deadlineMillis) {
            return new ClassificationResult(ClassificationResult.Kind.SUCCESS, AssistantIntent.GROUNDED_ANALYSIS);
        }

        @Override
        public GenerationResult generate(GenerationRequest request, String model, long deadlineMillis) {
            started.countDown();
            try {
                if (!resume.await(2, TimeUnit.SECONDS)) return new GenerationResult(GenerationResult.Kind.TIMEOUT,
                        List.of(), null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new GenerationResult(GenerationResult.Kind.TIMEOUT, List.of(), null);
            }
            return new GenerationResult(GenerationResult.Kind.SUCCESS,
                    List.of(new Claim("approved policy", List.of("E1"), List.of("approved policy"))),
                    "generated analysis");
        }
    }
}
