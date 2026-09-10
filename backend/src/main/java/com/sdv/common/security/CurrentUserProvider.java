package com.sdv.common.security;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F-BE-006. 인증된 Spring Security JWT Context로부터만 신뢰 가능한
 * {@link UserContext}를 만든다(AUT-002).
 *
 * <p>Fallback User를 만들지 않는다 - Authentication이 없거나, 인증되지 않았거나,
 * JWT 기반이 아니거나, subject가 없으면 예외를 던진다. 이 예외는 정상적인 인증된
 * 요청에서는 발생하지 않아야 한다(Spring Security가 이미 인증/인가를 통과시킨
 * 뒤에만 Controller가 호출되므로) - 발생한다면 방어적 Fail-Safe이며,
 * {@code GlobalExceptionHandler}가 안전한 500 응답으로 변환한다(원본 메시지는
 * 노출하지 않음).</p>
 */
@Component
public class CurrentUserProvider {

    public UserContext getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated context");
        }
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new IllegalStateException("Authentication is not a validated JWT context");
        }

        Jwt jwt = jwtAuthentication.getToken();
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("JWT is missing a non-blank subject");
        }

        return new UserContext(
                subject,
                jwt.getClaimAsString("email"),
                extractRoles(jwtAuthentication),
                extractGroups(jwt));
    }

    private Set<Role> extractRoles(Authentication authentication) {
        Set<Role> roles = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            for (Role role : Role.values()) {
                if (authority.getAuthority().equals("ROLE_" + role.name())) {
                    roles.add(role);
                }
            }
        }
        return roles;
    }

    private Set<String> extractGroups(Jwt jwt) {
        Object groupsClaim = jwt.getClaim("groups");
        if (groupsClaim instanceof List<?> list) {
            Set<String> groups = new LinkedHashSet<>();
            for (Object item : list) {
                if (item instanceof String value && !value.isBlank()) {
                    groups.add(value);
                }
            }
            return groups;
        }
        return Set.of();
    }
}
