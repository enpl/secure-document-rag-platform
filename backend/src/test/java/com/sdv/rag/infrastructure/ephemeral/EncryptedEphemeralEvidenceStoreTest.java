package com.sdv.rag.infrastructure.ephemeral;

import com.sdv.rag.application.EphemeralEvidenceProperties;
import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M12 Focused Acceptance Test Group E(캐시/생명주기, `docs/spec/SDV_v3.2_CORE_SPEC.md`
 * §2A.6) - 순수 단위 테스트. 이 Class 자체(암호화/TTL/변조/용량/Eviction)는 DB/외부
 * 전송이 전혀 없으므로 실제 Testcontainers 없이 검증한다 - 각 테스트는 Controlled Clock
 * (299/300초 경계)으로 결정론적이다.
 */
class EncryptedEphemeralEvidenceStoreTest {

    private static final Instant BASE = Instant.parse("2026-09-16T00:00:00Z");

    private static EvidenceKey key(String requester, String conversation, String sourceVersion) {
        return new EvidenceKey(requester, conversation, 1L, 2L, 3L, 1L, 1L, sourceVersion);
    }

    private static EncryptedEphemeralEvidenceStore storeWithClock(MutableClock clock, int maxEntries) {
        return new EncryptedEphemeralEvidenceStore(new EphemeralEvidenceProperties(300, maxEntries,
                16L * 1024 * 1024), clock);
    }

    @Test
    void aFreshlyStoredEntryIsReadableWithExactlyTheSameBinding() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("requester-1", "conv-1", "v1");

        EvidenceHandle handle = store.putEncrypted(k, "hello evidence".getBytes(StandardCharsets.UTF_8));
        Optional<byte[]> result = store.getIfAuthorizedAndCurrent(handle, k);

