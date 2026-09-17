package com.sdv.source;

import com.sdv.common.exception.NotFoundException;
import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import com.sdv.identity.application.IdentityRegistryService;
import com.sdv.identity.application.IdentityAccessException;
import com.sdv.identity.api.dto.AdminUserResponse;
import com.sdv.policy.application.EffectivePermissionService;
import com.sdv.rag.api.dto.RagFileSearchQuery;
import com.sdv.rag.api.dto.RagFileSearchResponse;
import com.sdv.rag.api.dto.RagFileSortKey;
import com.sdv.rag.application.FileMetadataDiscoveryService;
import com.sdv.rag.application.RagDiscoveryProperties;
import com.sdv.security.application.SecurityFindingService;
import com.sdv.security.infrastructure.persistence.entity.SecurityFindingEntity;
import com.sdv.security.infrastructure.persistence.repository.SecurityFindingJpaRepository;
import com.sdv.source.application.InvalidShareRequestException;
import com.sdv.source.application.ShareGenerationConflictException;
import com.sdv.source.application.SourceConnectionService;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.SourceSharingService;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.DocumentShare;
import com.sdv.source.domain.ShareAction;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceMetadataVerificationOutcome;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.DocumentShareRestrictionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.entity.SourceDocumentEntity;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.DocumentShareRestrictionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceDocumentJpaRepository;
import com.sdv.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
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
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M10B(SHR-001/002/003/005/006, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.13/§2A.14) -
 * B안 선택 공유 전체 흐름의 End-to-End 검증: 개인 연결/비공개 선택기, 명시적
 * 게시자→수신자 공유, 공유 기반 공통 검색, ADMIN 정책/차단, Disconnect의 공유
 * 보존, 위조 입력 거부, 동시 수정 Fencing.
 *
 * <p><b>이 이름이 뜻하지 않는 것</b> - {@code E2E}는 "여러 계층(Application
 * Service + 실제 PostgreSQL + 중앙 Policy 경로)을 함께 검증한다"는 뜻일 뿐, 실제
 * Google 계정 검증을 의미하지 않는다. Google Drive는 이 Class가 직접 만드는
 * {@link DocumentSourceConnector} Mock(Mockito, Spring Bean이 아니다)으로
 * 대체한다 - {@code GoogleDriveConnector}(실제 구현체)와 Spring 컨테이너 안에
 * 함께 두면 {@link SourceConnectorRegistry}가 중복 등록으로 기동에 실패하므로,
 * {@code FileMetadataDiscoveryServiceTest}와 같은 방식으로 이 Mock을 감싼 새
 * Registry/Service 인스턴스를 직접 만들어 쓴다. 실제 Google API 호출은 이 Test
 * 어디에서도 발생하지 않는다.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "sdv.keycloak.issuer-uri=http://localhost:8180/realms/sdv",
        "sdv.keycloak.audience=sdv-backend",
        "sdv.policy.permission-freshness-max-age=PT24H"
})
class SelectiveSharingE2ETest {

    private static final String ISSUER = "http://localhost:8180/realms/sdv";

    @Autowired
    private SourceConnectionService sourceConnectionService;
    @Autowired
    private SourceSharingService sourceSharingService;
    @Autowired
    private EffectivePermissionService effectivePermissionService;
    @Autowired
    private DocumentShareJpaRepository documentShareJpaRepository;
    @Autowired
    private RagDiscoveryProperties ragDiscoveryProperties;
    @Autowired
    private SourceConnectionJpaRepository sourceConnectionJpaRepository;
    @Autowired
    private SourceDocumentJpaRepository sourceDocumentJpaRepository;
    @Autowired
    private DocumentShareRestrictionJpaRepository documentShareRestrictionJpaRepository;
    @Autowired
    private PlatformTransactionManager platformTransactionManager;
    @Autowired
    private SecurityFindingJpaRepository securityFindingJpaRepository;
    @Autowired
    private SecurityFindingService securityFindingService;
    @Autowired
    private IdentityRegistryService identityRegistryService;

    private DocumentSourceConnector connectorMock;
    private FileMetadataDiscoveryService fileMetadataDiscoveryService;

