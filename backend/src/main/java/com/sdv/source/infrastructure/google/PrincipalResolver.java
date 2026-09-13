package com.sdv.source.infrastructure.google;

import com.sdv.source.domain.SourcePrincipal;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * F-BE-047 (M08 신규). Google Permission 원시 표현을 {@link SourcePrincipal}로
 * 정규화한다 - 모호한 경우는 절대 지어내지 않고 {@link Optional#empty()}로
 * Fail Closed 한다({@code docs/spec/SDV_v3.2_CORE_SPEC.md} §10:
 * "Ambiguous mapping must Fail Closed").
 *
 * <p>모호함으로 취급해 제외하는 경우:</p>
 * <ul>
 *   <li>{@code deleted=true} - 삭제된 사용자/그룹. 신원을 더 이상 신뢰할
 *       수 없다.</li>
 *   <li>{@code type}이 {@code user}/{@code group}인데 {@code emailAddress}가
 *       비어있다.</li>
 *   <li>{@code type}이 {@code domain}인데 {@code domain}이 비어있다.</li>
 *   <li>{@code type}이 {@code user}/{@code group}/{@code domain}/{@code anyone}
 *       중 어느 것도 아니다(알려지지 않은 값).</li>
 * </ul>
 */
@Component
public class PrincipalResolver {

    private static final Set<String> KNOWN_TYPES = Set.of("user", "group", "domain", "anyone");

    public Optional<SourcePrincipal> resolve(GoogleDriveClient.GooglePermission permission) {
        if (Boolean.TRUE.equals(permission.deleted())) {
            return Optional.empty();
        }
        String type = permission.type();
        if (type == null || !KNOWN_TYPES.contains(type)) {
            return Optional.empty();
        }
        return switch (type) {
            case "user", "group" -> valueOrEmpty(type, permission.emailAddress());
            case "domain" -> valueOrEmpty(type, permission.domain());
            case "anyone" -> Optional.of(new SourcePrincipal("anyone", "anyone"));
            default -> Optional.empty();
        };
    }

    private static Optional<SourcePrincipal> valueOrEmpty(String type, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SourcePrincipal(type, value));
    }
}
