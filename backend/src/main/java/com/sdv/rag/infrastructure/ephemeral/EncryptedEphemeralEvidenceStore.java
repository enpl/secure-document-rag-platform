package com.sdv.rag.infrastructure.ephemeral;

import com.sdv.rag.application.EphemeralEvidenceProperties;
import com.sdv.rag.application.port.out.EphemeralEvidenceCapacityExceededException;
import com.sdv.rag.application.port.out.EphemeralEvidenceStore;
import com.sdv.rag.domain.EvidenceHandle;
import com.sdv.rag.domain.EvidenceKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F-BE-208(M12 신규, `docs/spec/SDV_v3.2_CORE_SPEC.md` §2A.6 Encrypted Ephemeral
 * Evidence). {@link EphemeralEvidenceStore}를 Process-local Volatile Memory +
 * AES-256-GCM으로 구현한다 - DB/파일/Redis/Snapshot/Backup 어디에도 쓰지 않는다.
 *
 * <h2>Key 관리</h2>
 * <p>이 Process가 시작할 때 {@link SecureRandom}으로 생성한 AES-256 Key 하나만
 * 쓴다 - 재시작하면 그 이전 Process가 저장했던 어떤 Ciphertext도 복호화할 수 없다
 * (durable하지 않다는 것을 Key 자체가 강제한다). OAuth Token 암호화 Key(운영자가
 * 주입하는 Master Key)와는 완전히 별개다 - 절대 재사용/읽지 않는다.</p>
 *
 * <h2>변조(Tampering) 방어</h2>
 * <p>AES-GCM의 Associated Data에 근거 ID + {@link EvidenceKey} 전체 + 생성/만료
 * 시각을 함께 묶는다 - Ciphertext 자체가 다른 항목의 것으로 바뀌거나 결합 Metadata가
 * 조작되면 인증 Tag 검증이 실패해 {@link AEADBadTagException}이 발생한다. 이 경우
 * 그 항목을 즉시 제거하고 빈 결과를 반환한다(Fail Closed) - 손상된 값을 그대로
 * 반환하지 않는다.</p>
 *
 * <h2>TTL - Sliding 없음</h2>
 * <p>{@link #putEncrypted}가 계산한 {@code expiresAt}은 그 항목의 수명 동안 절대
 * 바뀌지 않는다 - {@link #getIfAuthorizedAndCurrent}는 읽기만 할 뿐 어떤 필드도
 * 갱신하지 않는다. 만료된 항목은 다음 접근(읽기 시도 또는 주기적 Sweep) 때
 * 제거된다.</p>
 *
 * <h2>Physical Erasure에 대한 정직한 한계</h2>
 * <p>제거 시 Ciphertext Byte 배열을 0으로 덮어쓰지만, JVM Garbage Collector가 그
 * 이전 시점에 이미 만들어 뒀을 수 있는 다른 내부 복사본(예: String Pool, 이동된
 * Heap 영역의 이전 복사)까지 지운다고 보장하지 않는다 - 이는 이 Class만의 한계가
 * 아니라 Java 자체의 한계다({@code SharedFileDownloadService.discard}와 동일한
 * 정직한 문서화 관례).</p>
 */
@Component
public class EncryptedEphemeralEvidenceStore implements EphemeralEvidenceStore {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> documentFences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> sourceFences = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> conversationFences = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private long admittedBytes;
    private final EphemeralEvidenceProperties properties;
    private final Clock clock;
    private final SecretKey processLocalKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public EncryptedEphemeralEvidenceStore(EphemeralEvidenceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트가 만료/TTL을 결정론적으로 재현하기 위한 패키지 전용 생성자. */
    EncryptedEphemeralEvidenceStore(EphemeralEvidenceProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.processLocalKey = generateProcessLocalKey();
    }

    @Override
    public EvidenceHandle putEncrypted(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes) {
        return putEncryptedFenced(key, plaintextEvidenceUtf8Bytes, captureDocumentFence(key.documentId()),
                captureSourceFence(key.sourceId()), captureConversationFence(key.requesterSubject(), key.conversationId()));
    }

    @Override
    public long captureDocumentFence(Long documentId) {
        return documentFences.computeIfAbsent(documentId, ignored -> new AtomicLong()).get();
    }

    @Override
    public EvidenceHandle putEncryptedFenced(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes,
            long expectedDocumentFence) {
        return putEncryptedFenced(key, plaintextEvidenceUtf8Bytes, expectedDocumentFence,
                captureSourceFence(key.sourceId()), captureConversationFence(key.requesterSubject(), key.conversationId()));
    }

    @Override
    public long captureSourceFence(Long sourceId) {
        return sourceFences.computeIfAbsent(sourceId, ignored -> new AtomicLong()).get();
    }

    @Override
    public long captureConversationFence(String requesterSubject, String conversationId) {
        return conversationFences.computeIfAbsent(conversationKey(requesterSubject, conversationId),
                ignored -> new AtomicLong()).get();
    }

    @Override
    public EvidenceHandle putEncryptedFenced(EvidenceKey key, byte[] plaintextEvidenceUtf8Bytes,
            long expectedDocumentFence, long expectedSourceFence, long expectedConversationFence) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(plaintextEvidenceUtf8Bytes, "plaintextEvidenceUtf8Bytes must not be null");
        synchronized (admissionLock) {
        if (captureDocumentFence(key.documentId()) != expectedDocumentFence
                || captureSourceFence(key.sourceId()) != expectedSourceFence
                || captureConversationFence(key.requesterSubject(), key.conversationId()) != expectedConversationFence) {
            throw new com.sdv.rag.application.port.out.EphemeralEvidenceInvalidatedException();
        }
        if (entries.size() >= properties.maxEntries()) {
            // Bounded Memory - 무제한으로 쌓이지 않는다(Fail Closed, 가장 오래된 항목을 몰래 밀어내지 않는다).
            throw new EphemeralEvidenceCapacityExceededException();
        }
        Instant now = clock.instant();
        Instant expiresAt = now.plusSeconds(properties.ttlSeconds());
        UUID id = UUID.randomUUID();
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        byte[] associatedData = associatedDataFor(id, key, now, expiresAt);
        byte[] ciphertext = seal(plaintextEvidenceUtf8Bytes, iv, associatedData);
        if (ciphertext.length > properties.maxBytes() - admittedBytes) {
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            throw new EphemeralEvidenceCapacityExceededException();
        }
        entries.put(id, new Entry(key, ciphertext, iv, now, expiresAt, expectedSourceFence, expectedConversationFence));
        admittedBytes += ciphertext.length;
        Arrays.fill(plaintextEvidenceUtf8Bytes, (byte) 0);
        return new EvidenceHandle(id, now, expiresAt);
        }
    }

    @Override
    public Optional<byte[]> getIfAuthorizedAndCurrent(EvidenceHandle handle, EvidenceKey currentBinding) {
        return getIfAuthorizedAndCurrentFenced(handle, currentBinding, captureSourceFence(currentBinding.sourceId()),
                captureConversationFence(currentBinding.requesterSubject(), currentBinding.conversationId()));
    }

    @Override
    public Optional<byte[]> getIfAuthorizedAndCurrentFenced(EvidenceHandle handle, EvidenceKey currentBinding,
            long expectedSourceFence, long expectedConversationFence) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(currentBinding, "currentBinding must not be null");
        synchronized (admissionLock) {
        Entry entry = entries.get(handle.id());
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(entry.expiresAt()) || !entry.key().equals(currentBinding)
                || entry.sourceFence() != expectedSourceFence || entry.conversationFence() != expectedConversationFence
                || captureSourceFence(currentBinding.sourceId()) != expectedSourceFence
                || captureConversationFence(currentBinding.requesterSubject(), currentBinding.conversationId())
                        != expectedConversationFence) {
            // 만료됐거나(Hard TTL, Sliding 없음) 결합이 더 이상 일치하지 않는다(요청자/대화/
            // 공유·연결 세대/Source Version 중 하나 이상 변경) - 즉시 제거하고 거부한다.
            removeLocked(handle.id());
            return Optional.empty();
        }
        byte[] associatedData = associatedDataFor(handle.id(), entry.key(), entry.createdAt(), entry.expiresAt());
        try {
            return Optional.of(open(entry.ciphertext(), entry.iv(), associatedData));
        } catch (GeneralSecurityException tampered) {
            // 인증 Tag 검증 실패 - Ciphertext/결합 Metadata가 변조됐다는 뜻이다(Fail Closed).
            removeLocked(handle.id());
            return Optional.empty();
        }
        }
    }

    @Override
    public void evict(EvidenceHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        remove(handle.id());
    }

    @Override
    public void evictByDocument(Long documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        synchronized (admissionLock) {
        documentFences.computeIfAbsent(documentId, ignored -> new AtomicLong()).incrementAndGet();
        entries.entrySet().stream().filter(e -> documentId.equals(e.getValue().key().documentId()))
                .map(java.util.Map.Entry::getKey).toList().forEach(this::removeLocked);
        }
    }

    @Override
    public void evictByConversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        synchronized (admissionLock) {
            entries.entrySet().stream().filter(e -> conversationId.equals(e.getValue().key().conversationId()))
                    .map(java.util.Map.Entry::getKey).toList().forEach(this::removeLocked);
        }
    }

    @Override
    public void evictByConversation(String requesterSubject, String conversationId) {
        Objects.requireNonNull(requesterSubject, "requesterSubject must not be null");
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        synchronized (admissionLock) {
            conversationFences.computeIfAbsent(conversationKey(requesterSubject, conversationId), ignored -> new AtomicLong())
                    .incrementAndGet();
            entries.entrySet().stream().filter(e -> requesterSubject.equals(e.getValue().key().requesterSubject())
                    && conversationId.equals(e.getValue().key().conversationId())).map(java.util.Map.Entry::getKey)
                    .toList().forEach(this::removeLocked);
        }
    }

    @Override
    public void evictBySource(Long sourceId) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        synchronized (admissionLock) {
            sourceFences.computeIfAbsent(sourceId, ignored -> new AtomicLong()).incrementAndGet();
            entries.entrySet().stream().filter(e -> sourceId.equals(e.getValue().key().sourceId()))
                    .map(java.util.Map.Entry::getKey).toList().forEach(this::removeLocked);
        }
    }

    /**
     * 주기적 Sweep - Hard TTL이 지났지만 그 사이 아무도 읽지 않은 항목까지 회수한다
     * (읽기 시점의 지연(Lazy) 제거만으로는 "아무도 다시 읽지 않는 만료 항목"이 다음
     * 접근 전까지 메모리에 남아있을 수 있다).
     */
    @Scheduled(fixedDelayString = "${sdv.rag.ephemeral-evidence.sweep-interval-ms:30000}")
    void sweepExpired() {
        evictExpired(clock.instant());
    }

    void evictExpired(Instant now) {
        synchronized (admissionLock) {
            entries.entrySet().stream().filter(e -> !now.isBefore(e.getValue().expiresAt()))
                    .map(java.util.Map.Entry::getKey).toList().forEach(this::removeLocked);
        }
    }

    /** 현재 보관 중인 항목 수 - 테스트/진단 전용. */
    int size() {
        return entries.size();
    }

    long admittedBytes() {
        synchronized (admissionLock) {
            return admittedBytes;
        }
    }

    private void remove(UUID id) {
        synchronized (admissionLock) {
            removeLocked(id);
        }
    }

    private void removeLocked(UUID id) {
        Entry removed = entries.remove(id);
        if (removed != null) {
            admittedBytes -= removed.ciphertext().length;
            Arrays.fill(removed.ciphertext(), (byte) 0);
            Arrays.fill(removed.iv(), (byte) 0);
        }
    }

    private static byte[] associatedDataFor(UUID id, EvidenceKey key, Instant createdAt, Instant expiresAt) {
        String joined = String.join("|", id.toString(), key.requesterSubject(), key.conversationId(),
                String.valueOf(key.sourceId()), String.valueOf(key.documentId()), String.valueOf(key.shareId()),
                String.valueOf(key.shareGeneration()), String.valueOf(key.connectionGeneration()),
                key.sourceVersion(), createdAt.toString(), expiresAt.toString());
        return joined.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] seal(byte[] plaintext, byte[] iv, byte[] associatedData) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, processLocalKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to seal ephemeral evidence", e);
        }
    }

    private byte[] open(byte[] ciphertext, byte[] iv, byte[] associatedData) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, processLocalKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(ciphertext);
    }

    private static SecretKey generateProcessLocalKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KEY_ALGORITHM);
            generator.init(KEY_BITS, new SecureRandom());
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES key generation is not available in this JVM", e);
        }
    }

    private static String conversationKey(String requesterSubject, String conversationId) {
        return requesterSubject + "\u0000" + conversationId;
    }

    private record Entry(EvidenceKey key, byte[] ciphertext, byte[] iv, Instant createdAt, Instant expiresAt,
            long sourceFence, long conversationFence) {
    }
}
