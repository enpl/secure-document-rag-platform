package com.sdv.testbed;

import com.sdv.common.model.UserContext;
import com.sdv.source.application.SourceConnectorRegistry;
import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.application.port.SourceCredentialException;
import com.sdv.source.application.port.SourceSyncException;
import com.sdv.source.domain.SourceConnection;
import com.sdv.source.domain.SourceContentOutcome;
import com.sdv.source.domain.SourceContentResult;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourceType;
import com.sdv.source.infrastructure.persistence.entity.SourceConnectionEntity;
import com.sdv.source.infrastructure.persistence.repository.SourceConnectionJpaRepository;
import com.sdv.source.infrastructure.persistence.repository.SourceOAuthTokenJpaRepository;
import com.sdv.testbed.dto.TestbedReadDiagnosticResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * M16A follow-up local testbed diagnostic
 * ({@code docs/runbooks/M16A_LOCAL_TESTBED.md}) - proves one bounded, live,
 * permission-checked read of a single explicitly-allowlisted Google Drive
 * file through the existing {@link DocumentSourceConnector} contract. Not a
 * canonical File/Feature ID.
 *
 * <p>This is deliberately NOT document search, catalog sync, indexing or an
 * AI answer - it never writes a {@code SourceDocument} catalog row and never
 * marks anything {@code INDEXED}. Every rejection path below runs strictly
 * before any Google-reaching connector call, so a denied request costs
 * exactly zero Google API calls.</p>
 *
 * <p>{@code @Profile("testbed")} here is defense in depth - the actually
 * network-reachable gate is {@link TestbedReadDiagnosticController}'s
 * combined {@code @Profile("testbed")} + {@code @ConditionalOnProperty}. This
 * class being registered outside that profile would still be inert (nothing
 * calls it), but there is no reason to register it at all outside the
 * testbed profile.</p>
 */
@Service
@Profile("testbed")
public class TestbedReadDiagnosticService {

    /** Diagnostic-only content cap - deliberately far stricter than the connector's own general 25MB bound. */
    static final long MAX_DIAGNOSTIC_CONTENT_BYTES = 1024L * 1024L; // 1 MiB

    private static final String GOOGLE_DRIVE_TYPE = SourceType.GOOGLE_DRIVE.name();
    private static final String PLAIN_TEXT_MIME_TYPE = "text/plain";

    private final SourceConnectionJpaRepository sourceConnectionJpaRepository;
    private final SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository;
    private final SourceConnectorRegistry connectorRegistry;
    private final TestbedDiagnosticsProperties properties;

    public TestbedReadDiagnosticService(SourceConnectionJpaRepository sourceConnectionJpaRepository,
            SourceOAuthTokenJpaRepository sourceOAuthTokenJpaRepository, SourceConnectorRegistry connectorRegistry,
            TestbedDiagnosticsProperties properties) {
        this.sourceConnectionJpaRepository = sourceConnectionJpaRepository;
        this.sourceOAuthTokenJpaRepository = sourceOAuthTokenJpaRepository;
        this.connectorRegistry = connectorRegistry;
        this.properties = properties;
    }