        assertThat(result).isPresent();
        assertThat(new String(result.get(), StandardCharsets.UTF_8)).isEqualTo("hello evidence");
    }

    @Test
    void anEntryAt299SecondsIsStillReadableAndExactlyAt300SecondsIsExpired() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("requester-2", "conv-2", "v1");
        EvidenceHandle handle = store.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));

        clock.advance(299);
        assertThat(store.getIfAuthorizedAndCurrent(handle, k)).as("299s must still be within the hard TTL")
                .isPresent();

        clock.advance(1); // now exactly 300s since creation
        assertThat(store.getIfAuthorizedAndCurrent(handle, k)).as("300s must be expired (hard TTL, no sliding)")
                .isEmpty();
    }

    @Test
    void repeatedReadsAndCopiesDoNotExtendTheOriginalExpiry() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("requester-3", "conv-3", "v1");
        EvidenceHandle handle = store.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));

        for (int i = 0; i < 5; i++) {
            clock.advance(50);
            assertThat(store.getIfAuthorizedAndCurrent(handle, k)).isPresent();
        }
        // 지금까지 250초 경과 - 남은 50초를 더 전진시켜 정확히 300초에 도달시킨다(Sliding이라면 아직 유효했을 것).
        clock.advance(50);
        assertThat(store.getIfAuthorizedAndCurrent(handle, k))
                .as("reuse must never have extended expiresAt beyond the original createdAt + TTL").isEmpty();
    }

    @Test
    void aMismatchedConversationOrRequesterBindingIsRejectedAndTheEntryIsEvicted() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey original = key("requester-4", "conv-4", "v1");
        EvidenceHandle handle = store.putEncrypted(original, "evidence".getBytes(StandardCharsets.UTF_8));

        EvidenceKey wrongConversation = key("requester-4", "conv-OTHER", "v1");
        assertThat(store.getIfAuthorizedAndCurrent(handle, wrongConversation))
                .as("a client-supplied conversation id is not proof of ownership").isEmpty();
        // 결합 불일치는 즉시 제거한다 - 이후 올바른 결합으로도 다시 읽을 수 없다.
        assertThat(store.getIfAuthorizedAndCurrent(handle, original)).isEmpty();
    }

    @Test
    void aChangedSourceVersionBindingIsRejected() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey original = key("requester-5", "conv-5", "v1");
        EvidenceHandle handle = store.putEncrypted(original, "evidence".getBytes(StandardCharsets.UTF_8));

        EvidenceKey changedVersion = key("requester-5", "conv-5", "v2");
        assertThat(store.getIfAuthorizedAndCurrent(handle, changedVersion))
                .as("a source version change must invalidate reuse even before hard TTL").isEmpty();
    }

    @Test
    void tamperedCiphertextFailsClosedAndTheEntryIsEvicted() throws Exception {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("requester-6", "conv-6", "v1");
        EvidenceHandle handle = store.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));

        corruptStoredCiphertext(store, handle);

        assertThat(store.getIfAuthorizedAndCurrent(handle, k))
                .as("a corrupted ciphertext/AAD must be rejected, never returned as garbage plaintext").isEmpty();
    }

    @Test
    void closeOrCancelEvictsImmediatelyEvenBeforeExpiry() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("requester-7", "conv-7", "v1");
        EvidenceHandle handle = store.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));

        store.evict(handle);

        assertThat(store.getIfAuthorizedAndCurrent(handle, k)).isEmpty();
    }

    @Test
    void evictByDocumentRemovesOnlyThatDocumentsEntries() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey forDocumentTwo = new EvidenceKey("r", "c", 1L, 2L, 3L, 1L, 1L, "v1");
        EvidenceKey forDocumentNine = new EvidenceKey("r", "c", 1L, 9L, 3L, 1L, 1L, "v1");
        EvidenceHandle handleTwo = store.putEncrypted(forDocumentTwo, "a".getBytes(StandardCharsets.UTF_8));
        EvidenceHandle handleNine = store.putEncrypted(forDocumentNine, "b".getBytes(StandardCharsets.UTF_8));

        store.evictByDocument(2L);

        assertThat(store.getIfAuthorizedAndCurrent(handleTwo, forDocumentTwo)).isEmpty();
        assertThat(store.getIfAuthorizedAndCurrent(handleNine, forDocumentNine)).isPresent();
    }

    @Test
    void evictByConversationRemovesOnlyThatConversationsEntries() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey convA = key("r", "conv-A", "v1");
        EvidenceKey convB = key("r", "conv-B", "v1");
        EvidenceHandle handleA = store.putEncrypted(convA, "a".getBytes(StandardCharsets.UTF_8));
        EvidenceHandle handleB = store.putEncrypted(convB, "b".getBytes(StandardCharsets.UTF_8));

        store.evictByConversation("conv-A");

        assertThat(store.getIfAuthorizedAndCurrent(handleA, convA)).isEmpty();
        assertThat(store.getIfAuthorizedAndCurrent(handleB, convB)).isPresent();
    }

    @Test
    void capacityLimitFailsClosedInsteadOfSilentlyEvictingTheOldestEntry() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 1);
        store.putEncrypted(key("r1", "c1", "v1"), "a".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> store.putEncrypted(key("r2", "c2", "v1"), "b".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EphemeralEvidenceCapacityExceededException.class);
    }

    @Test
    void sweepExpiredRemovesEntriesNoOneHasReadSinceExpiry() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore store = storeWithClock(clock, 10);
        EvidenceKey k = key("r", "c", "v1");
        store.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));
        assertThat(store.size()).isEqualTo(1);

        clock.advance(301);
        store.evictExpired(clock.instant());

        assertThat(store.size()).isEqualTo(0);
    }

    @Test
    void aRecreatedStoreCannotDecryptEntriesFromAPriorProcessInstance() {
        MutableClock clock = new MutableClock(BASE);
        EncryptedEphemeralEvidenceStore firstProcess = storeWithClock(clock, 10);
        EvidenceKey k = key("r", "c", "v1");
        EvidenceHandle handle = firstProcess.putEncrypted(k, "evidence".getBytes(StandardCharsets.UTF_8));

        // 새 Process-local Key로 다시 만든 Store(재시작을 흉내낸다) - 이전 Store의 항목 자체가
        // 아예 존재하지 않는다(In-Memory, 어떤 Backing Store도 공유하지 않는다).
        EncryptedEphemeralEvidenceStore recreated = storeWithClock(clock, 10);

        assertThat(recreated.getIfAuthorizedAndCurrent(handle, k))
                .as("no durable backing store means a fresh instance can never recover a prior entry").isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void corruptStoredCiphertext(EncryptedEphemeralEvidenceStore store, EvidenceHandle handle)
            throws Exception {
        Field entriesField = EncryptedEphemeralEvidenceStore.class.getDeclaredField("entries");
        entriesField.setAccessible(true);
        Map<Object, Object> entries = (Map<Object, Object>) entriesField.get(store);
        Object entry = entries.get(handle.id());
        Field ciphertextField = entry.getClass().getDeclaredField("ciphertext");
        ciphertextField.setAccessible(true);
        byte[] ciphertext = (byte[]) ciphertextField.get(entry);
        ciphertext[0] ^= (byte) 0xFF;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
