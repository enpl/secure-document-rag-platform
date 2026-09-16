package com.sdv.source.application.port;

import com.sdv.common.model.UserContext;
import com.sdv.source.domain.SourceChangePage;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDownloadResult;
import com.sdv.source.domain.SourceAccessContext;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceMetadataPage;
import com.sdv.source.domain.SourceMetadataVerificationResult;
import com.sdv.source.domain.SourcePermissionsResult;
import com.sdv.source.domain.SourceType;

/**
 * F-BE-023. SDV와 외부/Local Source 사이의 공통 경계(SRC-001).
 *
 * <p>Google/AWS/JPA/HTTP 같은 기술 특화 타입이 이 Port로 새어 들어오지
 * 않는다 - JDK 타입과 Source Domain 타입만 사용한다.</p>
 *
 * <h2>M08 후속 교정 - 계약을 안전하게 만든 3가지 변경</h2>
 * <p>M04가 정의한 원래 계약은 {@code byte[] fetchContent(Long sourceId,
 * String sourceDocumentId)}(누가 요청했는지·어떤 Version을 기대하는지 전혀
 * 알 수 없는, 무제한 크기의 즉시-소비 가능한 Byte 배열)와
 * {@code List<SourceDocument> findChanges(Long sourceId, String syncCursor)}
 * (Pagination/newStartPageToken/삭제-대-접근상실 구분을 전혀 표현할 수
 * 없는 단순 목록)이었다 - 둘 다 v1.4 Mandatory Live Retrieval(§2A.5)과
 * 실제 Google {@code changes.list} Pagination 의미를 정확히 표현할 수
 * 없었다. 이번 교정이 바꾼 것:</p>
 * <ol>
 *   <li>{@link #fetchContent}가 이제 {@link UserContext}(누구의 권한으로
 *       요청하는지)와 {@code expectedSourceVersion}(무엇이 최신이라고
 *       가정하고 있었는지)을 받고, {@link SourceContentResult}(검증 통과
 *       전에는 Byte 자체가 존재할 수 없는 Type)를 반환한다 - 이전처럼
 *       "일단 다 받은 뒤 나중에 확인"하는 것이 Type 수준에서 불가능하다.</li>
 *   <li>{@link #getPermissions}가 이제 {@link SourcePermissionsResult}
 *       (빈 목록과 "조회 실패/불확실"을 명확히 구분)를 반환한다.</li>
 *   <li>{@link #findChanges}가 이제 {@link SourceChangePage}(nextPageToken/
 *       newStartPageToken/삭제-대-접근상실 구분을 명시적으로 표현)를
 *       반환한다.</li>
 * </ol>
 *
 * <p>{@link #getMetadata}/{@link #listMetadata}/{@link #getPermissions}/{@link #findChanges}는
 * Catalog Sync(향후 M09) 성격의 작업이다 - Source Owner의 Credential로
 * 수행된다(Source를 연결한 사람의 권한). 오직 {@link #fetchContent}만
 * "최종 사용자의 권한으로 지금 이 Content를 읽어도 되는가"를 묻는다 -
 * 그래서 {@link UserContext}를 받는 유일한 메서드다(v1.4 §2A.4/§2A.5:
 * Catalog ACL은 빠른 Prefilter일 뿐, 최종 인가 결정은 현재 사용자 기준
 * Live 확인이다).</p>
 *
 * <h2>M08 Review 교정 - 누락됐던 Whole-Drive Metadata Discovery(항목 1)</h2>
 * <p>{@link #getMetadata}는 이미 파일 ID를 알고 있을 때만 쓸 수 있다 -
 * Source를 처음 연결한 시점에 사용자의 Drive 전체를 훑어 Metadata/ACL
 * Catalog를 채우는 연산이 이 Port에 없었다({@code changes.getStartPageToken}
 * 이후의 {@link #findChanges}만으로는 기존에 이미 존재하던 문서 목록을
 * 절대 알아낼 수 없다). {@link #listMetadata}가 그 최소 Page 계약을
 * 채운다 - 실제 최초 Crawl/Cursor Commit Orchestration은 여전히 M09
 * 책임이고, M08은 신뢰 가능한 Primitive만 제공한다.</p>
 *
 * <h2>M10 신규 - File Metadata Discovery 전용 Same-user 메타데이터 재확인</h2>
 * <p>{@link #verifyCurrentMetadata}는 v1.4 File Metadata Discovery(§2A.4)가
 * ACL Catalog Prefilter를 통과한 후보 하나에 대해, 노출 전 지금 이 순간의
 * 이름/타입/Version을 재확인하기 위한 것이다. {@link #getMetadata}(Owner
 * Credential, Catalog Sync 전용)와 달리 요청자 본인 결합을 요구하고,
 * {@link #fetchContent}와 달리 Content Byte를 전혀 요청하지 않는다(Media/
 * Export 호출 없음) - 메타데이터 가시성과 콘텐츠 접근은 서로 다른 결정이다.</p>
 */
public interface DocumentSourceConnector {

    /** 이 Connector가 처리하는 {@link SourceType} - Registry가 조회 Key로 사용한다. */
    SourceType supportedType();

    /**
     * Catalog Sync용 Metadata 조회 - Source Owner의 Credential로 수행된다.
     * Credential이 없거나 신뢰할 수 없으면 구현체가 적절한 Runtime
     * 예외(예: {@code SourceCredentialException})로 Fail Closed 한다 -
     * 존재하지 않는 문서를 지어내지 않는다.
     */
    SourceDocument getMetadata(Long sourceId, String sourceDocumentId);