    public TestbedReadDiagnosticResponse checkRead(UserContext admin, Long sourceId, String fileId) {
        if (!properties.enabled()) {
            // 방어적 이중 확인 - Controller의 @ConditionalOnProperty가 이미 이 경로 자체를
            // 등록하지 않았어야 정상이다. 여기 도달했다면 Google 호출 없이 즉시 거부한다.
            return denied("DIAGNOSTIC_DISABLED", "이 진단 기능은 비활성화되어 있습니다.");
        }

        String allowedFileId = properties.allowedFileId();
        if (allowedFileId == null || allowedFileId.isBlank() || !allowedFileId.equals(fileId)) {
            // 허용 목록(정확히 1개)에 없는 File ID - Drive를 조회하지 않고 즉시 거부한다.
            return denied("FILE_NOT_ALLOWLISTED",
                    "요청한 File ID가 이 진단의 허용 목록(정확히 1개)에 없습니다.");
        }

        Optional<SourceConnectionEntity> owned = admin == null || admin.subject() == null
                ? Optional.empty()
                : sourceConnectionJpaRepository.findByIdAndOwnerSubject(sourceId, admin.subject());
        if (owned.isEmpty()) {
            // 존재하지 않음/다른 관리자 소유 - 어느 쪽인지 구분해 노출하지 않는다(기존 M04
            // Owner-Scoped 조회 관례와 동일) - Connector를 전혀 호출하지 않는다.
            return denied("SOURCE_NOT_OWNED_OR_NOT_FOUND",
                    "이 Source를 찾을 수 없거나 요청한 관리자 소유가 아닙니다.");
        }
        SourceConnectionEntity connection = owned.get();
        if (!GOOGLE_DRIVE_TYPE.equals(connection.getType())
                || !SourceConnection.STATUS_ACTIVE.equals(connection.getStatus())) {
            return denied("SOURCE_NOT_ACTIVE",
                    "이 Source는 Google Drive 종류가 아니거나 현재 ACTIVE 상태가 아닙니다.");
        }
        if (!sourceOAuthTokenJpaRepository.existsBySourceId(sourceId)) {
            // 부작용 없는 존재 확인만 사용한다(MVP-17과 동일 이유) - Credential이 아예 없으면
            // Connector를 호출해 실패를 확인할 필요가 없다.
            return denied("MISSING_CREDENTIAL", "이 Source에 저장된 Google Credential이 없습니다.");
        }

        Optional<DocumentSourceConnector> connector = connectorRegistry.getConnector(SourceType.GOOGLE_DRIVE);
        if (connector.isEmpty()) {
            return denied("CONNECTOR_UNAVAILABLE", "Google Drive Connector가 등록되어 있지 않습니다.");
        }

        SourceDocument metadata;
        try {
            metadata = connector.get().getMetadata(sourceId, fileId);
        } catch (SourceCredentialException e) {
            return denied(e.getReason().name(), "Credential 문제로 Metadata를 조회하지 못했습니다.");
        } catch (SourceSyncException e) {
            return denied(e.getReason().name(), "Metadata 조회에 실패했습니다.");
        }

        if (!PLAIN_TEXT_MIME_TYPE.equals(metadata.getMimeType())) {
            // 실제 Content Fetch(두 번째 Google 호출) 전에 형식을 먼저 걸러낸다 - 이 진단은
            // 작은 순수 TXT 파일만 다룬다(Google Docs Export/PDF/이미지 등은 대상이 아니다).
            return denied("UNSUPPORTED_FORMAT_FOR_DIAGNOSTIC",
                    "이 진단은 text/plain 파일만 지원합니다 - 실제 형식: " + safeMimeType(metadata.getMimeType()));
        }

        SourceContentResult result = connector.get().fetchContent(admin, sourceId, fileId,
                metadata.getSourceVersion());
        if (result.outcome() != SourceContentOutcome.VERIFIED) {
            return denied(result.outcome().name(), "실시간 접근권한/Version 검증을 통과하지 못했습니다.");
        }

        byte[] content = result.content();
        int bytesRead = content == null ? 0 : content.length;
        // 결과 계산 즉시 참조를 버린다 - 이 진단은 원본 Byte/Text를 어디에도 보관하지 않는다.
        content = null;

        if (bytesRead > MAX_DIAGNOSTIC_CONTENT_BYTES) {
            return new TestbedReadDiagnosticResponse(false, "CONTENT_TOO_LARGE_FOR_DIAGNOSTIC", bytesRead, true,
                    "이 진단의 1MiB 상한을 초과했습니다 - 더 작은 테스트 파일을 사용하세요.");
        }

        return new TestbedReadDiagnosticResponse(true, "VERIFIED", bytesRead, true, null);
    }

    private static TestbedReadDiagnosticResponse denied(String outcome, String reason) {
        return new TestbedReadDiagnosticResponse(false, outcome, 0, false, reason);
    }

    private static String safeMimeType(String mimeType) {
        // MIME Type 자체는 민감정보가 아니다(Content가 아니다) - 그대로 노출해도 안전하다.
        return mimeType == null ? "(unknown)" : mimeType;
    }
}
