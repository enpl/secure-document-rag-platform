package com.sdv.source.infrastructure.google;

import com.sdv.common.model.UserContext;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.application.port.SourceCredentialException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.application.port.SourceTokenStore;
import com.sdv.source.application.port.TokenEnvelope;
import com.sdv.source.domain.DocumentIndexStatus;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceChangeRecord;
import com.sdv.source.domain.SourceChangeType;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceDocumentState;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F-BE-040 (M08 신규, Review 교정 반영). {@link DocumentSourceConnector}의
 * Google Drive 구현체 - v1.4에서 유일하게 활성화된 Core Connector다
 * (`CORE_SPEC.md` §2A.1). Source-neutral 연산을 실제 Google Drive 호출로
 * 옮긴다.
 *
 * <h2>Credential 결합 - 현재 구현할 수 있는 것과 없는 것을 정직하게 구분</h2>
 * <p>현재 Data Model({@code source_connections})은 Source 하나당 정확히
 * 하나의 소유자({@code owner_subject})와 하나의 Token 참조만 가진다 -
 * "이 Source에 연결된 여러 SDV 사용자 각자의 Google Credential"을 표현할
 * 방법이 없다(그런 Multi-user/Delegation Mapping 테이블은 이 작업의
 * Public Manifest에 정의돼 있지 않다 - 임의로 새로 만들지 않는다). 그래서:</p>
 * <ul>
 *   <li>{@link #getMetadata}/{@link #listMetadata}/{@link #getPermissions}/{@link #findChanges}
 *       (Catalog Sync 성격)는 항상 Source Owner의 Credential로 수행된다 -
 *       원래부터 "이 Source를 연결한 사람의 권한으로 Sync한다"는 개념과
 *       일치한다.</li>
 *   <li>{@link #fetchContent}(최종 사용자 Live Retrieval)는 요청자
 *       ({@code requestingUser})가 그 Source의 Owner와 정확히 같은 SDV
 *       Subject일 때만 Owner의 Credential을 쓴다 - 다르면 즉시
 *       {@link SourceContentOutcome#CREDENTIAL_NOT_BOUND_TO_USER}로 Fail
 *       Closed 한다(Owner의 Token을 조용히 대신 쓰지 않는다). 이는 이
 *       작업이 명시적으로 "UNVERIFIED"로 남기는 실제 제품 한계다 - 같은
 *       조직의 다른 사용자가 Owner가 연결한 Drive의 문서를 열람하려면
 *       Domain-wide Delegation 등 별도의, 아직 구현되지 않은 매핑이
 *       필요하다.</li>
 * </ul>
 *
 * <h2>M08 Review 교정(항목 7) - Credential/Scope 결합을 한 번 더 검증</h2>
 * <p>원안은 DB의 {@code owner_subject}와 요청자만 비교했다 - 저장된
 * {@link TokenEnvelope} 자체에는 "이 Token이 실제로 누구 것인지"를 나타내는
 * 필드가 없어, 예를 들어 잘못된 {@code sourceId} 아래 다른 사용자(또는 더
 * 광범위한 Service Account) Token이 실수로 저장된 경우를 구분할 수 없었다.
 * 이제 Google을 호출하기 전에 다음을 전부 확인한다(하나라도 실패하면 Google
 * 호출 0회로 Fail Closed):</p>
 * <ul>
 *   <li>{@link TokenEnvelope#boundSubject()}가 실제 요청 주체(Content는
 *       {@code requestingUser}, Catalog Sync는 Source Owner)와 같다.</li>
 *   <li>{@link TokenEnvelope#accessTokenExpiresAt()}이 지나지 않았다 - M08은
 *       OAuth Refresh Flow를 구현하지 않으므로("최대 한 번 Refresh"가
 *       0번으로 자동 충족된다), 만료된 Token은 곧바로 Credential 없음으로
 *       취급한다.</li>
 *   <li>{@link TokenEnvelope#scopes()}가 {@link #REQUIRED_SCOPE_DRIVE_READONLY}를
 *       포함한다 - Google 공식 문서(2026-09-13 확인, {@link GoogleDriveClient}
 *       Class Javadoc 참고)는 Scope 부족을 위한 별도 오류 {@code reason}을
 *       정의하지 않고 평범한 401과 구분되지 않게 응답한다. 그래서 이 사전
 *       검사가 Scope 부족을 신뢰 가능하게 알아내는 유일한 방법이다.</li>
 *   <li>{@code source_connections}가 존재하고, 종류가 Google Drive이며,
 *       상태가 {@code ACTIVE}다.</li>
 * </ul>
 *
 * <h2>Production Token Store 부재 - 정직하게 명시</h2>
 * <p>{@link SourceTokenStore}는 M08 시점에 Production 구현체가 없다
 * ({@code Optional<SourceTokenStore>}가 항상 비어있다 - {@link SourceTokenStore}
 * Class Javadoc 참고). 그래서 이 Class의 모든 실제 Content/Metadata 접근은
 * 현재 항상 {@link SourceCredentialException}/{@code MISSING_CREDENTIAL}로
 * 끝난다 - 이 사실을 숨기거나 완화하지 않는다. Test는 이 Class 자체의
 * 로직(Owner-Only 결합, Verify-Fetch-Verify, Pagination, 오류 분류)을
 * Fake {@link SourceTokenStore}로 검증한다.</p>
 */
@Component
public class GoogleDriveConnector implements DocumentSourceConnector {

    /**
     * v1.4가 요구하는 전체 Drive 발견+읽기에 충분한 유일한 Scope(공식 문서
     * 확인, {@link GoogleDriveClient} Class Javadoc 참고) - {@code drive.file}은
     * App이 만들지 않은 기존 문서를 못 보고, {@code drive.metadata.readonly}는
     * Content Download 자체가 불가능하다.
     */
    public static final String REQUIRED_SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly";

    private static final String GOOGLE_DRIVE_TYPE = "GOOGLE_DRIVE";
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final GoogleDriveClient client;
    private final GoogleDriveContentAdapter contentAdapter;
    private final GoogleDrivePermissionAdapter permissionAdapter;
    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final Optional<SourceTokenStore> sourceTokenStore;
    private final Clock clock;

    @Autowired
    public GoogleDriveConnector(GoogleDriveClient client, GoogleDriveContentAdapter contentAdapter,
            GoogleDrivePermissionAdapter permissionAdapter,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            Optional<SourceTokenStore> sourceTokenStore) {
        this(client, contentAdapter, permissionAdapter, sourceConnectionJpaRepository, sourceTokenStore,
                Clock.systemUTC());
    }

    /** 테스트가 통제된 {@link Clock}을 직접 주입하기 위한 패키지 전용 생성자(Token 만료 판정 결정론화). */
    GoogleDriveConnector(GoogleDriveClient client, GoogleDriveContentAdapter contentAdapter,
            GoogleDrivePermissionAdapter permissionAdapter,
            SourceConnectionJpaRepository sourceConnectionJpaRepository,
            Optional<SourceTokenStore> sourceTokenStore, Clock clock) {
        this.client = client;
        this.contentAdapter = contentAdapter;
        this.permissionAdapter = permissionAdapter;
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceTokenStore = sourceTokenStore;
        this.clock = clock;
    }

    @Override
    public SourceType supportedType() {
        return SourceType.GOOGLE_DRIVE;
    }

    @Override
    public SourceDocument getMetadata(Long sourceId, String sourceDocumentId) {
        String accessToken = resolveOwnerCredentialOrThrow(sourceId);
        GoogleDriveClient.GoogleFile file = getFileOrThrow(accessToken, sourceDocumentId);
        return toSourceDocument(sourceId, file);
    }

    @Override
    public SourceMetadataPage listMetadata(Long sourceId, String pageToken) {
        String accessToken = resolveOwnerCredentialOrThrow(sourceId);
        GoogleDriveClient.GoogleFilesListPage page;
        try {
            page = client.listFiles(accessToken, pageToken);
        } catch (GoogleApiException e) {
            throw toSyncException(e);
        }
        List<SourceDocument> documents = new ArrayList<>();
        if (page.files() != null) {
            for (GoogleDriveClient.GoogleFile file : page.files()) {
                documents.add(toSourceDocument(sourceId, file));
            }
        }
        boolean isLastPage = page.nextPageToken() == null;
        boolean isComplete = !Boolean.TRUE.equals(page.incompleteSearch());
        return new SourceMetadataPage(documents, page.nextPageToken(), isLastPage, isComplete);
    }

    @Override
    public SourceContentResult fetchContent(UserContext requestingUser, Long sourceId, String sourceDocumentId,
            String expectedSourceVersion) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElse(null);
        if (connection == null) {
            return SourceContentResult.failed(SourceContentOutcome.NOT_FOUND, "source connection not found");
        }
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())) {
            return SourceContentResult.failed(SourceContentOutcome.NOT_FOUND,
                    "source connection is not a google drive source");
        }
        if (!ACTIVE_STATUS.equals(connection.getStatus())) {
            return SourceContentResult.failed(SourceContentOutcome.ACCESS_DENIED,
                    "source connection is not active");
        }
        if (requestingUser == null || requestingUser.subject() == null
                || !requestingUser.subject().equals(connection.getOwnerSubject())) {
            // 현재 Data Model은 Owner에게만 Credential을 안전하게 묶을 수 있다 - 다른 사용자는 Fail Closed(UNVERIFIED 범위).
            return SourceContentResult.failed(SourceContentOutcome.CREDENTIAL_NOT_BOUND_TO_USER,
                    "content access is currently bound only to the source owner's credential");
        }
        Optional<TokenEnvelope> token;
        try {
            token = loadToken(sourceId);
        } catch (RuntimeException credentialResolutionFailure) {
            // 예: 복호화 실패 - 원본 예외 메시지를 그대로 옮기지 않는다(Secret/Key 정보를 담고 있을 수 있다).
            return SourceContentResult.failed(SourceContentOutcome.CREDENTIAL_UNREADABLE,
                    "stored credential could not be resolved");
        }
        if (token.isEmpty()) {
            return SourceContentResult.failed(SourceContentOutcome.MISSING_CREDENTIAL,
                    "no credential is available for this source");
        }
        TokenEnvelope envelope = token.get();
        if (!requestingUser.subject().equals(envelope.boundSubject())) {
            return SourceContentResult.failed(SourceContentOutcome.CREDENTIAL_NOT_BOUND_TO_USER,
                    "stored credential is not bound to the requesting user");
        }
        if (isExpired(envelope)) {
            return SourceContentResult.failed(SourceContentOutcome.MISSING_CREDENTIAL,
                    "stored credential is expired and no refresh flow is available");
        }
        if (!hasRequiredScope(envelope)) {
            return SourceContentResult.failed(SourceContentOutcome.INSUFFICIENT_SCOPE,
                    "stored credential does not grant the scope required for this read");
        }
        return contentAdapter.fetchVerified(envelope.accessToken(), sourceDocumentId, expectedSourceVersion);
    }

    /**
     * M08 후속 교정 - {@link #getPermissions}도 다른 Catalog-Sync 연산(항목 7의
     * {@link #resolveOwnerCredentialOrThrow}가 이미 강제하는 것)과 똑같이 Source
     * Connection 존재/종류/{@code ACTIVE} 상태와 Credential의 Owner 결합을
     * 먼저 확인해야 한다 - 원안은 Token의 만료/Scope만 확인하고 이 부분을
     * 빠뜨려서, 예를 들어 Disconnect된(DISABLED) Source나 다른 계정에 결합된
     * Credential로도 Google Permission 호출이 나갈 수 있었다. {@link
     * #resolveOwnerCredentialOrThrow}처럼 예외를 던지는 대신, 이 메서드는
     * 실패를 {@link SourcePermissionsResult#unknown()}으로 표현한다(원래
     * 계약 - 빈 목록과 "신뢰 가능하게 조회할 수 없었다"를 Type 수준에서
     * 구분, Google 호출 0회).
     */
    @Override
    public SourcePermissionsResult getPermissions(Long sourceId, String sourceDocumentId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElse(null);
        if (connection == null) {
            return SourcePermissionsResult.unknown();
        }
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())) {
            return SourcePermissionsResult.unknown();
        }
        if (!ACTIVE_STATUS.equals(connection.getStatus())) {
            return SourcePermissionsResult.unknown();
        }
        Optional<TokenEnvelope> token;
        try {
            token = loadToken(sourceId);
        } catch (RuntimeException credentialResolutionFailure) {
            return SourcePermissionsResult.unknown();
        }
        if (token.isEmpty()) {
            return SourcePermissionsResult.unknown();
        }
        TokenEnvelope envelope = token.get();
        if (!connection.getOwnerSubject().equals(envelope.boundSubject())) {
            return SourcePermissionsResult.unknown();
        }
        if (isExpired(envelope) || !hasRequiredScope(envelope)) {
            return SourcePermissionsResult.unknown();
        }
        return permissionAdapter.listAllPermissions(envelope.accessToken(), sourceDocumentId);
    }

    @Override
    public SourceChangePage findChanges(Long sourceId, String pageToken) {
        String accessToken = resolveOwnerCredentialOrThrow(sourceId);
        GoogleDriveClient.GoogleChangesPage page;
        try {
            page = client.listChanges(accessToken, pageToken);
        } catch (GoogleApiException e) {
            throw toSyncException(e);
        }
        List<SourceChangeRecord> records = new ArrayList<>();
        if (page.changes() != null) {
            for (GoogleDriveClient.GoogleChange change : page.changes()) {
                records.add(toChangeRecord(sourceId, change));
            }
        }
        boolean isLastPage = page.nextPageToken() == null;
        return new SourceChangePage(records, page.nextPageToken(),
                isLastPage ? page.newStartPageToken() : null, isLastPage);
    }

    /** {@code changes.getStartPageToken} - M09가 최초 Sync를 시작할 때 쓸 첫 Page Token. */
    public String getStartPageToken(Long sourceId) {
        String accessToken = resolveOwnerCredentialOrThrow(sourceId);
        try {
            return client.getStartPageToken(accessToken);
        } catch (GoogleApiException e) {
            throw toSyncException(e);
        }
    }

    /**
     * M09A 교정(MVP-08) - {@code changeType}을 먼저 확인한다. 이전에는 이
     * 메서드가 {@code changeType}을 전혀 읽지 않아, {@code changeType="drive"}
     * (공유 드라이브 자체에 대한 변경 - {@code fileId}/{@code file}이 채워지지
     * 않는다, {@link GoogleDriveClient} Class Javadoc 참고)가 {@code
     * change.file() == null}이라는 이유만으로 파일 삭제(REMOVED_OR_ACCESS_LOST)
     * 로 잘못 승격될 수 있었다 - 실제로는 파일이 지워진 것이 전혀 아니다.
     * 이제 {@code changeType="drive"}는 파일 삭제로 지어내지 않고 명시적으로
     * 안전한 실패({@link SourceSyncException.Reason#FAILED})로 처리한다 - Core
     * MVP는 공유 드라이브 자체를 지원하지 않으므로({@code CLAUDE.md} 범위),
     * 이 Change Feed 전체를 "부분 실패"로 표시하고 멈춘다(호출자인 {@code
     * com.sdv.sync.*}가 이를 삭제/카탈로그 갱신으로 오인하지 않는다).
     */
    private SourceChangeRecord toChangeRecord(Long sourceId, GoogleDriveClient.GoogleChange change) {
        if ("drive".equals(change.changeType())) {
            throw new SourceSyncException(SourceSyncException.Reason.FAILED,
                    "unsupported drive-level change encountered - shared drive change events are not "
                            + "supported in Core MVP");
        }
        if (Boolean.TRUE.equals(change.removed()) || change.file() == null) {
            return new SourceChangeRecord(change.fileId(), SourceChangeType.REMOVED_OR_ACCESS_LOST, null);
        }
        return new SourceChangeRecord(change.fileId(), SourceChangeType.CHANGED,
                toSourceDocument(sourceId, change.file()));
    }

    private SourceDocument toSourceDocument(Long sourceId, GoogleDriveClient.GoogleFile file) {
        boolean trashed = Boolean.TRUE.equals(file.trashed());
        Instant modifiedAt = parseModifiedTimeSafely(file.modifiedTime());
        return new SourceDocument(null, sourceId, file.id(), file.name(), file.mimeType(), file.version(),
                modifiedAt, trashed ? SourceDocumentState.DELETED : SourceDocumentState.ACTIVE,
                DocumentIndexStatus.PENDING, null);
    }

    /**
     * M08 Review 교정(항목 3) - {@code modifiedTime}은 보조 진단값일 뿐이다
     * ({@link GoogleDriveClient} Class Javadoc 참고, Version 비교의 근거로
     * 쓰지 않는다). 형식이 잘못돼 해석할 수 없어도 전체 요청을 실패시키지
     * 않고 {@code null}로 안전하게 넘어간다 - {@code Instant.parse}가 던지는
     * {@link DateTimeParseException}이 그대로 새어나가지 않게 막는다.
     */
    private static Instant parseModifiedTimeSafely(String modifiedTime) {
        if (modifiedTime == null || modifiedTime.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(modifiedTime);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String resolveOwnerCredentialOrThrow(Long sourceId) {
        SourceConnectionEntity connection = sourceConnectionJpaRepository.findById(sourceId).orElse(null);
        if (connection == null) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "source connection not found");
        }
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND,
                    "source connection is not a google drive source");
        }
        if (!ACTIVE_STATUS.equals(connection.getStatus())) {
            throw new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "source connection is not active");
        }
        Optional<TokenEnvelope> token;
        try {
            token = loadToken(sourceId);
        } catch (RuntimeException credentialResolutionFailure) {
            throw new SourceCredentialException(SourceCredentialException.Reason.CREDENTIAL_UNREADABLE,
                    "stored credential could not be resolved");
        }
        if (token.isEmpty()) {
            throw new SourceCredentialException(SourceCredentialException.Reason.MISSING_CREDENTIAL,
                    "no credential is available for this source");
        }
        TokenEnvelope envelope = token.get();
        if (!connection.getOwnerSubject().equals(envelope.boundSubject())) {
            throw new SourceCredentialException(SourceCredentialException.Reason.MISSING_CREDENTIAL,
                    "stored credential is not bound to the source owner");
        }
        if (isExpired(envelope)) {
            throw new SourceCredentialException(SourceCredentialException.Reason.REAUTHORIZATION_REQUIRED,
                    "stored credential is expired and no refresh flow is available");
        }
        if (!hasRequiredScope(envelope)) {
            throw new SourceCredentialException(SourceCredentialException.Reason.INSUFFICIENT_SCOPE,
                    "stored credential does not grant the scope required for this read");
        }
        return envelope.accessToken();
    }

    private boolean isExpired(TokenEnvelope envelope) {
        Instant expiresAt = envelope.accessTokenExpiresAt();
        return expiresAt != null && !expiresAt.isAfter(clock.instant());
    }

    private static boolean hasRequiredScope(TokenEnvelope envelope) {
        return envelope.scopes().contains(REQUIRED_SCOPE_DRIVE_READONLY);
    }

    private Optional<TokenEnvelope> loadToken(Long sourceId) {
        if (sourceTokenStore.isEmpty()) {
            return Optional.empty();
        }
        return sourceTokenStore.get().load(sourceId);
    }

    private GoogleDriveClient.GoogleFile getFileOrThrow(String accessToken, String sourceDocumentId) {
        try {
            return client.getFile(accessToken, sourceDocumentId);
        } catch (GoogleApiException e) {
            throw toSyncException(e);
        }
    }

    private static SourceSyncException toSyncException(GoogleApiException e) {
        return switch (e.getCategory()) {
            case NOT_FOUND -> new SourceSyncException(SourceSyncException.Reason.NOT_FOUND, "document not found");
            case QUOTA_OR_RATE_LIMIT, RETRYABLE_SERVER_ERROR ->
                    new SourceSyncException(SourceSyncException.Reason.ACCESS_UNKNOWN,
                            "could not obtain a trustworthy answer after bounded retries");
            case UNAUTHORIZED, PERMISSION_DENIED, BAD_REQUEST, UNKNOWN ->
                    new SourceSyncException(SourceSyncException.Reason.FAILED, "google drive sync call failed");
        };
    }
}
