package com.sdv.common.model;

import java.util.Set;

/**
 * F-BE-008. 검증된 JWT로부터 만들어지는 불변 현재 사용자 정보(AUT-002, AUT-004).
 *
 * 공식 필드는 정확히 {@code subject}, {@code email}, {@code roles}, {@code groups}
 * 뿐이다 - tenant/account ID 같은 필드를 임의로 추가하지 않는다. 이 작업(M03)에서
 * "계정 식별"은 검증된 JWT의 {@code sub} Claim(=subject)을 의미한다.
 *
 * {@code roles}/{@code groups}는 생성자에서 방어적으로 불변 Set으로 복사되므로,
 * 이 Record 자체가 이미 불변이다.
 */
public record UserContext(String subject, String email, Set<Role> roles, Set<String> groups) {

    public UserContext {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }
}
