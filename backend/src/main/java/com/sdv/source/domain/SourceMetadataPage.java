package com.sdv.source.domain;

import java.util.List;
import java.util.Objects;

/**
 * M08 Review 교정(항목 1) 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#listMetadata}
 * 한 페이지의 결과. 기존 {@link SourceChangePage}/{@link SourcePermissionsResult}와
 * 같은 방식으로, 이 Connector에게 이미 알려진 문서 ID를 조회하는 것과 Drive
 * 전체를 처음부터 훑어(Whole-Drive Discovery) Metadata/ACL Catalog를 채우는
 * 것은 서로 다른 연산이다 - {@link com.sdv.source.application.port.DocumentSourceConnector#getMetadata}
 * 는 후자를 대신할 수 없다(이미 파일 ID를 알고 있다고 가정한다).
 *
 * <ul>
 *   <li>{@link #nextPageToken()} - 다음 페이지가 있으면 그 Token, 마지막
 *       페이지면 {@code null}(다른 Page 계열 Type과 동일한 관례).</li>
 *   <li>{@link #isComplete()} - Google {@code files.list}의 {@code incompleteSearch}를
 *       그대로 반영한다. {@code true}(={@code incompleteSearch=false})일 때만
 *       이 페이지의 {@link #documents()}를 신뢰할 수 있는 전체 결과의 일부로
 *       취급한다. {@code false}면 일부 결과가 누락됐을 수 있다는 뜻이다 -
 *       이 값이 {@code false}인 페이지가 하나라도 있으면 호출자(M09)는 전체
 *       Traversal을 "완료"로 표시하거나, 이번 결과에 없다는 이유만으로 기존
 *       Catalog 행을 삭제해서는 안 된다("Never report the traversal as
 *       complete and never authorize deletion of absent catalog rows from an
 *       incomplete result" - 이 작업 지시사항).</li>
 * </ul>
 *
 * <p>DB Cursor/Catalog Commit은 M08 책임이 아니다 - M09가 원자적으로 처리한다.
 * 이 Class는 그 판단에 필요한 정보를 있는 그대로 전달할 뿐이다.</p>
 */
public record SourceMetadataPage(List<SourceDocument> documents, String nextPageToken, boolean isLastPage,
        boolean isComplete) {

    public SourceMetadataPage {
        Objects.requireNonNull(documents, "documents must not be null");
        documents = List.copyOf(documents);
        if (isLastPage) {
            if (nextPageToken != null) {
                throw new IllegalArgumentException("nextPageToken must be null on the last page");
            }
        } else {
            Objects.requireNonNull(nextPageToken, "nextPageToken must not be null when there are more pages");
        }
    }
}
