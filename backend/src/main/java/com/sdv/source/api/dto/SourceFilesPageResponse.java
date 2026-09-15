package com.sdv.source.api.dto;

import java.util.List;

/** M10B 신규 - {@code GET /api/sources/{id}/files}의 Page 응답. 원시 총계/Cursor를 노출하지 않는다. */
public record SourceFilesPageResponse(List<SourceFileResponse> items, boolean hasMore) {
}
