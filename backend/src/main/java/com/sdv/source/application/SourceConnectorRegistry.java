package com.sdv.source.application;

import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceType;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-BE-026. {@link SourceType}을 {@link DocumentSourceConnector}로 해석한다
 * (SRC-001, SRC-008, SRC-009).
 *
 * <p>Spring이 발견한 모든 {@code DocumentSourceConnector} Bean을 시작 시점에
 * 등록한다 - M04 시점에는 실제 구현체(Google Drive/Local Vault 등)가 아직
 * 없으므로 빈 목록으로도 정상 기동해야 한다(M06/M07 이전에도 Application
 * Context가 뜰 수 있어야 함). 같은 {@link SourceType}에 대한 중복 등록은
 * 기동 시점에 결정적으로 실패한다.</p>
 */
@Service
public class SourceConnectorRegistry {

    private final Map<SourceType, DocumentSourceConnector> connectorsByType;

    public SourceConnectorRegistry(List<DocumentSourceConnector> connectors) {
        Map<SourceType, DocumentSourceConnector> registered = new LinkedHashMap<>();
        for (DocumentSourceConnector connector : connectors) {
            SourceType type = connector.supportedType();
            DocumentSourceConnector existing = registered.putIfAbsent(type, connector);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate DocumentSourceConnector registration for SourceType " + type);
            }
        }
        this.connectorsByType = Map.copyOf(registered);
    }

    /** 등록된 Connector가 없으면 빈 Optional을 반환한다 - 안전하게 처리해야 한다. */
    public Optional<DocumentSourceConnector> getConnector(SourceType type) {
        return Optional.ofNullable(connectorsByType.get(type));
    }
}
