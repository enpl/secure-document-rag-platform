package com.sdv.rag.application.port.out;

import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceKey;

import java.util.Optional;

/**
 * F-BE-206(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.6 Encrypted Ephemeral
 * Evidence). 암호화된, 비영속(Non-durable) 근거(Evidence) Storage 계약 - Hard TTL은
 * 원본 생성 시점부터 최대 300초다. 읽기/재사용은 {@code expiresAt}을 절대 연장하지
 * 않는다(Sliding TTL 금지).
 *
 * <p>이 Port 자체는 SDV/Provider 인가를 다시 판단하지 않는다 - {@link
 * #getIfAuthorizedAndCurrent}의 {@code currentBinding}은 호출자({@link
 * com.sdv.rag.application.LiveEvidenceRetrievalService})가 재사용 직전 새로 수행한
 * 신선한(Fresh) SDV 공유 + Provider 접근/Version 재확인 결과로 조립한 {@link
 * EvidenceKey}여야 한다 - 이 Port는 그 값을 저장된 원본 {@link EvidenceKey}와
 * 기계적으로 비교할 뿐이다(요청자/대화/공유·연결 세대/Source Version 중 하나라도
 * 다르면 거부). 캐시 실패/불일치는 절대 권한을 확장하지 않는다 - 하나라도
 * 어긋나면 즉시 제거하고 빈 결과를 반환한다(Fail Closed, Stale Fallback 없음).</p>
 */
public interface EphemeralEvidenceStore {

    /**
     * 새 근거 하나를 암호화해 저장한다. {@code createdAt}은 이 Store 자신의
     * Clock으로 정한다(호출자가 넘긴 값으로 TTL을 조작할 수 없다). 반환된
     * {@link EvidenceHandle#expiresAt()}은 {@code createdAt + 300초}를 절대 넘지
     * 않는다.
     */
    EvidenceHandle putEncrypted(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes);

    default long captureDocumentFence(Long documentId) {
        return 0L;
    }

    default EvidenceHandle putEncryptedFenced(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes,
            long expectedDocumentFence) {
        return putEncrypted(key, plaintextEvidenceUtf8Bytes);
    }

    default long captureSourceFence(Long sourceId) { return 0L; }

    default long captureConversationFence(String requesterSubject, String conversationId) { return 0L; }

    default EvidenceHandle putEncryptedFenced(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes,
            long expectedDocumentFence, long expectedSourceFence, long expectedConversationFence) {
        return putEncryptedFenced(key, plaintextEvidenceUtf8Bytes, expectedDocumentFence);
    }

    default Optional<byte[]> getIfAuthorizedAndCurrentFenced(EvidenceHandle handle, EvidenceKey currentBinding,
            long expectedSourceFence, long expectedConversationFence) {
        return getIfAuthorizedAndCurrent(handle, currentBinding);
    }

    /**
     * {@code handle}이 아직 만료되지 않았고, 저장된 원본 {@link EvidenceKey}가
     * {@code currentBinding}과 정확히 같을 때만 평문을 복호화해 반환한다. 만료됐거나
     * 결합이 어긋나거나 변조가 감지되면 그 항목을 즉시 제거하고 {@link
     * Optional#empty()}를 반환한다 - 이 메서드 자체가 TTL을 연장하지 않는다.
     */
    Optional<byte[]> getIfAuthorizedAndCurrent(EvidenceHandle handle, EvidenceKey currentBinding);

    /** 즉시 제거 - 오류/취소/명시적 종료 시 호출한다. */
    void evict(EvidenceHandle handle);

    /** 이 문서에 대한 모든 근거를 제거한다 - Revoke/Block/Disconnect/Version 변경 감지 시 호출한다. */
    void evictByDocument(Long documentId);

    /** 이 Conversation에 속한 모든 근거를 제거한다 - Conversation 종료/취소 시 호출한다. */
    void evictByConversation(String conversationId);

    /** Scoped variant prevents a colliding conversation label from evicting another requester. */
    default void evictByConversation(String requesterSubject, String conversationId) {
        evictByConversation(conversationId);
    }

    /** Disconnect invalidation is source-wide, rather than document-specific. */
    default void evictBySource(Long sourceId) {
    }
}
