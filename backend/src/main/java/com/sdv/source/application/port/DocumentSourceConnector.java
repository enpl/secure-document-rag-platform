package com.sdv.source.application.port;

import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourceType;

import java.util.List;

/**
 * F-BE-023. SDV와 외부/Local Source 사이의 공통 경계(SRC-001).
 *
 * <p>Google/AWS/JPA/HTTP 같은 기술 특화 타입이 이 Port로 새어 들어오지
 * 않는다 - JDK 타입과 Source Domain 타입만 사용한다. M04는 실제
 * Connector 동작(Google Drive/Local Vault 통신)을 구현하지 않는다 -
 * {@link com.sdv.source.application.SourceConnectorRegistry}가 이후
 * 작업(M06/M07)에서 등록할 Connector를 위해 가장 작은 계약만 정의한다.</p>
 */
public interface DocumentSourceConnector {

    /** 이 Connector가 처리하는 {@link SourceType} - Registry가 조회 Key로 사용한다. */
    SourceType supportedType();

    SourceDocument getMetadata(Long sourceId, String sourceDocumentId);

    byte[] fetchContent(Long sourceId, String sourceDocumentId);

    List<SourcePermission> getPermissions(Long sourceId, String sourceDocumentId);

    List<SourceDocument> findChanges(Long sourceId, String syncCursor);
}
