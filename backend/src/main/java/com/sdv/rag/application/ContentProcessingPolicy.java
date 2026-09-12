package com.sdv.rag.application;

import com.sdv.rag.domain.ParseOutcomeKind;
import com.sdv.source.domain.DocumentIndexStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * v3.2 CORE_SPEC이 이름과 위치만 확정한 Class(F-BE-ID 없음, M06이 구현):
 * "{@code com.sdv.rag.application}의 {@code ContentProcessingPolicy}가 Source
 * 콘텐츠를 {@code DocumentIndexStatus}로 분류할 수 있다."
 *
 * <p>순수 분류 함수다 - Repository/HTTP 없이 직접 단위 테스트할 수 있다.
 * {@link ParseOutcomeKind#SUCCESS}는 {@link Optional#empty()}를 반환한다:
 * 추출 성공은 색인 완료({@code INDEXED})가 아니므로 {@code index_status}를
 * 건드리지 않는다(신규 문서라면 V003 기본값 {@code PENDING} 그대로 유지된다) -
 * 새 {@code DocumentIndexStatus} 값을 추가하거나 {@code READY} 같은 생명주기
 * 상태를 발명하지 않는다.</p>
 */
@Component
public class ContentProcessingPolicy {

    /** {@code Optional.empty()}면 호출자는 {@code index_status}를 전혀 쓰지 않아야 한다. */
    public Optional<DocumentIndexStatus> classify(ParseOutcomeKind kind) {
        return switch (kind) {
            case SUCCESS -> Optional.empty();
            case UNSUPPORTED_FORMAT -> Optional.of(DocumentIndexStatus.SKIPPED_UNSUPPORTED);
            case NO_TEXT -> Optional.of(DocumentIndexStatus.SKIPPED_NO_TEXT);
            case FAILED -> Optional.of(DocumentIndexStatus.FAILED);
        };
    }
}