    /**
     * Whole-Drive Metadata Discovery 한 페이지 - Source Owner의 Credential로
     * 수행된다. {@code pageToken}은 최초 호출에서는 {@code null}, 이후에는
     * 이전 {@link SourceMetadataPage#nextPageToken()}이다.
     * {@link SourceMetadataPage#isComplete()}가 {@code false}인 페이지가
     * 있으면, 호출자는 이번 Traversal을 완료로 표시하거나 이번 결과에 없다는
     * 이유만으로 기존 Catalog 행을 삭제해서는 안 된다. DB Cursor/Catalog
     * Commit은 이 메서드의 책임이 아니다(M09).
     */
    SourceMetadataPage listMetadata(Long sourceId, String pageToken);

    /**
     * 최종 사용자({@code requestingUser}) 권한으로, {@code expectedSourceVersion}과
     * 일치하는 현재 Content만 Bounded/검증된 형태로 반환한다(v1.4 §2A.5 Mandatory
     * Live Retrieval) - Fetch 전/후 접근권한·Version을 모두 재확인하기 전에는
     * 어떤 Byte도 {@link SourceContentResult#content()}로 노출되지 않는다
     * (Type 자체가 강제한다).
     */
    SourceContentResult fetchContent(UserContext requestingUser, Long sourceId, String sourceDocumentId,
            String expectedSourceVersion);

    /**
     * Server-created share context only.  This is intentionally separate from AI content retrieval:
     * it may use the publisher's exact file-bound delegation for an authorized recipient.
     */
    default SourceMetadataVerificationResult verifyDownload(SourceAccessContext context, long deadlineMs) {
        return SourceMetadataVerificationResult.failed(
                com.sdv.source.domain.SourceMetadataVerificationOutcome.FAILED,
                "shared download is not supported by this connector");
    }

    /** Bounded, non-AI download transport; the caller owns bytes only after VERIFIED. */
    default SourceDownloadResult fetchDownload(SourceAccessContext context, String expectedSourceVersion,
            long maxBytes, long deadlineMs) {
        return SourceDownloadResult.failed(com.sdv.source.domain.SourceContentOutcome.FAILED,
                "shared download is not supported by this connector");
    }

    /**
     * M12 신규(F-BE-103, §2A.5 Mandatory Live Retrieval) - AI 검색/응답을 위한
     * Share-bound Content Fetch 전 사전(pre-fetch) 확인. {@link #verifyDownload}와
     * 의도적으로 분리한 별도 연산이다 - AI 자격은 {@code ShareAction.VIEW} 부여 +
     * 별도의 Local AI Usage Policy 평가로 판단하며(CORE_SPEC §2A.5), {@code
     * ShareAction.DOWNLOAD}를 요구하거나 대신하지 않는다("Do not add a new
     * ShareAction or silently require DOWNLOAD for AI use" - 승인된 작업 범위).
     * 게시자(A)의 File-Bound Provider Delegation만 사용하고 요청자(B)에게 노출하지
     * 않는다.
     */
    default SourceMetadataVerificationResult verifyForAi(SourceAccessContext context, long deadlineMs) {
        return SourceMetadataVerificationResult.failed(
                com.sdv.source.domain.SourceMetadataVerificationOutcome.FAILED,
                "AI content retrieval is not supported by this connector");
    }

    /**
     * {@link #verifyForAi}와 동일한 Share-bound Credential로 실제 Content Byte를
     * Bounded/검증된 형태로 가져온다({@link SourceContentResult#outcome()}이
     * {@code VERIFIED}일 때만 Byte가 존재한다) - Core 포맷 화이트리스트/Google
     * Docs Transient Export/Fetch 전후 Version 재확인(1회 재시도 포함)을 모두
     * 적용한다({@link #fetchContent}와 동일한 검증, Credential 결합 방식만 다르다).
     */
    default SourceContentResult fetchForAi(SourceAccessContext context, String expectedSourceVersion,
            long deadlineMs) {
        return SourceContentResult.failed(com.sdv.source.domain.SourceContentOutcome.FAILED,
                "AI content retrieval is not supported by this connector");
    }

    /**
     * Catalog Sync용 ACL 조회 - Source Owner의 Credential로 수행된다. 모든
     * 페이지를 합쳐 반환한다(중간에 잘리지 않는다) - 실패/불확실 시
     * {@link SourcePermissionsResult#unknown()}/{@link SourcePermissionsResult#failed()}를
     * 반환하며, 이를 빈 권한 목록과 절대 혼동하지 않는다.
     */
    SourcePermissionsResult getPermissions(Long sourceId, String sourceDocumentId);

    /**
     * 변경 Feed 한 페이지를 조회한다 - Source Owner의 Credential로 수행된다.
     * {@code pageToken}은 첫 호출에서는 {@code changes.getStartPageToken}으로
     * 얻은 시작 Token, 이후 호출에서는 이전 {@link SourceChangePage#nextPageToken()}이다.
     * DB Cursor Commit은 이 메서드의 책임이 아니다(M09).
     */
    SourceChangePage findChanges(Long sourceId, String pageToken);

    /**
     * M10 신규(RAG-011) - Content Byte를 전혀 Fetch하지 않고, {@code requestingUser}
     * 본인의 Credential로 이 문서의 지금 이 순간 Google Metadata(이름/타입/Version/
     * Trashed/{@code canDownload})만 재확인한다. {@link #fetchContent}와 동일하게
     * 요청자 본인 결합을 요구한다 - 현재 Data Model 한계(Owner-Only Credential
     * 결합)도 동일하게 적용된다.
     */
    SourceMetadataVerificationResult verifyCurrentMetadata(UserContext requestingUser, Long sourceId,
            String sourceDocumentId);
}
