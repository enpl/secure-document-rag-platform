package com.sdv.source.domain;

import java.util.List;
import java.util.Objects;

/**
 * M08 신규 - {@link com.sdv.source.application.port.DocumentSourceConnector#getPermissions}
 * 의 반환값. 빈 목록({@code List.of()})과 "조회 자체가 실패/불확실했다"를
 * 절대 같은 것으로 취급하지 않는다 - Java Type 수준에서 구분한다(빈 목록을
 * "권한 없음"으로 잘못 해석하면 INV-SRC-002/INV-RAG-002를 어길 수 있다).
 */
public record SourcePermissionsResult(Kind kind, List<SourcePermission> permissions) {

    public enum Kind {
        /** {@link #permissions()}가 이 문서의 실제(모든 페이지를 합친) 권한 목록이다. */
        OK,
        /** 신뢰 가능하게 조회할 수 없었다(예: Credential 없음/Scope 부족) - Fail Closed로 취급해야 한다. */
        UNKNOWN,
        /** 조회 자체가 실패했다(Network/Timeout 등). */
        FAILED
    }

    public SourcePermissionsResult {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(permissions, "permissions must not be null");
        permissions = List.copyOf(permissions);
        if (kind != Kind.OK && !permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must be empty unless kind is OK");
        }
    }

    public static SourcePermissionsResult ok(List<SourcePermission> permissions) {
        return new SourcePermissionsResult(Kind.OK, permissions);
    }

    public static SourcePermissionsResult unknown() {
        return new SourcePermissionsResult(Kind.UNKNOWN, List.of());
    }

    public static SourcePermissionsResult failed() {
        return new SourcePermissionsResult(Kind.FAILED, List.of());
    }
}
