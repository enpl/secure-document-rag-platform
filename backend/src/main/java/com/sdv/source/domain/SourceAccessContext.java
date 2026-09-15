package com.sdv.source.domain;

import java.util.Objects;

/**
 * M10B 신규(`docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.5/§2A.13) - B안 공유 인가
 * 판단 하나에 필요한 모든 신원/자원 값을 한데 묶은, 서버가 만드는 값
 * 객체다. 요청자(B)/게시자(A)/Credential 소유자/Source/File/공유/요청한
 * 행위/세대가 서로 다른 값일 수 있다는 것을 Type으로 명시적으로 드러낸다 -
 * Client가 넘긴 어떤 값도 이 Context를 직접 만들지 않는다({@code
 * SourceSharingService}/{@code FileMetadataDiscoveryService}가 서버 측 조회
 * 결과로만 조립한다).
 *
 * <p>이 Context 자체는 아무 권한도 부여하지 않는다 - 순수 데이터 캐리어다.
 * 실제 판단은 {@code EffectivePermissionService.evaluateSharedAccess}가
 * 수행한다.</p>
 *
 * @param requesterSubject 지금 이 접근을 요청하는 인증된 SDV Subject(B) - 감사(Audit) Actor.
 * @param publisherSubject 이 공유를 게시한 SDV Subject(A) - Source Owner와 항상 같다.
 * @param sourceId 게시자가 소유한 {@code source_connections.id}.
 * @param documentId 대상 {@code source_documents.id}.
 * @param shareId 이 접근의 근거가 되는 {@code document_shares.id}.
 * @param requestedAction 요청된 행위(현재 이 Slice는 {@link ShareAction#VIEW}만 실제로 검증한다).
 * @param shareGeneration 판단 시점에 읽은 공유의 세대(JPA {@code @Version}) - 응답 노출 직전
 *         재확인에서 지금 저장된 값과 다르면 그 사이 공유가 갱신/철회/차단됐다는 뜻이다.
 * @param connectionGeneration 판단 시점에 읽은 게시자 Source 연결의 인가 세대(Connection/
 *         Authorization Epoch, Token Refresh 버전과는 다른 값 - {@code source_connections.
 *         connection_epoch}, V011). Disconnect마다 증가한다 - 지금 저장된 값과 다르면 그 사이
 *         Disconnect가 있었다는 뜻이며, Status가 우연히 다시 ACTIVE이더라도(재연결) 이 판단
 *         시점 기준으로는 낡은 것으로 취급한다.
 */
public record SourceAccessContext(String requesterSubject, String publisherSubject, Long sourceId, Long documentId,
        Long shareId, ShareAction requestedAction, long shareGeneration, long connectionGeneration) {

    public SourceAccessContext {
        requireNonBlank(requesterSubject, "requesterSubject");
        requireNonBlank(publisherSubject, "publisherSubject");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(shareId, "shareId must not be null");
        Objects.requireNonNull(requestedAction, "requestedAction must not be null");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
