package com.sdv.source.domain;

import java.util.List;
import java.util.Objects;

/**
 * M08 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#findChanges}
 * 한 페이지의 결과. Google {@code changes.list}의 Pagination 개념을 Source-neutral
 * 형태로 그대로 보존한다:
 *
 * <ul>
 *   <li>{@link #nextPageToken()} - 이 페이지 다음에 더 가져올 페이지가 있으면
 *       그 Token, 마지막 페이지면 {@code null}.</li>
 *   <li>{@link #newStartPageToken()} - 마지막 페이지에서만 채워진다(Google
 *       자체가 마지막 페이지에서만 응답에 포함시킨다) - 이 Traversal 전체가
 *       끝났을 때만 호출자(M09)가 다음 Sync의 시작점으로 저장해야 한다.
 *       {@link #isLastPage()}가 {@code false}인 동안 이 값을 커밋하지
 *       않는다("모든 페이지를 받기 전에 newStartPageToken으로 넘어가지
 *       않는다" - 이 작업 지시사항).</li>
 * </ul>
 *
 * <p>DB Cursor Commit은 M08 책임이 아니다 - M09가 Catalog/Outbox/Cursor
 * 상태를 원자적으로 Commit한다. 이 Class는 그 판단에 필요한 정보를 있는
 * 그대로 전달할 뿐이다.</p>
 */
public record SourceChangePage(List<SourceChangeRecord> changes, String nextPageToken, String newStartPageToken,
        boolean isLastPage) {

    public SourceChangePage {
        Objects.requireNonNull(changes, "changes must not be null");
        changes = List.copyOf(changes);
        if (isLastPage) {
            Objects.requireNonNull(newStartPageToken, "newStartPageToken must not be null on the last page");
            if (nextPageToken != null) {
                throw new IllegalArgumentException("nextPageToken must be null on the last page");
            }
        } else {
            Objects.requireNonNull(nextPageToken, "nextPageToken must not be null when there are more pages");
            if (newStartPageToken != null) {
                throw new IllegalArgumentException(
                        "newStartPageToken must be null until the last page has been received");
            }
        }
    }
}
