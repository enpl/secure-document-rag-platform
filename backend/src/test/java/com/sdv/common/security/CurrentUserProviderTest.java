package com.sdv.common.security;

import com.sdv.common.model.Role;
import com.sdv.common.model.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-BE-006 검증: 인증된 Spring Security JWT Context에서만 UserContext를
 * 만든다 - 없거나/미인증/비-JWT/subject 없음은 모두 거부하고, Fallback User를
 * 만들지 않는다(AUT-002).
 */
class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsMissingAuthentication() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(provider::getCurrentUser).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnauthenticatedAuthentication() {
        var unauthenticated = new UsernamePasswordAuthenticationToken("principal", "credentials");
        SecurityContextHolder.getContext().setAuthentication(unauthenticated);

        assertThatThrownBy(provider::getCurrentUser).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsNonJwtAuthentication() {
        var nonJwt = new TestingAuthenticationToken("principal", "credentials", "ROLE_USER");
        nonJwt.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(nonJwt);

        assertThatThrownBy(provider::getCurrentUser).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsJwtWithBlankSubject() {
        Jwt jwt = jwt("", null, List.of());
        var token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(token);

        assertThatThrownBy(provider::getCurrentUser).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void buildsUserContextFromValidatedJwtContext() {
        Jwt jwt = jwt("subject-1", "user@example.com", List.of("group-a", "group-b", "group-a"));
        var token = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(token);

        UserContext userContext = provider.getCurrentUser();

        assertThat(userContext.subject()).isEqualTo("subject-1");
        assertThat(userContext.email()).isEqualTo("user@example.com");
        assertThat(userContext.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(userContext.groups()).containsExactlyInAnyOrder("group-a", "group-b");
    }

    @Test
    void doesNotRequireEmailWhenSubjectIsPresent() {
        Jwt jwt = jwt("subject-2", null, List.of());
        var token = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(token);

        UserContext userContext = provider.getCurrentUser();

        assertThat(userContext.subject()).isEqualTo("subject-2");
        assertThat(userContext.email()).isNull();
    }

    private Jwt jwt(String subject, String email, List<String> groups) {
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("sub", subject);
        if (email != null) {
            claims.put("email", email);
        }
        claims.put("groups", groups);
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
    }
}
