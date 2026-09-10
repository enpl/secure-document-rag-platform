package com.sdv.source.application;

import com.sdv.source.application.port.DocumentSourceConnector;
import com.sdv.source.domain.SourceDocument;
import com.sdv.source.domain.SourcePermission;
import com.sdv.source.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-BE-026 검증: SourceType→Connector 조회는 결정론적이어야 하고, 중복 등록은
 * 기동 시점에 실패해야 하며, Connector가 하나도 없어도(M04 시점의 실제 상태)
 * 정상 구성되어야 한다.
 */
class SourceConnectorRegistryTest {

    @Test
    void constructsSuccessfullyWithNoRegisteredConnectors() {
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of());

        assertThat(registry.getConnector(SourceType.GOOGLE_DRIVE)).isEmpty();
    }

    @Test
    void resolvesRegisteredConnectorByType() {
        DocumentSourceConnector googleDrive = stubConnector(SourceType.GOOGLE_DRIVE);
        DocumentSourceConnector localVault = stubConnector(SourceType.LOCAL_VAULT);

        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(googleDrive, localVault));

        assertThat(registry.getConnector(SourceType.GOOGLE_DRIVE)).contains(googleDrive);
        assertThat(registry.getConnector(SourceType.LOCAL_VAULT)).contains(localVault);
    }

    @Test
    void returnsEmptyForUnsupportedOrUnwiredType() {
        SourceConnectorRegistry registry = new SourceConnectorRegistry(List.of(stubConnector(SourceType.GOOGLE_DRIVE)));

        assertThat(registry.getConnector(SourceType.SHAREPOINT)).isEmpty();
        assertThat(registry.getConnector(SourceType.S3)).isEmpty();
    }

    @Test
    void rejectsDuplicateRegistrationForSameTypeDeterministically() {
        DocumentSourceConnector first = stubConnector(SourceType.GOOGLE_DRIVE);
        DocumentSourceConnector second = stubConnector(SourceType.GOOGLE_DRIVE);

        assertThatThrownBy(() -> new SourceConnectorRegistry(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_DRIVE");
    }

    private static DocumentSourceConnector stubConnector(SourceType type) {
        return new DocumentSourceConnector() {
            @Override
            public SourceType supportedType() {
                return type;
            }

            @Override
            public SourceDocument getMetadata(Long sourceId, String sourceDocumentId) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public byte[] fetchContent(Long sourceId, String sourceDocumentId) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public List<SourcePermission> getPermissions(Long sourceId, String sourceDocumentId) {
                throw new UnsupportedOperationException("not used in this test");
            }

            @Override
            public List<SourceDocument> findChanges(Long sourceId, String syncCursor) {
                throw new UnsupportedOperationException("not used in this test");
            }
        };
    }
}
