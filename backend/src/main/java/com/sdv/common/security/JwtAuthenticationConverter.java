package com.sdv.common.security;

import com.sdv.common.model.Role;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * F-BE-007. Keycloak Realm Role/Client Role Claim만을 신뢰해 Spring 권한
 * ({@code ROLE_USER}/{@code ROLE_ADMIN})으로 변환한다(AUT-001, AUT-002).
 *
 * <p>지원하는 위치는 정확히 두 곳뿐이다 - Keycloak의 기본(default) Access Token
 * Claim 구조:</p>
 * <ul>
 *   <li>{@code realm_access.roles} (Realm Role)</li>
 *   <li>{@code resource_access."sdv-backend".roles} (sdv-backend Client Role)</li>
 * </ul>
 *
 * <p>그 외 위치(예: JWT 최상위 {@code roles} Claim, 임의의 문자열 Claim)는 절대
 * 권한으로 인정하지 않는다 - Claim 이름/값이 우연히 {@code "ROLE_ADMIN"}과 같더라도
 * 그 자체로는 인가 근거가 되지 않는다. 인식되지 않는 Role 이름은 무시한다.</p>
 *
 * <p>Role 이름은 정확히 {@code "USER"}/{@code "ADMIN"}과 일치할 때만 인식한다
 * (대소문자 구분, trim/대문자화 등 어떤 정규화도 하지 않는다) - {@code "admin"},
 * {@code "Admin"}, {@code " ADMIN"} 같은 변형은 권한으로 인정하지 않는다.</p>
 */
@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String BACKEND_CLIENT_ID = "sdv-backend";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (Role role : recognizedRoles(jwt)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        }
        return new JwtAuthenticationToken(jwt, authorities);
    }

    private Set<Role> recognizedRoles(Jwt jwt) {
        Set<String> rawRoleNames = new LinkedHashSet<>();
        rawRoleNames.addAll(realmRoleNames(jwt));
        rawRoleNames.addAll(backendClientRoleNames(jwt));

        Set<Role> roles = new LinkedHashSet<>();
        for (String raw : rawRoleNames) {
            recognizedRole(raw).ifPresent(roles::add);
        }
        return roles;
    }

    /** 정확한 문자열 일치만 인정한다 - trim/대소문자 정규화는 하지 않는다. */
    private Optional<Role> recognizedRole(String candidate) {
        for (Role role : Role.values()) {
            if (role.name().equals(candidate)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private List<String> realmRoleNames(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            return stringList(map.get("roles"));
        }
        return List.of();
    }

    private List<String> backendClientRoleNames(Jwt jwt) {
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map<?, ?> map) {
            Object backendClient = map.get(BACKEND_CLIENT_ID);
            if (backendClient instanceof Map<?, ?> clientMap) {
                return stringList(clientMap.get("roles"));
            }
        }
        return List.of();
    }

    private List<String> stringList(Object claimValue) {
        if (claimValue instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
