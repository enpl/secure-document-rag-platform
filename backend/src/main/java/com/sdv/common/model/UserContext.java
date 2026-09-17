package com.sdv.common.model;

import java.util.Set;

/**
 * F-BE-008. 검증된 JWT로부터 만들어지는 불변 현재 사용자 정보(AUT-002, AUT-004).
 *
 * 내부 권한 식별은 검증된 JWT의 {@code issuer + sub} 결합이다. {@code loginId}는
 * 사용자에게 보여줄 검색/표시 label일 뿐 권한 Key가 아니며, 이메일·role·loginId가
 * 저장된 clearance를 대신하지 않는다. tenant/account ID 같은 별도 권한 체계를
 * 만들지 않는다.
 *
 * {@code roles}/{@code groups}는 생성자에서 방어적으로 불변 Set으로 복사되므로,
 * 이 Record 자체가 이미 불변이다.
 */
public record UserContext(String subject, String email, Set<Role> roles, Set<String> groups,
        String issuer, String loginId) {

    /** Compatibility constructor for code that does not need directory identity. */
    public UserContext(String subject, String email, Set<Role> roles, Set<String> groups) {
        this(subject, email, roles, groups, null, null);
    }

    public UserContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