    @BeforeEach
    void setUp() {
        // supportedType()을 즉시 Stub해 둔다 - SourceConnectorRegistry의 생성자가 이 값을
        // 등록 Key로 한 번만 읽으므로, 각 Test Method 안에서 나중에 Stub하면 이미 늦는다.
        connectorMock = mock(DocumentSourceConnector.class);
        when(connectorMock.supportedType()).thenReturn(SourceType.GOOGLE_DRIVE);
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(connectorMock));
        fileMetadataDiscoveryService = new FileMetadataDiscoveryService(documentShareJpaRepository,
                effectivePermissionService, registry, ragDiscoveryProperties);
    }

    // ------------------------------------------------------------------
    // 1. 개인 연결과 비공개 선택기 - A만 자신의 Source를 browse할 수 있다.
    // ------------------------------------------------------------------

    @Test
    void ownerBrowsesTheirOwnPrivatePickerButAnotherUserCannot() {
        String a = "user-a-" + unique();
        String b = "user-b-" + unique();
        long sourceId = createActiveSource(a);
        createDocument(sourceId, "Report.pdf", "application/pdf", "v1");

        SourceConnectionService.FilesPage aView = sourceConnectionService.listFiles(sourceId, a, 0, 50);
        assertThat(aView.items()).hasSize(1);
        assertThat(aView.items().get(0).getName()).isEqualTo("Report.pdf");

        assertThatThrownBy(() -> sourceConnectionService.listFiles(sourceId, b, 0, 50))
                .as("a non-owner must not be able to browse another user's private picker")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void allAudienceUsesCurrentClearanceAndAuthorizationRevisionFencesAbaChanges() {
        String suffix = unique();
        String a = "publisher-clearance-" + suffix;
        String b = "sdv-user-b-" + suffix;
        String c = "sdv-user-c-" + suffix;
        String admin = "sdv-admin-" + suffix;
        AdminUserResponse bUser = registerAndAssign(admin, b, "INTERNAL", true);
        registerAndAssign(admin, c, "PUBLIC", true);
        registerAndAssign(admin, admin, null, true);

        long sourceId = createActiveSource(a);
        Doc shared = createDocument(sourceId, "All-internal.pdf", "application/pdf", "v1");
        createDocument(sourceId, "Private.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, shared.documentId(),
                "ALL_AUTHENTICATED", "INTERNAL", Set.of("VIEW"), List.of());
        stubVerified(sourceId, shared.sourceDocumentId(), "All-internal.pdf", "application/pdf", "v1");

        assertThat(discoverWithIdentity(b, Role.USER, sourceId).items()).hasSize(1);
        assertThat(discoverWithIdentity(c, Role.USER, sourceId).items()).isEmpty();
        assertThat(discoverWithIdentity(admin, Role.ADMIN, sourceId).items())
                .as("ADMIN role without clearance is not a content bypass").isEmpty();

        long connectionEpoch = sourceConnectionJpaRepository.findById(sourceId).orElseThrow().getConnectionEpoch();
        SourceAccessContext beforeDowngrade = new SourceAccessContext(b + "-subject", a, sourceId, shared.documentId(),
                share.getId(), ShareAction.VIEW, share.getGeneration(), connectionEpoch,
                bUser.authorizationRevision());

        AdminUserResponse downgraded = identityRegistryService.updateAccess(admin, bUser.id(), bUser.version(),
                "PUBLIC", true);
        AdminUserResponse upgraded = identityRegistryService.updateAccess(admin, downgraded.id(), downgraded.version(),
                "INTERNAL", true);

        assertThat(upgraded.authorizationRevision()).isGreaterThan(bUser.authorizationRevision());
        assertThat(effectivePermissionService.evaluateSharedAccess(
                identifiedUser(b, Role.USER), beforeDowngrade, null).isAllowed())
                .as("downgrade then upgrade must not revive an older authorization snapshot").isFalse();
        assertThat(discoverWithIdentity(b, Role.USER, sourceId).items()).hasSize(1);
    }

    @Test
    void namedAudienceRequiresBothStableSelectionAndSufficientClearance() {
        String suffix = unique();
        String a = "publisher-named-" + suffix;
        String b = "sdv-user-b-" + suffix;
        String c = "sdv-user-c-" + suffix;
        String admin = "sdv-admin-" + suffix;
        registerAndAssign(admin, b, "INTERNAL", true);
        registerAndAssign(admin, c, "INTERNAL", true);
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Named.pdf", "application/pdf", "v1");
        var recipients = identityRegistryService.resolveRecipients(ISSUER,
                Set.of(identityRegistryService.adminSearch(b, 0, 20).items().getFirst().id()));
        sourceSharingService.createShare(a, sourceId, doc.documentId(), "NAMED_USERS", "INTERNAL",
                Set.of("VIEW"), recipients);
        stubVerified(sourceId, doc.sourceDocumentId(), "Named.pdf", "application/pdf", "v1");

        assertThat(discoverWithIdentity(b, Role.USER, sourceId).items()).hasSize(1);
        assertThat(discoverWithIdentity(c, Role.USER, sourceId).items()).isEmpty();
    }

    @Test
    void loginDirectoryTreatsWildcardsLiterallyAndFailsClosedForAmbiguousOrDisabledIdentities() {
        String suffix = unique();
        String literalLogin = "sdv%_user-" + suffix;
        String collisionLogin = "collision-" + suffix;
        identityRegistryService.observeValidatedLogin(ISSUER, "literal-subject-" + suffix,
                literalLogin, "Literal User");
        identityRegistryService.observeValidatedLogin(ISSUER, "plain-subject-" + suffix,
                "sdvXYuser-" + suffix, "Plain User");

        assertThat(identityRegistryService.searchDirectory(ISSUER, "%_user-" + suffix))
                .extracting(candidate -> candidate.loginId())
                .containsExactly(literalLogin);
        assertThat(identityRegistryService.searchDirectory(ISSUER, literalLogin.toUpperCase()))
                .extracting(candidate -> candidate.loginId())
                .containsExactly(literalLogin);

        identityRegistryService.observeValidatedLogin(ISSUER, "collision-one-" + suffix,
                collisionLogin, "First");
        identityRegistryService.observeValidatedLogin(ISSUER, "collision-two-" + suffix,
                collisionLogin.toUpperCase(), "Second");
        AdminUserResponse first = identityRegistryService.adminSearch(collisionLogin, 0, 20).items().stream()
                .filter(candidate -> candidate.loginId().equals(collisionLogin)).findFirst().orElseThrow();
        assertThat(identityRegistryService.searchDirectory(ISSUER, collisionLogin)).isEmpty();
        assertThatThrownBy(() -> identityRegistryService.resolveRecipients(ISSUER, Set.of(first.id())))
                .isInstanceOf(IdentityAccessException.class);

        identityRegistryService.observeValidatedLogin(ISSUER, "collision-one-" + suffix,
                "renamed-" + suffix, "First Renamed");
        assertThat(identityRegistryService.resolveRecipients(ISSUER, Set.of(first.id())))
                .singleElement().satisfies(recipient -> {
                    assertThat(recipient.subject()).isEqualTo("collision-one-" + suffix);
                    assertThat(recipient.loginId()).isEqualTo("renamed-" + suffix);
                });
        assertThat(identityRegistryService.searchDirectory(ISSUER, collisionLogin))
                .extracting(candidate -> candidate.loginId())
                .containsExactly(collisionLogin.toUpperCase());

        AdminUserResponse renamed = identityRegistryService.adminSearch("renamed-" + suffix, 0, 20)
                .items().getFirst();
        identityRegistryService.updateAccess("admin-" + suffix, renamed.id(), renamed.version(), null, false);
        assertThat(identityRegistryService.searchDirectory(ISSUER, "renamed-" + suffix)).isEmpty();
        assertThatThrownBy(() -> identityRegistryService.resolveRecipients(ISSUER, Set.of(renamed.id())))
                .isInstanceOf(IdentityAccessException.class);
    }

    // ------------------------------------------------------------------
    // 2. 명시적 A→B 공유는 B의 native Google 권한 없이도 발견 가능하다; C와
    // 공유되지 않은 파일은 거부된다.
    // ------------------------------------------------------------------

    @Test
    void bDiscoversTheExplicitlySharedFileWithoutNativeGooglePermissionWhileCAndUnsharedFilesAreDenied() {
        String a = "publisher-a-" + unique();
        String b = "recipient-b-" + unique();
        String c = "outsider-c-" + unique();
        long sourceId = createActiveSource(a);
        Doc shared = createDocument(sourceId, "Shared.pdf", "application/pdf", "v1");
        Doc unshared = createDocument(sourceId, "Unshared.pdf", "application/pdf", "v1");

        sourceSharingService.createShare(a, sourceId, shared.documentId(), "INTERNAL", Set.of("VIEW"), Set.of(b));
        stubVerified(sourceId, shared.sourceDocumentId(), "Shared.pdf", "application/pdf", "v1");

        // B는 이 문서에 대해 어떤 native Google 권한도 없다(source_permissions 행 자체가
        // 전혀 없다) - 그래도 명시적 공유만으로 발견된다.
        RagFileSearchResponse bResult = discover(b, sourceId);
        assertThat(bResult.items().stream().map(item -> item.name()).toList())
                .containsExactly("Shared.pdf")
                .as("the sibling unshared document must never appear even to the authorized recipient")
                .doesNotContain("Unshared.pdf");

        RagFileSearchResponse cResult = discover(c, sourceId);
        assertThat(cResult.items()).as("an unlisted third party must never see the shared file").isEmpty();

        assertThat(unshared.documentId()).isNotEqualTo(shared.documentId());
    }

    // ------------------------------------------------------------------
    // 3. 위조된 owner/source/file/share/recipient/action 입력과 빈 수신자는
    // 인가를 넓힐 수 없다.
    // ------------------------------------------------------------------

    @Test
    void forgedShareCreationInputsAreRejected() {
        String a = "publisher-forge-a-" + unique();
        String attacker = "attacker-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");

        assertThatThrownBy(() -> sourceSharingService.createShare(attacker, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of("someone")))
                .as("a non-owner must never be able to publish a share for someone else's document")
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of()))
                .as("empty recipients must never be accepted, let alone treated as public")
                .isInstanceOf(InvalidShareRequestException.class);

        assertThatThrownBy(() -> sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("DELETE_EVERYTHING"), Set.of("someone")))
                .as("an unrecognized action must be rejected")
                .isInstanceOf(InvalidShareRequestException.class);

        assertThatThrownBy(() -> sourceSharingService.createShare(a, sourceId, doc.documentId(),
                "TOP_SECRET_UNKNOWN", Set.of("VIEW"), Set.of("someone")))
                .as("a missing/unmapped classification must fail closed, not invent a fifth value")
                .isInstanceOf(InvalidShareRequestException.class);
    }

    @Test
    void aForgedShareIdThatActuallyPointsToADifferentDocumentIsNeverAuthorized() {
        String a = "publisher-x-" + unique();
        String other = "publisher-y-" + unique();
        String b = "recipient-forge-" + unique();
        long sourceIdA = createActiveSource(a);
        Doc docA = createDocument(sourceIdA, "A.pdf", "application/pdf", "v1");
        DocumentShare shareOnA = sourceSharingService.createShare(a, sourceIdA, docA.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));

        long sourceIdOther = createActiveSource(other);
        Doc docOther = createDocument(sourceIdOther, "Other.pdf", "application/pdf", "v1");

        // shareOnA.getId()는 실제로 존재하는 공유이지만, docOther/sourceIdOther와는 무관하다 -
        // 이 mismatch를 evaluateSharedAccess가 직접 잡아내야 한다(Client가 넘긴 shareId만으로
        // 다른 문서의 접근을 넓힐 수 없다).
        long otherConnectionEpoch = sourceConnectionJpaRepository.findById(sourceIdOther).orElseThrow()
                .getConnectionEpoch();
        ensureLegacyRequester(b);
        long authorizationRevision = identityRegistryService.currentAuthorization(ISSUER, b).orElseThrow()
                .authorizationRevision();
        SourceAccessContext forgedContext = new SourceAccessContext(b, other, sourceIdOther, docOther.documentId(),
                shareOnA.getId(), ShareAction.VIEW, shareOnA.getGeneration(), otherConnectionEpoch,
                authorizationRevision);

        UserContext requester = new UserContext(b, b + "@example.com", Set.of(Role.USER), Set.of(), ISSUER, b);
        boolean allowed = effectivePermissionService.evaluateSharedAccess(requester, forgedContext, null)
                .isAllowed();

        assertThat(allowed).as("a shareId that does not actually bind to this document/source must be rejected")
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 4. ADMIN은 게시된 자료를 차단할 수 있지만 콘텐츠/비공개 Source 권한을
    // 우회할 수 없다; 게시자는 관리자 차단을 해제할 수 없다.
    // ------------------------------------------------------------------

    @Test
    void adminCanBlockAPublishedShareAndThePublisherCannotClearIt() {
        String a = "publisher-admin-" + unique();
        String b = "recipient-admin-" + unique();
        String admin = "admin-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Policy.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        stubVerified(sourceId, doc.sourceDocumentId(), "Policy.pdf", "application/pdf", "v1");

        assertThat(discover(b, sourceId).items()).hasSize(1);

        sourceSharingService.adminSetBlocked(admin, share.getId(), true, "policy review");
        assertThat(discover(b, sourceId).items())
                .as("an admin block must immediately hide the file from discovery").isEmpty();

        // 게시자는 일반 수정(PATCH)으로 이 차단을 해제할 방법이 없다 - applyOwnerUpdate는
        // adminBlocked 필드를 전혀 건드리지 않는다.
        DocumentShare afterAdminBlock = sourceSharingService.listOwn(a).stream()
                .filter(s -> s.getId().equals(share.getId())).findFirst().orElseThrow();
        assertThat(afterAdminBlock.isAdminBlocked()).isTrue();

        sourceSharingService.updateShare(a, share.getId(), afterAdminBlock.getGeneration(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        assertThat(discover(b, sourceId).items())
                .as("an owner update must never clear an administrative block").isEmpty();

        // ADMIN도 콘텐츠에 접근하지 않는다 - 이 흐름 전체에서 Connector의 Content 계열 메서드는
        // 한 번도 호출되지 않는다(정책/차단 관리는 순수 DB 연산).
        verify(connectorMock, never()).fetchContent(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // 5. Disconnect는 공유를 보존한 채 접근만 차단한다.
    // ------------------------------------------------------------------

    @Test
    void disconnectPreservesTheShareRecordWhileDenyingAccess() {
        String a = "publisher-disc-" + unique();
        String b = "recipient-disc-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        stubVerified(sourceId, doc.sourceDocumentId(), "Doc.pdf", "application/pdf", "v1");
        assertThat(discover(b, sourceId).items()).hasSize(1);

        sourceConnectionService.disconnect(sourceId, a);

        assertThat(discover(b, sourceId).items()).as("a paused connection must hide the file").isEmpty();
        DocumentShare stillThere = sourceSharingService.listOwn(a).stream()
                .filter(s -> s.getId().equals(share.getId())).findFirst().orElseThrow();
        assertThat(stillThere.isActive()).as("disconnect must not silently unshare the document").isTrue();
        assertThat(sourceDocumentJpaRepository.findById(doc.documentId()).orElseThrow().getState())
                .as("disconnect must not conflate connection loss with actual provider-file deletion")
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // 6. 철회/관리자 차단/원본 삭제는 재연결로도 복구되지 않는다(재연결 자체의
    // 신원 검증은 GoogleDriveOAuthServiceTest가 별도로 다룬다).
    // ------------------------------------------------------------------

    @Test
    void unsharingIsPermanentAndDoesNotRestoreEvenAfterTheConnectionIsReactivated() {
        String a = "publisher-unshare-" + unique();
        String b = "recipient-unshare-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        stubVerified(sourceId, doc.sourceDocumentId(), "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        assertThat(discover(b, sourceId).items()).hasSize(1);

        sourceSharingService.unshare(a, share.getId());
        assertThat(discover(b, sourceId).items()).isEmpty();

        // 재연결(같은 Source를 다시 ACTIVE로)해도 명시적으로 철회된 공유는 절대 되살아나지 않는다.
        reactivate(sourceId);
        assertThat(discover(b, sourceId).items())
                .as("an explicit unshare must never be revived by a later reconnect").isEmpty();
        assertThatThrownBy(() -> sourceSharingService.updateShare(a, share.getId(), share.getGeneration(),
                "INTERNAL", Set.of("VIEW"), Set.of(b)))
                .as("a revoked share is no longer a valid target for owner updates - re-sharing needs a new share")
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aProviderSideDeletionIsExcludedAsADefinitiveOutcomeNotAnAmbiguousOne() {
        String a = "publisher-deleted-" + unique();
        String b = "recipient-deleted-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL", Set.of("VIEW"), Set.of(b));
        when(connectorMock.verifyCurrentMetadata(any(), eq(sourceId), eq(doc.sourceDocumentId())))
                .thenReturn(SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND,
                        "deleted at the provider"));

        RagFileSearchResponse result = discover(b, sourceId);

        assertThat(result.items()).isEmpty();
        assertThat(result.partial())
                .as("a definitive provider-side deletion is a resolved exclusion, not an inability to verify")
                .isFalse();
    }

    @Test
    void aDifferentFileWithTheSameNameNeverInheritsAnExistingShare() {
        String a = "publisher-samename-" + unique();
        String b = "recipient-samename-" + unique();
        long sourceId = createActiveSource(a);
        Doc original = createDocument(sourceId, "Report.pdf", "application/pdf", "v1");
        sourceSharingService.createShare(a, sourceId, original.documentId(), "INTERNAL", Set.of("VIEW"), Set.of(b));
        // 원본이 삭제됐다(NOT_FOUND) - 동시에 같은 이름을 가진, 전혀 공유되지 않은 새 문서가 등록된다.
        when(connectorMock.verifyCurrentMetadata(any(), eq(sourceId), eq(original.sourceDocumentId())))
                .thenReturn(SourceMetadataVerificationResult.failed(SourceMetadataVerificationOutcome.NOT_FOUND,
                        "original deleted"));
        Doc replacement = createDocument(sourceId, "Report.pdf", "application/pdf", "v1");

        RagFileSearchResponse result = discover(b, sourceId);

        assertThat(result.items())
                .as("a same-named replacement file was never itself shared - it must not inherit the old share")
                .isEmpty();
        assertThat(replacement.documentId()).isNotEqualTo(original.documentId());
    }

    // ------------------------------------------------------------------
    // 7. 동시 공유 수정은 세대(Generation) Fencing으로 낡은 상태를 덮어쓰지
    // 못한다(결정론적 순차 호출 - Sleep/Thread 기반 경쟁 재현이 아니다).
    // ------------------------------------------------------------------

    @Test
    void aStaleGenerationUpdateIsRejectedInsteadOfOverwritingNewerState() {
        String a = "publisher-gen-" + unique();
        String b = "recipient-gen-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        long staleGeneration = share.getGeneration();

        // "첫 번째 탭"이 성공적으로 갱신한다 - 세대가 올라간다.
        sourceSharingService.updateShare(a, share.getId(), staleGeneration, "CONFIDENTIAL", Set.of("VIEW"),
                Set.of(b));

        // "두 번째 탭"은 여전히 낡은 세대를 들고 재시도한다 - 거부돼야 한다.
        assertThatThrownBy(() -> sourceSharingService.updateShare(a, share.getId(), staleGeneration, "PUBLIC",
                Set.of("VIEW"), Set.of(b)))
                .as("a stale generation must not be allowed to overwrite the newer committed state")
                .isInstanceOf(ShareGenerationConflictException.class);

        DocumentShare current = sourceSharingService.listOwn(a).stream()
                .filter(s -> s.getId().equals(share.getId())).findFirst().orElseThrow();
        assertThat(current.getClassification().name())
                .as("the rejected stale write must not have applied").isEqualTo("CONFIDENTIAL");
    }

    // ------------------------------------------------------------------
    // 8. M10B 보안 교정(그룹 A/B) - 세대(Generation) Fencing과 관리자 차단이
    // Owner PATCH/Unshare/Republish 사이의 경쟁에서도 유지된다.
    // ------------------------------------------------------------------

    /**
     * 그룹 A - "Owner PATCH pauses after reading old state; ADMIN block commits;
     * stale PATCH resumes and cannot remove the block." Owner가 세대를 읽은 뒤
     * ADMIN이 먼저 차단을 Commit하면, 그 낡은 세대를 든 채 재개된 Owner PATCH는
     * (차단 필드를 전혀 건드리지 않는 무해한 값 변경이라도) 거부돼야 한다 -
     * {@code DocumentShareEntity.generation}이 이제 진짜 {@code @Version}이므로
     * 이 순서는 실제 Thread 없이도 결정론적으로 재현된다(각 Service 호출이 그 자체로
     * 독립된 Transaction이다).
     */
    @Test
    void aStaleOwnerPatchCannotRemoveAnAdminBlockCommittedAfterItReadTheOldGeneration() {
        String a = "publisher-stale-block-" + unique();
        String b = "recipient-stale-block-" + unique();
        String admin = "admin-stale-block-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        long generationBeforeBlock = share.getGeneration(); // "PATCH 탭이 읽어 둔 낡은 상태".

        sourceSharingService.adminSetBlocked(admin, share.getId(), true, "policy review");

        assertThatThrownBy(() -> sourceSharingService.updateShare(a, share.getId(), generationBeforeBlock,
                "CONFIDENTIAL", Set.of("VIEW"), Set.of(b)))
                .as("a stale-generation owner PATCH resumed after an admin block committed must be rejected")
                .isInstanceOf(ShareGenerationConflictException.class);

        DocumentShare current = sourceSharingService.listOwn(a).stream()
                .filter(s -> s.getId().equals(share.getId())).findFirst().orElseThrow();
        assertThat(current.isAdminBlocked()).as("the admin block must remain in effect").isTrue();
        assertThat(current.getClassification().name())
                .as("the rejected stale write must not have applied").isEqualTo("INTERNAL");
    }

    /**
     * 그룹 B - "ADMIN block -> owner unshare -> republish same file remains
     * denied" 및 "Authorized ADMIN unblock works without reviving revoked shares
     * or expanding recipients/actions." 게시자가 차단된 공유를 unshare하고 같은
     * 파일을 새 shareId로 재게시해도 차단은 파일의 정규 신원({@code
     * document_share_restrictions}, V011)에 남아 새 공유도 처음부터 차단된 채로
     * 태어난다. ADMIN이 그 새 공유에 대해 명시적으로 해제하면 그제서야 발견 가능해
     * 지되, 수신자/행위/등급은 게시자가 재게시 때 지정한 그대로다(ADMIN이 넓히지
     * 않는다).
     */
    @Test
    void adminBlockSurvivesOwnerUnshareAndRepublishOfTheSameFile() {
        String a = "publisher-republish-" + unique();
        String b = "recipient-republish-" + unique();
        String admin = "admin-republish-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        stubVerified(sourceId, doc.sourceDocumentId(), "Doc.pdf", "application/pdf", "v1");
        assertThat(discover(b, sourceId).items()).hasSize(1);

        sourceSharingService.adminSetBlocked(admin, share.getId(), true, "policy review");
        assertThat(discover(b, sourceId).items()).isEmpty();

        sourceSharingService.unshare(a, share.getId());
        DocumentShare republished = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));

        assertThat(republished.getId()).as("republish must create a genuinely new share row")
                .isNotEqualTo(share.getId());
        assertThat(republished.isAdminBlocked())
                .as("a republished share for the same file must be born already blocked").isTrue();
        assertThat(discover(b, sourceId).items())
                .as("the administrative restriction must survive unshare + republish").isEmpty();

        sourceSharingService.adminSetBlocked(admin, republished.getId(), false, null);
        DocumentShare unblocked = sourceSharingService.listOwn(a).stream()
                .filter(s -> s.getId().equals(republished.getId())).findFirst().orElseThrow();
        assertThat(unblocked.isAdminBlocked()).isFalse();
        assertThat(unblocked.getRecipients())
                .as("an ADMIN unblock must never expand the publisher's own recipient list").containsExactly(b);
        assertThat(unblocked.getAllowedActions())
                .as("an ADMIN unblock must never expand the publisher's own granted actions")
                .containsExactly(ShareAction.VIEW);
        assertThat(discover(b, sourceId).items())
                .as("an authorized ADMIN unblock on the new share must restore discovery").hasSize(1);
    }

    /**
     * 그룹 A/B "overlap" - ADMIN의 차단 Commit과 게시자의 재게시(republish)가 같은
     * 문서 행 Lock을 두고 실제로 경쟁한다. {@link SourceDocumentJpaRepository#findByIdForUpdate}
     * 를 먼저 획득한 Transaction이 끝까지 Commit할 때까지, 다른 Transaction의 같은
     * 잠금 시도는 실제 DB 단에서 대기해야 한다("don't hold DB locks during provider
     * network calls" - 이 Test에서 Lock을 쥔 채 수행하는 작업은 순수 DB 연산뿐이다).
     * 짧은 Bounded Wait 안에 republish가 끝나지 않는다는 사실로 진짜 Blocking을
     * 증명하고, Lock 해제 후에는 republish가 Commit된 restriction을 정확히 관찰한다.
     */
    @Test
    void concurrentAdminBlockAndRepublishAreSerializedByTheDocumentRowLock() throws Exception {
        String a = "publisher-overlap-" + unique();
        String b = "recipient-overlap-" + unique();
        String admin = "admin-overlap-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        DocumentShare share = sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                Set.of("VIEW"), Set.of(b));
        sourceSharingService.unshare(a, share.getId()); // 활성 공유 없음 - republish 가능한 상태.

        CountDownLatch restrictionLockHeld = new CountDownLatch(1);
        CountDownLatch releaseRestrictionLock = new CountDownLatch(1);
        TransactionTemplate transactionTemplate = new TransactionTemplate(platformTransactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // "ADMIN 차단 Transaction" - 문서 행을 잠그고 Restriction을 기록한 채 아직
            // Commit하지 않고 붙잡고 있는다.
            Future<?> adminTransaction = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                sourceDocumentJpaRepository.findByIdForUpdate(doc.documentId());
                documentShareRestrictionJpaRepository
                        .save(new DocumentShareRestrictionEntity(sourceId, doc.documentId(), admin, Instant.now()));
                restrictionLockHeld.countDown();
                try {
                    assertThat(releaseRestrictionLock.await(10, TimeUnit.SECONDS))
                            .as("test must release the lock within the bounded wait").isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));

            assertThat(restrictionLockHeld.await(10, TimeUnit.SECONDS)).isTrue();
            Future<DocumentShare> republishFuture = executor
                    .submit(() -> sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL",
                            Set.of("VIEW"), Set.of(b)));
            assertThatThrownBy(() -> republishFuture.get(300, TimeUnit.MILLISECONDS))
                    .as("republish must genuinely block on the same document row lock while the admin "
                            + "transaction is still open")
                    .isInstanceOf(TimeoutException.class);

            releaseRestrictionLock.countDown();
            adminTransaction.get(10, TimeUnit.SECONDS);
            DocumentShare republished = republishFuture.get(10, TimeUnit.SECONDS);

            assertThat(republished.isAdminBlocked())
                    .as("the republish must observe the now-committed restriction, not a stale pre-commit read")
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 9. 선택기/검색은 Content Byte를 Fetch하지 않는다.
    // ------------------------------------------------------------------

    @Test
    void discoveryAndThePickerNeverFetchFileContent() {
        String a = "publisher-nocontent-" + unique();
        String b = "recipient-nocontent-" + unique();
        long sourceId = createActiveSource(a);
        Doc doc = createDocument(sourceId, "Doc.pdf", "application/pdf", "v1");
        sourceSharingService.createShare(a, sourceId, doc.documentId(), "INTERNAL", Set.of("VIEW"), Set.of(b));
        stubVerified(sourceId, doc.sourceDocumentId(), "Doc.pdf", "application/pdf", "v1");

        sourceConnectionService.listFiles(sourceId, a, 0, 50);
        discover(b, sourceId);

        verify(connectorMock, never()).fetchContent(any(), any(), any(), any());
        verify(connectorMock, never()).getMetadata(any(), any());
        verify(connectorMock, never()).listMetadata(any(), any());
    }

    @Test
    void adminFindingQueriesAndUpdatesExcludePrivateNeverPublishedDocumentsIncludingLegacyRows() {
        securityFindingJpaRepository.deleteAll();
        String owner = "finding-owner-" + unique();
        long sourceId = createActiveSource(owner);
        Doc published = createDocument(sourceId, "Published.pdf", "application/pdf", "v1");
        Doc privateDocument = createDocument(sourceId, "Private.pdf", "application/pdf", "v1");
        sourceSharingService.createShare(owner, sourceId, published.documentId(), "SECRET", Set.of("VIEW"),
                Set.of("recipient-b"));

        SecurityFindingEntity allowed = securityFindingJpaRepository.saveAndFlush(new SecurityFindingEntity(
                "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION", "HIGH", sourceId, published.documentId(),
                java.util.Map.of("classification", "SECRET", "principalType", "ANYONE")));
        SecurityFindingEntity privateLegacy = securityFindingJpaRepository.saveAndFlush(new SecurityFindingEntity(
                "BROAD_PROVIDER_SHARING_HIGH_CLASSIFICATION", "HIGH", sourceId, privateDocument.documentId(),
                java.util.Map.of("classification", "SECRET", "principalType", "ANYONE")));

        var page = securityFindingService.list(null, 0, 1);

        assertThat(page.items()).singleElement().satisfies(item -> assertThat(item.id()).isEqualTo(allowed.getId()));
        assertThat(page.hasMore()).as("excluded private rows must not leak through pagination").isFalse();
        assertThatThrownBy(() -> securityFindingService.update(
                new UserContext("admin-a", null, Set.of(Role.ADMIN), Set.of()), privateLegacy.getId(), "ACKNOWLEDGED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(securityFindingService.update(new UserContext("admin-a", null, Set.of(Role.ADMIN), Set.of()),
                allowed.getId(), "ACKNOWLEDGED").status()).isEqualTo("ACKNOWLEDGED");
        verify(connectorMock, never()).fetchContent(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private long createActiveSource(String ownerSubject) {
        SourceConnectionEntity entity = new SourceConnectionEntity("GOOGLE_DRIVE", "Test Source", "ACTIVE", "FULL",
                ownerSubject);
        // M10B 보안 교정(그룹 C) - "don't enable sharing for legacy connection with unknown
        // provider identity." 이 Helper가 만드는 Source는 이미 재인증을 거쳐 신원이 확인된
        // 일반적인 연결을 나타낸다 - EffectivePermissionService.decideShared의
        // SOURCE_IDENTITY_UNVERIFIED 검사를 통과시킨다.
        entity.adoptProviderAccountId("verified-account-" + ownerSubject);
        sourceConnectionJpaRepository.saveAndFlush(entity);
        return entity.getId();
    }

    private void reactivate(long sourceId) {
        SourceConnectionEntity entity = sourceConnectionJpaRepository.findById(sourceId).orElseThrow();
        entity.changeStatus(SourceConnection.STATUS_ACTIVE);
        sourceConnectionJpaRepository.saveAndFlush(entity);
    }

    private Doc createDocument(long sourceId, String name, String mimeType, String version) {
        String sourceDocumentId = "doc-" + unique();
        SourceDocumentEntity entity = new SourceDocumentEntity(sourceId, sourceDocumentId, name, mimeType, version,
                Instant.now(), "ACTIVE", "PENDING", null);
        sourceDocumentJpaRepository.saveAndFlush(entity);
        return new Doc(entity.getId(), sourceDocumentId);
    }

    private void stubVerified(long sourceId, String sourceDocumentId, String name, String mimeType, String version) {
        when(connectorMock.verifyCurrentMetadata(any(), eq(sourceId), eq(sourceDocumentId)))
                .thenReturn(SourceMetadataVerificationResult.verified(name, mimeType, version, Instant.now(), true));
    }

    private RagFileSearchResponse discover(String subject, Long sourceId) {
        ensureLegacyRequester(subject);
        RagFileSearchQuery query = new RagFileSearchQuery(null, null, sourceId, null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        return fileMetadataDiscoveryService.search(new UserContext(subject, subject + "@example.com",
                Set.of(Role.USER), Set.of(), ISSUER, subject), query);
    }

    private RagFileSearchResponse discoverWithIdentity(String subject, Role role, Long sourceId) {
        RagFileSearchQuery query = new RagFileSearchQuery(null, null, sourceId, null, null,
                RagFileSortKey.MODIFIED_AT_DESC, 0, 20);
        return fileMetadataDiscoveryService.search(identifiedUser(subject, role), query);
    }

    private AdminUserResponse registerAndAssign(String actor, String loginId, String level, boolean active) {
        identityRegistryService.observeValidatedLogin(ISSUER, loginId + "-subject", loginId, loginId);
        AdminUserResponse user = identityRegistryService.adminSearch(loginId, 0, 20).items().stream()
                .filter(candidate -> candidate.loginId().equals(loginId)).findFirst().orElseThrow();
        return identityRegistryService.updateAccess(actor, user.id(), user.version(), level, active);
    }

    private static UserContext identifiedUser(String loginId, Role role) {
        return new UserContext(loginId + "-subject", loginId + "@example.com", Set.of(role), Set.of(), ISSUER,
                loginId);
    }

    private void ensureLegacyRequester(String subject) {
        if (identityRegistryService.currentAuthorization(ISSUER, subject).isPresent()) return;
        identityRegistryService.observeValidatedLogin(ISSUER, subject, subject, subject);
        String query = subject.substring(0, Math.min(subject.length(), 50));
        AdminUserResponse row = identityRegistryService.adminSearch(query, 0, 50).items().stream()
                .filter(candidate -> candidate.loginId().equals(subject)).findFirst().orElseThrow();
        identityRegistryService.updateAccess("admin-test", row.id(), row.version(), "SECRET", true);
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private record Doc(Long documentId, String sourceDocumentId) {
    }
}
